// JavaScript facade for „Stationär im WLAN“ in the browser (ADR 0094 §3, ADR 0095, ADR 0054 §5b): after Meshtastic is
// on the radio, the page hands over the serial line, and the core writes radio profile, Wi-Fi and gateway values the
// same way as the app. Only the line (WebSerial) comes from the browser.
@file:OptIn(DelicateCoroutinesApi::class)

package app.anynear.anmp.core.radio

import app.anynear.anmp.core.RadioGateway
import app.anynear.anmp.core.firmware.WebLine
import app.anynear.anmp.core.firmware.WebSerialLine
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlin.js.Promise

/** Gateway of the signed radio list, opaque for the page ([app.anynear.anmp.core.AnmpCoreWeb.radioGateway]). */
@JsExport
class WebRadioGateway internal constructor(internal val gateway: RadioGateway)

/**
 * Radio area of the signed radio list (ADR 0054 §1b), opaque for the page ([app.anynear.anmp.core.AnmpCoreWeb.radioArea]):
 * [name] for people, [label] with LoRa region and preset (`EU_868, ShortSlow`).
 */
@JsExport
class WebRadioArea internal constructor(internal val target: RadioTarget) {
    val name: String get() = target.area.orEmpty()
    val label: String get() = target.label
}

/**
 * Outcome of [RadioWifiWeb.setUp]. [kind]: `done`, `noAnswer` (no Meshtastic on the cable), `noWifi` (the radio has
 * none) or `incomplete` (it did not keep the values). For `done`: [name] of the radio as it shows in the app, [wifi]
 * and [mqtt] `yes`, `no` or `unknown` ([mqtt] is `unknown` also without a gateway).
 */
@JsExport
class WebWifiResult(val kind: String, val name: String, val wifi: String, val mqtt: String)

/** What the radio on the cable is, read over Meshtastic: [answered] at all, [hasWifi], [name] (may be empty). */
@JsExport
class WebRadioProbe(val answered: Boolean, val hasWifi: Boolean, val name: String)

@JsExport
object RadioWifiWeb {
    /**
     * Waits for Meshtastic on [line] (the first start after installing takes a while) and reads what the radio is.
     * The line must be open at 115200 baud.
     */
    fun probe(line: WebSerialLine, log: ((String) -> Unit)?): Promise<WebRadioProbe> = GlobalScope.promise {
        val state = RadioConsole(WebLine(line), log ?: {}).read()
        WebRadioProbe(state != null, state?.hasWifi == true, state?.snapshot?.owner?.long_name.orEmpty())
    }

    /**
     * „Stationär im WLAN“: radio profile with the settings of [area] (without one the default of ADR 0006), name, Wi-Fi
     * [ssid] and [password] and, with [gateway], the values of the radio bridge. [onPhase] gets `connecting`, `writing`, `checking` and `network` (waiting for the Wi-Fi). The
     * password goes only onto the radio, nothing keeps it.
     */
    fun setUp(
        line: WebSerialLine,
        gateway: WebRadioGateway?,
        area: WebRadioArea?,
        ssid: String,
        password: String,
        onPhase: (String) -> Unit,
        log: ((String) -> Unit)?,
    ): Promise<WebWifiResult> = GlobalScope.promise {
        val console = RadioConsole(WebLine(line), log ?: {})
        val result = RadioWifiSetup(console, gateway?.gateway, log ?: {}, target = area?.target ?: RadioTarget.DEFAULT).run(ssid, password) { onPhase(it.name.lowercase()) }
        when (result) {
            is RadioWifiSetup.Result.Done -> WebWifiResult("done", result.name, word(result.wifi), word(result.mqtt))
            RadioWifiSetup.Result.NoAnswer -> WebWifiResult("noAnswer", "", UNKNOWN, UNKNOWN)
            RadioWifiSetup.Result.NoWifi -> WebWifiResult("noWifi", "", UNKNOWN, UNKNOWN)
            RadioWifiSetup.Result.Incomplete -> WebWifiResult("incomplete", "", UNKNOWN, UNKNOWN)
        }
    }

    private const val UNKNOWN = "unknown"

    private fun word(v: Boolean?) = when (v) {
        true -> "yes"
        false -> "no"
        null -> UNKNOWN
    }
}
