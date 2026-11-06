package io.nekohasekai.sfa.utils

import android.content.Context
import android.content.pm.PackageInfo
import io.nekohasekai.sfa.bg.ParceledListSlice
import io.nekohasekai.sfa.xposed.HookStatusKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object HookStatusClient {
    data class Status(val active: Boolean, val lastPatchedAt: Long, val version: Int, val systemPid: Int)

    private val statusFlow = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = statusFlow

    @Volatile
    private var appContext: Context? = null

    fun register(context: Context) {
        appContext = context.applicationContext
        refresh()
    }

    fun refresh() {
        refreshStatus { readStatus() }
    }

    // A separately installed system hook can reject forks or incompatible clients.
    // It is optional: denial must clear stale privileges and leave the app usable.
    internal fun refreshStatus(readStatus: () -> Status?) {
        statusFlow.value = optionalHookRequest(readStatus)
    }

    private fun readStatus(): Status? {
        val context = appContext ?: return null
        val binder = ConnectivityBinderUtils.getBinder(context) ?: return null
        return ConnectivityBinderUtils.withParcel { data, reply ->
            data.writeInterfaceToken(HookStatusKeys.DESCRIPTOR)
            val ok = binder.transact(HookStatusKeys.TRANSACTION_STATUS, data, reply, 0)
            if (!ok) {
                return@withParcel null
            }
            reply.readException()
            Status(
                active = reply.readInt() != 0,
                lastPatchedAt = reply.readLong(),
                version = reply.readInt(),
                systemPid = reply.readInt(),
            )
        }
    }

    fun getInstalledPackages(context: Context, flags: Long, userId: Int): List<PackageInfo>? {
        return optionalHookRequest { queryInstalledPackages(context, flags, userId) }
    }

    private fun queryInstalledPackages(context: Context, flags: Long, userId: Int): List<PackageInfo>? {
        val binder = ConnectivityBinderUtils.getBinder(context) ?: return null
        return ConnectivityBinderUtils.withParcel { data, reply ->
            data.writeInterfaceToken(HookStatusKeys.DESCRIPTOR)
            data.writeLong(flags)
            data.writeInt(userId)
            val ok = binder.transact(HookStatusKeys.TRANSACTION_GET_INSTALLED_PACKAGES, data, reply, 0)
            if (!ok) return@withParcel null
            reply.readException()
            val slice = ParceledListSlice.CREATOR.createFromParcel(reply, PackageInfo::class.java.classLoader)
            @Suppress("UNCHECKED_CAST")
            (slice as ParceledListSlice<PackageInfo>).list
        }
    }

    private fun <T> optionalHookRequest(request: () -> T?): T? = try {
        request()
    } catch (_: Exception) {
        // No authorization, incompatible protocol, or dead Binder: use ordinary APIs.
        null
    }
}
