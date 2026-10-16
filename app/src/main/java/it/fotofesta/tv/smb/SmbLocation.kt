package it.fotofesta.tv.smb

import java.net.URLDecoder

/**
 * Cartella o file di un computer o NAS con le cartelle condivise (SMB): computer, condivisione e
 * percorso con "/" (vuoto = cartella principale della condivisione). Senza condivisione indica
 * l'elenco delle condivisioni del computer.
 */
data class SmbLocation(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val share: String = "",
    val path: String = "",
) {
    /** Chiave del computer: accesso e connessione valgono per tutte le sue condivisioni. */
    val server: String get() = "$host:$port"

    val name: String get() = path.substringAfterLast('/').ifEmpty { share.ifEmpty { host } }

    /** Percorso dentro la condivisione come lo vuole SMB, con "\". */
    val smbPath: String get() = path.replace('/', '\\')

    /** Percorso nel browser di cartelle: "smb://computer:porta/condivisione/cartella". */
    val browserPath: String
        get() = buildString {
            append(PREFIX).append(host).append(':').append(port)
            if (share.isNotEmpty()) {
                append('/').append(share)
                if (path.isNotEmpty()) append('/').append(path)
            }
        }

    fun child(name: String): SmbLocation = when {
        share.isEmpty() -> copy(share = name, path = "")
        path.isEmpty() -> copy(path = name)
        else -> copy(path = "$path/$name")
    }

    /** Cartella superiore; dalla condivisione si torna all'elenco delle condivisioni, poi null. */
    fun parent(): SmbLocation? = when {
        path.isNotEmpty() -> copy(path = path.substringBeforeLast('/', ""))
        share.isNotEmpty() -> copy(share = "")
        else -> null
    }

    companion object {
        const val DEFAULT_PORT = 445
        private const val PREFIX = "smb://"

        /** Percorso del browser ([browserPath]); null se non è una cartella condivisa. */
        fun parse(browserPath: String): SmbLocation? {
            if (!browserPath.startsWith(PREFIX)) return null
            val rest = browserPath.removePrefix(PREFIX)
            val authority = rest.substringBefore('/')
            val colon = authority.lastIndexOf(':')
            if (colon <= 0) return null
            val port = authority.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65_535 } ?: return null
            val segments = rest.substringAfter('/', "").split('/').filter { it.isNotEmpty() }
            return SmbLocation(authority.substring(0, colon), port, segments.firstOrNull().orEmpty(), segments.drop(1).joinToString("/"))
        }
    }
}

/**
 * Indirizzo scritto con il telecomando: "192.168.1.20", "nas.local", "NAS:4450", "\\NAS\Foto\2024"
 * oppure "smb://nas/Foto". Condivisione e cartella, se ci sono, si aprono subito dopo l'accesso.
 */
object SmbAddress {
    private val HOST = Regex("^[A-Za-z0-9._-]{1,253}$")
    private val IPV6 = Regex("^[0-9A-Fa-f:.]{2,45}(%[A-Za-z0-9]+)?$")

    fun parse(input: String): SmbLocation? {
        var text = input.trim()
        if (text.startsWith("smb://", ignoreCase = true)) {
            text = runCatching { URLDecoder.decode(text.substring(6).replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
        }
        text = text.replace('\\', '/').trimStart('/')
        // "utente@nas": l'utente si scrive nel suo campo, qui conta il computer.
        val authority = text.substringBefore('/').substringAfterLast('@')
        val host: String
        val port: Int?
        when {
            authority.startsWith("[") -> {
                val end = authority.indexOf(']')
                if (end < 0) return null
                host = authority.substring(1, end)
                val after = authority.substring(end + 1)
                port = if (after.isEmpty()) null else after.removePrefix(":").toIntOrNull() ?: return null
                if (!IPV6.matches(host)) return null
            }
            authority.count { it == ':' } > 1 -> {
                host = authority
                port = null
                if (!IPV6.matches(host)) return null
            }
            else -> {
                host = authority.substringBefore(':')
                port = if (':' in authority) authority.substringAfter(':').toIntOrNull() ?: return null else null
                if (!HOST.matches(host)) return null
            }
        }
        if (port != null && port !in 1..65_535) return null
        val segments = text.substringAfter('/', "").split('/').map { it.trim() }.filter { it.isNotEmpty() && it != "." && it != ".." }
        return SmbLocation(host, port ?: SmbLocation.DEFAULT_PORT, segments.firstOrNull().orEmpty(), segments.drop(1).joinToString("/"))
    }
}

/**
 * Accesso a un computer. Senza nome utente si entra come ospite, come fanno molti NAS per le
 * cartelle pubbliche. Il nome può contenere il dominio: "UFFICIO\mario" o "mario@ufficio".
 */
data class SmbLogin(val user: String = "", val password: String = "", val domain: String = "") {
    val guest: Boolean get() = user.isBlank()

    /** La password non finisce nei log. */
    override fun toString(): String = "SmbLogin(user=$user, domain=$domain, guest=$guest)"

    companion object {
        fun of(user: String, password: String): SmbLogin {
            val name = user.trim()
            return when {
                '\\' in name -> SmbLogin(name.substringAfter('\\'), password, name.substringBefore('\\'))
                // "mario@ufficio" è un nome UPN: NTLM lo accetta come nome utente senza dominio.
                else -> SmbLogin(name, if (name.isEmpty()) "" else password)
            }
        }
    }
}

/** Cartella condivisa scelta: si ritrova all'avvio e nel salvaschermo. La password è salvata a parte, cifrata. */
data class SmbFolder(val location: SmbLocation, val title: String, val user: String, val domain: String)
