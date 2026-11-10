package io.nekohasekai.sfa.mihomo

import android.content.Context
import io.nekohasekai.libbox.Libbox
import org.json.JSONObject
import java.io.File
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Mihomo handles rules, providers and proxy protocols; libbox supplies Android's VPN transport. */
object MihomoRuntime {
    @Volatile private var process: Process? = null
    @Volatile var controllerPort: Int = 0
        private set
    @Volatile var secret: String = ""
        private set
    val isRunning: Boolean get() = process?.running() == true

    private fun Process.running(): Boolean = try { exitValue(); false } catch (_: IllegalThreadStateException) { true }

    private fun Process.awaitExit(seconds: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (running() && System.nanoTime() < deadline) Thread.sleep(50)
        return !running()
    }

    private fun executable(context: Context): String {
        val binary = File(context.applicationInfo.nativeLibraryDir, "libmihomo.so")
        require(binary.canExecute()) { "This APK does not include a compatible mihomo core" }
        return binary.absolutePath
    }

    private fun tcpPort(): Int = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    private fun dnsPort(): Int = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun runtimeYaml(content: String, socks: Int, dnsPort: Int, api: Int, token: String): String {
        val map = CoreConfig.clashMap(content) ?: error("Expected Clash configuration")
        // A downloaded profile must not open listeners on the LAN or replace our VPN transport.
        listOf("port", "mixed-port", "redir-port", "tproxy-port").forEach { map[it] = 0 }
        listOf("external-controller-tls", "external-controller-unix", "external-controller-pipe",
            "external-ui", "external-ui-url", "external-ui-name", "interface-name", "routing-mark").forEach { map.remove(it) }
        map["socks-port"] = socks
        map["allow-lan"] = false
        map["bind-address"] = "127.0.0.1"
        map["authentication"] = listOf("$token:$token")
        map["skip-auth-prefixes"] = emptyList<String>()
        map["listeners"] = emptyList<Any>()
        map["tun"] = mapOf("enable" to false)
        map["external-controller"] = "127.0.0.1:$api"
        map["secret"] = token
        val profile = (map["profile"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }?.toMutableMap() ?: mutableMapOf()
        profile["store-selected"] = true
        map["profile"] = profile
        val dns = (map["dns"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }?.toMutableMap() ?: mutableMapOf()
        dns["enable"] = true
        dns["listen"] = "127.0.0.1:$dnsPort"
        if (dns["nameserver"] == null) dns["nameserver"] = listOf("https://223.5.5.5/dns-query", "https://1.1.1.1/dns-query")
        map["dns"] = dns
        return CoreConfig.yaml().dump(map)
    }

    @Synchronized fun validate(context: Context, content: String) {
        val directory = File(context.filesDir, "mihomo").also { it.mkdirs() }
        BundledGeoData.ensure(context, directory)
        val file = File.createTempFile("check-", ".yaml", context.cacheDir)
        val output = File.createTempFile("check-", ".log", context.cacheDir)
        var child: Process? = null
        try {
            file.writeText(runtimeYaml(content, 0, 0, 0, UUID.randomUUID().toString()))
            child = ProcessBuilder(executable(context), "-t", "-d", directory.path, "-f", file.path)
                .redirectErrorStream(true).redirectOutput(output).start()
            require(child.awaitExit(45)) { "Mihomo configuration check timed out" }
            // Do not include core output: it can contain subscription credentials.
            require(child.exitValue() == 0) {
                val log = output.bufferedReader().use { it.readText().takeLast(32768) }
                when {
                    log.contains("can't download") -> "Mihomo could not download required rule data. Check network access and custom geodata URLs."
                    log.contains("dns resolve failed") -> "Mihomo could not resolve a required server. Check the configuration's DNS settings."
                    else -> "Mihomo rejected this configuration. Check proxies, groups and rules."
                }
            }
        } finally {
            child?.destroy()
            file.delete()
            output.delete()
        }
    }

    @Synchronized fun prepare(context: Context, content: String): String {
        close()
        val socks = tcpPort()
        val api = generateSequence { tcpPort() }.first { it != socks }
        val dns = generateSequence { dnsPort() }.first { it != socks && it != api }
        val token = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "mihomo").also { it.mkdirs() }
        BundledGeoData.ensure(context, directory)
        val file = File(directory, "runtime.yaml")
        file.writeText(runtimeYaml(content, socks, dns, api, token))
        val child = ProcessBuilder(executable(context), "-d", directory.path, "-f", file.path)
            .redirectErrorStream(true).start()
        process = child
        secret = token
        controllerPort = api
        MihomoControl.clearLogs()
        thread(name = "mihomo-output", isDaemon = true) {
            runCatching { child.inputStream.bufferedReader().useLines { lines -> lines.forEach { MihomoControl.appendLog(it) } } }
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
            while (true) {
                check(child.running()) { "Mihomo exited during startup; check your configuration and network" }
                val ready = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", socks), 200) }; true }.getOrDefault(false)
                if (ready) break
                check(System.nanoTime() < deadline) { "Mihomo startup timed out" }
                Thread.sleep(100)
            }
            val bridge = """
                {
                  "dns": {"servers": [{"type":"udp","tag":"mihomo-dns","server":"127.0.0.1","server_port":$dns}],"final":"mihomo-dns"},
                  "inbounds": [{"type":"tun","tag":"vpn","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"mtu":1500,"auto_route":true,"exclude_package":[${JSONObject.quote(context.packageName)}]}],
                  "outbounds": [{"type":"socks","tag":"Mihomo","server":"127.0.0.1","server_port":$socks,"version":"5","username":"$token","password":"$token"}],
                  "route": {"rules":[{"action":"sniff"},{"protocol":"dns","action":"hijack-dns"}],"final":"Mihomo","auto_detect_interface":true}
                }
            """.trimIndent()
            Libbox.checkConfig(bridge)
            return bridge
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    @Synchronized fun close() {
        process?.let { child ->
            child.destroy()
            check(child.awaitExit(3)) { "Mihomo did not stop; restart the application before reconnecting" }
        }
        process = null
        controllerPort = 0
        secret = ""
    }
}
