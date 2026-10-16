package it.fotofesta.tv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.fotofesta.tv.R
import it.fotofesta.tv.model.BrowserEntry
import it.fotofesta.tv.model.BrowserKind
import it.fotofesta.tv.model.BrowserState

/**
 * Browser di cartelle per scegliere una cartella di foto o un brano con il telecomando. Sostituisce
 * il selettore di documenti di Android, che su molte TV non c'è.
 */
@Composable
fun FileBrowserScreen(
    state: BrowserState,
    onOpen: (String) -> Unit,
    onUp: () -> Unit,
    onUseFolder: () -> Unit,
    onChooseTrack: (BrowserEntry) -> Unit,
    onSystemPicker: () -> Unit,
    onBack: () -> Unit,
) {
    val photos = state.kind == BrowserKind.PHOTOS
    val focusKey = state.directory ?: "roots"
    // Cartella senza elementi da scegliere: il fuoco va ai pulsanti sopra l'elenco.
    val listEmpty = state.directory != null && !state.loading && state.folders.isEmpty() && (photos || state.files.isEmpty())
    // Nessun disco e nessun selettore di sistema: resta solo "Indietro", che deve avere il fuoco.
    val nothingToOpen = state.directory == null && !state.loading && state.roots.isEmpty() && !state.systemPickerAvailable
    Column(
        Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 30.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(
                stringResource(if (photos) R.string.browser_kicker_photos else R.string.browser_kicker_music),
                stringResource(if (photos) R.string.browser_title_photos else R.string.browser_title_music),
                state.directory ?: stringResource(R.string.browser_roots_hint),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(16.dp))
            TvButton(
                stringResource(R.string.action_back),
                onBack,
                modifier = if (nothingToOpen) Modifier.initialFocus("no-drive") else Modifier,
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
            )
        }
        if (state.directory != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TvButton(
                    stringResource(R.string.browser_up),
                    onUp,
                    modifier = if (listEmpty && !photos) Modifier.initialFocus(focusKey) else Modifier,
                    icon = Icons.Rounded.ArrowUpward,
                )
                if (photos) {
                    TvButton(
                        stringResource(R.string.browser_use_folder),
                        onUseFolder,
                        modifier = if (listEmpty) Modifier.initialFocus(focusKey) else Modifier,
                        icon = Icons.Rounded.CheckCircle,
                        selected = true,
                        enabled = !state.loading,
                    )
                    Text(
                        quantityStringResource(R.plurals.browser_photos_here, state.photosHere),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                }
            }
        }
        if (state.loading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        // Il primo elemento prende il fuoco a ogni cambio di cartella: la prima pressione del
        // telecomando non va persa.
        // Ogni cartella parte dall'inizio dell'elenco, dove si trova l'elemento che prende il fuoco.
        val listState = remember(focusKey) { LazyListState() }
        LazyColumn(Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.directory == null) {
                itemsIndexed(state.roots, key = { _, root -> root.path }) { index, root ->
                    BrowserRow(
                        label = root.label,
                        detail = root.path,
                        icon = if (root.removable) Icons.Rounded.Usb else Icons.Rounded.SdStorage,
                        onClick = { onOpen(root.path) },
                        modifier = if (index == 0) Modifier.initialFocus(focusKey) else Modifier,
                    )
                }
                if (state.systemPickerAvailable) {
                    item(key = "system-picker") {
                        BrowserRow(
                            label = stringResource(R.string.browser_system_picker),
                            icon = Icons.Rounded.FolderOpen,
                            onClick = onSystemPicker,
                            modifier = if (state.roots.isEmpty()) Modifier.initialFocus(focusKey) else Modifier,
                        )
                    }
                }
                if (state.roots.isEmpty() && !state.systemPickerAvailable) {
                    item(key = "no-drive") { EmptyText(stringResource(R.string.browser_no_drive)) }
                }
            } else {
                itemsIndexed(state.folders, key = { _, folder -> "folder:" + folder.path }) { index, folder ->
                    BrowserRow(
                        label = folder.name,
                        icon = Icons.Rounded.Folder,
                        onClick = { onOpen(folder.path) },
                        modifier = if (index == 0) Modifier.initialFocus(focusKey) else Modifier,
                    )
                }
                if (!photos) {
                    itemsIndexed(state.files, key = { _, file -> "file:" + file.path }) { index, file ->
                        BrowserRow(
                            label = file.name,
                            icon = Icons.Rounded.MusicNote,
                            onClick = { onChooseTrack(file) },
                            modifier = if (index == 0 && state.folders.isEmpty()) Modifier.initialFocus(focusKey) else Modifier,
                        )
                    }
                }
                if (state.folders.isEmpty() && (photos || state.files.isEmpty())) {
                    item(key = "empty") {
                        EmptyText(stringResource(if (photos) R.string.browser_empty_photos else R.string.browser_empty_music))
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = TextSecondary, modifier = Modifier.padding(8.dp))
}

/** Riga a tutta larghezza con icona, nome ed eventuale dettaglio, ben visibile quando ha il fuoco. */
@Composable
private fun BrowserRow(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.015f else 1f, label = "rowFocus")
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(if (focused) 3.dp else 1.dp, if (focused) Amber else Color(0xFF394563)),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (focused) Color(0xFF2B3859) else SurfaceBlue,
            contentColor = TextPrimary,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = Amber, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail != null) {
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
