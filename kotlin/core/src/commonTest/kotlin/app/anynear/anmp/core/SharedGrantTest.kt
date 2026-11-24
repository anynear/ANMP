package app.anynear.anmp.core

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * One grant under a circle purpose and a contact purpose at once, as apps before ANMP-Dev#193 issued it (same body in
 * the same second, Ed25519 is deterministic). Revoking the circle revoked it on the relays for the contact too; the
 * contact could no longer write and was never given a new grant (field report 24.11.2026: one tick only, the reply
 * came by the radio inbox, its picture only as a notice).
 */
class SharedGrantTest {
    private suspend fun state(d: Device): CoreState =
        coreJson.decodeFromString(CoreState.serializer(), assertNotNull(d.secrets.get(AnmpCore.STATE_KEY)).decodeToString())

    private suspend fun save(d: Device, s: CoreState) = d.secrets.put(AnmpCore.STATE_KEY, coreJson.encodeToString(CoreState.serializer(), s).encodeToByteArray())

    /** Anna and Ben are contacts and in Anna's circle; returns the circle and the contact grant Ben writes with. */
    private suspend fun World.contactsInACircle(anna: Device, ben: Device): Pair<String, String> {
        assertIs<AddContactResult.Added>(agreeContact(ben.core, anna.core))
        eventually { anna.core.canWriteTo(ben.deviceId) && ben.core.canWriteTo(anna.deviceId) }
        val circle = anna.core.createCircle("Familie")
        assertIs<JoinResult.Joined>(ben.core.joinCircle(anna.core.inviteLink(circle.id)!!))
        eventually { anna.core.membersOf(circle.id).any { it.deviceId == ben.deviceId } }
        eventually { state(anna).issued.any { it.purpose.startsWith("circle:${circle.id}:") && !it.revoked } }
        var contact: String? = null
        eventually {
            val issued = state(anna).issued.filter { it.purpose.startsWith("contact:") && !it.revoked }.map { it.grant }.toSet()
            contact = state(ben).grants.firstOrNull { it.contact && it.inbox == anna.info.inbox && it.grant in issued }?.grant
            contact != null
        }
        return circle.id to contact!!
    }

    /** Restarts [d] with its stored state changed by [change], like an app update over the state an older one left. */
    private suspend fun World.restart(d: Device, change: (CoreState) -> CoreState): Device {
        d.core.stop()
        devices -= d
        save(d, change(state(d)))
        return device(d.name, secrets = d.secrets, store = d.store, blobs = d.blobs)
    }

    @Test
    fun leavingACircleWhoseGrantIsAlsoTheContactGrantGivesTheContactANewOne() = world {
        val ben = device("Ben")
        val (circleId, contact) = contactsInACircle(device("Anna"), ben)
        val hash = state(devices.first { it.name == "Anna" }).issued.first { it.grant == contact }.hash
        val anna = restart(devices.first { it.name == "Anna" }) { s ->
            s.copy(issued = s.issued + IssuedGrant("circle:$circleId:0", contact, hash, s.issued.first { it.hash == hash }.until))
        }

        // Revokes the shared grant on the relays: the contact entry must not be handed out again.
        anna.core.leaveCircle(circleId)
        eventually { state(anna).issued.none { it.hash == hash && !it.revoked } }
        eventually { ben.core.canWriteTo(anna.info.personId) && state(ben).grants.any { it.contact && it.inbox == anna.info.inbox && it.grant != contact } }
        val out = assertNotNull(ben.core.sendDirect(anna.info.personId, "Kommt das an?"))
        anna.await<CoreEvent.Message> { it.mid == out.mid }
    }

    @Test
    fun aStoredContactGrantRevokedUnderACirclePurposeIsReplacedAfterTheUpdate() = world {
        val ben = device("Ben")
        val (circleId, contact) = contactsInACircle(device("Anna"), ben)
        val first = devices.first { it.name == "Anna" }
        val hash = state(first).issued.first { it.grant == contact }.hash
        // As an older app left it: the circle entry carried the same grant and was revoked, the contact entry not.
        val anna = restart(first) { s ->
            s.copy(issued = s.issued + IssuedGrant("circle:$circleId:0", contact, hash, s.issued.first { it.hash == hash }.until, revoked = true))
        }

        eventually { state(anna).issued.none { it.hash == hash && !it.revoked } }
        // Ben gets a new contact grant without either of them doing anything.
        eventually {
            val issued = state(anna).issued.filter { it.purpose.startsWith("contact:") && !it.revoked }.map { it.grant }.toSet()
            state(ben).grants.any { it.contact && it.inbox == anna.info.inbox && it.grant != contact && it.grant in issued }
        }
        val out = assertNotNull(ben.core.sendDirect(anna.info.personId, "Kommt das an?"))
        anna.await<CoreEvent.Message> { it.mid == out.mid }
        assertTrue(anna.core.canWriteTo(ben.deviceId))
    }
}
