package it.fotofesta.tv.guests

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.UUID
import kotlin.random.Random

/**
 * Giochi della festa con i telefoni, oltre ai quiz di [PhoneGame]: disegna e indovina, muro dei
 * disegni, romanzo a più mani, telefono senza fili, complimenti segreti, missioni fotografiche,
 * paparazzi, lo stesso istante, ricrea la foto, chi è la talpa e capsula del tempo. In più la caccia
 * al QR, i telefoni come luci e le monete della festa, che ogni gioco assegna.
 *
 * La TV sceglie il gioco e i testi (già tradotti: parole da disegnare, missioni, frasi); la pagina dei
 * telefoni legge da [phoneJson] cosa mostrare e manda i contributi con [handle]. I nomi degli
 * invitati sono quelli di [hub]. È la stessa logica dell'app Apple (`PartyPlay.swift`).
 *
 * Thread-safe: il server lo usa dai suoi thread, l'interfaccia dal thread principale.
 */
class PartyPlay(
    private val hub: PartyHub,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {
    enum class Kind(val key: String) {
        DRAW_GUESS("draw_guess"),
        DRAWING_WALL("wall"),
        STORY("story"),
        WHISPER("whisper"),
        COMPLIMENTS("compliments"),
        MISSIONS("missions"),
        PAPARAZZI("paparazzi"),
        SAME_MOMENT("moment"),
        RECREATE("recreate"),
        MOLE("mole"),
        CAPSULE("capsule"),
    }

    /** Contributo di un telefono: una frase, un disegno o un tentativo. */
    data class Entry(
        val id: String,
        val player: String,
        val name: String?,
        val text: String? = null,
        val drawing: String? = null,
        val atMillis: Long,
    )

    /** Gioco in corso come lo vede la TV. */
    data class State(
        val kind: Kind,
        val round: String,
        /** Testo per tutti, già tradotto dalla TV ("Disegnate il vostro animale preferito"). */
        val prompt: String,
        /** Chi disegna, la talpa o la star dei paparazzi. */
        val special: PartyHub.Participant?,
        /** Parola da disegnare, missione della talpa, frase iniziale o foto da rifare. */
        val secret: String?,
        val entries: List<Entry>,
        val open: Boolean,
        /** Chi è la talpa: si vota. */
        val voting: Boolean,
        val revealed: Boolean,
        /** Disegna e indovina: chi ha indovinato. */
        val winner: PartyHub.Participant?,
        /** Lo stesso istante: quando scattare (epoch ms). */
        val momentAtMillis: Long,
        val startedAtMillis: Long,
        /** Chi è la talpa: voti per ogni invitato sospettato, per nome. */
        val votes: Map<String, Int>,
    )

    /** Foto arrivata durante un gioco fotografico: la TV la mostra con il suo perché. */
    data class Tag(val kind: Kind, val round: String, val text: String?)

    data class Coins(val name: String, val coins: Int)

    /** Telefoni come luci: effetto e momento d'inizio, uguale per tutti. */
    enum class Lights(val key: String) { RAINBOW("rainbow"), WAVE("wave"), PULSE("pulse"), CANDLE("candle") }

    enum class Result { OK, CORRECT, WRONG, CLOSED, INVALID, RATE_LIMITED, TOO_LATE, ALREADY }

    private class Running(
        val kind: Kind,
        val round: String,
        val prompt: String,
        val special: PartyHub.Participant?,
        val secret: String?,
        val pool: List<String>,
        val momentAtMillis: Long,
        val startedAtMillis: Long,
    ) {
        val entries = ArrayList<Entry>()
        var open = true
        var voting = false
        var revealed = false
        var winner: PartyHub.Participant? = null
        val votes = LinkedHashMap<String, String>()
        val missions = HashMap<String, String>()
        val rewarded = HashSet<String>()
        /** Telefono senza fili: chi ha il turno e fino a quando. */
        var holder: String? = null
        var holderUntil = 0L
    }

    private var running: Running? = null
    private val tags = LinkedHashMap<String, Tag>()
    private val coins = LinkedHashMap<String, Int>()
    private val lastEntry = HashMap<String, Long>()
    private var treasure: String? = null
    private var treasureUntil = 0L
    private val treasureFound = ArrayList<String>()
    private var lights: Lights? = null
    private var lightsStarted = 0L
    private var revision = 0L

    /** Chiamato a ogni cambio, da qualunque thread. */
    @Volatile var onChange: (() -> Unit)? = null

    /**
     * Nuovo gioco. [pool] sono i testi tra cui scegliere (parole da disegnare, missioni, frasi
     * iniziali); per "Ricrea la foto" il primo è l'identificativo della foto da rifare. Restituisce
     * null se servono invitati con il nome e non ce ne sono abbastanza.
     */
    fun start(kind: Kind, prompt: String, pool: List<String> = emptyList()): State? {
        val state = synchronized(this) {
            val people = hub.participants()
            val needsSpecial = kind == Kind.DRAW_GUESS || kind == Kind.MOLE || kind == Kind.PAPARAZZI
            if (needsSpecial && people.isEmpty()) return null
            if (kind == Kind.COMPLIMENTS && people.size < 2) return null
            val special = if (needsSpecial) people[random.nextInt(people.size)] else null
            val secret = when (kind) {
                Kind.DRAW_GUESS, Kind.MOLE, Kind.WHISPER -> pool.takeIf { it.isNotEmpty() }?.let { it[random.nextInt(it.size)] }
                Kind.STORY, Kind.RECREATE -> pool.firstOrNull()
                else -> null
            }
            val now = nowMillis()
            running = Running(
                kind = kind,
                round = UUID.randomUUID().toString().take(12),
                prompt = GuestSender.clean(prompt, MAX_PROMPT_LENGTH) ?: "",
                special = special,
                secret = secret,
                pool = pool,
                momentAtMillis = if (kind == Kind.SAME_MOMENT) now + MOMENT_DELAY_MS else 0,
                startedAtMillis = now,
            )
            revision++
            stateLocked()
        }
        onChange?.invoke()
        return state
    }

    /** Chi è la talpa: si smette di giocare e si vota. */
    fun startVoting() {
        synchronized(this) {
            val current = running ?: return
            if (current.kind != Kind.MOLE || current.revealed) return
            current.voting = true
            revision++
        }
        onChange?.invoke()
    }

    /**
     * Fine del gioco: si chiudono i contributi e si mostra il risultato (il romanzo intero, la talpa,
     * i propositi della capsula). Le monete si assegnano qui per i giochi che si decidono alla fine.
     */
    fun reveal() {
        synchronized(this) {
            val current = running ?: return
            if (current.revealed) return
            current.open = false
            current.voting = false
            current.revealed = true
            if (current.kind == Kind.MOLE) {
                val mole = current.special
                val caught = mole != null && mostVoted(current) == mole.player
                if (mole != null) {
                    if (caught) current.votes.forEach { (voter, suspect) -> if (suspect == mole.player) addCoins(voter, 2) }
                    else addCoins(mole.player, 5)
                }
            }
            revision++
        }
        onChange?.invoke()
    }

    /** Nessun gioco in corso; le monete e le foto già etichettate restano. */
    fun stop() {
        synchronized(this) {
            running = null
            revision++
        }
        onChange?.invoke()
    }

    @Synchronized
    fun current(): State? = stateLocked()

    @Synchronized
    fun revision(): Long = revision

    /** Chi è la talpa: è stata scoperta? (dopo [reveal]) */
    @Synchronized
    fun moleCaught(): Boolean {
        val current = running ?: return false
        return current.kind == Kind.MOLE && current.revealed && current.special != null && mostVoted(current) == current.special.player
    }

    /**
     * Foto arrivata dal telefono [player] durante un gioco fotografico: missione, paparazzi, lo
     * stesso istante o ricrea la foto. Restituisce l'etichetta da mostrare con la foto, se c'è.
     */
    fun photoArrived(player: String?, photoId: String): Tag? {
        val tag = synchronized(this) {
            val current = running ?: return null
            if (player == null || !current.open) return null
            val now = nowMillis()
            val tag = when (current.kind) {
                Kind.MISSIONS -> {
                    val mission = missionLocked(current, player) ?: return null
                    if (current.rewarded.add(player)) addCoins(player, 2)
                    Tag(Kind.MISSIONS, current.round, mission)
                }
                Kind.PAPARAZZI -> {
                    val star = current.special ?: return null
                    if (player == star.player) return null
                    if (current.rewarded.size < 3 && current.rewarded.add(player)) addCoins(player, 3 - current.rewarded.size + 1)
                    Tag(Kind.PAPARAZZI, current.round, star.name)
                }
                Kind.SAME_MOMENT -> {
                    // Valgono le foto scattate (e arrivate) nel minuto dopo il via.
                    if (now < current.momentAtMillis || now > current.momentAtMillis + MOMENT_WINDOW_MS) return null
                    if (current.rewarded.add(player)) addCoins(player, 1)
                    Tag(Kind.SAME_MOMENT, current.round, null)
                }
                Kind.RECREATE -> {
                    if (current.rewarded.add(player)) addCoins(player, 2)
                    Tag(Kind.RECREATE, current.round, current.secret)
                }
                else -> return null
            }
            tags[photoId] = tag
            while (tags.size > MAX_TAGS) tags.remove(tags.keys.first())
            revision++
            tag
        }
        onChange?.invoke()
        return tag
    }

    @Synchronized
    fun tag(photoId: String): Tag? = tags[photoId]

    /** Foto etichettate nel gioco [round], dalla prima arrivata. */
    @Synchronized
    fun taggedPhotos(round: String): List<String> = tags.filterValues { it.round == round }.keys.toList()

    /** Monete della festa per [player] (scommesse vinte, quiz...). */
    fun award(player: String, amount: Int) {
        if (amount <= 0 || !PhoneGame.validPlayer(player)) return
        synchronized(this) {
            addCoins(player, amount)
            revision++
        }
        onChange?.invoke()
    }

    /** Classifica delle monete: più monete prima; a parità, chi ha cominciato prima. */
    @Synchronized
    fun leaderboard(limit: Int = 10): List<Coins> {
        val names = hub.participants().associate { it.player to it.name }
        return coins.entries.withIndex()
            .sortedWith(compareByDescending<IndexedValue<Map.Entry<String, Int>>> { it.value.value }.thenBy { it.index })
            .take(limit)
            .map { Coins(names[it.value.key] ?: PhoneGame.DEFAULT_NAME, it.value.value) }
    }

    @Synchronized
    fun coinsOf(player: String): Int = coins[player] ?: 0

    /** Caccia al QR: un codice valido per [TREASURE_MS]; i primi tre telefoni che lo inquadrano vincono. */
    fun startTreasure(): String {
        val token = synchronized(this) {
            val token = UUID.randomUUID().toString().replace("-", "").take(16)
            treasure = token
            treasureUntil = nowMillis() + TREASURE_MS
            treasureFound.clear()
            revision++
            token
        }
        onChange?.invoke()
        return token
    }

    /** Codice della caccia in corso, se non è scaduto e non l'hanno già trovato in tre. */
    @Synchronized
    fun treasure(): String? = treasure?.takeIf { nowMillis() < treasureUntil && treasureFound.size < 3 }

    @Synchronized
    fun treasureFinders(): List<String> {
        val names = hub.participants().associate { it.player to it.name }
        return treasureFound.map { names[it] ?: PhoneGame.DEFAULT_NAME }
    }

    fun setLights(value: Lights?) {
        synchronized(this) {
            lights = value
            lightsStarted = nowMillis()
            revision++
        }
        onChange?.invoke()
    }

    @Synchronized
    fun lights(): Lights? = lights

    /** Fine della festa: si cancella tutto, monete comprese. */
    fun clear() {
        synchronized(this) {
            running = null
            tags.clear()
            coins.clear()
            lastEntry.clear()
            treasure = null
            treasureFound.clear()
            lights = null
            revision++
        }
        onChange?.invoke()
    }

    /** Cosa mostra la pagina del telefono [player], in JSON; null se non c'è niente da fare. */
    fun phoneJson(player: String?): String {
        val json = JSONObject()
        val changed = synchronized(this) {
            val now = nowMillis()
            json.put("now", now)
            json.put("coins", player?.let { coins[it] } ?: 0)
            lights?.let { json.put("lights", JSONObject().put("pattern", it.key).put("started", lightsStarted)) }
            val current = running
            if (current == null || player == null || !PhoneGame.validPlayer(player)) {
                json.put("game", JSONObject.NULL)
                return@synchronized false
            }
            val assigned = current.kind == Kind.WHISPER && claimWhisperLocked(current, player, now)
            json.put("game", gameJsonLocked(current, player, now))
            assigned
        }
        if (changed) onChange?.invoke()
        return json.toString()
    }

    /** Contributo di un telefono (modulo "type=play&command=...&round=..."): stato HTTP e JSON. */
    fun handle(form: Map<String, String>): Pair<Int, String> {
        val player = form["player"].orEmpty()
        if (!PhoneGame.validPlayer(player)) return 400 to """{"error":"invalid"}"""
        hub.join(player, form["name"])
        val result = when (form["command"]) {
            "treasure" -> claimTreasure(player, form["token"])
            "vote" -> vote(player, form["round"], form["target"])
            else -> contribute(player, form["round"], form["text"], form["drawing"], form["target"])
        }
        val (status, body) = when (result) {
            Result.OK -> 200 to """{"status":"ok"}"""
            Result.CORRECT -> 200 to """{"status":"correct"}"""
            Result.WRONG -> 200 to """{"status":"wrong"}"""
            Result.CLOSED -> 409 to """{"error":"closed"}"""
            Result.INVALID -> 400 to """{"error":"invalid"}"""
            Result.RATE_LIMITED -> 429 to """{"error":"rate"}"""
            Result.TOO_LATE -> 410 to """{"error":"late"}"""
            Result.ALREADY -> 409 to """{"error":"already"}"""
        }
        if (status == 200) onChange?.invoke()
        return status to body
    }

    private fun claimTreasure(player: String, token: String?): Result = synchronized(this) {
        val current = treasure ?: return Result.TOO_LATE
        if (token == null || !java.security.MessageDigest.isEqual(token.toByteArray(), current.toByteArray())) return Result.INVALID
        if (nowMillis() >= treasureUntil || treasureFound.size >= 3) return Result.TOO_LATE
        if (player in treasureFound) return Result.ALREADY
        treasureFound += player
        addCoins(player, 4 - treasureFound.size)
        revision++
        Result.OK
    }

    private fun vote(player: String, round: String?, target: String?): Result = synchronized(this) {
        val current = running ?: return Result.CLOSED
        if (current.round != round || current.kind != Kind.MOLE || !current.voting) return Result.CLOSED
        val suspect = hub.participants().firstOrNull { it.name == target && it.player != player } ?: return Result.INVALID
        current.votes[player] = suspect.player
        revision++
        Result.OK
    }

    private fun contribute(player: String, round: String?, rawText: String?, rawDrawing: String?, target: String?): Result {
        // I complimenti diventano dediche anonime: approvazione e limiti sono quelli delle dediche.
        synchronized(this) {
            val current = running ?: return Result.CLOSED
            if (current.round != round || !current.open) return Result.CLOSED
            if (current.kind == Kind.COMPLIMENTS) {
                val to = hub.participants().firstOrNull { it.name == target && it.player != player } ?: return Result.INVALID
                val text = GuestSender.clean(rawText, PartyHub.MAX_MESSAGE_LENGTH - to.name.length - 4) ?: return Result.INVALID
                return@synchronized Triple(to, text, current)
            }
            null
        }?.let { (to, text, current) ->
            val result = hub.message(player, "💌 ${to.name}: $text", null, anonymous = true)
            if (result == PartyHub.Result.OK || result == PartyHub.Result.PENDING) {
                synchronized(this) {
                    if (current.rewarded.add(player)) addCoins(player, 1)
                    revision++
                }
            }
            return when (result) {
                PartyHub.Result.OK, PartyHub.Result.PENDING -> Result.OK
                PartyHub.Result.RATE_LIMITED -> Result.RATE_LIMITED
                PartyHub.Result.DISABLED -> Result.CLOSED
                else -> Result.INVALID
            }
        }
        return synchronized(this) {
            val current = running ?: return@synchronized Result.CLOSED
            if (current.round != round || !current.open || current.voting) return@synchronized Result.CLOSED
            val now = nowMillis()
            val text = GuestSender.clean(rawText, MAX_TEXT_LENGTH)
            val drawing = rawDrawing?.takeIf(::validDrawing)
            val drawer = current.special?.player == player
            val minimumGap = if (current.kind == Kind.DRAW_GUESS && drawer) DRAW_LIVE_GAP_MS else ENTRY_GAP_MS
            if (now - (lastEntry[player] ?: 0L) < minimumGap) return@synchronized Result.RATE_LIMITED
            if (current.entries.size >= MAX_ENTRIES) return@synchronized Result.CLOSED
            val name = hub.nameOf(player)
            val result = when (current.kind) {
                Kind.DRAW_GUESS ->
                    if (drawer) {
                        drawing ?: return@synchronized Result.INVALID
                        // Il disegno in corso sostituisce quello di prima: la TV lo vede crescere.
                        current.entries.removeAll { it.player == player && it.drawing != null }
                        current.entries += Entry(newId(), player, name, drawing = drawing, atMillis = now)
                        Result.OK
                    } else {
                        text ?: return@synchronized Result.INVALID
                        val word = current.secret
                        current.entries += Entry(newId(), player, name, text = text, atMillis = now)
                        if (word != null && guessMatches(text, word)) {
                            current.open = false
                            current.revealed = true
                            current.winner = PartyHub.Participant(player, name ?: PhoneGame.DEFAULT_NAME)
                            addCoins(player, 3)
                            current.special?.let { addCoins(it.player, 2) }
                            Result.CORRECT
                        } else {
                            Result.WRONG
                        }
                    }
                Kind.DRAWING_WALL -> {
                    drawing ?: return@synchronized Result.INVALID
                    current.entries.removeAll { it.player == player }
                    current.entries += Entry(newId(), player, name, drawing = drawing, atMillis = now)
                    if (current.rewarded.add(player)) addCoins(player, 1)
                    Result.OK
                }
                Kind.STORY -> {
                    text ?: return@synchronized Result.INVALID
                    // Nessuno scrive due frasi di fila: il bello è continuare quella di un altro.
                    if (current.entries.lastOrNull()?.player == player) return@synchronized Result.ALREADY
                    current.entries += Entry(newId(), player, name, text = text, atMillis = now)
                    addCoins(player, 1)
                    Result.OK
                }
                Kind.WHISPER -> {
                    if (current.holder != player || now > current.holderUntil) return@synchronized Result.CLOSED
                    val wantsDrawing = current.entries.size % 2 == 0
                    if (wantsDrawing && drawing == null || !wantsDrawing && text == null) return@synchronized Result.INVALID
                    current.entries += if (wantsDrawing) Entry(newId(), player, name, drawing = drawing, atMillis = now)
                    else Entry(newId(), player, name, text = text, atMillis = now)
                    current.holder = null
                    addCoins(player, 1)
                    if (current.entries.size >= MAX_WHISPER_STEPS) current.open = false
                    Result.OK
                }
                Kind.CAPSULE -> {
                    text ?: return@synchronized Result.INVALID
                    current.entries.removeAll { it.player == player }
                    current.entries += Entry(newId(), player, name, text = text, atMillis = now)
                    if (current.rewarded.add(player)) addCoins(player, 1)
                    Result.OK
                }
                else -> Result.INVALID
            }
            if (result == Result.OK || result == Result.CORRECT || result == Result.WRONG) {
                lastEntry[player] = now
                revision++
            }
            result
        }
    }

    private fun stateLocked(): State? {
        val current = running ?: return null
        val tally = LinkedHashMap<String, Int>()
        current.votes.values.forEach { suspect ->
            val name = hub.nameOf(suspect) ?: PhoneGame.DEFAULT_NAME
            tally[name] = (tally[name] ?: 0) + 1
        }
        return State(
            kind = current.kind,
            round = current.round,
            prompt = current.prompt,
            special = current.special,
            secret = current.secret,
            entries = current.entries.toList(),
            open = current.open,
            voting = current.voting,
            revealed = current.revealed,
            winner = current.winner,
            momentAtMillis = current.momentAtMillis,
            startedAtMillis = current.startedAtMillis,
            votes = tally,
        )
    }

    private fun gameJsonLocked(current: Running, player: String, now: Long): JSONObject {
        val json = JSONObject()
            .put("round", current.round)
            .put("kind", current.kind.key)
            .put("prompt", current.prompt)
            .put("open", current.open)
            .put("voting", current.voting)
            .put("revealed", current.revealed)
        val special = current.special
        val you = special?.player == player
        json.put("you", you)
        special?.let { json.put("special", it.name) }
        var input = "none"
        when (current.kind) {
            Kind.DRAW_GUESS -> {
                input = if (you) "draw" else "text"
                if (you) json.put("secret", current.secret)
                current.winner?.let { json.put("winner", it.name) }
                if (current.revealed) json.put("answer", current.secret)
            }
            Kind.DRAWING_WALL -> input = "draw"
            Kind.STORY -> {
                input = "text"
                // Si vede solo l'ultima frase, come nel gioco su carta.
                json.put("previous", current.entries.lastOrNull()?.text ?: current.secret)
                json.put("count", current.entries.size)
                json.put("mine", current.entries.lastOrNull()?.player == player)
            }
            Kind.WHISPER -> {
                val holding = current.holder == player && now <= current.holderUntil
                json.put("turn", holding)
                json.put("count", current.entries.size)
                if (holding) {
                    val previous = current.entries.lastOrNull()
                    if (previous == null) json.put("previous", current.secret)
                    else if (previous.drawing != null) json.put("previousDrawing", previous.drawing)
                    else json.put("previous", previous.text)
                    input = if (current.entries.size % 2 == 0) "draw" else "text"
                    json.put("seconds", ((current.holderUntil - now) / 1000).coerceAtLeast(0))
                }
                json.put("done", current.entries.any { it.player == player })
            }
            Kind.COMPLIMENTS -> {
                input = "text"
                json.put("choices", JSONArray(hub.participants().filter { it.player != player }.map { it.name }))
            }
            Kind.MISSIONS -> missionLocked(current, player)?.let { json.put("secret", it) }
            Kind.PAPARAZZI -> Unit
            Kind.SAME_MOMENT -> json.put("moment", current.momentAtMillis)
            Kind.RECREATE -> Unit
            Kind.MOLE -> {
                if (you) json.put("secret", current.secret)
                if (current.voting) {
                    input = "choice"
                    json.put("choices", JSONArray(hub.participants().filter { it.player != player }.map { it.name }))
                    current.votes[player]?.let { voted -> hub.nameOf(voted)?.let { json.put("voted", it) } }
                }
                if (current.revealed) json.put("caught", special != null && mostVoted(current) == special.player)
            }
            Kind.CAPSULE -> {
                input = "text"
                if (current.revealed) json.put("entries", JSONArray(current.entries.map { (it.name?.let { name -> "$name: " } ?: "") + it.text }))
            }
        }
        if (!current.open) input = "none"
        json.put("input", input)
        json.put("sent", current.entries.any { it.player == player } || current.rewarded.contains(player))
        return json
    }

    /** Telefono senza fili: il primo telefono libero che non ha ancora giocato prende il turno. */
    private fun claimWhisperLocked(current: Running, player: String, now: Long): Boolean {
        if (!current.open) return false
        if (current.holder != null && now <= current.holderUntil) return false
        if (current.entries.any { it.player == player }) return false
        current.holder = player
        current.holderUntil = now + WHISPER_TURN_MS
        revision++
        return true
    }

    private fun missionLocked(current: Running, player: String): String? {
        if (current.pool.isEmpty()) return null
        return current.missions.getOrPut(player) { current.pool[current.missions.size % current.pool.size] }
    }

    private fun mostVoted(current: Running): String? =
        current.votes.values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    private fun addCoins(player: String, amount: Int) {
        if (player !in coins && coins.size >= MAX_PLAYERS) return
        coins[player] = (coins[player] ?: 0) + amount
    }

    private fun newId() = UUID.randomUUID().toString().take(12)

    companion object {
        const val MAX_PROMPT_LENGTH = 160
        const val MAX_TEXT_LENGTH = 200
        const val MAX_ENTRIES = 400
        const val MAX_TAGS = 2_000
        const val MAX_PLAYERS = 1_000
        const val MAX_WHISPER_STEPS = 12
        /** Un disegno: al massimo 20.000 caratteri ("c,x,y,x,y;c,x,y..."). */
        const val MAX_DRAWING_LENGTH = 20_000
        const val MAX_STROKES = 400
        const val COLORS = 8
        const val ENTRY_GAP_MS = 2_000L
        /** Chi disegna manda il disegno che cresce ogni secondo circa. */
        const val DRAW_LIVE_GAP_MS = 700L
        const val WHISPER_TURN_MS = 90_000L
        const val MOMENT_DELAY_MS = 15_000L
        const val MOMENT_WINDOW_MS = 60_000L
        const val TREASURE_MS = 30_000L

        /**
         * Disegno valido: tratti separati da ";", ognuno "colore,x,y,x,y..." con colore 0–7 e
         * coordinate intere 0–1000 (la pagina le riduce alla tela quadrata).
         */
        fun validDrawing(value: String): Boolean {
            if (value.isEmpty() || value.length > MAX_DRAWING_LENGTH) return false
            if (!value.all { it.isDigit() || it == ',' || it == ';' }) return false
            val strokes = value.split(';')
            if (strokes.size > MAX_STROKES) return false
            return strokes.all { stroke ->
                val numbers = stroke.split(',')
                numbers.size >= 3 && numbers.size % 2 == 1 &&
                    numbers.all { it.isNotEmpty() && it.length <= 4 } &&
                    numbers[0].toInt() < COLORS && numbers.drop(1).all { it.toInt() <= 1000 }
            }
        }

        /** Tratti di un disegno valido: colore e coordinate 0–1 (x, y alternati). */
        fun strokes(value: String): List<Pair<Int, FloatArray>> {
            if (!validDrawing(value)) return emptyList()
            return value.split(';').map { stroke ->
                val numbers = stroke.split(',').map(String::toInt)
                numbers[0] to FloatArray(numbers.size - 1) { numbers[it + 1] / 1000f }
            }
        }

        /** "Gatto", "il gatto!", "GATTÒ" valgono tutti "gatto". */
        fun guessMatches(guess: String, word: String): Boolean {
            val target = normalize(word)
            if (target.isEmpty()) return false
            val typed = normalize(guess)
            return typed == target || typed.split(' ').contains(target)
        }

        private fun normalize(value: String): String =
            Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
                .filter { it.isLetterOrDigit() || it == ' ' }
                .split(' ').filter(String::isNotEmpty).joinToString(" ")
    }
}
