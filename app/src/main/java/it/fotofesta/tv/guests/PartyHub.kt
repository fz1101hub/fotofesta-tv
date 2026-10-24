package it.fotofesta.tv.guests

import java.util.UUID

/**
 * Attività della festa che arrivano dalla pagina dei telefoni oltre alle foto e ai giochi: reazioni
 * alla foto sullo schermo e dediche. La TV legge da qui le reazioni da animare, le dediche da
 * mostrare o approvare, quante reazioni ha avuto ogni foto (premio "Foto della serata") e chi ha
 * partecipato (estrazione a premi).
 *
 * Thread-safe: i telefoni arrivano dai thread del server, la TV dal thread principale.
 */
class PartyHub(private val nowMillis: () -> Long = System::currentTimeMillis) {
    /** Cosa i telefoni possono fare; con [moderateMessages] le dediche aspettano l'approvazione. */
    data class Features(
        val reactions: Boolean = true,
        val messages: Boolean = true,
        val moderateMessages: Boolean = false,
    )

    /** Reazioni possibili, nello stesso ordine dei pulsanti della pagina. */
    enum class Reaction(val emoji: String) {
        HEART("❤️"), LAUGH("😂"), PARTY("🎉"), WOW("😮"), CLAP("👏"), FIRE("🔥"),
    }

    /** Reazione arrivata: [serial] cresce sempre, così la TV sa quali ha già animato. */
    data class ReactionEvent(val serial: Long, val reaction: Reaction)

    data class Message(val id: String, val text: String, val name: String?, val atMillis: Long, val approved: Boolean)

    data class Participant(val player: String, val name: String)

    /** Annuncio sullo schermo e sui telefoni; [serial] distingue due annunci uguali. */
    data class Announcement(val serial: Long, val text: String, val atMillis: Long)

    /** Invitato che dirige lo slideshow dal suo telefono fino a [untilMillis]. */
    data class Director(val player: String, val name: String, val untilMillis: Long)

    enum class Result { OK, PENDING, RATE_LIMITED, DISABLED, INVALID, FULL }

    private var features = Features()
    private var showing: String? = null
    private val reactionCounts = HashMap<String, Int>()
    private val recentReactions = ArrayDeque<ReactionEvent>()
    private var serial = 0L
    private val reactionTimes = HashMap<String, ArrayDeque<Long>>()
    private val messageTimes = HashMap<String, ArrayDeque<Long>>()
    private val messages = ArrayList<Message>()
    private val names = LinkedHashMap<String, String>()
    private var announcement: Announcement? = null
    private var announcementSerial = 0L
    private var director: Director? = null

    /** Comandi del regista di turno (avanti, indietro, pausa), da qualunque thread. */
    @Volatile var onDirectorCommand: ((PartyCommand) -> Unit)? = null

    /** Dediche cambiate (arrivata, approvata, tolta) o impostazioni cambiate, da qualunque thread. */
    @Volatile var onChange: (() -> Unit)? = null

    /** Ogni reazione accettata, da qualunque thread: la TV la anima sopra lo slideshow. */
    @Volatile var onReaction: ((ReactionEvent) -> Unit)? = null

    fun setFeatures(value: Features) {
        synchronized(this) {
            features = value
            // Senza moderazione le dediche in attesa non avrebbero più modo di comparire.
            if (!value.moderateMessages) approvePendingLocked()
        }
        onChange?.invoke()
    }

    @Synchronized
    fun features(): Features = features

    /** Foto sullo schermo in questo momento: le reazioni dei telefoni contano per lei. */
    @Synchronized
    fun showing(photoId: String?) {
        showing = photoId
    }

    /** Reazione di un telefono alla foto sullo schermo; [index] è la posizione in [Reaction]. */
    fun react(player: String, index: Int, name: String?): Result {
        val event = synchronized(this) {
            val reaction = Reaction.entries.getOrNull(index)
            when {
                !features.reactions -> return Result.DISABLED
                reaction == null || !PhoneGame.validPlayer(player) -> return Result.INVALID
                !allow(reactionTimes, player, REACTION_BURST, REACTION_WINDOW_MS) -> return Result.RATE_LIMITED
            }
            remember(player, name)
            showing?.let { reactionCounts[it] = (reactionCounts[it] ?: 0) + 1 }
            val event = ReactionEvent(++serial, reaction!!)
            recentReactions.addLast(event)
            while (recentReactions.size > MAX_RECENT_REACTIONS) recentReactions.removeFirst()
            event
        }
        onReaction?.invoke(event)
        return Result.OK
    }

    /** Reazioni arrivate dopo [afterSerial], dalle più vecchie: le ultime [MAX_RECENT_REACTIONS]. */
    @Synchronized
    fun reactionsAfter(afterSerial: Long): List<ReactionEvent> = recentReactions.filter { it.serial > afterSerial }

    @Synchronized
    fun reactionCount(photoId: String): Int = reactionCounts[photoId] ?: 0

    /** Reazioni ricevute da ogni foto, per il premio "Foto della serata". */
    @Synchronized
    fun reactionCounts(): Map<String, Int> = HashMap(reactionCounts)

    /** Dedica di un telefono: testo ripulito, al massimo [MAX_MESSAGE_LENGTH] caratteri. */
    fun message(player: String, text: String?, name: String?): Result {
        val result = synchronized(this) {
            val cleaned = GuestSender.clean(text, MAX_MESSAGE_LENGTH)
            when {
                !features.messages -> return Result.DISABLED
                cleaned == null || !PhoneGame.validPlayer(player) -> return Result.INVALID
                messages.size >= MAX_MESSAGES -> return Result.FULL
                !allow(messageTimes, player, MESSAGE_BURST, MESSAGE_WINDOW_MS) -> return Result.RATE_LIMITED
            }
            val signature = remember(player, name)
            val approved = !features.moderateMessages
            messages += Message(UUID.randomUUID().toString(), cleaned!!, signature, nowMillis(), approved)
            if (approved) Result.OK else Result.PENDING
        }
        onChange?.invoke()
        return result
    }

    /** Dediche approvate, dalla più vecchia: quelle da mostrare e il libro degli ospiti. */
    @Synchronized
    fun messages(): List<Message> = messages.filter { it.approved }

    @Synchronized
    fun pendingMessages(): List<Message> = messages.filterNot { it.approved }

    fun approveMessage(id: String) = changeMessages {
        val index = messages.indexOfFirst { it.id == id }
        if (index >= 0) messages[index] = messages[index].copy(approved = true)
    }

    fun approveAllMessages() = changeMessages { approvePendingLocked() }

    fun removeMessage(id: String) = changeMessages { messages.removeAll { it.id == id } }

    /** Chi ha partecipato con un nome (reazioni o dediche), nell'ordine in cui è arrivato. */
    @Synchronized
    fun participants(): List<Participant> = names.map { (player, name) -> Participant(player, name) }

    /** Annuncio della regia ("Il buffet è aperto!"): compare sullo schermo e sui telefoni. */
    fun announce(text: String?): Boolean {
        val cleaned = GuestSender.clean(text, MAX_ANNOUNCEMENT_LENGTH) ?: return false
        synchronized(this) { announcement = Announcement(++announcementSerial, cleaned, nowMillis()) }
        onChange?.invoke()
        return true
    }

    /** Ultimo annuncio, se è di meno di [ANNOUNCEMENT_PHONE_MS] fa. */
    @Synchronized
    fun announcement(): Announcement? = announcement?.takeIf { nowMillis() - it.atMillis < ANNOUNCEMENT_PHONE_MS }

    /**
     * Regista per due minuti: un invitato estratto tra chi ha partecipato con un nome dirige lo
     * slideshow dal suo telefono. Null se nessuno ha ancora scritto il proprio nome.
     */
    fun startDirector(random: kotlin.random.Random = kotlin.random.Random.Default, durationMillis: Long = DIRECTOR_MS): Director? {
        val chosen = synchronized(this) {
            val candidates = names.entries.toList()
            if (candidates.isEmpty()) return null
            val (player, name) = candidates[random.nextInt(candidates.size)]
            Director(player, name, nowMillis() + durationMillis).also { director = it }
        }
        onChange?.invoke()
        return chosen
    }

    fun stopDirector() {
        synchronized(this) { director = null }
        onChange?.invoke()
    }

    /** Regista di turno, se il suo tempo non è finito. */
    @Synchronized
    fun director(): Director? = director?.takeIf { nowMillis() < it.untilMillis }

    /** Fine della festa: si cancella tutto. */
    fun clear() {
        synchronized(this) {
            announcement = null
            director = null
            showing = null
            reactionCounts.clear()
            recentReactions.clear()
            reactionTimes.clear()
            messageTimes.clear()
            messages.clear()
            names.clear()
        }
        onChange?.invoke()
    }

    /** Cosa può fare la pagina dei telefoni, in JSON. */
    @Synchronized
    fun featuresJson(): String =
        """{"reactions":${features.reactions},"messages":${features.messages},"moderated":${features.moderateMessages}}"""

    /** Annuncio e regista di turno per la pagina del telefono [player], in JSON. */
    fun partyJson(player: String?): String {
        val current = announcement()
        val director = director()
        return buildString {
            append("""{"announcement":""")
            if (current == null) append("null") else {
                append("""{"id":""").append(current.serial).append(""","text":""").append(GuestUploadServer.jsonString(current.text)).append('}')
            }
            append(""","director":""")
            if (director == null) append("null") else {
                append("""{"name":""").append(GuestUploadServer.jsonString(director.name))
                append(""","you":""").append(player != null && player == director.player)
                append(""","seconds":""").append(((director.untilMillis - nowMillis()) / 1000).coerceAtLeast(0)).append('}')
            }
            append('}')
        }
    }

    /** Richiesta di un telefono (modulo "type=...&player=..."): stato HTTP e risposta JSON. */
    fun handle(form: Map<String, String>): Pair<Int, String> {
        val player = form["player"].orEmpty()
        val name = GuestSender.clean(form["name"])
        val result = when (form["type"]) {
            "react" -> react(player, form["reaction"]?.toIntOrNull() ?: -1, name)
            "message" -> message(player, form["text"], name)
            "director" -> direct(player, form["command"])
            else -> Result.INVALID
        }
        return when (result) {
            Result.OK -> 200 to """{"status":"ok"}"""
            Result.PENDING -> 200 to """{"status":"pending"}"""
            Result.RATE_LIMITED -> 429 to """{"error":"rate"}"""
            Result.DISABLED -> 403 to """{"error":"disabled"}"""
            Result.FULL -> 507 to """{"error":"full"}"""
            Result.INVALID -> 400 to """{"error":"invalid"}"""
        }
    }

    /** Comando del regista di turno: solo dal suo telefono e solo finché dura il suo turno. */
    private fun direct(player: String, command: String?): Result {
        val current = director() ?: return Result.DISABLED
        if (current.player != player) return Result.DISABLED
        val action = when (command) {
            "next" -> PartyCommand.Next
            "previous" -> PartyCommand.Previous
            "play" -> PartyCommand.Play
            "pause" -> PartyCommand.Pause
            else -> return Result.INVALID
        }
        onDirectorCommand?.invoke(action)
        return Result.OK
    }

    private inline fun changeMessages(block: () -> Unit) {
        synchronized(this) { block() }
        onChange?.invoke()
    }

    private fun approvePendingLocked() {
        for (index in messages.indices) if (!messages[index].approved) messages[index] = messages[index].copy(approved = true)
    }

    /** Ricorda il nome del telefono e restituisce quello da mostrare (l'ultimo scritto). */
    private fun remember(player: String, name: String?): String? {
        if (name != null && (player in names || names.size < MAX_TRACKED_PLAYERS)) names[player] = name
        return names[player] ?: name
    }

    /** Al massimo [burst] azioni in [windowMillis] dallo stesso telefono. */
    private fun allow(times: HashMap<String, ArrayDeque<Long>>, player: String, burst: Int, windowMillis: Long): Boolean {
        val now = nowMillis()
        val recent = times.getOrPut(player) { ArrayDeque() }
        while (recent.isNotEmpty() && now - recent.first() >= windowMillis) recent.removeFirst()
        if (recent.size >= burst) return false
        recent.addLast(now)
        // I telefoni passati di qui sono al massimo qualche centinaio: la mappa non cresce senza limite.
        if (times.size > MAX_TRACKED_PLAYERS) times.entries.removeAll { it.value.isEmpty() }
        return true
    }

    companion object {
        const val MAX_MESSAGE_LENGTH = 160
        const val MAX_ANNOUNCEMENT_LENGTH = 120
        /** L'annuncio resta sui telefoni per un minuto (sullo schermo lo decide la TV). */
        const val ANNOUNCEMENT_PHONE_MS = 60_000L
        const val DIRECTOR_MS = 120_000L
        const val MAX_MESSAGES = 500
        const val MAX_RECENT_REACTIONS = 64
        /** Cinque reazioni ogni tre secondi: abbastanza per divertirsi, troppo poche per intasare lo schermo. */
        const val REACTION_BURST = 5
        const val REACTION_WINDOW_MS = 3_000L
        /** Tre dediche al minuto per telefono. */
        const val MESSAGE_BURST = 3
        const val MESSAGE_WINDOW_MS = 60_000L
        private const val MAX_TRACKED_PLAYERS = 1_000
    }
}
