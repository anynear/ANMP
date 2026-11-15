package app.anynear.anmp.core.radio

import app.anynear.anmp.core.RadioGateway
import app.anynear.anmp.core.firmware.SerialLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * [RadioConsole] and [RadioWifiSetup] against real Meshtastic firmware: `meshtasticd --sim` speaks the same stream API
 * on TCP 4403 as a radio on the serial line. Only with `ANMP_MESHTASTICD=host:port`, e.g.
 * `docker run --rm -p 4499:4403 <meshtasticd image> meshtasticd --sim` and `ANMP_MESHTASTICD=127.0.0.1:4499`.
 * The simulator has no Wi-Fi hardware, so the check of the Wi-Fi itself is left out; that needs a real radio.
 */
class MeshtasticdTest {
    private val target = System.getenv("ANMP_MESHTASTICD")?.takeIf { it.isNotBlank() }

    /** The stream API over TCP; a fresh socket after the simulator restarts. */
    private class TcpLine(private val host: String, private val port: Int) : SerialLine {
        private var socket: Socket? = null

        private fun open(): Socket = socket?.takeIf { !it.isClosed } ?: Socket(host, port).also { it.soTimeout = 200; socket = it }

        override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
            try {
                open().getOutputStream().apply { write(bytes); flush() }
            } catch (e: java.io.IOException) {
                socket = null
            }
            Unit
        }

        override suspend fun read(timeoutMs: Int): ByteArray = withContext(Dispatchers.IO) {
            val s = try { open() } catch (e: java.io.IOException) { Thread.sleep(timeoutMs.toLong()); return@withContext ByteArray(0) }
            s.soTimeout = timeoutMs.coerceAtLeast(1)
            val buf = ByteArray(4096)
            try {
                val n = s.getInputStream().read(buf)
                if (n < 0) { s.close(); socket = null; ByteArray(0) } else buf.copyOf(n)
            } catch (e: SocketTimeoutException) {
                ByteArray(0)
            } catch (e: java.io.IOException) {
                socket = null
                ByteArray(0)
            }
        }

        override suspend fun setBaud(baud: Int) {}
        override suspend fun setLines(dtr: Boolean, rts: Boolean) {}
    }

    /** Has Wi-Fi as far as [RadioWifiSetup] asks; the simulator reports none. */
    private class WithWifi(private val inner: RadioAdmin) : RadioAdmin by inner {
        override suspend fun read(): RadioState? = inner.read()?.let {
            RadioState(it.nodeNum, it.metadata?.newBuilder()?.also { m -> m.hasWifi = true }?.build(), it.snapshot)
        }
        override suspend fun netStatus(state: RadioState): RadioNetStatus? = null
    }

    @Test
    fun setsUpProfileWifiAndGatewayOnRealFirmware() = runBlocking {
        val (host, port) = (target ?: return@runBlocking).split(":").let { it[0] to it[1].toInt() }
        val console = RadioConsole(TcpLine(host, port), log = ::println, timing = RadioConsole.Timing(answer = 90.seconds))
        assertNotNull(console.read(), "meshtasticd antwortet nicht")
        val gateway = RadioGateway("mqtt.anynear.net", 1883, false, "gw", "secret", "msh/EU_868")
        val setup = RadioWifiSetup(WithWifi(console), gateway, log = ::println, timing = RadioWifiSetup.Timing(restart = 15.seconds, network = 1.seconds))
        val done = assertIs<RadioWifiSetup.Result.Done>(setup.run("Zuhause", "pass1234"))
        println("fertig: $done")

        val after = assertNotNull(console.read()).snapshot
        assertTrue(RadioProfile.deviations(after).isEmpty(), "offen: ${RadioProfile.deviations(after)}")
        assertEquals(true, after.network?.wifi_enabled)
        assertEquals("Zuhause", after.network?.wifi_ssid)
        assertEquals(true, after.mqtt?.enabled)
        assertEquals("mqtt.anynear.net", after.mqtt?.address)
        assertTrue(GatewaySetup.gatewayChannels(after.channels).all { it.settings?.uplink_enabled == true })
        assertTrue(GatewaySetup.plan(after, gateway).isEmpty())
    }
}
