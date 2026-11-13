package app.anynear.anmp.core

import app.anynear.andp.Credentials
import app.anynear.andp.Grant
import app.anynear.andp.GrantBody
import app.anynear.andp.ID
import app.anynear.andp.Meta
import app.anynear.andp.Placement
import app.anynear.andp.Rights
import app.anynear.andp.SigningKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/** Software for radios (ADR 0056 §1a): anynear publishes the list, everyone reads it from the relays. */
class PublishedSpaceTest {
    private suspend fun World.configWithSoftware(owner: SigningKey): AnmpCoreConfig {
        val now = Clock.System.now().epochSeconds
        val grant = Grant.issue(owner, GrantBody(owner.key, Rights(1) + Rights(2), null, null, now + 86_400, now - 60))
        val base = net.config()
        return AnmpCoreConfig(base.networkId, base.networkKey, seedUrl = base.seedUrl, timings = base.timings,
            radioInboxSpace = "", radioInboxGrant = "",
            radioSoftwareSpace = owner.key.toString(), radioSoftwareGrant = grant.toString())
    }

    /** Writes like the release step: the list as an entry at the holders of the space. */
    private suspend fun World.publish(owner: SigningKey, list: ByteArray) {
        val entry = Meta.signData(owner, owner.key, list, Clock.System.now().epochSeconds, placement = Placement.SPACE)
        for (r in net.holders(owner.key.toString())) net.connector.connect(r.relay)!!.put(Credentials(owner), entry, list)
    }

    @Test
    fun everyoneReadsTheList() = world {
        val owner = testKey("radio software owner")
        val config = configWithSoftware(owner)
        val list = """{"list":"…","sig":"…"}""".encodeToByteArray()
        publish(owner, list)

        val d = device("Clara", config = config)
        assertEquals(owner.key.toString(), d.core.radioSoftwareKey)
        val lists = d.core.radioSoftwareLists()
        assertEquals(1, lists?.size)
        assertContentEquals(list, lists!!.single())
    }

    @Test
    fun aLaggingHolderDoesNotHideTheList() = world {
        val owner = testKey("radio software owner")
        val config = configWithSoftware(owner)
        val list = "list".encodeToByteArray()
        publish(owner, list)
        net.holding(owner.key, ID.of(list)).take(2).forEach { it.lose(owner.key, ID.of(list)) }

        val d = device("Ben", config = config)
        assertContentEquals(list, d.core.radioSoftwareLists()?.single())
    }

    @Test
    fun withoutSetupNothingHappens() = world {
        val d = device("Anna")
        assertNull(d.core.radioSoftwareKey)
        assertNull(d.core.radioSoftwareLists())
    }
}
