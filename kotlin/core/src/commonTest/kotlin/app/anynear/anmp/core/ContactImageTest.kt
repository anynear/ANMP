package app.anynear.anmp.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs

class ContactImageTest {
    private suspend fun Device.sees(other: Device, picture: ByteArray) {
        var ref: ImageRef? = null
        eventually { ref = core.membersOf(MemberState.CONTACTS).firstOrNull { it.deviceId == other.deviceId }?.image; ref != null }
        assertContentEquals(picture, assertIs<BlobResult.Found>(core.fetchImage(ref!!)).plaintext)
    }

    @Test
    fun linkOwnerPictureSetBefore() = world {
        val a = device("Anna"); val b = device("Ben")
        val picture = ByteArray(4000) { 5 }
        a.core.setOwnImage(picture)
        agreeContact(b.core, a.core)
        b.sees(a, picture)
    }

    @Test
    fun linkUserPictureSetBefore() = world {
        val a = device("Anna"); val b = device("Ben")
        val picture = ByteArray(4000) { 6 }
        b.core.setOwnImage(picture)
        agreeContact(b.core, a.core)
        a.sees(b, picture)
    }

    @Test
    fun linkOwnerPictureSetAfter() = world {
        val a = device("Anna"); val b = device("Ben")
        agreeContact(b.core, a.core)
        eventually { a.core.canWriteTo(b.deviceId) && b.core.canWriteTo(a.deviceId) }
        val picture = ByteArray(4000) { 7 }
        a.core.setOwnImage(picture)
        b.sees(a, picture)
    }

    @Test
    fun linkUserPictureSetAfter() = world {
        val a = device("Anna"); val b = device("Ben")
        agreeContact(b.core, a.core)
        eventually { a.core.canWriteTo(b.deviceId) && b.core.canWriteTo(a.deviceId) }
        val picture = ByteArray(4000) { 8 }
        b.core.setOwnImage(picture)
        a.sees(b, picture)
    }
}
