package it.fotofesta.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Scale
import it.fotofesta.tv.R
import it.fotofesta.tv.ScreenshotScenario
import it.fotofesta.tv.model.PhotoItem
import it.fotofesta.tv.model.PixelRect
import it.fotofesta.tv.model.PuzzleBoard
import it.fotofesta.tv.model.PuzzleLevel
import it.fotofesta.tv.model.pieceRect
import it.fotofesta.tv.model.puzzleCrop
import kotlin.math.roundToInt

/** Dimensione a cui si decodifica la foto del puzzle: basta per un tabellone a tutta larghezza. */
private const val PUZZLE_IMAGE_WIDTH = 1600
private const val PUZZLE_IMAGE_HEIGHT = 900

/**
 * Puzzle con le foto della raccolta: prima si sceglie il livello, poi si scambiano i pezzi finché la
 * foto è di nuovo intera. Indietro dal tabellone torna alla scelta del livello.
 */
@Composable
fun PuzzleGameScreen(photos: List<PhotoItem>, onBack: () -> Unit) {
    if (photos.size < MIN_GAME_PHOTOS) {
        NotEnoughPhotos(stringResource(R.string.games_puzzle_title), onBack)
        return
    }
    // Nella schermata "puzzle_game" della CI (solo debug) il gioco parte con il livello medio.
    var level by rememberSaveable { mutableStateOf(PuzzleLevel.MEDIUM.takeIf { ScreenshotScenario.name == "puzzle_game" }) }
    val chosen = level
    if (chosen == null) {
        PuzzleLevelPicker(onPick = { level = it }, onBack = onBack)
    } else {
        BackHandler { level = null }
        PuzzleBoardScreen(photos, chosen, onChangeLevel = { level = null })
    }
}

@Composable
private fun PuzzleLevelPicker(onPick: (PuzzleLevel) -> Unit, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(30.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            SectionTitle(
                stringResource(R.string.games_puzzle_title),
                stringResource(R.string.puzzle_choose_level),
                stringResource(R.string.puzzle_choose_level_body),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(16.dp))
            TvButton(stringResource(R.string.action_back), onBack, icon = Icons.AutoMirrored.Rounded.ArrowBack)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            PuzzleLevel.entries.forEach { level ->
                FocusSurface(
                    onClick = { onPick(level) },
                    modifier = Modifier.weight(1f).height(210.dp)
                        .then(if (level == PuzzleLevel.EASY) Modifier.initialFocus() else Modifier),
                    shape = RoundedCornerShape(16.dp),
                    color = SurfaceDark,
                    border = BorderStroke(1.dp, TextPrimary.copy(alpha = .08f)),
                    focusScale = 1.04f,
                ) { focused ->
                    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        GridPreview(level, if (focused) OnFocus else TextPrimary, Modifier.height(72.dp).aspectRatio(16f / 9f))
                        Text(stringResource(level.labelRes), style = MaterialTheme.typography.titleLarge)
                        Text(
                            quantityStringResource(R.plurals.puzzle_pieces, level.pieces),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (focused) OnFocusSecondary else TextSecondary,
                        )
                    }
                }
            }
        }
    }
}

private val PuzzleLevel.labelRes: Int
    get() = when (this) {
        PuzzleLevel.EASY -> R.string.puzzle_level_easy
        PuzzleLevel.MEDIUM -> R.string.puzzle_level_medium
        PuzzleLevel.HARD -> R.string.puzzle_level_hard
    }

/** Disegno della griglia del livello: si capisce a colpo d'occhio quanti pezzi ha. */
@Composable
private fun GridPreview(level: PuzzleLevel, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 2.dp.toPx()
        drawRoundRect(color, cornerRadius = CornerRadius(6.dp.toPx()), style = Stroke(stroke))
        for (column in 1 until level.columns) {
            val x = size.width * column / level.columns
            drawLine(color, Offset(x, 0f), Offset(x, size.height), stroke)
        }
        for (row in 1 until level.rows) {
            val y = size.height * row / level.rows
            drawLine(color, Offset(0f, y), Offset(size.width, y), stroke)
        }
    }
}

/** Stato del caricamento della foto del puzzle. */
private sealed interface PuzzleImage {
    data object Loading : PuzzleImage
    data object Failed : PuzzleImage
    data class Ready(val bitmap: ImageBitmap, val crop: PixelRect) : PuzzleImage
}

@Composable
private fun rememberPuzzleImage(photo: PhotoItem): PuzzleImage {
    val context = LocalContext.current
    val image by produceState<PuzzleImage>(PuzzleImage.Loading, photo.id) {
        value = PuzzleImage.Loading
        val request = ImageRequest.Builder(context)
            .data(photo.ref.coilModel())
            .size(PUZZLE_IMAGE_WIDTH, PUZZLE_IMAGE_HEIGHT)
            .scale(Scale.FILL)
            // I pezzi si disegnano ritagliando la bitmap: serve in memoria normale.
            .allowHardware(false)
            .memoryCacheKey("puzzle:${photo.id}")
            .build()
        val bitmap = (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap()
        value = if (bitmap == null) {
            PuzzleImage.Failed
        } else {
            PuzzleImage.Ready(bitmap.asImageBitmap(), puzzleCrop(bitmap.width, bitmap.height))
        }
    }
    return image
}

@Composable
private fun PuzzleBoardScreen(photos: List<PhotoItem>, level: PuzzleLevel, onChangeLevel: () -> Unit) {
    var round by remember { mutableIntStateOf(0) }
    // Non è uno State osservato: serve solo a non riproporre subito la stessa foto.
    val lastPhotoId = remember { arrayOfNulls<String>(1) }
    val photo = remember(round, level) {
        photos.filter { it.id != lastPhotoId[0] }.ifEmpty { photos }.random().also { lastPhotoId[0] = it.id }
    }
    var board by remember(round, level) { mutableStateOf(PuzzleBoard.shuffled(level)) }
    var selected by remember(round, level) { mutableStateOf<Int?>(null) }
    var showPhoto by remember(round, level) { mutableStateOf(false) }
    val image = rememberPuzzleImage(photo)
    val newGameFocus = remember { FocusRequester() }
    // A foto ricomposta i pezzi non servono più: il fuoco passa a "Nuova partita".
    LaunchedEffect(board.solved) { if (board.solved) runCatching { newGameFocus.requestFocus() } }

    fun choose(cell: Int) {
        if (board.solved) return
        val first = selected
        if (first == null) {
            selected = cell
        } else {
            // La stessa casella annulla la scelta; un'altra scambia i due pezzi.
            if (first != cell) board = board.swap(first, cell)
            selected = null
        }
    }

    Column(
        Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 27.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(
                stringResource(R.string.puzzle_kicker, board.moves),
                when {
                    board.solved -> stringResource(R.string.puzzle_complete, board.moves)
                    selected != null -> stringResource(R.string.puzzle_pick_second)
                    else -> stringResource(R.string.puzzle_pick_first)
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(16.dp))
            TvButton(
                stringResource(if (showPhoto) R.string.puzzle_hide_photo else R.string.puzzle_show_photo),
                { showPhoto = !showPhoto },
                icon = Icons.Rounded.Image,
                enabled = image is PuzzleImage.Ready && !board.solved,
            )
            Spacer(Modifier.width(12.dp))
            TvButton(
                stringResource(R.string.memory_new_game),
                { round++ },
                modifier = Modifier.focusRequester(newGameFocus),
                icon = Icons.Rounded.Refresh,
            )
            Spacer(Modifier.width(12.dp))
            TvButton(stringResource(R.string.puzzle_change_level), onChangeLevel, icon = Icons.Rounded.Tune)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val boardWidth = minOf(maxWidth, maxHeight * 16f / 9f)
            Box(Modifier.size(boardWidth, boardWidth * 9f / 16f), contentAlignment = Alignment.Center) {
                when (val loaded = image) {
                    PuzzleImage.Loading -> CircularProgressIndicator(color = TextSecondary)
                    PuzzleImage.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.BrokenImage, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(56.dp))
                        Text(stringResource(R.string.photo_unavailable), style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
                    }
                    is PuzzleImage.Ready -> {
                        // A foto ricomposta i pezzi si uniscono senza spazi.
                        val gap = if (board.solved) 0.dp else 4.dp
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                            repeat(level.rows) { row ->
                                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                                    repeat(level.columns) { column ->
                                        val cell = row * level.columns + column
                                        PuzzleTile(
                                            image = loaded,
                                            level = level,
                                            piece = board.positions[cell],
                                            cell = cell,
                                            selected = selected == cell,
                                            correct = board.isCorrect(cell),
                                            onClick = { choose(cell) },
                                            modifier = Modifier.weight(1f).fillMaxHeight()
                                                .then(if (cell == 0) Modifier.initialFocus(round to level) else Modifier),
                                        )
                                    }
                                }
                            }
                        }
                        if (showPhoto) {
                            // La foto intera sopra il tabellone, finché non si nasconde.
                            PieceImage(loaded, loaded.crop, Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PuzzleTile(
    image: PuzzleImage.Ready,
    level: PuzzleLevel,
    piece: Int,
    cell: Int,
    selected: Boolean,
    correct: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = when {
        selected -> stringResource(R.string.puzzle_cell_selected, cell + 1)
        correct -> stringResource(R.string.puzzle_cell_correct, cell + 1)
        else -> stringResource(R.string.puzzle_cell, cell + 1)
    }
    FocusSurface(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = description },
        shape = RoundedCornerShape(6.dp),
        color = SurfaceDark,
        focusScale = 1.08f,
    ) { focused ->
        // Con il fuoco il pezzo ha una cornice bianca; il pezzo scelto ha il bordo nel colore dell'app.
        Box(Modifier.fillMaxSize().padding(if (focused) 4.dp else 0.dp)) {
            PieceImage(image, level.pieceRect(image.crop, piece), Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)))
            if (selected) Box(Modifier.fillMaxSize().border(4.dp, Accent, RoundedCornerShape(4.dp)))
        }
    }
}

/** Disegna la parte [source] della foto a tutta superficie. */
@Composable
private fun PieceImage(image: PuzzleImage.Ready, source: PixelRect, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawImage(
            image.bitmap,
            srcOffset = IntOffset(source.left, source.top),
            srcSize = IntSize(source.width, source.height),
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.Medium,
        )
    }
}
