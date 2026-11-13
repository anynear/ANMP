package app.anynear.anmp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebAddContactTest {
    private fun member(personId: String?) = MemberState(MemberState.CONTACTS, "dev-1", "Test", "key", personId = personId)

    @Test
    fun addedAndKnownCarryThePersonId() {
        val added = WebAddContact.of(AddContactResult.Added(member("person-1")))
        assertEquals("added", added.status)
        assertEquals("person-1", added.peer)

        val known = WebAddContact.of(AddContactResult.AlreadyKnown(member("person-2")))
        assertEquals("known", known.status)
        assertEquals("person-2", known.peer)
    }

    @Test
    fun deviceWithoutKeyChainIsThePerson() {
        assertEquals("dev-1", WebAddContact.of(AddContactResult.Added(member(null))).peer)
    }

    @Test
    fun otherResultsHaveNoPeer() {
        val cases = listOf(
            AddContactResult.KeyMismatch("Test") to "mismatch",
            AddContactResult.Own to "own",
            AddContactResult.ForeignNetwork to "foreign",
            AddContactResult.Invalid to "invalid",
        )
        for ((result, status) in cases) {
            val web = WebAddContact.of(result)
            assertEquals(status, web.status)
            assertNull(web.peer)
        }
    }
}
