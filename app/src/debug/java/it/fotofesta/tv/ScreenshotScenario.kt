package it.fotofesta.tv

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.lifecycleScope
import it.fotofesta.tv.model.AppState
import it.fotofesta.tv.model.BrowserKind
import it.fotofesta.tv.model.CountdownEvent
import it.fotofesta.tv.model.Destination
import it.fotofesta.tv.model.SettingsCategory
import it.fotofesta.tv.model.SlideshowLayout
import it.fotofesta.tv.network.NetworkMedia
import it.fotofesta.tv.network.NetworkPath
import it.fotofesta.tv.smb.SmbAddress
import it.fotofesta.tv.smb.SmbLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Calendar

/**
 * Schermate per gli store e per i controlli nell'emulatore (solo build di debug): con l'extra
 * [EXTRA] l'app apre da sola la schermata dello scenario, con foto demo, foto mandate come dai
 * telefoni degli invitati e le cartelle dei server di prova del computer che ospita l'emulatore.
 * Lo stato si legge in cache/screenshot-status.txt: "ready" quando la schermata è pronta.
 * Vedi scripts/android_screenshots.sh.
 */
object ScreenshotScenario {
    private const val TAG = "FotoFestaShots"
    private const val EXTRA = "fotofesta.screenshot"

    /** Scenario in corso, letto anche dalle schermate dei giochi; null nell'uso normale. */
    var name: String? = null
        private set

    fun start(activity: MainActivity, viewModel: FotoFestaViewModel, intent: Intent) {
        val scenario = intent.getStringExtra(EXTRA) ?: return
        name = scenario
        val status = File(activity.cacheDir, "screenshot-status.txt")
        status.writeText("running")
        val run = Run(activity, viewModel, intent)
        activity.lifecycleScope.launch {
            try {
                run.play(scenario)
                status.writeText("ready")
                Log.i(TAG, "$scenario pronto")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Log.e(TAG, "$scenario non riuscito", failure)
                status.writeText("error: ${failure.message ?: failure.javaClass.simpleName}")
            }
        }
    }

    private class Run(private val activity: MainActivity, private val vm: FotoFestaViewModel, intent: Intent) {
        private val services = activity.appServices
        private val smbHost = SmbAddress.parse(intent.getStringExtra("fotofesta.smb") ?: "10.0.2.2:4450")?.copy(share = "", path = "")
            ?: error("Indirizzo SMB non valido")
        private val smbUser = intent.getStringExtra("fotofesta.smb_user").orEmpty()
        private val smbPassword = intent.getStringExtra("fotofesta.smb_password").orEmpty()
        private val smbShare = intent.getStringExtra("fotofesta.smb_share") ?: "Foto"
        private val dlnaLocation = intent.getStringExtra("fotofesta.dlna") ?: "http://10.0.2.2:8200/rootDesc.xml"
        private val dlnaUdn = intent.getStringExtra("fotofesta.dlna_udn") ?: "uuid:fotofesta-nas-di-prova"
        private var uploads = 0

        suspend fun play(scenario: String) {
            when (scenario) {
                "home" -> {
                    demo()
                    vm.back()
                }
                "slideshow" -> demo()
                "settings" -> {
                    fresh()
                    vm.setSettingsCategory(SettingsCategory.SLIDESHOW)
                    vm.navigate(Destination.SETTINGS)
                }
                "settings_party" -> {
                    fresh()
                    vm.setSettingsCategory(SettingsCategory.PARTY)
                    vm.navigate(Destination.SETTINGS)
                }
                "games" -> {
                    demo()
                    vm.navigate(Destination.GAMES)
                }
                "memory", "mystery", "puzzle", "puzzle_game" -> {
                    demo()
                    vm.navigate(Destination.GAMES)
                    vm.navigate(
                        when (scenario) {
                            "memory" -> Destination.MEMORY
                            "mystery" -> Destination.MYSTERY
                            else -> Destination.PUZZLE
                        }
                    )
                }
                "guests" -> {
                    demo()
                    vm.navigate(Destination.GUESTS)
                }
                "party" -> {
                    party(listOf("Giulia", "Marco", "Sara"))
                    vm.navigate(Destination.GUESTS)
                }
                "guest_slideshow" -> {
                    party(emptyList())
                    vm.startPartyAndPlay()
                    upload("Giulia", portrait = true)
                    upload("Marco", portrait = false)
                    until("foto degli invitati") { it.guests.shown >= 2 }
                    delay(4_000)
                }
                "who_game", "who_phone" -> {
                    party(listOf("Giulia", "Marco", "Sara"))
                    vm.navigate(Destination.GAMES)
                    vm.navigate(Destination.WHO_TOOK_IT)
                    if (scenario == "who_phone") answerFromPhones()
                }
                "awards" -> {
                    party(listOf("Giulia", "Marco", "Giulia", "Sara"))
                    vm.navigate(Destination.AWARDS)
                }
                "review" -> {
                    party(emptyList())
                    vm.setGuestApproval(true)
                    until("approvazione") { it.guests.approval }
                    upload("Sara", portrait = true)
                    upload("Luca", portrait = false)
                    until("foto da approvare") { it.guests.pending.size >= 2 }
                    vm.navigate(Destination.GUEST_REVIEW)
                }
                "browser", "browser_saved" -> {
                    fresh()
                    vm.openBrowser(BrowserKind.PHOTOS, systemPickerAvailable = false)
                    until("browser") { !it.browser.loading && !it.browser.searchingNetwork }
                }
                "icloud" -> {
                    fresh()
                    vm.openBrowser(BrowserKind.PHOTOS, systemPickerAvailable = false)
                    vm.openICloudLink()
                    until("QR per il link") { it.icloud.pairingUrl != null }
                }
                "smb_login" -> {
                    fresh()
                    vm.openBrowser(BrowserKind.PHOTOS, systemPickerAvailable = false)
                    vm.openSmbLogin(smbHost)
                }
                "smb_error" -> {
                    fresh()
                    vm.openBrowser(BrowserKind.PHOTOS, systemPickerAvailable = false)
                    vm.openSmbLogin(smbHost)
                    vm.smbConnect(smbAddress(), smbUser, smbPassword + "-sbagliata")
                    until("errore di accesso", 40_000) { it.smb.errorRes != null && !it.smb.loading }
                }
                "smb_shares" -> smbShares()
                "smb_folder" -> smbFolder()
                "smb_slideshow" -> {
                    smbFolder()
                    useFolderAndPlay()
                }
                "smb_video" -> {
                    smbFolder()
                    useFolderAndPlay()
                    showVideo()
                }
                "dlna" -> dlna(folder = false)
                "dlna_folder" -> dlna(folder = true)
                "dlna_slideshow" -> {
                    dlna(folder = true)
                    useFolderAndPlay()
                }
                "dlna_video" -> {
                    dlna(folder = true)
                    useFolderAndPlay()
                    showVideo()
                }
                "mosaic" -> {
                    vm.updateSettings(vm.state.value.settings.copy(layout = SlideshowLayout.MOSAIC, intervalSeconds = 5, lightMode = false))
                    demo()
                    // Dopo due cambi di foto le caselle accanto sono piene.
                    until("mosaico", 30_000) { it.slideTick >= 3 }
                    delay(2_000)
                }
                "countdown" -> {
                    // Il conto alla rovescia compare nell'ultima ora.
                    val now = Calendar.getInstance()
                    val target = (now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE) + 35) % (24 * 60)
                    vm.updateSettings(
                        vm.state.value.settings.copy(
                            layout = SlideshowLayout.SINGLE,
                            countdown = true,
                            countdownMinutes = target,
                            countdownEvent = CountdownEvent.CAKE,
                        )
                    )
                    demo()
                }
                else -> error("Scenario sconosciuto: $scenario")
            }
            // Messaggi e avanzamento in sovrimpressione non devono finire nelle schermate.
            until("fine caricamento") { !it.busy }
            vm.clearMessage()
        }

        /** Nessuna festa in corso e nessun messaggio, come all'apertura dell'app. */
        private suspend fun fresh() {
            val party = services.guests.state.value
            if (party.approval) vm.setGuestApproval(false)
            if (party.active) vm.endGuestParty()
            // La festa finisce sul suo thread e la raccolta dell'app la segue poco dopo: un gioco
            // aperto prima prenderebbe la raccolta della festa (vuota con le foto demo).
            until("fine della festa") {
                val guests = services.guests.state.value
                !guests.active && !guests.approval && !it.guests.active && !it.guests.approval
            }
            vm.clearMessage()
        }

        /** Foto demo nello slideshow. */
        private suspend fun demo() {
            fresh()
            vm.useDemoAndPlay()
            until("slideshow") { it.destination == Destination.SLIDESHOW }
        }

        /** Festa con le foto demo come raccolta e le foto mandate da [senders], una a testa. */
        private suspend fun party(senders: List<String>) {
            demo()
            vm.back()
            vm.startGuestParty()
            until("indirizzo per gli invitati", 30_000) { it.guests.active && it.guests.url != null }
            senders.forEachIndexed { index, sender -> upload(sender, portrait = index % 2 == 0) }
            if (senders.isNotEmpty()) until("foto degli invitati") { it.guests.shown >= senders.size }
        }

        /**
         * Manda una foto come la pagina per i telefoni: POST /photos con la chiave del QR. La festa è
         * in HTTP in chiaro sulla rete di casa, che la configurazione di rete dell'app permette solo
         * a LanHttp.
         */
        private suspend fun upload(sender: String, portrait: Boolean) {
            val url = vm.state.value.guests.url ?: error("La festa non ha un indirizzo")
            val bytes = guestPhoto(portrait)
            withContext(Dispatchers.IO) {
                val headers = mapOf("X-FotoFesta-Key" to key(url), "X-FotoFesta-Sender" to sender, "Content-Type" to "image/jpeg")
                services.lanHttp.post(base(url) + "/photos", headers, bytes).use { response ->
                    check(response.isSuccessful) { "Foto di $sender rifiutata: ${response.status}" }
                }
            }
        }

        /** Foto demo diversa a ogni invio; verticale come quelle dei telefoni, o orizzontale. */
        private fun guestPhoto(portrait: Boolean): ByteArray {
            val pictures = listOf(R.drawable.demo_birthday, R.drawable.demo_mountains, R.drawable.demo_picnic, R.drawable.demo_coast)
            val picture = pictures[uploads++ % pictures.size]
            val source = BitmapFactory.decodeResource(activity.resources, picture)
            val bitmap = if (portrait) {
                val width = minOf(source.width, source.height * 3 / 4)
                Bitmap.createBitmap(source, (source.width - width) / 2, 0, width, source.height)
            } else {
                source
            }
            return ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
                out.toByteArray()
            }
        }

        /** Due invitati rispondono dal telefono alla domanda del gioco, come la pagina della festa. */
        private suspend fun answerFromPhones() {
            val url = vm.state.value.guests.url ?: error("La festa non ha un indirizzo")
            val round = withTimeoutOrNull(30_000) {
                var current: String? = null
                while (current == null) {
                    current = withContext(Dispatchers.IO) {
                        services.lanHttp.get(base(url) + "/game", mapOf("X-FotoFesta-Key" to key(url))).use { response ->
                            JSONObject(response.bytes(64 * 1024).toString(Charsets.UTF_8))
                                .optString("round").takeIf { it.isNotEmpty() && it != "null" }
                        }
                    }
                    if (current == null) delay(500)
                }
                current
            } ?: error("Nessuna domanda per i telefoni")
            listOf(Triple("telefono-giulia", "Giulia", 0), Triple("telefono-marco", "Marco", 1)).forEach { (player, nickname, choice) ->
                withContext(Dispatchers.IO) {
                    val form = "round=$round&choice=$choice&player=$player&name=$nickname".toByteArray()
                    val headers = mapOf("X-FotoFesta-Key" to key(url), "Content-Type" to "application/x-www-form-urlencoded")
                    services.lanHttp.post(base(url) + "/game/answer", headers, form).use { response ->
                        check(response.isSuccessful) { "Risposta di $nickname rifiutata: ${response.status}" }
                    }
                }
            }
            // La schermata mostra la risposta giusta dopo due risposte (WhoTookItScreen).
            delay(3_000)
        }

        private fun smbAddress(): String = "${smbHost.host}:${smbHost.port}"

        /** Elenco delle condivisioni del computer di prova, dopo l'accesso con utente e password. */
        private suspend fun smbShares() {
            fresh()
            vm.openBrowser(BrowserKind.PHOTOS, systemPickerAvailable = false)
            vm.openSmbLogin(smbHost)
            vm.smbConnect(smbAddress(), smbUser, smbPassword)
            val server = SmbLocation(smbHost.host, smbHost.port).browserPath
            until("condivisioni", 40_000) {
                it.smb.errorRes?.let { message -> error("Accesso non riuscito: ${activity.getString(message)}") }
                it.destination == Destination.FILE_BROWSER && it.browser.directory == server && !it.browser.loading
            }
        }

        private suspend fun smbFolder() {
            smbShares()
            val share = SmbLocation(smbHost.host, smbHost.port, smbShare).browserPath
            vm.browserOpen(share)
            until("cartella condivisa", 40_000) { it.browser.directory == share && !it.browser.loading }
        }

        /** Server DLNA di prova, aggiunto con l'indirizzo: nell'emulatore la ricerca in multicast non arriva. */
        private suspend fun dlna(folder: Boolean) {
            fresh()
            withContext(Dispatchers.IO) { services.networkMedia.find(dlnaUdn, dlnaLocation) }
                ?: error("Server DLNA di prova non raggiungibile: $dlnaLocation")
            vm.openBrowser(BrowserKind.PHOTOS, systemPickerAvailable = false)
            val root = NetworkPath.of(dlnaUdn, NetworkMedia.ROOT)
            vm.browserOpen(root)
            until("cartella principale del server", 30_000) { it.browser.directory == root && !it.browser.loading }
            if (!folder) return
            val party = vm.state.value.browser.folders.firstOrNull() ?: error("Il server non ha cartelle")
            vm.browserOpen(party.path)
            until("cartella del server", 30_000) { it.browser.directory == party.path && !it.browser.loading }
        }

        /** "Usa questa cartella", poi lo slideshow della raccolta appena letta. */
        private suspend fun useFolderAndPlay() {
            vm.browserUseFolder()
            until("raccolta letta", 60_000) { it.destination == Destination.HOME && !it.busy }
            vm.playCurrent()
            until("slideshow") { it.destination == Destination.SLIDESHOW }
            delay(4_000)
        }

        /** Avanza fino al primo video della raccolta e lo lascia andare per qualche secondo. */
        private suspend fun showVideo() {
            val photos = vm.state.value.photos
            check(photos.any { it.video }) { "Nessun video nella raccolta (${photos.size} elementi)" }
            repeat(photos.size) {
                if (vm.state.value.let { state -> state.photos.getOrNull(state.currentIndex)?.video } == true) {
                    delay(6_000)
                    return
                }
                vm.next()
                delay(300)
            }
            error("Video non raggiunto")
        }

        private suspend fun until(what: String, timeoutMs: Long = 20_000, condition: (AppState) -> Boolean) {
            withTimeoutOrNull(timeoutMs) { vm.state.first(condition) } ?: error("Tempo scaduto: $what")
        }

        private fun base(url: String): String = Uri.parse(url).let { "${it.scheme}://${it.encodedAuthority}" }

        private fun key(url: String): String = Uri.parse(url).getQueryParameter("k") ?: error("Chiave mancante")
    }
}
