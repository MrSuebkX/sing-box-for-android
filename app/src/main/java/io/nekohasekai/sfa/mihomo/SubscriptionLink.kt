package io.nekohasekai.sfa.mihomo

import java.net.URI
import java.net.URLDecoder

/** Decode the outer link once; never decode the subscription URL's own query. */
object SubscriptionLink {
    data class Subscription(val url: String, val name: String?)

    fun parse(input: String): Subscription {
        val uri = URI(input.trim())
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") {
            require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null) { "Invalid subscription URL" }
            return Subscription(uri.toASCIIString(), null)
        }
        require(scheme == "clash" || scheme == "clashmeta") { "Unsupported subscription link" }
        require(uri.host == "install-config" && (uri.path.isNullOrEmpty() || uri.path == "/")) {
            "Expected clash://install-config?url=…"
        }
        val parameters = (uri.rawQuery ?: "").split('&').filter { it.isNotEmpty() }.map {
            val parts = it.split('=', limit = 2)
            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }
        require(parameters.count { it.first == "url" } == 1) { "Expected exactly one subscription URL" }
        val target = parameters.single { it.first == "url" }.second
        require(URI(target).scheme?.lowercase() in listOf("https", "http")) { "Subscription must use HTTP or HTTPS" }
        return parse(target).copy(name = parameters.firstOrNull { it.first == "name" }?.second?.takeIf { it.isNotBlank() })
    }
}
