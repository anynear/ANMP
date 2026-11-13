package app.anynear.anmp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Recognize the device of a contact link without adding the contact (for the web app, e.g. the support contact). */
class ContactDeviceTest {
    @Test
    fun linkNamesTheDeviceWithoutAddingIt() = world {
        val a = device("Anna"); val b = device("Ben")
        assertEquals(a.deviceId, b.core.contactDevice(a.core.ownContactLink()))
        assertNull(b.core.membersOf(MemberState.CONTACTS).firstOrNull { it.deviceId == a.deviceId }, "nicht hinzugefügt")
    }

    @Test
    fun otherTextIsNoContact() = world {
        val b = device("Ben")
        assertNull(b.core.contactDevice("Hallo"))
    }
}
