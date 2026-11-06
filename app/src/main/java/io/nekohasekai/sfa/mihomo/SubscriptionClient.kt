package io.nekohasekai.sfa.mihomo

import io.nekohasekai.sfa.utils.HTTPClient

object SubscriptionClient {
    fun fetch(link: String): String = HTTPClient().use {
        it.getString(SubscriptionLink.parse(link).url, "clash.meta/1.19.33 DualTunnel")
    }
}
