package app.anynear.anmp.core

import app.anynear.andp.Grant
import app.anynear.andp.Key
import app.anynear.andp.Rights
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * The radio spaces of the anynear network (ADR 0051, 0054 §5a, 0061): space and grant match, for anyone, without a
 * practical expiry. While a grant is still empty, that way is off.
 */
class RadioInboxConfigTest {
    private fun check(spaceText: String, grantText: String, rights: Rights) {
        val space = Key.parse(spaceText)
        if (grantText.isEmpty()) {
            assertNull(RadioInbox.parse(spaceText, grantText), "ohne grant aus")
            return
        }
        val body = Grant.parse(grantText).body
        assertEquals(space, body.space, "grant gehört zum space")
        assertNull(body.grantee, "grant für alle")
        assertEquals(rights, body.rights)
        val now = Clock.System.now().epochSeconds
        assertTrue(body.issued <= now && now < body.until, "grant gilt jetzt")
        // Without a practical expiry: in an emergency there may be no app update any more (ADR 0051).
        assertTrue(body.until - now > 50L * 365 * 86_400, "grant läuft in den nächsten 50 Jahren nicht ab")
        assertTrue(RadioInbox.parse(spaceText, grantText) != null)
    }

    private val readListWrite = Rights(1) + Rights(2) + Rights(4)

    @Test
    fun radioInboxIsConsistent() = check(AnmpCoreConfig.RADIO_INBOX_SPACE, AnmpCoreConfig.RADIO_INBOX_GRANT, readListWrite)

    @Test
    fun radioHeardIsConsistent() = check(AnmpCoreConfig.RADIO_HEARD_SPACE, AnmpCoreConfig.RADIO_HEARD_GRANT, readListWrite)

    @Test
    fun radioOutboxIsConsistent() = check(AnmpCoreConfig.RADIO_OUTBOX_SPACE, AnmpCoreConfig.RADIO_OUTBOX_GRANT, readListWrite)

    @Test
    fun radioSoftwareIsConsistent() =
        check(AnmpCoreConfig.RADIO_SOFTWARE_SPACE, AnmpCoreConfig.RADIO_SOFTWARE_GRANT, Rights(1) + Rights(2))
}
