package it.fotofesta.tv.guests

import java.security.MessageDigest
import java.security.SecureRandom

/** Comandi che la TV esegue per la regia dal telefono e, in parte, per il regista di turno. */
sealed interface PartyCommand {
    data object Next : PartyCommand
    data object Previous : PartyCommand
    data object Play : PartyCommand
    data object Pause : PartyCommand
    data class ApprovePhoto(val id: String) : PartyCommand
    data class RejectPhoto(val id: String) : PartyCommand
    data class ApproveMessage(val id: String) : PartyCommand
    data class RejectMessage(val id: String) : PartyCommand
    data object ApproveAll : PartyCommand
    data class Announce(val text: String) : PartyCommand
    data object StartDirector : PartyCommand
    data object StartQuiet : PartyCommand
    data object Develop : PartyCommand
    /** Canzone richiesta già suonata (o scartata) da chi fa la musica. */
    data class SongDone(val id: String) : PartyCommand
    /** Preparazione della festa dalla regia: nome, colore, scaletta, invitati attesi, logo e sponsor. */
    data class SetBrandName(val name: String) : PartyCommand
    data class SetAccent(val color: String?) : PartyCommand
    data class SetSchedule(val text: String) : PartyCommand
    data class SetGuestList(val text: String) : PartyCommand
    data object RemoveLogo : PartyCommand
    data class RemoveSponsor(val id: String) : PartyCommand
    data class SetQuiz(val text: String) : PartyCommand
    data class RemoveKid(val id: String) : PartyCommand
    /** Nuovo capitolo della festa: le foto da adesso in poi gli appartengono. */
    data class Chapter(val title: String) : PartyCommand
}

/**
 * Regia dal telefono: chi organizza apre la stessa pagina della festa, scrive il PIN mostrato sulla
 * TV e da lì manda avanti le foto, mette in pausa, approva foto e dediche, fa annunci e passa la
 * regia a un invitato per due minuti. Più telefoni possono entrare con lo stesso PIN.
 *
 * Il PIN cambia a ogni festa ([reset]); i tentativi sbagliati sono limitati per indirizzo e, dopo
 * troppi errori in tutto, la regia si blocca per qualche minuto. Thread-safe.
 */
class HostRemote(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    /** Foto in attesa di approvazione, come la vede la pagina di regia. */
    data class PendingPhoto(val id: String, val sender: String?)

    /** Stato della TV per la pagina di regia, aggiornato dalla TV a ogni cambio. */
    data class Status(
        val slideshow: Boolean = false,
        val playing: Boolean = false,
        val title: String? = null,
        val photos: Int = 0,
        val pendingPhotos: List<PendingPhoto> = emptyList(),
    )

    sealed interface Login {
        data class Accepted(val token: String) : Login
        data object Wrong : Login
        data object RateLimited : Login
        data object Locked : Login
        data object Disabled : Login
    }

    private var pin: String = newPin()
    private var enabled = true
    private val tokens = LinkedHashSet<String>()
    private val attempts = HashMap<String, ArrayDeque<Long>>()
    private var failures = 0
    private var lockedUntil = 0L
    private var status = Status()

    /** Chiamato con ogni comando accettato, da qualunque thread: la TV lo esegue sul thread principale. */
    @Volatile var onCommand: ((PartyCommand) -> Unit)? = null

    /** PIN da mostrare sulla TV; null se la regia dal telefono è spenta. */
    @Synchronized
    fun pin(): String? = pin.takeIf { enabled }

    @Synchronized
    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) tokens.clear()
    }

    /** Nuova festa: nuovo PIN, e chi era entrato con il vecchio deve rientrare. */
    @Synchronized
    fun reset() {
        pin = newPin()
        tokens.clear()
        attempts.clear()
        failures = 0
        lockedUntil = 0
    }

    @Synchronized
    fun updateStatus(value: Status) {
        status = value
    }

    /** Accesso con il PIN da [client] (l'indirizzo del telefono). */
    @Synchronized
    fun login(candidate: String?, client: String): Login {
        val now = nowMillis()
        if (!enabled) return Login.Disabled
        if (now < lockedUntil) return Login.Locked
        val recent = attempts.getOrPut(client) { ArrayDeque() }
        while (recent.isNotEmpty() && now - recent.first() >= ATTEMPT_WINDOW_MS) recent.removeFirst()
        if (recent.size >= MAX_ATTEMPTS_PER_CLIENT) return Login.RateLimited
        recent.addLast(now)
        if (attempts.size > MAX_TRACKED_CLIENTS) attempts.entries.removeAll { it.value.isEmpty() }
        val typed = candidate.orEmpty().filter(Char::isDigit)
        if (!MessageDigest.isEqual(typed.toByteArray(), pin.toByteArray())) {
            if (++failures >= MAX_FAILURES) {
                // Troppi errori in tutto: qualcuno prova tutti i PIN. Il PIN resta quello mostrato
                // sulla TV, ma per qualche minuto non entra nessuno.
                lockedUntil = now + LOCK_MS
                failures = 0
            }
            return Login.Wrong
        }
        val token = newToken()
        tokens += token
        while (tokens.size > MAX_TOKENS) tokens.remove(tokens.first())
        return Login.Accepted(token)
    }

    @Synchronized
    fun isHost(token: String?): Boolean = enabled && token != null && tokens.any { MessageDigest.isEqual(it.toByteArray(), token.toByteArray()) }

    /** Comando dalla pagina di regia (modulo "command=...&id=...&text=..."): stato HTTP e JSON. */
    fun handle(token: String?, form: Map<String, String>): Pair<Int, String> {
        if (!isHost(token)) return 401 to """{"error":"host"}"""
        val id = form["id"]?.takeIf { it.length in 1..80 && it.all { char -> char.isLetterOrDigit() || char == '-' || char == '_' } }
        val command = when (form["command"]) {
            "next" -> PartyCommand.Next
            "previous" -> PartyCommand.Previous
            "play" -> PartyCommand.Play
            "pause" -> PartyCommand.Pause
            "approve_photo" -> id?.let(PartyCommand::ApprovePhoto)
            "reject_photo" -> id?.let(PartyCommand::RejectPhoto)
            "approve_message" -> id?.let(PartyCommand::ApproveMessage)
            "reject_message" -> id?.let(PartyCommand::RejectMessage)
            "approve_all" -> PartyCommand.ApproveAll
            "announce" -> GuestSender.clean(form["text"], PartyHub.MAX_ANNOUNCEMENT_LENGTH)?.let(PartyCommand::Announce)
            "director" -> PartyCommand.StartDirector
            "quiet" -> PartyCommand.StartQuiet
            "develop" -> PartyCommand.Develop
            "song_done" -> id?.let(PartyCommand::SongDone)
            "brand_name" -> PartyCommand.SetBrandName(form["text"].orEmpty().take(MAX_SETUP_TEXT))
            "brand_accent" -> PartyCommand.SetAccent(form["text"]?.takeIf(PartySetup::validColor))
            "schedule" -> PartyCommand.SetSchedule(form["text"].orEmpty().take(MAX_SETUP_TEXT))
            "guest_list" -> PartyCommand.SetGuestList(form["text"].orEmpty().take(MAX_SETUP_TEXT))
            "logo_remove" -> PartyCommand.RemoveLogo
            "sponsor_remove" -> id?.let(PartyCommand::RemoveSponsor)
            "quiz" -> PartyCommand.SetQuiz(form["text"].orEmpty().take(MAX_SETUP_TEXT))
            "kid_remove" -> id?.let(PartyCommand::RemoveKid)
            "chapter" -> GuestSender.clean(form["text"], 60)?.let(PartyCommand::Chapter)
            else -> null
        } ?: return 400 to """{"error":"invalid"}"""
        onCommand?.invoke(command)
        return 200 to """{"status":"ok"}"""
    }

    /** Stato per la pagina di regia, con le dediche da approvare prese da [hub]. */
    fun statusJson(hub: PartyHub, setup: String = "null"): String {
        val current = synchronized(this) { status }
        val messages = hub.pendingMessages()
        val director = hub.director()
        return buildString {
            append("""{"slideshow":""").append(current.slideshow)
            append(""","playing":""").append(current.playing)
            append(""","title":""").append(current.title?.let(GuestUploadServer::jsonString) ?: "null")
            append(""","photos":""").append(current.photos)
            append(""","pendingPhotos":[""")
            current.pendingPhotos.take(MAX_LISTED).forEachIndexed { index, photo ->
                if (index > 0) append(',')
                append("""{"id":""").append(GuestUploadServer.jsonString(photo.id))
                append(""","sender":""").append(photo.sender?.let(GuestUploadServer::jsonString) ?: "null").append('}')
            }
            append("""],"pendingMessages":[""")
            messages.take(MAX_LISTED).forEachIndexed { index, message ->
                if (index > 0) append(',')
                append("""{"id":""").append(GuestUploadServer.jsonString(message.id))
                append(""","text":""").append(GuestUploadServer.jsonString(message.text))
                append(""","name":""").append(message.name?.let(GuestUploadServer::jsonString) ?: "null").append('}')
            }
            append("""],"songs":[""")
            hub.songRequests().takeLast(MAX_LISTED).forEachIndexed { index, song ->
                if (index > 0) append(',')
                append("""{"id":""").append(GuestUploadServer.jsonString(song.id))
                append(""","text":""").append(GuestUploadServer.jsonString(song.text))
                append(""","name":""").append(song.name?.let(GuestUploadServer::jsonString) ?: "null").append('}')
            }
            append("""],"setup":""").append(setup)
            append(""","director":""").append(director?.let { GuestUploadServer.jsonString(it.name) } ?: "null")
            append('}')
        }
    }

    /** Foto che la pagina di regia può chiedere: solo quelle in attesa di approvazione. */
    @Synchronized
    fun canShow(id: String): Boolean = status.pendingPhotos.any { it.id == id }

    private fun newPin(): String = (0 until PIN_LENGTH).joinToString("") { random.nextInt(10).toString() }

    private fun newToken(): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val PIN_LENGTH = 6
        /** Cinque tentativi al minuto per telefono. */
        const val MAX_ATTEMPTS_PER_CLIENT = 5
        const val ATTEMPT_WINDOW_MS = 60_000L
        /** Dopo venti errori in tutto la regia si blocca per cinque minuti. */
        const val MAX_FAILURES = 20
        const val LOCK_MS = 5 * 60_000L
        /** Telefoni che possono fare regia insieme. */
        const val MAX_TOKENS = 8
        const val MAX_LISTED = 40
        /** Scaletta e invitati attesi: qualche decina di righe. */
        const val MAX_SETUP_TEXT = 4_000
        private const val MAX_TRACKED_CLIENTS = 500
    }
}
