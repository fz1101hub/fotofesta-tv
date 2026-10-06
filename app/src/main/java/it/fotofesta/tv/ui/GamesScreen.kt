package it.fotofesta.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Numero minimo di foto perché i giochi abbiano senso (almeno due coppie / due risposte). */
const val MIN_GAME_PHOTOS = 2

@Composable
fun GamesScreen(photoCount: Int, onBack: () -> Unit, onMemory: () -> Unit, onMystery: () -> Unit) {
    val playable = photoCount >= MIN_GAME_PHOTOS
    Column(Modifier.fillMaxSize().padding(46.dp), verticalArrangement = Arrangement.spacedBy(30.dp)) {
        Row(Modifier.fillMaxWidth()) {
            SectionTitle("Gioca insieme", "Minigiochi con le tue foto", "Usa il telecomando. Nessun punteggio o dato lascia la TV.")
            Spacer(Modifier.weight(1f))
            TvButton("Indietro", onBack, icon = Icons.AutoMirrored.Rounded.ArrowBack)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            SourceCard(
                "Memory dei ricordi",
                "Trova le coppie di foto nel minor numero di mosse. Perfetto da giocare in famiglia.",
                Icons.Rounded.GridView,
                Coral,
                onMemory,
                Modifier.weight(1f),
                enabled = playable,
            )
            SourceCard(
                "Foto misteriosa",
                "Indovina il ricordo nascosto tra più possibilità.",
                Icons.Rounded.Visibility,
                Cyan,
                onMystery,
                Modifier.weight(1f),
                enabled = playable,
            )
        }
        Text(
            if (playable) "Raccolta attiva: $photoCount foto"
            else "Servono almeno $MIN_GAME_PHOTOS foto: scegli una sorgente dalla schermata principale.",
            color = TextSecondary,
        )
    }
}

@Composable
fun NotEnoughPhotos(title: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(46.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        SectionTitle(title, "Servono più foto", "Per giocare occorrono almeno $MIN_GAME_PHOTOS foto nella raccolta attiva.")
        TvButton("Indietro", onBack, icon = Icons.AutoMirrored.Rounded.ArrowBack)
    }
}
