package it.fotofesta.tv.data

import android.annotation.SuppressLint
import android.content.Context
import it.fotofesta.tv.model.Countdown
import it.fotofesta.tv.model.CountdownEvent
import it.fotofesta.tv.model.FitMode
import it.fotofesta.tv.model.FrameStyle
import it.fotofesta.tv.model.PhotoFilter
import it.fotofesta.tv.model.PhotoLimit
import it.fotofesta.tv.model.PartyTemplate
import it.fotofesta.tv.model.SlideshowLayout
import it.fotofesta.tv.model.SlideshowSettings
import it.fotofesta.tv.model.TransitionType
import it.fotofesta.tv.network.NetworkFolder
import it.fotofesta.tv.smb.SmbFolder
import it.fotofesta.tv.smb.SmbLocation
import it.fotofesta.tv.tv.BootReceiver
import it.fotofesta.tv.tv.TvFeatures

/**
 * Preferenze dell'app, divise in due file:
 * - `slideshow_settings`: impostazioni dello slideshow, incluse nel backup del sistema;
 * - `device_sources`: cartella USB, brano e ultima sorgente. Restano escluse dal backup perché i permessi
 *   sugli URI non passano a un altro dispositivo e perché indicano contenuti presenti solo su questa TV.
 */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("slideshow_settings", Context.MODE_PRIVATE)
    private val sources = context.getSharedPreferences("device_sources", Context.MODE_PRIVATE)
    private val lowRam by lazy { TvFeatures.isLowRam(context) }

    /** Si può chiedere una recensione: mai chiesta, o non negli ultimi [everyMillis]. */
    fun reviewDue(everyMillis: Long): Boolean = System.currentTimeMillis() - sources.getLong("review_asked", 0L) > everyMillis

    fun markReviewAsked() = sources.edit().putLong("review_asked", System.currentTimeMillis()).apply()

    /** PIN del chiosco: si salva solo un'impronta con il sale, mai il PIN. */
    fun savePin(pin: String?) {
        if (pin == null) {
            sources.edit().remove("kiosk_pin").remove("kiosk_salt").apply()
            return
        }
        val salt = ByteArray(16).also(java.security.SecureRandom()::nextBytes)
        val saltText = java.util.Base64.getEncoder().encodeToString(salt)
        sources.edit().putString("kiosk_salt", saltText).putString("kiosk_pin", pinHash(pin, saltText)).apply()
    }

    fun hasPin(): Boolean = sources.contains("kiosk_pin")

    fun pinMatches(pin: String): Boolean {
        val salt = sources.getString("kiosk_salt", null) ?: return false
        val expected = sources.getString("kiosk_pin", null) ?: return false
        return java.security.MessageDigest.isEqual(expected.toByteArray(), pinHash(pin, salt).toByteArray())
    }

    private fun pinHash(pin: String, salt: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest((salt + ":" + pin).toByteArray(Charsets.UTF_8))
        return java.util.Base64.getEncoder().encodeToString(digest)
    }

    /** Impostazioni dello slideshow e della festa, per un'altra TV o una chiavetta pronta all'uso. */
    fun exportSettings(): org.json.JSONObject = org.json.JSONObject().apply {
        prefs.all.forEach { (key, value) ->
            when (value) {
                is Boolean -> put(key, org.json.JSONObject().put("b", value))
                is Int -> put(key, org.json.JSONObject().put("i", value))
                is Long -> put(key, org.json.JSONObject().put("l", value))
                is Float -> put(key, org.json.JSONObject().put("f", value.toDouble()))
                is String -> put(key, org.json.JSONObject().put("s", value))
            }
        }
    }

    /** Il contrario di [exportSettings]; i valori di tipo sconosciuto si saltano. */
    fun importSettings(json: org.json.JSONObject) {
        val editor = prefs.edit()
        json.keys().forEach { key ->
            val entry = json.optJSONObject(key) ?: return@forEach
            when {
                entry.has("b") -> editor.putBoolean(key, entry.optBoolean("b"))
                entry.has("i") -> editor.putInt(key, entry.optInt("i"))
                entry.has("l") -> editor.putLong(key, entry.optLong("l"))
                entry.has("f") -> editor.putFloat(key, entry.optDouble("f").toFloat())
                entry.has("s") -> editor.putString(key, entry.optString("s"))
            }
        }
        editor.apply()
    }

    /** Brano o cartella di brani ([folder]) scelti per la musica di sottofondo. */
    data class SavedMusic(val uri: String, val name: String, val folder: Boolean = false)

    init {
        migrateSourcesOutOfBackup()
    }

    fun load(): SlideshowSettings = SlideshowSettings(
        intervalSeconds = prefs.getInt("interval", 8),
        transition = enumValueOrDefault(prefs.getString("transition", null), TransitionType.FADE),
        // Chiave nuova dalla 1.3.0: "Foto intera" era il valore predefinito e lo sfondo sfocato lo
        // sostituisce; resta solo la scelta esplicita di riempire lo schermo.
        fitMode = prefs.getString("fit_mode", null)?.let { enumValueOrDefault(it, FitMode.BLUR) }
            ?: if (prefs.getString("fit", null) == FitMode.CROP.name) FitMode.CROP else FitMode.BLUR,
        layout = enumValueOrDefault(prefs.getString("layout", null), SlideshowLayout.SINGLE),
        shuffle = prefs.getBoolean("shuffle", false),
        reducedMotion = prefs.getBoolean("reduced_motion", false),
        showClock = prefs.getBoolean("clock", true),
        musicVolume = prefs.getFloat("volume", 0.35f),
        beatSync = prefs.getBoolean("beat_sync", false),
        songRequests = prefs.getBoolean("song_requests", true),
        guestAlbum = prefs.getBoolean("guest_album", true),
        fixedAddress = prefs.getBoolean("fixed_address", false),
        guestVideos = prefs.getBoolean("guest_videos", true),
        frameStyle = enumValueOrDefault(prefs.getString("frame_style", null), FrameStyle.NONE),
        sponsorEvery = prefs.getInt("sponsor_every", 0).takeIf { it in SPONSOR_OPTIONS } ?: 0,
        screenMargin = prefs.getInt("screen_margin", 0).takeIf { it in MARGIN_OPTIONS } ?: 0,
        keystone = prefs.getInt("keystone", 0).coerceIn(-4, 4),
        tableButton = prefs.getBoolean("table_button", false),
        speakAnnouncements = prefs.getBoolean("speak_announcements", false),
        kiosk = prefs.getBoolean("kiosk", false),
        alwaysParty = prefs.getBoolean("always_party", false),
        nightlyReset = prefs.getInt("nightly_reset", -1).takeIf { it in RESET_OPTIONS } ?: -1,
        panoramas = prefs.getBoolean("panoramas", true),
        rivalParty = prefs.getBoolean("rival_party", false),
        boredTv = prefs.getBoolean("bored_tv", false),
        partyLights = prefs.getBoolean("party_lights", true),
        autoPrint = prefs.getBoolean("auto_print", false),
        autoReceipt = prefs.getBoolean("auto_receipt", false),
        ftpServer = prefs.getBoolean("ftp_server", false),
        usbCamera = prefs.getBoolean("usb_camera", true),
        nasCopy = prefs.getBoolean("nas_copy", false),
        usbStorage = prefs.getBoolean("usb_storage", false),
        hotFolder = prefs.getBoolean("hot_folder", false),
        motionPhotos = prefs.getBoolean("motion_photos", true),
        hdrPhotos = prefs.getBoolean("hdr_photos", false),
        kenBurns = prefs.getBoolean("ken_burns", false),
        sleepTimerMinutes = prefs.getInt("sleep_timer", 0),
        countdown = prefs.getBoolean("countdown", false),
        countdownMinutes = prefs.getInt("countdown_minutes", 0).takeIf { it in Countdown.TIME_OPTIONS } ?: 0,
        countdownEvent = enumValueOrDefault(prefs.getString("countdown_event", null), CountdownEvent.MIDNIGHT),
        videos = prefs.getBoolean("videos", true),
        videoSound = prefs.getBoolean("video_sound", true),
        phoneReactions = prefs.getBoolean("phone_reactions", true),
        phoneMessages = prefs.getBoolean("phone_messages", true),
        phoneHost = prefs.getBoolean("phone_host", true),
        photoLimit = enumValueOrDefault(prefs.getString("photo_limit", null), PhotoLimit.NONE),
        agingPhotos = prefs.getBoolean("aging_photos", false),
        noTraces = prefs.getBoolean("no_traces", false),
        resumeParty = prefs.getBoolean("resume_party", true),
        backupCopy = prefs.getBoolean("backup_copy", false),
        filter = enumValueOrDefault(prefs.getString("filter", null), PhotoFilter.NONE),
        confetti = prefs.getBoolean("confetti", false),
        newsMode = prefs.getBoolean("news_mode", false),
        openingTitles = prefs.getBoolean("opening_titles", false),
        portrait = prefs.getBoolean("portrait", false),
        template = enumValueOrDefault(prefs.getString("template", null), PartyTemplate.NONE),
        // Sulle TV con poca memoria la modalità leggera parte già accesa.
        lightMode = prefs.getBoolean("light_mode", lowRam),
        projectorMode = prefs.getBoolean("projector_mode", false),
        autoStart = prefs.getBoolean(BootReceiver.KEY_AUTO_START, false),
        dlnaReceiver = prefs.getBoolean("dlna_receiver", false),
        homeChannel = prefs.getBoolean("home_channel", true),
    )

    fun save(value: SlideshowSettings) {
        prefs.edit()
            .putInt("interval", value.intervalSeconds)
            .putString("transition", value.transition.name)
            .putString("fit_mode", value.fitMode.name)
            .putString("layout", value.layout.name)
            .putBoolean("shuffle", value.shuffle)
            .putBoolean("reduced_motion", value.reducedMotion)
            .putBoolean("clock", value.showClock)
            .putFloat("volume", value.musicVolume)
            .putBoolean("beat_sync", value.beatSync)
            .putBoolean("song_requests", value.songRequests)
            .putBoolean("guest_album", value.guestAlbum)
            .putBoolean("fixed_address", value.fixedAddress)
            .putBoolean("guest_videos", value.guestVideos)
            .putString("frame_style", value.frameStyle.name)
            .putInt("sponsor_every", value.sponsorEvery)
            .putInt("screen_margin", value.screenMargin)
            .putInt("keystone", value.keystone)
            .putBoolean("table_button", value.tableButton)
            .putBoolean("speak_announcements", value.speakAnnouncements)
            .putBoolean("kiosk", value.kiosk)
            .putBoolean("always_party", value.alwaysParty)
            .putInt("nightly_reset", value.nightlyReset)
            .putBoolean("panoramas", value.panoramas)
            .putBoolean("rival_party", value.rivalParty)
            .putBoolean("bored_tv", value.boredTv)
            .putBoolean("party_lights", value.partyLights)
            .putBoolean("auto_print", value.autoPrint)
            .putBoolean("auto_receipt", value.autoReceipt)
            .putBoolean("ftp_server", value.ftpServer)
            .putBoolean("usb_camera", value.usbCamera)
            .putBoolean("nas_copy", value.nasCopy)
            .putBoolean("usb_storage", value.usbStorage)
            .putBoolean("hot_folder", value.hotFolder)
            .putBoolean("motion_photos", value.motionPhotos)
            .putBoolean("hdr_photos", value.hdrPhotos)
            .putBoolean("ken_burns", value.kenBurns)
            .putInt("sleep_timer", value.sleepTimerMinutes)
            .putBoolean("countdown", value.countdown)
            .putInt("countdown_minutes", value.countdownMinutes)
            .putString("countdown_event", value.countdownEvent.name)
            .putBoolean("videos", value.videos)
            .putBoolean("video_sound", value.videoSound)
            .putBoolean("phone_reactions", value.phoneReactions)
            .putBoolean("phone_messages", value.phoneMessages)
            .putBoolean("phone_host", value.phoneHost)
            .putString("photo_limit", value.photoLimit.name)
            .putBoolean("aging_photos", value.agingPhotos)
            .putBoolean("no_traces", value.noTraces)
            .putBoolean("resume_party", value.resumeParty)
            .putBoolean("backup_copy", value.backupCopy)
            .putString("filter", value.filter.name)
            .putBoolean("confetti", value.confetti)
            .putBoolean("news_mode", value.newsMode)
            .putBoolean("opening_titles", value.openingTitles)
            .putBoolean("portrait", value.portrait)
            .putString("template", value.template.name)
            .putBoolean("light_mode", value.lightMode)
            .putBoolean("projector_mode", value.projectorMode)
            .putBoolean(BootReceiver.KEY_AUTO_START, value.autoStart)
            .putBoolean("dlna_receiver", value.dlnaReceiver)
            .putBoolean("home_channel", value.homeChannel)
            .apply()
    }

    /** Cartella della chiavetta per la copia delle foto della festa (solo questa TV, fuori dal backup). */
    fun saveUsbBackup(uri: String?, name: String?) {
        sources.edit().putString("usb_backup_uri", uri).putString("usb_backup_name", name).apply()
    }

    fun loadUsbBackup(): Pair<String, String>? {
        val uri = sources.getString("usb_backup_uri", null) ?: return null
        return uri to sources.getString("usb_backup_name", null).orEmpty()
    }

    /** Luci della festa collegate (Hue, WLED, Nanoleaf), con la chiave avuta all'abbinamento. */
    fun saveLights(devices: List<it.fotofesta.tv.hardware.PartyLights.Device>) {
        val array = org.json.JSONArray(devices.map { it.toJson() })
        runCatching { lightSecrets.put("lights", array.toString()) }
    }

    fun loadLights(): List<it.fotofesta.tv.hardware.PartyLights.Device> = runCatching {
        val array = org.json.JSONArray(lightSecrets.get("lights") ?: return emptyList())
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(it.fotofesta.tv.hardware.PartyLights.Device::fromJson) }
    }.getOrDefault(emptyList())

    // Le chiavi delle luci danno il controllo delle lampade di casa: cifrate come le password.
    private val lightSecrets by lazy { KeystoreStrings(sources, "fotofesta_lights_v1") }

    /** Stampante delle foto (IPP) e stampante degli scontrini. */
    fun savePrinter(printer: it.fotofesta.tv.hardware.Ipp.Printer?) {
        sources.edit().apply {
            if (printer == null) remove("ipp_host").remove("ipp_port").remove("ipp_path").remove("ipp_name")
            else putString("ipp_host", printer.host).putInt("ipp_port", printer.port).putString("ipp_path", printer.path).putString("ipp_name", printer.name)
        }.apply()
    }

    fun loadPrinter(): it.fotofesta.tv.hardware.Ipp.Printer? {
        val host = sources.getString("ipp_host", null) ?: return null
        return it.fotofesta.tv.hardware.Ipp.Printer(host, sources.getInt("ipp_port", it.fotofesta.tv.hardware.Ipp.PORT), sources.getString("ipp_path", null) ?: "ipp/print", sources.getString("ipp_name", null) ?: host)
    }

    fun saveReceiptPrinter(host: String?, wide: Boolean) {
        sources.edit().putString("receipt_host", host).putBoolean("receipt_wide", wide).apply()
    }

    fun loadReceiptPrinter(): Pair<String, Boolean>? =
        sources.getString("receipt_host", null)?.let { it to sources.getBoolean("receipt_wide", false) }

    /** Password del server FTP per le fotocamere: sei cifre, la stessa finché non si cambia. */
    fun ftpPassword(): String = sources.getString("ftp_password", null) ?: (100_000 + java.security.SecureRandom().nextInt(900_000)).toString().also {
        sources.edit().putString("ftp_password", it).apply()
    }

    /** Indirizzo del vecchio telefono usato come telecamera. */
    fun saveCameraAddress(address: String) {
        sources.edit().putString("camera_address", address).apply()
    }

    fun loadCameraAddress(): String = sources.getString("camera_address", null).orEmpty()

    fun loadMusic(): SavedMusic? {
        val uri = sources.getString("music_uri", null) ?: return null
        return SavedMusic(uri, sources.getString("music_name", null).orEmpty(), sources.getBoolean("music_folder", false))
    }

    fun saveMusic(uri: String, name: String, folder: Boolean = false) {
        sources.edit().putString("music_uri", uri).putString("music_name", name).putBoolean("music_folder", folder).apply()
    }

    fun clearMusic() {
        sources.edit().remove("music_uri").remove("music_name").remove("music_folder").apply()
    }

    /** Cartella scelta con il selettore di documenti di Android. */
    fun saveUsbTree(uri: String) {
        sources.edit().putString("usb_tree_uri", uri).remove("usb_folder").putString("last_source", "USB").apply()
    }

    fun loadUsbTree(): String? = sources.getString("usb_tree_uri", null)

    /** Cartella scelta con il browser interno, come percorso sul disco. */
    fun saveUsbFolder(path: String) {
        sources.edit().putString("usb_folder", path).remove("usb_tree_uri").putString("last_source", "USB").apply()
    }

    fun loadUsbFolder(): String? = sources.getString("usb_folder", null)

    /** Cartella di un server multimediale della rete di casa. */
    fun saveNetworkFolder(folder: NetworkFolder) {
        sources.edit()
            .putString("network_udn", folder.udn)
            .putString("network_location", folder.location)
            .putString("network_container", folder.containerId)
            .putString("network_title", folder.title)
            .putString("last_source", "NETWORK")
            .apply()
    }

    /** Album condiviso di iCloud: il codice del link pubblico e il nome, per il browser. */
    fun saveICloudAlbum(token: String, name: String) {
        sources.edit().putString("icloud_token", token).putString("icloud_name", name).putString("last_source", "ICLOUD").apply()
    }

    fun loadICloudToken(): String? = sources.getString("icloud_token", null)

    fun loadICloudName(): String? = sources.getString("icloud_name", null)

    /** Password delle cartelle condivise: cifrata con una chiave del Keystore che non lascia la TV. */
    private val secrets by lazy { KeystoreStrings(sources, "fotofesta_smb_v1") }

    /** Cartella condivisa (SMB) di un computer o NAS, con l'accesso usato per aprirla. */
    fun saveSmbFolder(folder: SmbFolder, password: String) {
        sources.edit()
            .putString("smb_host", folder.location.host)
            .putInt("smb_port", folder.location.port)
            .putString("smb_share", folder.location.share)
            .putString("smb_path", folder.location.path)
            .putString("smb_title", folder.title)
            .putString("smb_user", folder.user)
            .putString("smb_domain", folder.domain)
            .putString("last_source", "SMB")
            .apply()
        // Se il Keystore non funziona la password non si salva: al prossimo avvio si richiede.
        runCatching { secrets.put("smb_password", password.ifEmpty { null }) }
    }

    fun loadSmbFolder(): SmbFolder? {
        val host = sources.getString("smb_host", null) ?: return null
        val share = sources.getString("smb_share", null)?.takeIf { it.isNotEmpty() } ?: return null
        val location = SmbLocation(host, sources.getInt("smb_port", SmbLocation.DEFAULT_PORT), share, sources.getString("smb_path", null).orEmpty())
        return SmbFolder(
            location,
            sources.getString("smb_title", null).orEmpty().ifBlank { location.name },
            sources.getString("smb_user", null).orEmpty(),
            sources.getString("smb_domain", null).orEmpty(),
        )
    }

    fun loadSmbPassword(): String = runCatching { secrets.get("smb_password") }.getOrNull().orEmpty()

    fun loadNetworkFolder(): NetworkFolder? {
        val udn = sources.getString("network_udn", null) ?: return null
        val location = sources.getString("network_location", null) ?: return null
        val container = sources.getString("network_container", null) ?: return null
        return NetworkFolder(udn, location, container, sources.getString("network_title", null).orEmpty())
    }

    fun saveLastSource(source: String) {
        sources.edit().putString("last_source", source).apply()
    }

    fun loadLastSource(): String = sources.getString("last_source", "DEMO") ?: "DEMO"

    /** Le versioni precedenti salvavano le sorgenti nel file incluso nel backup: le sposta una volta. */
    @SuppressLint("ApplySharedPref") // commit sincrono voluto: le chiavi si tolgono dal vecchio file solo dopo
    private fun migrateSourcesOutOfBackup() {
        val keys = listOf("music_uri", "music_name", "usb_tree_uri", "last_source").filter(prefs::contains)
        // usb_folder è nato direttamente in device_sources: non serve spostarlo.
        if (keys.isEmpty()) return
        val moved = sources.edit()
        val removed = prefs.edit()
        keys.forEach { key ->
            if (!sources.contains(key)) prefs.getString(key, null)?.let { moved.putString(key, it) }
            removed.remove(key)
        }
        // Prima si scrivono le sorgenti e solo se la scrittura riesce si cancellano dal vecchio file.
        if (moved.commit()) removed.apply()
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
}

/** Ogni quante foto uno sponsor: mai, 5, 10 o 20. */
val SPONSOR_OPTIONS = listOf(0, 5, 10, 20)

/** Margini dello schermo in percentuale. */
val MARGIN_OPTIONS = listOf(0, 2, 4, 6)

/** Ora dell'azzeramento notturno: mai, o dalle 3 alle 6. */
val RESET_OPTIONS = listOf(-1, 3, 4, 5, 6)

/** Correzione trapezio, nell'ordine in cui la scorre l'impostazione. */
val KEYSTONE_OPTIONS = listOf(0, 1, 2, 3, 4, -4, -3, -2, -1)
