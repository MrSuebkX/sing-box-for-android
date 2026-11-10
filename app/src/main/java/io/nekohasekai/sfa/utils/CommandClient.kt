package io.nekohasekai.sfa.utils

import android.util.Log
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogEntry
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.sfa.ktx.toList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

open class CommandClient(
    private val scope: CoroutineScope,
    private val connectionTypes: List<ConnectionType>,
    private val handler: Handler,
    private val localOnly: Boolean = false,
) {
    constructor(
        scope: CoroutineScope,
        connectionType: ConnectionType,
        handler: Handler,
        localOnly: Boolean = false,
    ) : this(scope, listOf(connectionType), handler, localOnly)

    private val additionalHandlers = mutableListOf<Handler>()
    private var cachedGroups: MutableList<OutboundGroup>? = null
    private var cachedOutbounds: List<io.nekohasekai.libbox.OutboundGroupItem>? = null

    fun addHandler(handler: Handler) {
        synchronized(additionalHandlers) {
            if (!additionalHandlers.contains(handler)) {
                additionalHandlers.add(handler)
                cachedGroups?.let { groups ->
                    handler.updateGroups(groups)
                }
                cachedOutbounds?.let { outbounds ->
                    handler.updateOutbounds(outbounds)
                }
            }
        }
    }

    fun removeHandler(handler: Handler) {
        synchronized(additionalHandlers) {
            additionalHandlers.remove(handler)
        }
    }

    private fun getAllHandlers(): List<Handler> = synchronized(additionalHandlers) {
        listOf(handler) + additionalHandlers
    }

    enum class ConnectionType {
        Status,
        Groups,
        Log,
        ClashMode,
        Connections,
        Outbounds,
    }

    interface Handler {
        fun onConnected() {}

        fun onDisconnected() {}

        fun onConnectionError(message: String) {}

        fun updateStatus(status: StatusMessage) {}

        fun setDefaultLogLevel(level: Int) {}

        fun clearLogs() {}

        fun appendLogs(message: List<LogEntry>) {}

        fun updateGroups(newGroups: MutableList<OutboundGroup>) {}

        fun updateOutbounds(outbounds: List<io.nekohasekai.libbox.OutboundGroupItem>) {}

        fun initializeClashMode(modeList: List<String>, currentMode: String) {}

        fun updateClashMode(newMode: String) {}

        fun writeConnectionEvents(events: ConnectionEvents) {}
    }

    private val access = Any()
    private var connectionEpoch = 0
    private var commandClient: io.nekohasekai.libbox.CommandClient? = null
    private var mihomoJob: kotlinx.coroutines.Job? = null

    private fun startMihomoUpdates(epoch: Int) {
        mihomoJob?.cancel()
        mihomoJob = scope.launch(Dispatchers.IO) {
            val connections = Libbox.newMihomoConnections()
            var logCursor = 0L
            if (ConnectionType.Log in connectionTypes) getAllHandlers().forEach { it.clearLogs(); it.setDefaultLogLevel(4) }
            while (isActiveEpoch(epoch) && io.nekohasekai.sfa.mihomo.MihomoControl.active) {
                try {
                    val api = io.nekohasekai.sfa.mihomo.MihomoControl
                    if (ConnectionType.Groups in connectionTypes) {
                        val iterator = Libbox.parseMihomoGroups(api.request("/proxies").toString())
                        val groups = mutableListOf<OutboundGroup>()
                        while (iterator.hasNext()) { val group = iterator.next(); group.isExpand = api.expanded(group.tag); groups.add(group) }
                        if (!isActiveEpoch(epoch)) break
                        cachedGroups = groups
                        if (ConnectionType.Groups in connectionTypes) getAllHandlers().forEach { it.updateGroups(groups) }
                    }
                    if (ConnectionType.ClashMode in connectionTypes) {
                        val mode = api.request("/configs").getString("mode")
                        if (isActiveEpoch(epoch)) getAllHandlers().forEach { it.initializeClashMode(listOf("rule", "global", "direct"), mode) }
                    }
                    if (ConnectionType.Connections in connectionTypes) {
                        val events = connections.update(api.request("/connections").toString())
                        if (isActiveEpoch(epoch)) getAllHandlers().forEach { it.writeConnectionEvents(events) }
                    }
                    if (ConnectionType.Log in connectionTypes) {
                        val snapshot = api.logSnapshot(logCursor)
                        val entries = snapshot.map { (_, line) -> LogEntry().apply {
                            level = when { line.contains("level=error") -> 2; line.contains("level=warning") -> 3; line.contains("level=debug") -> 5; else -> 4 }
                            message = "[mihomo] $line"
                        } }
                        snapshot.lastOrNull()?.let { logCursor = it.first }
                        if (entries.isNotEmpty() && isActiveEpoch(epoch)) getAllHandlers().forEach { it.appendLogs(entries) }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    if (isActiveEpoch(epoch)) handler.onConnectionError("Mihomo: ${e.message}")
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    fun connect() {
        mihomoJob?.cancel()
        val epoch: Int
        val previousClient: io.nekohasekai.libbox.CommandClient?
        synchronized(access) {
            epoch = ++connectionEpoch
            previousClient = commandClient
            commandClient = null
        }
        // A remote connect dials over the network and blocks until the probe
        // completes, so it must run off the main thread.
        if (previousClient != null) {
            // The dropped Go-side Disconnected callback is suppressed by the epoch
            // bump, so the owner-initiated disconnect is reported deterministically.
            getAllHandlers().forEach { it.onDisconnected() }
        }
        scope.launch(Dispatchers.IO) {
            previousClient?.apply {
                runCatching {
                    disconnect()
                }
            }
            val options = CommandClientOptions()
            connectionTypes.forEach { connectionType ->
                val command =
                    when (connectionType) {
                        ConnectionType.Status -> Libbox.CommandStatus
                        ConnectionType.Groups -> Libbox.CommandGroup
                        ConnectionType.Log -> Libbox.CommandLog
                        ConnectionType.ClashMode -> Libbox.CommandClashMode
                        ConnectionType.Connections -> Libbox.CommandConnections
                        ConnectionType.Outbounds -> Libbox.CommandOutbounds
                    }
                options.addCommand(command)
            }
            options.statusInterval = 1 * 1000 * 1000 * 1000
            val remoteServer = if (localOnly) null else CommandTarget.remoteServer
            val newClient: io.nekohasekai.libbox.CommandClient
            try {
                newClient =
                    if (remoteServer != null) {
                        Libbox.newRemoteCommandClient(
                            ClientHandler(epoch),
                            options,
                            CommandTarget.libboxOptions(remoteServer),
                        )
                    } else {
                        io.nekohasekai.libbox.CommandClient(ClientHandler(epoch), options)
                    }
                newClient.connect()
                if (isActiveEpoch(epoch) && io.nekohasekai.sfa.mihomo.MihomoControl.active) startMihomoUpdates(epoch)
            } catch (e: Exception) {
                Log.d("CommandClient", "connect failed", e)
                if (isActiveEpoch(epoch)) {
                    handler.onConnectionError(e.message ?: e.toString())
                }
                return@launch
            }
            val stale =
                synchronized(access) {
                    if (epoch != connectionEpoch) {
                        true
                    } else {
                        commandClient = newClient
                        false
                    }
                }
            if (stale) {
                runCatching {
                    newClient.disconnect()
                }
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun disconnect() {
        mihomoJob?.cancel()
        mihomoJob = null
        val client: io.nekohasekai.libbox.CommandClient?
        synchronized(access) {
            connectionEpoch++
            client = commandClient
            commandClient = null
        }
        if (client != null) {
            getAllHandlers().forEach { it.onDisconnected() }
            // The owning scope may already be cancelled when this is called from
            // ViewModel.onCleared, so the connection is released independently.
            GlobalScope.launch(Dispatchers.IO) {
                runCatching {
                    client.disconnect()
                }
            }
        }
    }

    private fun isActiveEpoch(epoch: Int): Boolean = synchronized(access) { epoch == connectionEpoch }

    private inner class ClientHandler(private val epoch: Int) : CommandClientHandler {
        override fun connected() {
            if (!isActiveEpoch(epoch)) return
            getAllHandlers().forEach { it.onConnected() }
            Log.d("CommandClient", "connected")
        }

        override fun disconnected(message: String?) {
            if (!isActiveEpoch(epoch)) return
            getAllHandlers().forEach { it.onDisconnected() }
            if (message != null) {
                handler.onConnectionError(message)
            }
            Log.d("CommandClient", "disconnected: $message")
        }

        override fun writeGroups(message: OutboundGroupIterator?) {
            if (io.nekohasekai.sfa.mihomo.MihomoControl.active) return
            if (message == null || !isActiveEpoch(epoch)) {
                return
            }
            val groups = mutableListOf<OutboundGroup>()
            while (message.hasNext()) {
                groups.add(message.next())
            }
            cachedGroups = groups
            getAllHandlers().forEach { it.updateGroups(groups) }
        }

        override fun writeOutbounds(message: OutboundGroupItemIterator?) {
            if (message == null || !isActiveEpoch(epoch)) {
                return
            }
            val outbounds = mutableListOf<io.nekohasekai.libbox.OutboundGroupItem>()
            while (message.hasNext()) {
                outbounds.add(message.next())
            }
            cachedOutbounds = outbounds
            getAllHandlers().forEach { it.updateOutbounds(outbounds) }
        }

        override fun setDefaultLogLevel(level: Int) {
            if (io.nekohasekai.sfa.mihomo.MihomoControl.active) return
            if (!isActiveEpoch(epoch)) return
            getAllHandlers().forEach { it.setDefaultLogLevel(level) }
        }

        override fun clearLogs() {
            if (io.nekohasekai.sfa.mihomo.MihomoControl.active) return
            if (!isActiveEpoch(epoch)) return
            getAllHandlers().forEach { it.clearLogs() }
        }

        override fun writeLogs(messageList: LogIterator?) {
            if (io.nekohasekai.sfa.mihomo.MihomoControl.active) return
            if (messageList == null || !isActiveEpoch(epoch)) {
                return
            }
            val logs = messageList.toList()
            getAllHandlers().forEach { it.appendLogs(logs) }
        }

        override fun writeStatus(message: StatusMessage) {
            if (!isActiveEpoch(epoch)) return
            getAllHandlers().forEach { it.updateStatus(message) }
        }

        override fun initializeClashMode(modeList: StringIterator, currentMode: String) {
            if (io.nekohasekai.sfa.mihomo.MihomoControl.active) return
            if (!isActiveEpoch(epoch)) return
            val modes = modeList.toList()
            getAllHandlers().forEach { it.initializeClashMode(modes, currentMode) }
        }

        override fun updateClashMode(newMode: String) {
            if (io.nekohasekai.sfa.mihomo.MihomoControl.active) return
            if (!isActiveEpoch(epoch)) return
            getAllHandlers().forEach { it.updateClashMode(newMode) }
        }

        override fun writeConnectionEvents(events: ConnectionEvents?) {
            if (io.nekohasekai.sfa.mihomo.MihomoControl.active) return
            if (events == null || !isActiveEpoch(epoch)) return
            getAllHandlers().forEach { it.writeConnectionEvents(events) }
        }
    }
}
