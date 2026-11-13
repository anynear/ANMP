package app.anynear.anmp.core

import app.anynear.andp.AndpClient
import app.anynear.andp.Relay
import app.anynear.andp.pinned

/** Apps: IP address from the network list, TLS key pinned (ANDP §12). */
actual fun platformConnector(): RelayConnector = object : RelayConnector {
    override fun connect(relay: Relay): AndpClient? = if (relay.addr.isEmpty()) null else AndpClient.pinned(relay)

    override fun connectUrl(baseUrl: String): AndpClient = AndpClient.forUrl(baseUrl)
}
