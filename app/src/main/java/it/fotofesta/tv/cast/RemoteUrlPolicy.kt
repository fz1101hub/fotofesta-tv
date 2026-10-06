package it.fotofesta.tv.cast

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Regole per gli URL ricevuti dai sender Cast: solo HTTPS sulla porta standard, senza credenziali
 * e mai verso host locali, privati o scritti in forme numeriche ambigue (ottale, esadecimale,
 * intero singolo) che resolver diversi interpretano in modo diverso.
 *
 * La verifica è sintattica: non protegge dal DNS rebinding, che va gestito a livello di rete.
 */
internal object RemoteUrlPolicy {
    fun isAllowed(value: String): Boolean = runCatching {
        if (value.length !in 10..2_048) return@runCatching false
        val uri = URI(value)
        if (uri.scheme?.lowercase() != "https" || uri.rawUserInfo != null || uri.port !in listOf(-1, 443)) {
            return@runCatching false
        }
        val host = uri.host ?: return@runCatching false
        !isLocalOrAmbiguousHost(host)
    }.getOrDefault(false)

    fun isLocalOrAmbiguousHost(rawHost: String): Boolean {
        val host = rawHost.lowercase().removePrefix("[").removeSuffix("]").removeSuffix(".")
        if (host.isEmpty()) return true
        if (':' in host) return isLocalIpv6(host)
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
            host.endsWith(".internal") || host.endsWith(".home.arpa")
        ) return true
        val lastLabel = host.substringAfterLast('.')
        val looksNumeric = lastLabel.isNotEmpty() &&
            (lastLabel.all(Char::isDigit) || lastLabel.startsWith("0x"))
        if (!looksNumeric) return false
        val octets = strictIpv4(host) ?: return true
        return isLocalIpv4(octets)
    }

    /** Accetta solo la forma decimale puntata canonica, senza zeri iniziali. */
    private fun strictIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for ((index, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3 || !part.all(Char::isDigit)) return null
            if (part.length > 1 && part.startsWith('0')) return null
            octets[index] = part.toInt().takeIf { it in 0..255 } ?: return null
        }
        return octets
    }

    private fun isLocalIpv4(o: IntArray): Boolean =
        o[0] == 0 || o[0] == 10 || o[0] == 127 ||
            (o[0] == 100 && o[1] in 64..127) ||
            (o[0] == 169 && o[1] == 254) ||
            (o[0] == 172 && o[1] in 16..31) ||
            (o[0] == 192 && o[1] == 168) ||
            (o[0] == 192 && o[1] == 0 && o[2] == 0) ||
            (o[0] == 198 && o[1] in 18..19) ||
            o[0] >= 224

    private fun isLocalIpv6(host: String): Boolean {
        if (host.any { !(it.isLetterOrDigit() || it == ':' || it == '.') }) return true
        // Con ':' nel testo InetAddress interpreta un letterale e non esegue risoluzioni DNS.
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return true
        if (address is Inet4Address) {
            return isLocalIpv4(IntArray(4) { address.address[it].toInt() and 0xff })
        }
        if (address !is Inet6Address) return true
        val bytes = address.address
        val mappedOrCompatible = (0 until 10).all { bytes[it] == 0.toByte() } &&
            ((bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()) ||
                (bytes[10] == 0.toByte() && bytes[11] == 0.toByte()))
        if (mappedOrCompatible) {
            return isLocalIpv4(IntArray(4) { bytes[12 + it].toInt() and 0xff })
        }
        return address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress ||
            (bytes[0].toInt() and 0xfe) == 0xfc
    }
}
