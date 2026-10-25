package it.fotofesta.tv.guests

/**
 * Muro di telefoni: gli invitati appoggiano i telefoni uno accanto all'altro sul tavolo e ognuno
 * mostra un pezzo della stessa foto, come un maxischermo. Prima i telefoni si mettono in fila
 * (ognuno riceve il suo numero), poi la TV manda la foto. Si usano solo foto approvate degli
 * invitati, che i telefoni scaricano dalla parete con la chiave della festa.
 */
class PhoneWall {
    enum class Phase(val key: String) { JOINING("joining"), SHOWING("showing") }

    data class State(val phase: Phase, val players: List<String>, val columns: Int, val rows: Int, val photoId: String?)

    /** Chiamato a ogni cambio, anche dai thread del server. */
    @Volatile var onChange: (() -> Unit)? = null

    private var phase: Phase? = null
    private val players = ArrayList<String>()
    private var photoId: String? = null
    /** Cresce a ogni foto: i telefoni ridisegnano anche se la foto è la stessa di prima. */
    private var serial = 0L
    /** Cresce a ogni gran finale: i telefoni del muro esplodono in coriandoli tutti insieme. */
    private var finale = 0L

    fun start() {
        synchronized(this) {
            phase = Phase.JOINING
            players.clear()
            photoId = null
            serial++
        }
        onChange?.invoke()
    }

    /** Manda [id] a tutti i telefoni in fila; false se il muro è spento o non c'è nessuno. */
    fun show(id: String): Boolean {
        synchronized(this) {
            if (phase == null || players.isEmpty()) return false
            phase = Phase.SHOWING
            photoId = id
            serial++
        }
        onChange?.invoke()
        return true
    }

    /** Di nuovo in fila: per cambiare la disposizione o far entrare altri telefoni. */
    fun rearrange() {
        synchronized(this) {
            if (phase == null) return
            phase = Phase.JOINING
            photoId = null
            serial++
        }
        onChange?.invoke()
    }

    /** Gran finale: coriandoli e lampi su tutti i telefoni del muro. */
    fun finale() {
        synchronized(this) {
            if (phase != Phase.SHOWING) return
            finale++
        }
        onChange?.invoke()
    }

    fun stop() {
        synchronized(this) {
            phase = null
            players.clear()
            photoId = null
        }
        onChange?.invoke()
    }

    fun state(): State? = synchronized(this) {
        val current = phase ?: return null
        val (columns, rows) = grid(players.size)
        State(current, players.toList(), columns, rows, photoId)
    }

    /** Stato per la pagina di un telefono; "null" se il muro è spento. */
    fun phoneJson(player: String?): String = synchronized(this) {
        val current = phase ?: return "null"
        val (columns, rows) = grid(players.size)
        val slot = player?.let { players.indexOf(it) }?.takeIf { it >= 0 }
        buildString {
            append("""{"phase":"""").append(current.key).append('"')
            append(""","slot":""").append(slot?.toString() ?: "null")
            append(""","count":""").append(players.size)
            append(""","columns":""").append(columns)
            append(""","rows":""").append(rows)
            append(""","photo":""").append(photoId?.let { GuestUploadServer.jsonString(it) } ?: "null")
            append(""","serial":""").append(serial)
            append(""","finale":""").append(finale)
            append('}')
        }
    }

    /** Richiesta "type=wall&command=join|leave" di un telefono: stato HTTP e risposta JSON. */
    fun handle(form: Map<String, String>): Pair<Int, String> {
        val player = form["player"]?.takeIf(PhoneGame::validPlayer) ?: return 400 to """{"error":"invalid"}"""
        val result = synchronized(this) {
            when {
                phase == null -> 403 to """{"error":"disabled"}"""
                form["command"] == "leave" -> {
                    players.remove(player)
                    200 to """{"status":"ok"}"""
                }
                form["command"] != "join" -> 400 to """{"error":"invalid"}"""
                player in players -> 200 to """{"status":"ok"}"""
                // A foto già mandata la disposizione è fissata: si entra al prossimo giro.
                phase == Phase.SHOWING -> 409 to """{"error":"showing"}"""
                players.size >= MAX_PHONES -> 507 to """{"error":"full"}"""
                else -> {
                    players += player
                    200 to """{"status":"ok"}"""
                }
            }
        }
        if (result.first == 200) onChange?.invoke()
        return result
    }

    companion object {
        const val MAX_PHONES = 12

        /**
         * Colonne e righe per [count] telefoni in verticale: fino a quattro in fila, poi due o tre
         * file. Le foto sono quasi sempre orizzontali, quindi si preferisce allargare.
         */
        fun grid(count: Int): Pair<Int, Int> {
            val rows = when {
                count <= 4 -> 1
                count <= 8 -> 2
                else -> 3
            }
            val columns = ((count.coerceAtLeast(1) + rows - 1) / rows)
            return columns to rows
        }
    }
}
