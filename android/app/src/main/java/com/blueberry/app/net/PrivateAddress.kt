package com.blueberry.app.net

/**
 * Cleartext HTTP разрешён только для локальных адресов: 10.x, 172.16–31.x, 192.168.x,
 * link-local 169.254.x, loopback и имена *.local. Любой другой хост клиент отвергает.
 */
object PrivateAddress {
    fun isAllowedHost(host: String): Boolean {
        val h = host.trim().trimStart('[').trimEnd(']').lowercase()
        if (h.isEmpty()) return false
        if (h == "localhost" || h.endsWith(".local")) return true
        parseIpv4(h)?.let { return isPrivateV4(it) }
        if (h.contains(':')) return h == "::1" || h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")
        return false
    }

    private fun parseIpv4(h: String): IntArray? {
        val parts = h.split('.')
        if (parts.size != 4) return null
        val nums = parts.map { it.toIntOrNull() ?: return null }
        if (nums.any { it !in 0..255 }) return null
        return nums.toIntArray()
    }

    private fun isPrivateV4(a: IntArray): Boolean = when {
        a[0] == 10 -> true
        a[0] == 172 && a[1] in 16..31 -> true
        a[0] == 192 && a[1] == 168 -> true
        a[0] == 169 && a[1] == 254 -> true
        a[0] == 127 -> true
        else -> false
    }
}
