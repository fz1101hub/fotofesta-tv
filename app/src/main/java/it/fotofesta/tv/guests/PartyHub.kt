package it.fotofesta.tv.guests

import org.json.JSONArray
import org.json.JSONObject
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

    /** Invitato che ha inquadrato il QR dell'ingresso: [order] è la sua posizione d'arrivo, da 1. */
    data class Arrival(val player: String, val name: String, val atMillis: Long, val order: Int)

    enum class Result { OK, PENDING, RATE_LIMITED, DISABLED, INVALID, FULL, QUIET }

    /**
     * Regole per le foto degli invitati: al massimo [perGuest] foto a testa (0 = nessun limite) e,
     * con [disposable], il rullino usa e getta: le foto si vedono solo dopo lo sviluppo.
     */
    data class PhotoRules(val perGuest: Int = 0, val disposable: Boolean = false)

    /** Esito di [takePhoto]: si può mandare la foto, il rullino è finito, o è il momento senza telefoni. */
    enum class PhotoTake { ALLOWED, LIMIT, QUIET, NO_PLAYER }

    private var features = Features()
    private var showing: String? = null
    private val reactionCounts = HashMap<String, Int>()
    private val recentReactions = ArrayDeque<ReactionEvent>()
    private var serial = 0L
    private val reactionTimes = HashMap<String, ArrayDeque<Long>>()
    private val messageTimes = HashMap<String, ArrayDeque<Long>>()
    private val messages = ArrayList<Message>()
    private val names = LinkedHashMap<String, String>()
    /** Reazioni per tipo (cuore, risata...), per "La festa in numeri". */
    private val reactionKinds = IntArray(Reaction.entries.size)
    private var announcement: Announcement? = null
    private var announcementSerial = 0L
    private var director: Director? = null
    private var rules = PhotoRules()
    private val photosByPlayer = HashMap<String, Int>()
    private var developed = false
    private var quietUntil = 0L
    /** Arrivi dal QR dell'ingresso, in ordine. */
    private val arrivals = LinkedHashMap<String, Arrival>()

    /** Comandi del regista di turno (avanti, indietro, pausa), da qualunque thread. */
    @Volatile var onDirectorCommand: ((PartyCommand) -> Unit)? = null

    /** Dediche cambiate (arrivata, approvata, tolta) o impostazioni cambiate, da qualunque thread. */
    @Volatile var onChange: (() -> Unit)? = null

    /** Ogni reazione accettata, da qualunque thread: la TV la anima sopra lo slideshow. */
    @Volatile var onReaction: ((ReactionEvent) -> Unit)? = null

    /** Un invitato appena arrivato (QR dell'ingresso), da qualunque thread: la TV gli dà il benvenuto. */
    @Volatile var onArrival: ((Arrival) -> Unit)? = null

    /**
     * Arrivo di un invitato dal QR dell'ingresso. Il benvenuto parte una volta sola per telefono;
     * un secondo arrivo aggiorna solo il nome.
     */
    fun arrive(player: String, name: String?): Result {
        if (!PhoneGame.validPlayer(player) || name == null) return Result.INVALID
        val arrival = synchronized(this) {
            remember(player, name)
            val known = arrivals[player]
            when {
                known != null -> {
                    arrivals[player] = known.copy(name = name)
                    null
                }
                arrivals.size >= MAX_TRACKED_PLAYERS -> return Result.FULL
                else -> Arrival(player, name, nowMillis(), arrivals.size + 1).also { arrivals[player] = it }
            }
        }
        arrival?.let { onArrival?.invoke(it) }
        onChange?.invoke()
        return Result.OK
    }

    /** Arrivi in ordine, dal primo all'ultimo. */
    @Synchronized
    fun arrivals(): List<Arrival> = arrivals.values.toList()

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
            reactionKinds[reaction!!.ordinal]++
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
    fun message(player: String, text: String?, name: String?, anonymous: Boolean = false): Result {
        val result = synchronized(this) {
            val cleaned = GuestSender.clean(text, MAX_MESSAGE_LENGTH)
            when {
                !features.messages -> return Result.DISABLED
                cleaned == null || !PhoneGame.validPlayer(player) -> return Result.INVALID
                messages.size >= MAX_MESSAGES -> return Result.FULL
                !allow(messageTimes, player, MESSAGE_BURST, MESSAGE_WINDOW_MS) -> return Result.RATE_LIMITED
            }
            // Un complimento segreto resta anonimo anche per chi ha già scritto il proprio nome.
            val signature = remember(player, name).takeUnless { anonymous }
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

    /** Quante reazioni di ogni tipo, nell'ordine di [Reaction]. */
    @Synchronized
    fun reactionTotals(): List<Int> = reactionKinds.toList()

    /** Il telefono [player] partecipa a un gioco con [name]: il nome resta per estrazione e regista. */
    fun join(player: String, name: String?): String? {
        if (!PhoneGame.validPlayer(player)) return null
        return synchronized(this) { remember(player, GuestSender.clean(name)) }
    }

    @Synchronized
    fun nameOf(player: String): String? = names[player]

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

    fun setPhotoRules(value: PhotoRules) {
        synchronized(this) { rules = value }
        onChange?.invoke()
    }

    @Synchronized
    fun photoRules(): PhotoRules = rules

    /**
     * Il telefono [player] vuole mandare una foto: con un limite a testa la si conta subito (se poi
     * la TV la rifiuta si restituisce con [returnPhoto]).
     */
    @Synchronized
    fun takePhoto(player: String?): PhotoTake {
        if (nowMillis() < quietUntil) return PhotoTake.QUIET
        if (rules.perGuest <= 0) return PhotoTake.ALLOWED
        if (player == null || !PhoneGame.validPlayer(player)) return PhotoTake.NO_PLAYER
        val taken = photosByPlayer[player] ?: 0
        if (taken >= rules.perGuest) return PhotoTake.LIMIT
        if (photosByPlayer.size < MAX_TRACKED_PLAYERS || player in photosByPlayer) photosByPlayer[player] = taken + 1
        return PhotoTake.ALLOWED
    }

    /** Foto contata da [takePhoto] ma non salvata (memoria piena, formato sbagliato): lo scatto torna. */
    @Synchronized
    fun returnPhoto(player: String?) {
        if (player == null) return
        val taken = photosByPlayer[player] ?: return
        if (taken <= 1) photosByPlayer.remove(player) else photosByPlayer[player] = taken - 1
    }

    /** Scatti rimasti al telefono [player]; null se non c'è un limite. */
    @Synchronized
    fun photosLeft(player: String?): Int? {
        if (rules.perGuest <= 0) return null
        return (rules.perGuest - (player?.let { photosByPlayer[it] } ?: 0)).coerceAtLeast(0)
    }

    /** Scatti usati in tutto con il rullino. */
    @Synchronized
    fun photosTaken(): Int = photosByPlayer.values.sum()

    /** Sviluppo del rullino usa e getta: da qui in poi le foto si vedono. */
    fun develop() {
        synchronized(this) { developed = true }
        onChange?.invoke()
    }

    /** Le foto del rullino si possono mostrare (o il rullino non c'è). */
    @Synchronized
    fun isDeveloped(): Boolean = !rules.disposable || developed

    /** Tre minuti senza telefoni: foto, reazioni e dediche aspettano fino alla fine. */
    fun startQuiet(durationMillis: Long = QUIET_MS) {
        synchronized(this) { quietUntil = nowMillis() + durationMillis }
        onChange?.invoke()
    }

    fun stopQuiet() {
        synchronized(this) { quietUntil = 0 }
        onChange?.invoke()
    }

    /** Millisecondi che restano del momento senza telefoni (0 se non è in corso). */
    @Synchronized
    fun quietLeftMillis(): Long = (quietUntil - nowMillis()).coerceAtLeast(0)

    /**
     * Stato da salvare per riprendere la festa dopo un'interruzione: dediche, nomi, reazioni per
     * foto, scatti usati e sviluppo del rullino. Annunci, regista e momento senza telefoni no: dopo
     * un riavvio non avrebbero più senso.
     */
    @Synchronized
    fun snapshot(): String {
        val json = JSONObject()
        json.put("messages", JSONArray().apply {
            messages.forEach { message ->
                put(JSONObject().put("id", message.id).put("text", message.text).put("name", message.name ?: JSONObject.NULL)
                    .put("at", message.atMillis).put("approved", message.approved))
            }
        })
        // In un array, non in un oggetto: l'ordine di arrivo conta (estrazione, regista) e JSONObject non lo conserva.
        json.put("names", JSONArray().apply { names.forEach { (player, name) -> put(JSONObject().put("player", player).put("name", name)) } })
        json.put("reactions", JSONObject().apply { reactionCounts.forEach { (photo, count) -> put(photo, count) } })
        json.put("photos", JSONObject().apply { photosByPlayer.forEach { (player, count) -> put(player, count) } })
        json.put("developed", developed)
        json.put("arrivals", JSONArray().apply {
            arrivals.values.forEach { put(JSONObject().put("player", it.player).put("name", it.name).put("at", it.atMillis)) }
        })
        return json.toString()
    }

    /** Riprende lo stato salvato con [snapshot]; un testo non valido viene ignorato. */
    fun restore(snapshot: String) {
        val restored = runCatching {
            val json = JSONObject(snapshot)
            synchronized(this) {
                messages.clear()
                val list = json.optJSONArray("messages") ?: JSONArray()
                for (index in 0 until minOf(list.length(), MAX_MESSAGES)) {
                    val item = list.getJSONObject(index)
                    val text = GuestSender.clean(item.optString("text"), MAX_MESSAGE_LENGTH) ?: continue
                    val name = if (item.isNull("name")) null else GuestSender.clean(item.optString("name"))
                    messages += Message(item.optString("id").ifEmpty { UUID.randomUUID().toString() }, text, name, item.optLong("at"), item.optBoolean("approved"))
                }
                names.clear()
                json.optJSONArray("names")?.let { all ->
                    for (index in 0 until minOf(all.length(), MAX_TRACKED_PLAYERS)) {
                        val item = all.optJSONObject(index) ?: continue
                        val player = item.optString("player").takeIf(PhoneGame::validPlayer) ?: continue
                        GuestSender.clean(item.optString("name"))?.let { names[player] = it }
                    }
                }
                reactionCounts.clear()
                json.optJSONObject("reactions")?.let { all -> all.keys().forEach { key -> reactionCounts[key] = all.optInt(key).coerceAtLeast(0) } }
                photosByPlayer.clear()
                json.optJSONObject("photos")?.let { all -> all.keys().forEach { key -> photosByPlayer[key] = all.optInt(key).coerceAtLeast(0) } }
                developed = json.optBoolean("developed")
                arrivals.clear()
                json.optJSONArray("arrivals")?.let { all ->
                    for (index in 0 until minOf(all.length(), MAX_TRACKED_PLAYERS)) {
                        val item = all.optJSONObject(index) ?: continue
                        val player = item.optString("player").takeIf(PhoneGame::validPlayer) ?: continue
                        val name = GuestSender.clean(item.optString("name")) ?: continue
                        arrivals[player] = Arrival(player, name, item.optLong("at"), arrivals.size + 1)
                    }
                }
            }
        }.isSuccess
        if (restored) onChange?.invoke()
    }

    /** Fine della festa: si cancella tutto. */
    fun clear() {
        synchronized(this) {
            announcement = null
            director = null
            photosByPlayer.clear()
            developed = false
            quietUntil = 0
            showing = null
            reactionCounts.clear()
            reactionKinds.fill(0)
            recentReactions.clear()
            reactionTimes.clear()
            messageTimes.clear()
            messages.clear()
            names.clear()
            arrivals.clear()
        }
        onChange?.invoke()
    }

    /** Cosa può fare la pagina dei telefoni, in JSON. */
    @Synchronized
    fun featuresJson(): String =
        """{"reactions":${features.reactions},"messages":${features.messages},"moderated":${features.moderateMessages},"arrivals":true,"wall":true}"""

    /** Annuncio e regista di turno per la pagina del telefono [player], in JSON. */
    fun partyJson(player: String?): String {
        val current = announcement()
        val director = director()
        return buildString {
            append("""{"announcement":""")
            if (current == null) append("null") else {
                append("""{"id":""").append(current.serial).append(""","text":""").append(GuestUploadServer.jsonString(current.text)).append('}')
            }
            append(""","quiet":""").append(quietLeftMillis() / 1000)
            append(""","shots":""")
            val left = photosLeft(player)
            if (left == null) append("null") else {
                append("""{"left":""").append(left).append(""","limit":""").append(photoRules().perGuest)
                append(""","disposable":""").append(photoRules().disposable).append(""","developed":""").append(isDeveloped()).append('}')
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
        val result = when {
            // Il regista e chi arriva alla porta passano anche nei tre minuti senza telefoni.
            quietLeftMillis() > 0 && form["type"] != "director" && form["type"] != "arrive" -> Result.QUIET
            else -> null
        } ?: when (form["type"]) {
            "react" -> react(player, form["reaction"]?.toIntOrNull() ?: -1, name)
            "message" -> message(player, form["text"], name)
            "director" -> direct(player, form["command"])
            "arrive" -> arrive(player, name)
            else -> Result.INVALID
        }
        return when (result) {
            Result.OK -> 200 to """{"status":"ok"}"""
            Result.PENDING -> 200 to """{"status":"pending"}"""
            Result.RATE_LIMITED -> 429 to """{"error":"rate"}"""
            Result.DISABLED -> 403 to """{"error":"disabled"}"""
            Result.FULL -> 507 to """{"error":"full"}"""
            Result.INVALID -> 400 to """{"error":"invalid"}"""
            Result.QUIET -> 423 to """{"error":"quiet"}"""
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
        /** Tre minuti senza telefoni. */
        const val QUIET_MS = 3 * 60_000L
        /** Scatti del rullino usa e getta, come le macchine di una volta. */
        const val DISPOSABLE_SHOTS = 27
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
