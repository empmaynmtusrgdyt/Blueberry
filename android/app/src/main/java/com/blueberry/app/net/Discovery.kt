package com.blueberry.app.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class DiscoveredServer(val name: String, val host: String, val port: Int)

/**
 * Автообнаружение `_blueberry._tcp` через NsdManager. mDNS на hotspot Android работает не на всех устройствах,
 * поэтому это второй способ после сохранённых данных QR, а ручной ввод всегда остаётся запасным.
 */
class Discovery(private val context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val resolveMutex = Mutex() // NsdManager.resolveService не любит параллельные вызовы

    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo): DiscoveredServer? = resolveMutex.withLock {
        suspendCancellableCoroutine { cont ->
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host?.hostAddress
                    if (cont.isActive) cont.resume(
                        if (host != null) DiscoveredServer(serviceInfo.serviceName, host, serviceInfo.port) else null
                    )
                }
            })
        }
    }

    /** Поток найденных серверов; подписка запускает поиск, отмена останавливает. */
    fun discover(): Flow<DiscoveredServer> = callbackFlow {
        val lock = wifi.createMulticastLock("blueberry-mdns").apply { setReferenceCounted(false); acquire() }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                scope.launch { resolve(serviceInfo)?.let { trySend(it) } }
            }
        }
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            if (lock.isHeld) lock.release()
        }
    }

    companion object { const val SERVICE_TYPE = "_blueberry._tcp." }
}
