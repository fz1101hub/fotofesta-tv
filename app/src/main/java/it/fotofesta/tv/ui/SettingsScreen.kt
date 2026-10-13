package it.fotofesta.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import it.fotofesta.tv.R
import it.fotofesta.tv.model.AppState
import it.fotofesta.tv.model.FitMode
import it.fotofesta.tv.model.SlideshowSettings
import it.fotofesta.tv.model.TransitionType
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    state: AppState,
    onBack: () -> Unit,
    onChange: (SlideshowSettings) -> Unit,
    onPickMusic: () -> Unit,
    onRemoveMusic: () -> Unit,
    onDisconnectGoogle: () -> Unit,
    onPlay: () -> Unit,
) {
    val settings = state.settings
    Column(
        Modifier.fillMaxSize().padding(horizontal = 46.dp, vertical = 30.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                SectionTitle(stringResource(R.string.settings_kicker), stringResource(R.string.settings_title), stringResource(R.string.settings_subtitle))
            }
            TvButton(stringResource(R.string.action_back), onBack, icon = Icons.AutoMirrored.Rounded.ArrowBack)
            Spacer(Modifier.size(12.dp))
            TvButton(stringResource(R.string.action_start), onPlay, icon = Icons.Rounded.PlayArrow)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingRow(stringResource(R.string.settings_interval), stringResource(R.string.settings_interval_body)) {
                listOf(3, 5, 8, 12, 20).forEach { seconds ->
                    TvButton(stringResource(R.string.settings_seconds, seconds), { onChange(settings.copy(intervalSeconds = seconds)) }, selected = settings.intervalSeconds == seconds)
                }
            }
            SettingRow(stringResource(R.string.settings_transition), stringResource(R.string.settings_transition_body)) {
                TransitionType.entries.forEach { transition ->
                    TvButton(stringResource(transition.labelRes), { onChange(settings.copy(transition = transition)) }, selected = settings.transition == transition)
                }
            }
            SettingRow(stringResource(R.string.settings_fit), stringResource(R.string.settings_fit_body)) {
                FitMode.entries.forEach { fit ->
                    TvButton(stringResource(fit.labelRes), { onChange(settings.copy(fitMode = fit)) }, selected = settings.fitMode == fit)
                }
            }
            SettingRow(stringResource(R.string.settings_shuffle), stringResource(R.string.settings_shuffle_body)) {
                TvButton(if (settings.shuffle) stringResource(R.string.settings_on) else stringResource(R.string.settings_off), { onChange(settings.copy(shuffle = !settings.shuffle)) }, selected = settings.shuffle)
            }
            SettingRow(stringResource(R.string.settings_reduced_motion), stringResource(R.string.settings_reduced_motion_body)) {
                TvButton(if (settings.reducedMotion) stringResource(R.string.settings_on) else stringResource(R.string.settings_off), { onChange(settings.copy(reducedMotion = !settings.reducedMotion)) }, selected = settings.reducedMotion)
            }
            SettingRow(stringResource(R.string.settings_ken_burns), stringResource(R.string.settings_ken_burns_body)) {
                TvButton(
                    if (settings.kenBurns) stringResource(R.string.settings_on) else stringResource(R.string.settings_off),
                    { onChange(settings.copy(kenBurns = !settings.kenBurns)) },
                    selected = settings.kenBurns,
                    enabled = !settings.reducedMotion,
                )
            }
            SettingRow(stringResource(R.string.settings_sleep_timer), stringResource(R.string.settings_sleep_timer_body)) {
                TvButton(
                    stringResource(R.string.settings_off),
                    { onChange(settings.copy(sleepTimerMinutes = 0)) },
                    selected = settings.sleepTimerMinutes == 0,
                )
                SLEEP_TIMER_OPTIONS.forEach { minutes ->
                    TvButton(
                        stringResource(R.string.settings_minutes, minutes),
                        { onChange(settings.copy(sleepTimerMinutes = minutes)) },
                        selected = settings.sleepTimerMinutes == minutes,
                    )
                }
            }
            SettingRow(stringResource(R.string.settings_clock), stringResource(R.string.settings_clock_body)) {
                TvButton(if (settings.showClock) stringResource(R.string.settings_visible) else stringResource(R.string.settings_hidden), { onChange(settings.copy(showClock = !settings.showClock)) }, selected = settings.showClock)
            }
            SettingRow(stringResource(R.string.settings_music), state.musicName ?: stringResource(R.string.settings_music_none)) {
                TvButton(stringResource(R.string.settings_music_pick), onPickMusic, icon = Icons.Rounded.LibraryMusic)
                if (state.musicUri != null) TvButton(stringResource(R.string.settings_music_remove), onRemoveMusic, icon = Icons.Rounded.DeleteOutline)
            }
            SettingRow(stringResource(R.string.settings_volume), stringResource(R.string.settings_volume_body)) {
                listOf(.15f, .35f, .60f).forEach { volume ->
                    TvButton(stringResource(R.string.settings_volume_value, (volume * 100).roundToInt()), { onChange(settings.copy(musicVolume = volume)) }, selected = settings.musicVolume == volume)
                }
            }
            SettingRow(stringResource(R.string.settings_google_data), stringResource(R.string.settings_google_data_body)) {
                TvButton(stringResource(R.string.settings_google_disconnect), onDisconnectGoogle, icon = Icons.Rounded.DeleteOutline)
            }
            Text(
                stringResource(R.string.settings_privacy),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

private val SLEEP_TIMER_OPTIONS = listOf(30, 60, 120)
