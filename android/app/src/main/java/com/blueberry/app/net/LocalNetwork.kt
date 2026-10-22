package com.blueberry.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.InetAddress
import javax.net.SocketFactory

/**
 * Выбор сети для связи с маком. Если телефон подключён к Wi-Fi без интернета, Android может сделать
 * основной сетью мобильную, и сокеты без привязки уйдут мимо Wi-Fi. Поэтому для локального адреса берём
 * Wi-Fi/Ethernet-сеть, в подсети которой лежит адрес, и открываем сокеты через её socketFactory.
 *
 * Когда телефон сам раздаёт hotspot, клиентской Wi-Fi сети у него нет: мак подключён к самому телефону,
 * трафик идёт по локальному интерфейсу напрямую. Привязка в этом случае не нужна и не применяется.
 */
class LocalNetwork(context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun localNetworks(): List<Network> = cm.allNetworks.filter { n ->
        val caps = cm.getNetworkCapabilities(n) ?: return@filter false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** Есть ли клиентское Wi-Fi/Ethernet подключение (для различения «нет сети» и «сервер недоступен»). */
    fun hasLocalNetwork(): Boolean = localNetworks().isNotEmpty()

    /** Сеть, в подсети которой лежит [host]; null — привязка не нужна/невозможна. */
    fun networkFor(host: String): Network? {
        val nets = localNetworks()
        if (nets.isEmpty()) return null
        val ip: InetAddress? = if (host.any { it.isLetter() }) null else runCatching { InetAddress.getByName(host) }.getOrNull()
        if (ip == null) return nets.first() // имя *.local: просто Wi-Fi
        return nets.firstOrNull { n ->
            cm.getLinkProperties(n)?.linkAddresses?.any { la ->
                sameSubnet(la.address.address, ip.address, la.prefixLength)
            } == true
        }
    }

    fun socketFactoryFor(host: String): SocketFactory? = networkFor(host)?.socketFactory

    companion object {
        fun sameSubnet(a: ByteArray, b: ByteArray, prefix: Int): Boolean {
            if (a.size != b.size) return false
            var bits = prefix
            for (i in a.indices) {
                if (bits <= 0) return true
                val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
                if ((a[i].toInt() and mask) != (b[i].toInt() and mask)) return false
                bits -= 8
            }
            return true
        }
    }
}
