package it.fotofesta.tv.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import it.fotofesta.tv.R
import it.fotofesta.tv.tv.TvFeatures
import it.fotofesta.tv.model.AppState
import it.fotofesta.tv.model.FitMode
import it.fotofesta.tv.model.PartyTemplate
import it.fotofesta.tv.model.PhotoFilter
import it.fotofesta.tv.model.PhotoLimit
import it.fotofesta.tv.model.SettingsCategory
import it.fotofesta.tv.model.SlideshowSettings
import it.fotofesta.tv.model.SlideshowLayout
import it.fotofesta.tv.model.TransitionType
import it.fotofesta.tv.model.Countdown
import it.fotofesta.tv.model.CountdownEvent
import kotlin.math.roundToInt
import android.text.format.DateFormat
import java.util.Calendar
import androidx.compose.ui.platform.LocalContext

/** Impostazioni che si scelgono da un elenco di valori, aperto al posto delle voci. */
private enum class Picker { INTERVAL, TRANSITION, FIT, LAYOUT, SLEEP, COUNTDOWN_TIME, COUNTDOWN_EVENT, VOLUME, FILTER, TEMPLATE }

private val INTERVAL_OPTIONS = listOf(3, 5, 8, 12, 20)
private val SLEEP_TIMER_OPTIONS = listOf(0, 30, 60, 120)
private val VOLUME_OPTIONS = listOf(.15f, .35f, .60f)

private data class Category(val value: SettingsCategory, @StringRes val label: Int, val icon: ImageVector)

private val CATEGORIES = listOf(
    Category(SettingsCategory.SLIDESHOW, R.string.settings_cat_slideshow, Icons.Rounded.Slideshow),
    Category(SettingsCategory.MUSIC, R.string.settings_cat_music, Icons.Rounded.MusicNote),
    Category(SettingsCategory.PARTY, R.string.settings_cat_party, Icons.Rounded.Groups),
    Category(SettingsCategory.TV, R.string.settings_cat_tv, Icons.Rounded.Tv),
    Category(SettingsCategory.SCREENSAVER, R.string.settings_cat_screensaver, Icons.Rounded.DarkMode),
    Category(SettingsCategory.PRIVACY, R.string.settings_cat_privacy, Icons.Rounded.Shield),
)

/**
 * Impostazioni in due colonne: a sinistra le sezioni (spostando il fuoco si apre la sezione), a
 * destra le voci con il valore scritto e gli interruttori per i sì e no. Le voci con "›" aprono
 * l'elenco dei valori; Indietro lo richiude.
 */
@Composable
fun SettingsScreen(
    state: AppState,
    onCategory: (SettingsCategory) -> Unit,
    onChange: (SlideshowSettings) -> Unit,
    onPickMusic: () -> Unit,
    onRemoveMusic: () -> Unit,
    onDisconnectGoogle: () -> Unit,
    onOpenScreensaverSettings: () -> Unit,
    onApproval: (Boolean) -> Unit,
    onQrInSlideshow: (Boolean) -> Unit,
    onWifi: () -> Unit,
    onRemoveWifi: () -> Unit,
    onPickUsbBackup: () -> Unit = {},
    onRemoveUsbBackup: () -> Unit = {},
) {
    val selected = state.settingsCategory
    val context = LocalContext.current
    // Fire TV non lascia scegliere salvaschermi di altre app né mostra i canali nella schermata Home.
    val fireTv = remember { TvFeatures.isFireTv(context) }
    val categories = remember(fireTv) { if (fireTv) CATEGORIES.filter { it.value != SettingsCategory.SCREENSAVER } else CATEGORIES }
    val firstItem = remember { FocusRequester() }
    val categoryFocus = remember { CATEGORIES.associate { it.value to FocusRequester() } }
    val pickerFocus = remember { Picker.entries.associateWith { FocusRequester() } }
    var picker by remember { mutableStateOf<Picker?>(null) }
    var returnTo by remember { mutableStateOf<Picker?>(null) }

    // All'apertura il fuoco va alla prima voce; dopo, cambiare sezione non lo sposta. Tornando in
    // Musica o in Festa (dal browser della musica o dalla Wi‑Fi) va sulla sezione aperta: altrimenti
    // finirebbe sulla prima sezione, che con il fuoco si apre al posto di quella in uso.
    LaunchedEffect(Unit) {
        runCatching {
            if (selected == SettingsCategory.SLIDESHOW) firstItem.requestFocus() else categoryFocus.getValue(selected).requestFocus()
        }
    }
    // Chiuso l'elenco dei valori, il fuoco torna sulla voce che lo aveva aperto.
    LaunchedEffect(picker) {
        val target = returnTo
        if (picker == null && target != null) {
            runCatching { pickerFocus.getValue(target).requestFocus() }
            returnTo = null
        }
    }
    LaunchedEffect(selected) { picker = null }
    BackHandler(enabled = picker != null) {
        returnTo = picker
        picker = null
    }

    fun open(value: Picker) {
        picker = value
    }

    fun choose(apply: () -> Unit) {
        apply()
        returnTo = picker
        picker = null
    }

    // Dalla colonna destra, la freccia a sinistra torna sempre alla sezione aperta.
    val toCategory = Modifier.focusProperties { left = categoryFocus.getValue(selected) }
    val settings = state.settings

    Row(Modifier.fillMaxSize().background(Background)) {
        Column(
            Modifier.width(268.dp).fillMaxHeight().background(SurfaceDark)
                .padding(start = 48.dp, end = 20.dp, top = 27.dp, bottom = 27.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BrandMark()
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
            Spacer(Modifier.height(12.dp))
            categories.forEach { category ->
                CategoryItem(
                    stringResource(category.label),
                    category.icon,
                    selected = category.value == selected,
                    onClick = {
                        onCategory(category.value)
                        runCatching { firstItem.requestFocus() }
                    },
                    onFocused = { onCategory(category.value) },
                    modifier = Modifier.focusRequester(categoryFocus.getValue(category.value)),
                )
            }
        }
        Column(
            Modifier.weight(1f).fillMaxHeight().padding(start = 36.dp, end = 48.dp, top = 82.dp),
        ) {
            Kicker(stringResource(CATEGORIES.first { it.value == selected }.label))
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 27.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (picker) {
                    Picker.INTERVAL -> OptionsPanel(
                        stringResource(R.string.settings_interval),
                        INTERVAL_OPTIONS.map { quantityStringResource(R.plurals.settings_interval_value, it) },
                        INTERVAL_OPTIONS.indexOf(settings.intervalSeconds),
                        toCategory,
                    ) { index -> choose { onChange(settings.copy(intervalSeconds = INTERVAL_OPTIONS[index])) } }
                    Picker.TRANSITION -> OptionsPanel(
                        stringResource(R.string.settings_transition),
                        TransitionType.entries.map { stringResource(it.labelRes) },
                        settings.transition.ordinal,
                        toCategory,
                    ) { index -> choose { onChange(settings.copy(transition = TransitionType.entries[index])) } }
                    Picker.FIT -> OptionsPanel(
                        stringResource(R.string.settings_fit),
                        FitMode.entries.map { stringResource(it.labelRes) },
                        settings.fitMode.ordinal,
                        toCategory,
                        descriptions = FitMode.entries.map { stringResource(it.descriptionRes) },
                    ) { index -> choose { onChange(settings.copy(fitMode = FitMode.entries[index])) } }
                    Picker.LAYOUT -> OptionsPanel(
                        stringResource(R.string.settings_layout),
                        SlideshowLayout.entries.map { stringResource(it.labelRes) },
                        settings.layout.ordinal,
                        toCategory,
                        descriptions = SlideshowLayout.entries.map { stringResource(it.descriptionRes) },
                    ) { index -> choose { onChange(settings.copy(layout = SlideshowLayout.entries[index])) } }
                    Picker.FILTER -> OptionsPanel(
                        stringResource(R.string.settings_filter),
                        PhotoFilter.entries.map { stringResource(it.labelRes) },
                        settings.filter.ordinal,
                        toCategory,
                    ) { index -> choose { onChange(settings.copy(filter = PhotoFilter.entries[index])) } }
                    Picker.TEMPLATE -> OptionsPanel(
                        stringResource(R.string.settings_template),
                        PartyTemplate.entries.map { stringResource(it.labelRes) },
                        settings.template.ordinal,
                        toCategory,
                    ) { index -> choose { onChange(PartyTemplate.entries[index].apply(settings)) } }
                    Picker.SLEEP -> OptionsPanel(
                        stringResource(R.string.settings_sleep_timer),
                        SLEEP_TIMER_OPTIONS.map { sleepLabel(it) },
                        SLEEP_TIMER_OPTIONS.indexOf(settings.sleepTimerMinutes),
                        toCategory,
                    ) { index -> choose { onChange(settings.copy(sleepTimerMinutes = SLEEP_TIMER_OPTIONS[index])) } }
                    Picker.COUNTDOWN_TIME -> OptionsPanel(
                        stringResource(R.string.settings_countdown_time),
                        Countdown.TIME_OPTIONS.map { timeLabel(it) },
                        Countdown.TIME_OPTIONS.indexOf(settings.countdownMinutes),
                        toCategory,
                    ) { index -> choose { onChange(settings.copy(countdownMinutes = Countdown.TIME_OPTIONS[index])) } }
                    Picker.COUNTDOWN_EVENT -> OptionsPanel(
                        stringResource(R.string.settings_countdown_event),
                        CountdownEvent.entries.map { stringResource(it.labelRes) },
                        settings.countdownEvent.ordinal,
                        toCategory,
                        descriptions = CountdownEvent.entries.map { stringResource(it.messageRes) },
                    ) { index -> choose { onChange(settings.copy(countdownEvent = CountdownEvent.entries[index])) } }
                    Picker.VOLUME -> OptionsPanel(
                        stringResource(R.string.settings_volume),
                        VOLUME_OPTIONS.map { stringResource(R.string.settings_volume_value, (it * 100).roundToInt()) },
                        VOLUME_OPTIONS.indexOf(settings.musicVolume),
                        toCategory,
                    ) { index -> choose { onChange(settings.copy(musicVolume = VOLUME_OPTIONS[index])) } }
                    null -> when (selected) {
                        SettingsCategory.SLIDESHOW -> {
                            SettingsItem(
                                stringResource(R.string.settings_interval),
                                { open(Picker.INTERVAL) },
                                Modifier.focusRequester(firstItem).focusRequester(pickerFocus.getValue(Picker.INTERVAL)).then(toCategory),
                                value = quantityStringResource(R.plurals.settings_interval_value, settings.intervalSeconds),
                            )
                            SettingsItem(
                                stringResource(R.string.settings_transition),
                                { open(Picker.TRANSITION) },
                                Modifier.focusRequester(pickerFocus.getValue(Picker.TRANSITION)).then(toCategory),
                                value = stringResource(settings.transition.labelRes),
                            )
                            SettingsItem(
                                stringResource(R.string.settings_fit),
                                { open(Picker.FIT) },
                                Modifier.focusRequester(pickerFocus.getValue(Picker.FIT)).then(toCategory),
                                subtitle = stringResource(settings.fitMode.descriptionRes),
                                value = stringResource(settings.fitMode.labelRes),
                            )
                            SettingsItem(
                                stringResource(R.string.settings_layout),
                                { open(Picker.LAYOUT) },
                                Modifier.focusRequester(pickerFocus.getValue(Picker.LAYOUT)).then(toCategory),
                                subtitle = stringResource(settings.layout.descriptionRes),
                                value = stringResource(settings.layout.labelRes),
                            )
                            SettingsItem(
                                stringResource(R.string.settings_videos),
                                { onChange(settings.copy(videos = !settings.videos)) },
                                toCategory,
                                subtitle = stringResource(R.string.settings_videos_body),
                                checked = settings.videos,
                            )
                            if (settings.videos) {
                                SettingsItem(
                                    stringResource(R.string.settings_video_sound),
                                    { onChange(settings.copy(videoSound = !settings.videoSound)) },
                                    toCategory,
                                    subtitle = stringResource(R.string.settings_video_sound_body),
                                    checked = settings.videoSound,
                                )
                            }
                            SettingsItem(
                                stringResource(R.string.settings_shuffle),
                                { onChange(settings.copy(shuffle = !settings.shuffle)) },
                                toCategory,
                                checked = settings.shuffle,
                            )
                            SettingsItem(
                                stringResource(R.string.settings_ken_burns),
                                { onChange(settings.copy(kenBurns = !settings.kenBurns)) },
                                toCategory,
                                subtitle = stringResource(R.string.settings_ken_burns_body),
                                checked = settings.kenBurns && !settings.reducedMotion,
                                enabled = !settings.reducedMotion,
                            )
                            SettingsItem(
                                stringResource(R.string.settings_reduced_motion),
                                { onChange(settings.copy(reducedMotion = !settings.reducedMotion)) },
                                toCategory,
                                subtitle = stringResource(R.string.settings_reduced_motion_body),
                                checked = settings.reducedMotion,
                            )
                            SettingsItem(
                                stringResource(R.string.settings_clock),
                                { onChange(settings.copy(showClock = !settings.showClock)) },
                                toCategory,
                                checked = settings.showClock,
                            )
                            SettingsItem(
                                stringResource(R.string.settings_filter),
                                { open(Picker.FILTER) },
                                Modifier.focusRequester(pickerFocus.getValue(Picker.FILTER)).then(toCategory),
                                value = stringResource(settings.filter.labelRes),
                            )
                            SettingsItem(
                                stringResource(R.string.settings_confetti),
                                { onChange(settings.copy(confetti = !settings.confetti)) },
                                toCategory,
                                subtitle = stringResource(R.string.settings_confetti_body),
                                checked = settings.confetti,
                            )
                            SettingsItem(
                                stringResource(R.string.settings_news),
                                { onChange(settings.copy(newsMode = !settings.newsMode)) },
                                toCategory,
                                subtitle = stringResource(R.string.settings_news_body),
                                checked = settings.newsMode,
                            )
                            SettingsItem(
                                stringResource(R.string.settings_opening),
                                { onChange(settings.copy(openingTitles = !settings.openingTitles)) },
                                toCategory,
                                subtitle = stringResource(R.string.settings_opening_body),
                                checked = settings.openingTitles,
                            )
                            SettingsItem(
                                stringResource(R.string.settings_portrait),
                                { onChange(settings.copy(portrait = !settings.portrait)) },
                                toCategory,
                                subtitle = stringResource(R.string.settings_portrait_body),
                                checked = settings.portrait,
                            )
                            SettingsItem(
                                stringResource(R.string.settings_sleep_timer),
                                { open(Picker.SLEEP) },
                                Modifier.focusRequester(pickerFocus.getValue(Picker.SLEEP)).then(toCategory),
                                subtitle = stringResource(R.string.settings_sleep_timer_body),
                                value = sleepLabel(settings.sleepTimerMinutes),
                            )
                            SettingsItem(
                                stringResource(R.string.settings_countdown),
                                { onChange(settings.copy(countdown = !settings.countdown)) },
                                toCategory,
                                subtitle = stringResource(R.string.settings_countdown_body),
                                checked = settings.countdown,
                            )
                            if (settings.countdown) {
                                SettingsItem(
                                    stringResource(R.string.settings_countdown_time),
                                    { open(Picker.COUNTDOWN_TIME) },
                                    Modifier.focusRequester(pickerFocus.getValue(Picker.COUNTDOWN_TIME)).then(toCategory),
                                    value = timeLabel(settings.countdownMinutes),
                                )
                                SettingsItem(
                                    stringResource(R.string.settings_countdown_event),
                                    { open(Picker.COUNTDOWN_EVENT) },
                                    Modifier.focusRequester(pickerFocus.getValue(Picker.COUNTDOWN_EVENT)).then(toCategory),
                                    value = stringResource(settings.countdownEvent.labelRes),
                                )
                            }
                        }
                        SettingsCategory.MUSIC -> {
                            SettingsItem(
                                stringResource(R.string.settings_music),
                                onPickMusic,
                                Modifier.focusRequester(firstItem).then(toCategory),
                                subtitle = stringResource(R.string.settings_music_body),
                                value = state.musicName ?: stringResource(R.string.settings_music_value_none),
                            )
                            if (state.musicUri != null) {
                                SettingsItem(
                                    stringResource(R.string.settings_music_remove_item),
                                    {
                                        // La voce sparisce: il fuoco passa prima alla voce del brano.
                                        runCatching { firstItem.requestFocus() }
                                        onRemoveMusic()
                                    },
                                    toCategory,
                                )
                            }
                            SettingsItem(
                                stringResource(R.string.settings_volume),
                                { open(Picker.VOLUME) },
                                Modifier.focusRequester(pickerFocus.getValue(Picker.VOLUME)).then(toCategory),
                                subtitle = stringResource(R.string.settings_volume_body),
                                value = stringResource(R.string.settings_volume_value, (settings.musicVolume * 100).roundToInt()),
                            )
                        }
                        SettingsCategory.PARTY -> {
                            SettingsItem(
                                stringResource(R.string.settings_template),
                                { open(Picker.TEMPLATE) },
                                Modifier.focusRequester(firstItem).focusRequester(pickerFocus.getValue(Picker.TEMPLATE)).then(toCategory),
                                subtitle = stringResource(R.string.settings_template_body),
                                value = stringResource(settings.template.labelRes),
                            )
                            SettingsItem(
                                stringResource(R.string.guests_qr_in_slideshow),
                                { onQrInSlideshow(!state.guests.qrInSlideshow) },
                                toCategory,
                                subtitle = stringResource(R.string.guests_qr_in_slideshow_body),
                                checked = state.guests.qrInSlideshow,
                            )
                            SettingsItem(
                                stringResource(R.string.guests_approval),
                                { onApproval(!state.guests.approval) },
                                toCategory,
                                subtitle = stringResource(R.string.guests_approval_body),
                                checked = state.guests.approval,
                            )
                            SettingsItem(
                                stringResource(R.string.settings_phone_reactions),
                                { onChange(state.settings.copy(phoneReactions = !state.settings.phoneReactions)) },
                                toCategory,
                                subtitle = stringResource(R.string.settings_phone_reactions_body),
                                checked = state.settings.phoneReactions,
                            )
                            SettingsItem(
                                stringResource(R.string.settings_phone_messages),
                                { onChange(state.settings.copy(phoneMessages = !state.settings.phoneMessages)) },
                                toCategory,
                                subtitle = stringResource(R.string.settings_phone_messages_body),
                                checked = state.settings.phoneMessages,
                            )
                            SettingsItem(
                                stringResource(R.string.settings_phone_host),
                                { onChange(state.settings.copy(phoneHost = !state.settings.phoneHost)) },
                                toCategory,
                                subtitle = stringResource(R.string.settings_phone_host_body),
                                checked = state.settings.phoneHost,
                            )
                            // Un clic passa al valore successivo: senza limite, una a testa, rullino.
                            SettingsItem(
                                stringResource(R.string.settings_photo_limit),
                                {
                                    val values = PhotoLimit.entries
                                    onChange(state.settings.copy(photoLimit = values[(state.settings.photoLimit.ordinal + 1) % values.size]))
                                },
                                toCategory,
                                subtitle = stringResource(R.string.settings_photo_limit_body),
                                value = stringResource(state.settings.photoLimit.labelRes),
                            )
                            SettingsItem(
                                stringResource(R.string.settings_aging),
                                { onChange(state.settings.copy(agingPhotos = !state.settings.agingPhotos)) },
                                toCategory,
                                subtitle = stringResource(R.string.settings_aging_body),
                                checked = state.settings.agingPhotos,
                            )
                            SettingsItem(
                                stringResource(R.string.settings_no_traces),
                                { onChange(state.settings.copy(noTraces = !state.settings.noTraces)) },
                                toCategory,
                                subtitle = stringResource(R.string.settings_no_traces_body),
                                checked = state.settings.noTraces,
                            )
                            SettingsItem(
                                stringResource(R.string.settings_resume),
                                { onChange(state.settings.copy(resumeParty = !state.settings.resumeParty)) },
                                toCategory,
                                subtitle = stringResource(R.string.settings_resume_body),
                                checked = state.settings.resumeParty,
                            )
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                SettingsItem(
                                    stringResource(R.string.settings_backup),
                                    { onChange(state.settings.copy(backupCopy = !state.settings.backupCopy)) },
                                    toCategory,
                                    subtitle = stringResource(R.string.settings_backup_body),
                                    checked = state.settings.backupCopy,
                                )
                            }
                            SettingsItem(
                                stringResource(R.string.guests_wifi),
                                onWifi,
                                toCategory,
                                subtitle = stringResource(R.string.guests_wifi_body),
                                value = state.guests.wifiName ?: stringResource(R.string.settings_wifi_none),
                            )
                            if (state.guests.wifiName != null) {
                                SettingsItem(
                                    stringResource(R.string.settings_wifi_remove_item),
                                    {
                                        runCatching { firstItem.requestFocus() }
                                        onRemoveWifi()
                                    },
                                    toCategory,
                                )
                            }
                        }
                        SettingsCategory.TV -> {
                            SettingsItem(
                                stringResource(R.string.settings_light_mode),
                                { onChange(settings.copy(lightMode = !settings.lightMode)) },
                                Modifier.focusRequester(firstItem).then(toCategory),
                                subtitle = stringResource(R.string.settings_light_mode_body),
                                checked = settings.lightMode,
                            )
                            SettingsItem(
                                stringResource(R.string.settings_projector),
                                { onChange(settings.copy(projectorMode = !settings.projectorMode)) },
                                toCategory,
                                subtitle = stringResource(R.string.settings_projector_body),
                                checked = settings.projectorMode,
                            )
                            SettingsItem(
                                stringResource(R.string.settings_auto_start),
                                { onChange(settings.copy(autoStart = !settings.autoStart)) },
                                toCategory,
                                subtitle = stringResource(R.string.settings_auto_start_body),
                                checked = settings.autoStart,
                            )
                            if (!fireTv) {
                                SettingsItem(
                                    stringResource(R.string.settings_home_channel),
                                    { onChange(settings.copy(homeChannel = !settings.homeChannel)) },
                                    toCategory,
                                    subtitle = stringResource(R.string.settings_home_channel_body),
                                    checked = settings.homeChannel,
                                )
                            }
                            SettingsItem(
                                stringResource(R.string.settings_dlna),
                                { onChange(settings.copy(dlnaReceiver = !settings.dlnaReceiver)) },
                                toCategory,
                                subtitle = stringResource(R.string.settings_dlna_body),
                                checked = settings.dlnaReceiver,
                            )
                            SettingsItem(
                                stringResource(R.string.settings_usb_backup),
                                onPickUsbBackup,
                                toCategory,
                                subtitle = stringResource(R.string.settings_usb_backup_body),
                                value = state.usbBackupName ?: stringResource(R.string.settings_wifi_none),
                            )
                            if (fireTv) {
                                // Solo da leggere: le frasi di Alexa arrivano alla sessione multimediale dell'app.
                                SettingsItem(
                                    stringResource(R.string.settings_alexa),
                                    {},
                                    toCategory,
                                    subtitle = stringResource(R.string.settings_alexa_body),
                                )
                            }
                            if (state.usbBackupName != null) {
                                SettingsItem(
                                    stringResource(R.string.settings_usb_backup_remove),
                                    {
                                        runCatching { firstItem.requestFocus() }
                                        onRemoveUsbBackup()
                                    },
                                    toCategory,
                                )
                            }
                        }
                        SettingsCategory.SCREENSAVER -> SettingsItem(
                            stringResource(R.string.settings_screensaver_open),
                            onOpenScreensaverSettings,
                            Modifier.focusRequester(firstItem).then(toCategory),
                            subtitle = stringResource(R.string.settings_screensaver_body),
                        )
                        SettingsCategory.PRIVACY -> {
                            SettingsItem(
                                stringResource(R.string.settings_google_disconnect),
                                onDisconnectGoogle,
                                Modifier.focusRequester(firstItem).then(toCategory),
                                subtitle = stringResource(R.string.settings_google_data_body),
                            )
                            // Selezionabile per poterlo leggere tutto scorrendo con il telecomando.
                            FocusSurface(
                                onClick = {},
                                modifier = Modifier.fillMaxWidth().then(toCategory),
                                shape = RoundedCornerShape(12.dp),
                                color = SurfaceDark,
                                focusScale = 1.015f,
                            ) { focused ->
                                Text(
                                    stringResource(R.string.settings_privacy),
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (focused) OnFocusSecondary else TextSecondary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun timeLabel(minutesOfDay: Int): String {
    val calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, minutesOfDay / 60)
        set(Calendar.MINUTE, minutesOfDay % 60)
    }
    return DateFormat.getTimeFormat(LocalContext.current).format(calendar.time)
}

@Composable
private fun sleepLabel(minutes: Int): String =
    if (minutes == 0) stringResource(R.string.settings_off) else stringResource(R.string.settings_minutes, minutes)

/** Elenco dei valori di un'impostazione: quello in uso ha il segno di spunta e riceve il fuoco. */
@Composable
private fun OptionsPanel(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    itemModifier: Modifier,
    descriptions: List<String>? = null,
    onSelect: (Int) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleLarge, color = TextPrimary, modifier = Modifier.padding(bottom = 6.dp))
    options.forEachIndexed { index, label ->
        val chosen = index == selectedIndex
        FocusSurface(
            onClick = { onSelect(index) },
            modifier = (if (chosen || (selectedIndex < 0 && index == 0)) Modifier.initialFocus(title) else Modifier)
                .fillMaxWidth()
                .then(itemModifier),
            shape = RoundedCornerShape(12.dp),
            color = SurfaceDark,
            focusScale = 1.025f,
        ) { focused ->
            Row(
                Modifier.heightIn(min = 52.dp).padding(horizontal = 18.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.titleSmall)
                    descriptions?.getOrNull(index)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = if (focused) OnFocusSecondary else TextSecondary)
                    }
                }
                if (chosen) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = stringResource(R.string.settings_selected),
                        tint = if (focused) OnFocus else Accent,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}
