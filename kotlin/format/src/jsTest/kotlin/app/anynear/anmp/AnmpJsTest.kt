package app.anynear.anmp

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** JS facade: same packets as the common API. */
class AnmpJsTest {
    @Test
    fun circleAndInternetRule() = runTest {
        val keys = AnmpCircleKeys.derive(Fixtures.circleSecret)
        val a = Fixtures.deviceA()
        val content = AnmpContent(id = Fixtures.messageId, ts = 1, text = "Hallo", key = a.publicKey, signKey = a.signKey)
        val packet = AnmpJs.encode(AnmpHeader(AnmpKind.CIRCLE, a.tag), content, "c", keys.cipher, a)
        assertEquals("Hallo", AnmpJs.decode(packet, "c", keys.cipher, a.signKey, true)?.content?.text)
        assertTrue(AnmpJs.decode(packet, "c", keys.cipher, null, true)?.signed == true)
        assertNull(AnmpJs.decode(packet, "c", keys.cipher, Fixtures.deviceB().signKey, true))
        val unsigned = AnmpJs.encode(AnmpHeader(AnmpKind.CIRCLE, a.tag), content, "c", keys.cipher, null)
        assertNull(AnmpJs.decode(unsigned, "c", keys.cipher, null, true))
    }
}
