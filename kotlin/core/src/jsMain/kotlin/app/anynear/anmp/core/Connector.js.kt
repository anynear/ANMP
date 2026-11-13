package app.anynear.anmp.core

import app.anynear.andp.AndpClient
import app.anynear.andp.Relay

/** Browsers: DNS name with Web PKI (ANDP §12); relays without `host` are not reachable. */
actual fun platformConnector(): RelayConnector = object : RelayConnector {
    override fun connect(relay: Relay): AndpClient? = if (relay.host.isEmpty()) null else AndpClient.webPki(relay)

    override fun connectUrl(baseUrl: String): AndpClient = AndpClient.forUrl(baseUrl)
}
