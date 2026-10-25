package it.fotofesta.tv.ui

import android.graphics.BitmapFactory
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import it.fotofesta.tv.R
import it.fotofesta.tv.guests.ExtraScreenClient
import it.fotofesta.tv.network.PartyBeacon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Festa trovata sulla rete: nome della TV principale e, una volta risolto, indirizzo e porta. */
private data class FoundParty(val name: String, val info: NsdServiceInfo)

/**
 * Schermo aggiuntivo: questa TV (per esempio un Fire TV Stick in un'altra stanza) trova la festa di
 * un'altra TV della casa, chiede il PIN della regia e poi mostra le foto degli invitati, le dediche
 * e il QR per mandarne altre.
 */
@Composable
fun ExtraScreen(client: ExtraScreenClient, onBack: () -> Unit) {
    var party by remember { mutableStateOf<ExtraScreenClient.Party?>(null) }
    val current = party
    if (current == null) {
        ExtraScreenSetup(client, onConnected = { party = it }, onBack = onBack)
    } else {
        WallScreen(client, current, onLost = { party = null })
    }
}

@Composable
private fun ExtraScreenSetup(client: ExtraScreenClient, onConnected: (ExtraScreenClient.Party) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val found = remember { mutableStateListOf<FoundParty>() }
    var address by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val wrongPin = stringResource(R.string.extra_wrong_pin)
    val unavailable = stringResource(R.string.extra_unavailable)
    val notFound = stringResource(R.string.extra_not_found)

    // Ricerca delle feste sulla rete (DNS-SD): si aggiornano da sole mentre la schermata è aperta.
    DisposableEffect(Unit) {
        val nsd = context.getSystemService(NsdManager::class.java)
        // Le notifiche arrivano da un thread di sistema: l'elenco si aggiorna sul thread principale.
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post { if (found.none { it.name == info.serviceName }) found += FoundParty(info.serviceName, info) }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post { found.removeAll { it.name == info.serviceName } }
            }
        }
        val started = nsd != null && runCatching { nsd.discoverServices(PartyBeacon.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }.isSuccess
        onDispose { if (started) runCatching { nsd?.stopServiceDiscovery(listener) } }
    }

    fun connect() {
        val base = target ?: return
        busy = true
        status = null
        scope.launch {
            try {
                val connected = withContext(Dispatchers.IO) { client.login(base, pin) }
                onConnected(connected)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: ExtraScreenClient.WrongPinException) {
                status = wrongPin
            } catch (_: ExtraScreenClient.UnavailableException) {
                status = unavailable
            } catch (_: Exception) {
                status = notFound
            } finally {
                busy = false
            }
        }
    }

    fun choose(party: FoundParty) {
        busy = true
        status = null
        resolve(context, party.info) { host, port ->
            busy = false
            if (host == null) status = notFound else target = "http://$host:$port"
        }
    }

    Column(
        Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 27.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(stringResource(R.string.extra_kicker), stringResource(R.string.extra_title), stringResource(R.string.extra_subtitle), modifier = Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            TvButton(stringResource(R.string.action_back), onBack, icon = Icons.AutoMirrored.Rounded.ArrowBack)
        }
        val chosen = target
        if (chosen == null) {
            Kicker(stringResource(R.string.extra_found))
            if (found.isEmpty()) HintText(stringResource(R.string.extra_searching))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                found.take(4).forEachIndexed { index, party ->
                    TvButton(party.name, { choose(party) }, modifier = if (index == 0) Modifier.initialFocus(found.size) else Modifier, icon = Icons.Rounded.Tv, enabled = !busy)
                }
            }
            Row(Modifier.widthIn(max = 760.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvTextField(
                    value = address,
                    onValueChange = { address = it.take(100) },
                    label = stringResource(R.string.extra_address),
                    placeholder = "192.168.1.20",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (address.isNotBlank()) target = ExtraScreenClient.base(address) }),
                    modifier = Modifier.weight(1f).then(if (found.isEmpty()) Modifier.initialFocus() else Modifier),
                )
                TvButton(stringResource(R.string.extra_use_address), { if (address.isNotBlank()) target = ExtraScreenClient.base(address) }, icon = Icons.Rounded.Link)
            }
        } else {
            Text(chosen.removePrefix("http://"), style = MaterialTheme.typography.titleMedium, color = TextSecondary)
            Row(Modifier.widthIn(max = 640.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(9) },
                    label = stringResource(R.string.extra_pin),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { connect() }),
                    modifier = Modifier.weight(1f).initialFocus(chosen),
                )
                TvButton(stringResource(R.string.extra_connect), ::connect, icon = Icons.Rounded.Link, enabled = !busy && pin.length >= 4)
            }
            HintText(stringResource(R.string.extra_pin_hint))
            TvButton(stringResource(R.string.extra_other), { target = null; pin = "" }, ghost = true)
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = ErrorText) }
        PhoneKeyboardHint()
    }
}

/** Indirizzo della festa trovata; [done] arriva sul thread principale. */
@Suppress("DEPRECATION")
private fun resolve(context: android.content.Context, info: NsdServiceInfo, done: (String?, Int) -> Unit) {
    val nsd = context.getSystemService(NsdManager::class.java) ?: return done(null, 0)
    val main = android.os.Handler(android.os.Looper.getMainLooper())
    val listener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            main.post { done(null, 0) }
        }
        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            val host = serviceInfo.host?.hostAddress?.takeIf { '.' in it }
            main.post { done(host, serviceInfo.port) }
        }
    }
    if (runCatching { nsd.resolveService(info, listener) }.isFailure) done(null, 0)
}

/**
 * Parete delle foto su questa TV: le foto approvate degli invitati della festa principale, con chi le
 * ha mandate, le dediche che scorrono, l'annuncio della regia e il QR per mandare altre foto.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WallScreen(client: ExtraScreenClient, party: ExtraScreenClient.Party, onLost: () -> Unit) {
    var wall by remember { mutableStateOf<ExtraScreenClient.Wall?>(null) }
    var shown by remember { mutableStateOf<Pair<ImageBitmap, String?>?>(null) }
    var failures by remember { mutableIntStateOf(0) }

    LaunchedEffect(party) {
        while (true) {
            try {
                wall = withContext(Dispatchers.IO) { client.wall(party) }
                failures = 0
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: ExtraScreenClient.UnavailableException) {
                // Festa finita o ricominciata: la chiave non vale più.
                onLost()
                return@LaunchedEffect
            } catch (_: Exception) {
                failures++
            }
            delay(WALL_POLL_MS)
        }
    }
    // Giro delle foto: le nuove passano davanti, poi si riprende dall'ultima vista.
    LaunchedEffect(party) {
        val seen = HashSet<String>()
        val fresh = ArrayDeque<ExtraScreenClient.WallPhoto>()
        var first = true
        var index = 0
        while (true) {
            val photos = wall?.photos.orEmpty()
            photos.forEach { photo -> if (seen.add(photo.id) && !first) fresh.addLast(photo) }
            if (photos.isNotEmpty()) first = false
            val next = fresh.removeFirstOrNull() ?: photos.getOrNull(index++ % photos.size.coerceAtLeast(1))
            if (next != null) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val bytes = client.photo(party, next.id)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    }
                }.getOrNull()?.let { shown = it to next.sender }
                delay(WALL_SLIDE_MS)
            } else {
                delay(1_000)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Crossfade(shown, animationSpec = tween(1_000), label = "parete") { current ->
            if (current != null) {
                Image(current.first, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
        val state = wall
        if (shown == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.extra_wall_empty), style = MaterialTheme.typography.headlineMedium, color = TextPrimary, textAlign = TextAlign.Center)
                if (failures > 2) HintText(stringResource(R.string.extra_unavailable))
            }
        }
        Column(Modifier.align(Alignment.TopStart).padding(horizontal = 40.dp, vertical = 28.dp)) {
            BrandMark()
            state?.let { Text(listOf(it.title, pluralCount(it.count)).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.titleLarge, color = TextPrimary) }
        }
        shown?.second?.let { sender ->
            Text(
                stringResource(R.string.guest_photo_from, sender),
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 96.dp)
                    .background(Color.Black.copy(alpha = .55f), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        state?.announcement?.let { text ->
            Text(
                text,
                style = MaterialTheme.typography.displaySmall,
                color = OnAccent,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).widthIn(max = 900.dp)
                    .background(Accent, RoundedCornerShape(24.dp)).padding(horizontal = 32.dp, vertical = 24.dp),
            )
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 40.dp, vertical = 24.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            val ticker = state?.messages.orEmpty().joinToString("   ✦   ")
            Text(ticker, style = MaterialTheme.typography.titleMedium, color = TextSecondary, maxLines = 1, modifier = Modifier.weight(1f).basicMarquee())
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                QrCode(party.guestUrl, stringResource(R.string.guests_qr_send), Modifier.clip(RoundedCornerShape(10.dp)), size = 120.dp, padding = 8.dp)
                Text(stringResource(R.string.guests_qr_send), style = MaterialTheme.typography.labelLarge, color = TextPrimary)
            }
        }
    }
}

@Composable
private fun pluralCount(count: Int): String = quantityStringResource(R.plurals.guests_stat_received, count).let { label -> "$count $label" }

private const val WALL_POLL_MS = 4_000L
private const val WALL_SLIDE_MS = 7_000L
