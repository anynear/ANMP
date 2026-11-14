package app.anynear.anmp.core

import app.anynear.andp.ID
import app.anynear.andp.Key
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Personal store ("Meine Ablage", ADR 0041): own space, no expiry, rename replaces, delete removes. */
class StoreTest {
    @Test
    fun securedCountsOperatorsThatHoldTheContent() = world {
        val a = device("Anna")
        val ref = a.core.storePut("f1", ByteArray(3000) { 4 }, "Pass.pdf", "pdf", added = 1000)
        a.await<CoreEvent.Stored> { it.ref == ref }
        val content = ID(assertNotNull(a.core.storeList()).single().blob)
        eventually { net.holding(storeSpace(a), content).size == net.holders(content.toString()).size }

        val all = assertNotNull(a.core.storeList()).single()
        assertEquals(3, all.operators)
        assertTrue(all.secured)
        // One copy lost: two operators still hold it, still secured (at least two, ADR 0028 §7).
        val holders = net.holders(content.toString())
        holders[0].lose(storeSpace(a), content)
        assertNotNull(a.core.storeList()).single().let { assertEquals(2, it.operators); assertTrue(it.secured) }
        // Two lost: only one operator, no longer secured.
        holders[1].lose(storeSpace(a), content)
        assertNotNull(a.core.storeList()).single().let { assertEquals(1, it.operators); assertTrue(!it.secured) }
    }

    @Test
    fun putListFetchRenameDelete() = world {
        val a = device("Anna")
        val ref = a.core.storePut("f1", ByteArray(3000) { 3 }, "Vollmacht.pdf", "pdf", added = 1000)
        a.await<CoreEvent.Stored> { it.ref == ref }

        val listed = assertNotNull(a.core.storeList()).single()
        assertEquals("Vollmacht.pdf", listed.name)
        assertEquals("pdf", listed.type)
        assertEquals(3000, listed.size)
        assertContentEquals(ByteArray(3000) { 3 }, assertIs<BlobResult.Found>(a.core.storeFetch(listed)).plaintext)

        // Without expiry on every holder, and not readable as a mailbox: the space is not the personal mailbox.
        val holders = net.relays.filter { r -> r.puts.any { it.id.toString() == listed.entryId } }
        assertTrue(holders.isNotEmpty())
        // Named are the relays that hold the content, not those of the entry: the content is placed by its own ID.
        val content = ID(listed.blob)
        eventually { net.holding(storeSpace(a), content).size == net.holders(content.toString()).size }
        val contentHolders = net.holders(content.toString()).map { it.relay.site.ifEmpty { it.relay.node.toString().take(8) } }
        assertEquals(contentHolders, assertNotNull(a.core.storeList()).single().relays)
        // A lost copy is no longer named.
        net.holders(content.toString()).first().lose(storeSpace(a), content)
        assertEquals(contentHolders.drop(1), assertNotNull(a.core.storeList()).single().relays)
        assertTrue(net.relays.flatMap { it.puts }.filter { it.space.toString() != a.info.inbox }.all { it.until == 0L })
        assertNotEquals(a.info.inbox, net.relays.flatMap { it.puts }.first { it.id.toString() == listed.entryId }.space.toString())

        // Renamed: put again under the same file ID; the older entry goes at the next listing.
        val renamed = a.core.storePut("f1", ByteArray(3000) { 3 }, "Vollmacht Oma.pdf", "pdf", added = 1000)
        a.await<CoreEvent.Stored> { it.ref == renamed && it !== a.all<CoreEvent.Stored>().first { s -> s.ref == ref } }
        eventually { a.core.storeList()?.singleOrNull()?.name == "Vollmacht Oma.pdf" }
        eventually { a.core.storeList()?.size == 1 }

        assertTrue(a.core.storeDelete("f1"))
        eventually { a.core.storeList()?.isEmpty() == true }
    }

    @Test
    fun usageCountsLikeTheRelays() = world {
        val a = device("Anna")
        assertEquals(null, a.core.storeUsage())
        net.relays.forEach { it.policy = it.policy.copy(quotaSpaceBytes = 5_000_000) }
        net.relays.first().policy = net.relays.first().policy.copy(quotaSpaceBytes = 4_000_000)
        val ref = a.core.storePut("f1", ByteArray(3000) { 3 }, "Vollmacht.pdf", "pdf", added = 1000)
        a.await<CoreEvent.Stored> { it.ref == ref }

        val listed = assertNotNull(a.core.storeList()).single()
        val entry = net.relays.flatMap { it.puts }.first { it.id.toString() == listed.entryId }
        // Entry and the content it refers to (ANDP §7), as the relays count them. The holders learn of it one after
        // another, so wait until the answer settles (on slow CI machines this took longer than one round).
        val expected = entry.size + entry.refs.sumOf { it.size }
        eventually { a.core.storeUsage()?.used == expected }
        val usage = assertNotNull(a.core.storeUsage())
        assertEquals(expected, usage.used)
        assertTrue(usage.used > 3000)
        // Holders that answered and do not know of a lower quota: the smallest one asked counts.
        assertTrue(usage.quota == 5_000_000L || usage.quota == 4_000_000L)

        // Deleted: nothing is used any more.
        assertTrue(a.core.storeDelete("f1"))
        eventually { a.core.storeList()?.isEmpty() == true }
        eventually { a.core.storeUsage()?.used == 0L }
    }

    @Test
    fun anotherInstallationWithTheSameKeysSeesTheFiles() = world {
        val a = device("Anna")
        val ref = a.core.storePut("f2", "Notfallplan".encodeToByteArray(), "Notfallplan.txt", "file", added = 5)
        a.await<CoreEvent.Stored> { it.ref == ref }

        // As after restoring a backup on a new phone: the same store secret.
        val copied = MemorySecretStore().apply { put(StoreKeys.SECRET, a.secrets.get(StoreKeys.SECRET)) }
        val b = device("Anna neu", secrets = copied)
        eventually { b.core.storeList()?.size == 1 }
        val f = assertNotNull(b.core.storeList()).single()
        assertEquals("Notfallplan.txt", f.name)
        assertEquals("Notfallplan", assertIs<BlobResult.Found>(b.core.storeFetch(f)).plaintext.decodeToString())

        // Someone else has another store and sees nothing of Anna's.
        val c = device("Ben")
        eventually { c.core.storeList() == emptyList<StoredFile>() }
    }
}

/** The space of [d]'s personal store, from its store secret. */
private suspend fun storeSpace(d: Device): Key = StoreKeys.of(d.secrets.get(StoreKeys.SECRET)!!).space

/** Circle store ("Kreis-Ablage", ADR 0049): every member sees and changes the files; a key change moves them. */
class CircleStoreTest {
    @Test
    fun membersShareTheFiles() = world {
        val a = device("Anna")
        val b = device("Ben")
        val c = device("Clara")
        val circle = a.core.createCircle("Familie")
        b.core.joinCircle(a.core.inviteLink(circle.id)!!)
        eventually { a.core.membersOf(circle.id).size == 1 && b.core.membersOf(circle.id).size == 1 }

        val ref = assertNotNull(a.core.circleStorePut(circle.id, "k1", "Treffpunkt Rathaus".encodeToByteArray(), "Treffpunkt.txt", "file", added = 7))
        a.await<CoreEvent.Stored> { it.ref == ref }

        // Ben sees and opens it; it is neither in Anna's personal store nor readable for Clara outside the circle.
        val seen = assertNotNull(b.core.circleStoreList(circle.id)).single()
        assertTrue(assertNotNull(b.core.circleStoreUsage(circle.id)).used > "Treffpunkt Rathaus".length)
        assertEquals(null, c.core.circleStoreUsage(circle.id))
        assertEquals("Treffpunkt.txt", seen.name)
        assertEquals("Treffpunkt Rathaus", assertIs<BlobResult.Found>(b.core.circleStoreFetch(circle.id, seen)).plaintext.decodeToString())
        assertEquals(emptyList(), assertNotNull(a.core.storeList()))
        assertEquals(null, c.core.circleStoreList(circle.id))
        // Without expiry, in a space of its own (not the circle space of the chat).
        assertTrue(net.relays.flatMap { it.puts }.filter { it.space == seen.space }.all { it.until == 0L })
        assertNotEquals(circle.id, seen.space.toString())

        // Ben renames it, Anna sees the new name; Ben deletes it, it is gone for Anna too.
        val renamed = assertNotNull(b.core.circleStorePut(circle.id, "k1", "Treffpunkt Rathaus".encodeToByteArray(), "Treffpunkt neu.txt", "file", added = 7))
        b.await<CoreEvent.Stored> { it.ref == renamed }
        eventually { a.core.circleStoreList(circle.id)?.singleOrNull()?.name == "Treffpunkt neu.txt" }
        assertTrue(b.core.circleStoreDelete(circle.id, "k1"))
        eventually { a.core.circleStoreList(circle.id)?.isEmpty() == true }
    }

    @Test
    fun keyChangeMovesTheFilesAndShutsOutTheRemoved() = world {
        val a = device("Anna")
        val b = device("Ben")
        val c = device("Clara")
        val circle = a.core.createCircle("Familie")
        b.core.joinCircle(a.core.inviteLink(circle.id)!!)
        c.core.joinCircle(a.core.inviteLink(circle.id)!!)
        eventually { a.core.membersOf(circle.id).size == 2 && b.core.membersOf(circle.id).size == 2 && c.core.membersOf(circle.id).size == 2 }
        val ref = assertNotNull(c.core.circleStorePut(circle.id, "k2", ByteArray(2000) { 5 }, "Notfallplan.pdf", "pdf", added = 9))
        c.await<CoreEvent.Stored> { it.ref == ref }
        val before = assertNotNull(a.core.circleStoreList(circle.id)).single()

        a.core.removeMember(circle.id, b.deviceId)
        c.await<CoreEvent.KeyChanged> { it.kind == CoreEvent.KIND_SWITCHED }
        b.await<CoreEvent.KeyChanged> { it.kind == CoreEvent.KIND_REMOVED_YOU }

        // Listing copies the file into the space of the new key and then removes it from the previous one.
        eventually { c.core.circleStoreList(circle.id)?.singleOrNull()?.let { it.space != before.space } == true }
        val moved = assertNotNull(a.core.circleStoreList(circle.id)).single()
        assertEquals("Notfallplan.pdf", moved.name)
        assertContentEquals(ByteArray(2000) { 5 }, assertIs<BlobResult.Found>(a.core.circleStoreFetch(circle.id, moved)).plaintext)
        eventually { net.relays.none { it.has(before.space!!, ID.parse(before.entryId)) } }
        // Ben is out: the core gives him nothing more of the circle store.
        assertEquals(null, b.core.circleStoreList(circle.id))
    }

    /** ADR 0049 §5: whoever leaves last takes the store with them; before that it stays for the others. */
    @Test
    fun theLastToLeaveDeletesTheStore() = world {
        val a = device("Anna")
        val b = device("Ben")
        val circle = a.core.createCircle("Familie")
        b.core.joinCircle(a.core.inviteLink(circle.id)!!)
        eventually { a.core.membersOf(circle.id).size == 1 && b.core.membersOf(circle.id).size == 1 }
        val ref = assertNotNull(a.core.circleStorePut(circle.id, "k1", ByteArray(3000) { 4 }, "Plan.pdf", "pdf", added = 1))
        a.await<CoreEvent.Stored> { it.ref == ref }
        val f = assertNotNull(a.core.circleStoreList(circle.id)).single()
        val space = assertNotNull(f.space)

        // Ben leaves: Anna is still in it, the file stays.
        assertTrue(!b.core.aloneIn(circle.id))
        b.core.leaveCircle(circle.id)
        eventually { a.core.membersOf(circle.id).isEmpty() }
        eventually { a.core.circleStoreList(circle.id)?.singleOrNull()?.name == "Plan.pdf" }
        val now = assertNotNull(a.core.circleStoreList(circle.id)).single()
        val nowSpace = assertNotNull(now.space)

        // Anna is the last: leaving deletes entry and content on every relay.
        assertTrue(a.core.aloneIn(circle.id))
        a.core.leaveCircle(circle.id)
        eventually { net.relays.none { it.has(nowSpace, ID.parse(now.entryId)) || it.has(nowSpace, ID(now.blob)) } }
        eventually { net.relays.none { it.has(space, ID.parse(f.entryId)) } }
    }

    @Test
    fun withoutRelaysTheStoreIsDeletedLater() = world {
        // A short maintenance round, so the retry comes within the test.
        val a = device("Anna", timings = TEST_TIMINGS.copy(purgeCheck = 1.seconds))
        val circle = a.core.createCircle("Notizen")
        val ref = assertNotNull(a.core.circleStorePut(circle.id, "k1", "Liste".encodeToByteArray(), "Liste.txt", "file", added = 1))
        a.await<CoreEvent.Stored> { it.ref == ref }
        val f = assertNotNull(a.core.circleStoreList(circle.id)).single()
        val space = assertNotNull(f.space)

        net.relays.forEach { it.offline = true }
        a.core.leaveCircle(circle.id)
        assertEquals(null, a.core.circle(circle.id))
        // Let the first cleanup run into the offline relays; otherwise it may only start once they are back.
        kotlinx.coroutines.delay(1.seconds)
        net.relays.forEach { it.offline = false }
        assertTrue(net.relays.any { it.has(space, ID.parse(f.entryId)) })
        // The next maintenance round tries again.
        eventually(timeout = 30.seconds) { net.relays.none { it.has(space, ID.parse(f.entryId)) } }
    }

    @Test
    fun ownOtherDevicesDoNotCountAsOthers() = world {
        val phone = device("Anna")
        val tablet = addTablet(phone)
        val circle = phone.core.createCircle("Familie")
        eventually { tablet.core.circle(circle.id) != null }
        val b = device("Ben")
        b.core.joinCircle(phone.core.inviteLink(circle.id)!!)
        eventually { phone.core.membersOf(circle.id).any { it.deviceId == b.deviceId } }
        assertTrue(!phone.core.aloneIn(circle.id))
        b.core.leaveCircle(circle.id)
        eventually { phone.core.membersOf(circle.id).none { it.deviceId == b.deviceId } }
        assertTrue(phone.core.aloneIn(circle.id))
    }
}

/** Shared quota (ADR 0079): one quota over all stores; in a circle only what one put in counts. */
class SharedQuotaTest {
    @Test
    fun circleFilesCountForWhoPutThemIn() = world {
        val a = device("Anna")
        val b = device("Ben")
        val circle = a.core.createCircle("Familie")
        b.core.joinCircle(a.core.inviteLink(circle.id)!!)
        eventually { a.core.membersOf(circle.id).size == 1 && b.core.membersOf(circle.id).size == 1 }

        val mine = a.core.storePut("p1", ByteArray(2000) { 1 }, "Plan.pdf", "pdf", added = 1)
        a.await<CoreEvent.Stored> { it.ref == mine }
        val shared = assertNotNull(a.core.circleStorePut(circle.id, "k1", ByteArray(5000) { 2 }, "Karte.pdf", "pdf", added = 2))
        a.await<CoreEvent.Stored> { it.ref == shared }

        assertNotNull(a.core.storeList())
        val seenByAnna = assertNotNull(a.core.circleStoreList(circle.id)).single()
        assertEquals(a.info.personId, seenByAnna.by)
        assertNotNull(b.core.storeList())
        assertNotNull(b.core.circleStoreList(circle.id))

        val anna = assertNotNull(a.core.storageUsage())
        assertEquals(assertNotNull(a.core.storeUsage()).used + seenByAnna.weight, anna.used)
        assertTrue(anna.used > 7000)
        // Ben put nothing in: the circle file does not count for him.
        assertEquals(0, assertNotNull(b.core.storageUsage()).used)

        // Ben renames it: it still counts for Anna.
        val renamed = assertNotNull(b.core.circleStorePut(circle.id, "k1", ByteArray(5000) { 2 }, "Karte neu.pdf", "pdf", added = 2))
        b.await<CoreEvent.Stored> { it.ref == renamed }
        eventually { b.core.circleStoreList(circle.id)?.singleOrNull()?.name == "Karte neu.pdf" }
        assertEquals(a.info.personId, assertNotNull(b.core.circleStoreList(circle.id)).single().by)
        assertEquals(0, assertNotNull(b.core.storageUsage()).used)
    }

    @Test
    fun withoutSubscriptionThePersonHas250MiB() = world {
        val a = device("Anna")
        net.relays.forEach { it.policy = it.policy.copy(quotaSpaceBytes = 1L shl 30) }
        val ref = a.core.storePut("p1", ByteArray(3000) { 1 }, "Plan.pdf", "pdf", added = 1)
        a.await<CoreEvent.Stored> { it.ref == ref }
        assertNotNull(a.core.storeList())
        // The relays allow 1 GiB per space; the person's quota is lower (ADR 0076, 0079).
        assertEquals(250L shl 20, assertNotNull(a.core.storageUsage()).quota)
        assertEquals(1L shl 30, assertNotNull(a.core.storeUsage()).quota)
    }

    @Test
    fun aFullQuotaStopsNewFilesButNotChanges() = world {
        val a = device("Anna")
        net.relays.forEach { it.policy = it.policy.copy(quotaSpaceBytes = 6000) }
        val ref = a.core.storePut("p1", ByteArray(3000) { 1 }, "Plan.pdf", "pdf", added = 1)
        a.await<CoreEvent.Stored> { it.ref == ref }
        assertNotNull(a.core.storeList())

        val full = assertFailsWith<StorageFullException> { a.core.storePut("p2", ByteArray(3000) { 2 }, "Noch.pdf", "pdf", added = 2) }
        assertEquals(6000, full.quota)
        // The same file again (rename) is a change, not a new file.
        a.core.storePut("p1", ByteArray(3000) { 1 }, "Plan neu.pdf", "pdf", added = 1)
    }
}

/** Bin (ADR 0086): deleting moves a file into the bin, where it still counts, until it is deleted for good. */
class BinTest {
    @Test
    fun trashRestoreAndStillCounted() = world {
        val a = device("Anna")
        val ref = a.core.storePut("f1", ByteArray(3000) { 3 }, "Vollmacht.pdf", "pdf", added = 1000)
        a.await<CoreEvent.Stored> { it.ref == ref }
        val before = assertNotNull(a.core.storeList()).single()
        assertEquals(0, before.trashed)
        assertEquals(0, before.purgeAt)
        val used = assertNotNull(a.core.storageUsage()).used

        assertTrue(a.core.storeTrash("f1"))
        eventually { a.core.storeList()?.singleOrNull()?.let { it.trashed > 0 } == true }
        val trashed = assertNotNull(a.core.storeList()).single()
        assertEquals(trashed.trashed + 7 * 86_400_000L, trashed.purgeAt)
        assertEquals("Vollmacht.pdf", trashed.name)
        // Still counted: the content stays on the relays.
        assertTrue(assertNotNull(a.core.storageUsage()).used >= used)
        assertContentEquals(ByteArray(3000) { 3 }, assertIs<BlobResult.Found>(a.core.storeFetch(trashed)).plaintext)

        assertTrue(a.core.storeRestore("f1"))
        eventually { a.core.storeList()?.singleOrNull()?.trashed == 0L }
    }

    @Test
    fun theBinIsEmptiedAfterItsTime() = world {
        val a = device("Anna", timings = TEST_TIMINGS.copy(trashKeep = 3.seconds))
        val ref = a.core.storePut("f1", ByteArray(3000) { 3 }, "Alt.pdf", "pdf", added = 1000)
        a.await<CoreEvent.Stored> { it.ref == ref }
        assertNotNull(a.core.storeList())
        assertTrue(a.core.storeTrash("f1"))
        eventually { a.core.storeList()?.singleOrNull()?.let { it.trashed > 0 } == true }
        val trashed = assertNotNull(a.core.storeList()).single()

        assertEquals(trashed.trashed + 3000, trashed.purgeAt)
        // Before the end it is still there, after it the next listing deletes it for good.
        assertEquals(1, assertNotNull(a.core.storeList()).size)
        eventually { a.core.storeList()?.isEmpty() == true }
        eventually { net.relays.none { it.has(storeSpace(a), ID(trashed.blob)) } }
    }

    @Test
    fun inACircleEveryMemberSeesTheBin() = world {
        val a = device("Anna")
        val b = device("Ben")
        val circle = a.core.createCircle("Familie")
        b.core.joinCircle(a.core.inviteLink(circle.id)!!)
        eventually { a.core.membersOf(circle.id).size == 1 && b.core.membersOf(circle.id).size == 1 }
        val ref = assertNotNull(a.core.circleStorePut(circle.id, "p1", ByteArray(2000) { 1 }, "Plan.pdf", "pdf", added = 1))
        a.await<CoreEvent.Stored> { it.ref == ref }
        eventually { b.core.circleStoreList(circle.id)?.singleOrNull()?.name == "Plan.pdf" }

        assertTrue(b.core.circleStoreTrash(circle.id, "p1"))
        eventually { a.core.circleStoreList(circle.id)?.singleOrNull()?.let { it.trashed > 0 } == true }
        assertTrue(a.core.circleStoreRestore(circle.id, "p1"))
        eventually { b.core.circleStoreList(circle.id)?.singleOrNull()?.trashed == 0L }
        // Who put it in stays the same over bin and back.
        val by = assertNotNull(a.core.circleStoreList(circle.id)).single().by
        assertNotNull(by)
        assertEquals(by, assertNotNull(b.core.circleStoreList(circle.id)).single().by)
    }
}

