package it.fotofesta.tv.guests

/** Rete Wi‑Fi di casa, per il QR che fa entrare gli invitati senza dettare la password. */
data class WifiNetwork(val ssid: String, val password: String) {
    val isOpen: Boolean get() = password.isEmpty()

    /**
     * Testo del QR nel formato riconosciuto dalle fotocamere di Android e iPhone:
     * `WIFI:T:WPA;S:<nome>;P:<password>;;`. WPA vale anche per WPA2 e per le reti WPA3 in modalità mista.
     */
    fun qrPayload(): String = buildString {
        append("WIFI:")
        append(if (isOpen) "T:nopass;" else "T:WPA;")
        append("S:").append(escape(ssid)).append(';')
        if (!isOpen) append("P:").append(escape(password)).append(';')
        append(';')
    }

    companion object {
        const val MAX_SSID_LENGTH = 32
        const val MIN_PASSWORD_LENGTH = 8
        const val MAX_PASSWORD_LENGTH = 63

        /** Errore da mostrare per i valori inseriti, oppure null se la rete è valida. */
        fun validate(ssid: String, password: String): Problem? = when {
            ssid.isBlank() -> Problem.MISSING_NAME
            ssid.toByteArray(Charsets.UTF_8).size > MAX_SSID_LENGTH -> Problem.NAME_TOO_LONG
            password.isNotEmpty() && password.length !in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH -> Problem.BAD_PASSWORD
            else -> null
        }

        /** Nel formato WIFI: i caratteri `\ ; , : "` vanno preceduti da una barra rovesciata. */
        internal fun escape(value: String): String = buildString {
            value.forEach { char ->
                if (char in "\\;,:\"") append('\\')
                append(char)
            }
        }
    }

    enum class Problem { MISSING_NAME, NAME_TOO_LONG, BAD_PASSWORD }
}
