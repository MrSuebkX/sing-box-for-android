package io.nekohasekai.sfa.mihomo

import io.nekohasekai.sfa.utils.CommandTarget
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** The existing screens call this adapter whenever a local Clash profile is running. */
object MihomoControl {
    val active: Boolean get() = !CommandTarget.isRemote && MihomoRuntime.isRunning
    fun path(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    fun request(path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        check(active) { "Mihomo is not running" }
        val port = MihomoRuntime.controllerPort
        val secret = MihomoRuntime.secret
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 3000
            connection.readTimeout = 12000
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer $secret")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            check(connection.responseCode in 200..299) { "Mihomo request failed (${connection.responseCode})" }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            check(port == MihomoRuntime.controllerPort && secret == MihomoRuntime.secret) { "Core changed during request" }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally { connection.disconnect() }
    }
    fun select(group: String, item: String) { request("/proxies/${path(group)}", "PUT", JSONObject().put("name", item)) }
    private val preferences get() = io.nekohasekai.sfa.Application.application.getSharedPreferences("mihomo-ui", android.content.Context.MODE_PRIVATE)
    fun expanded(group: String): Boolean = preferences.getBoolean(group, true)
    fun expand(group: String, expanded: Boolean) { preferences.edit().putBoolean(group, expanded).apply() }
    fun mode(mode: String) { request("/configs", "PATCH", JSONObject().put("mode", mode)) }
    fun close(id: String? = null) { request(if (id == null) "/connections" else "/connections/${path(id)}", "DELETE") }
    fun test(tag: String) {
        val proxies = request("/proxies").getJSONObject("proxies")
        val group = proxies.optJSONObject(tag)?.has("all") == true
        request("/${if (group) "group" else "proxies"}/${path(tag)}/delay?timeout=5000&url=https%3A%2F%2Fwww.gstatic.com%2Fgenerate_204")
    }
    private val logs = ArrayDeque<Pair<Long, String>>()
    private var sequence = 0L
    @Synchronized fun appendLog(line: String) {
        logs.addLast(++sequence to line.take(8192).replace(Regex("https?://[^\\s\\\"]+"), "[URL]"))
        while (logs.size > 1000) logs.removeFirst()
    }
    @Synchronized fun logSnapshot(after: Long): List<Pair<Long, String>> = logs.filter { it.first > after }
    @Synchronized fun clearLogs() { logs.clear() }
}
