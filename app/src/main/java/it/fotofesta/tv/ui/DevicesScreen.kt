package it.fotofesta.tv.ui

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import it.fotofesta.tv.R
import it.fotofesta.tv.hardware.EscPos
import it.fotofesta.tv.hardware.Ipp
import it.fotofesta.tv.hardware.PartyLights
import it.fotofesta.tv.model.DevicesUiState
import it.fotofesta.tv.model.SlideshowSettings

/** Azioni della schermata dei dispositivi, passate dal ViewModel. */
class DevicesActions(
    val onSettings: (SlideshowSettings) -> Unit,
    val onPairLight: (PartyLights.Device) -> Unit,
    val onCancelPairing: () -> Unit,
    val onRemoveLight: (PartyLights.Device) -> Unit,
    val onTestLight: (PartyLights.Device) -> Unit,
    val onChoosePrinter: (Ipp.Printer) -> Unit,
    val onRemovePrinter: () -> Unit,
    val onTestPrinter: () -> Unit,
    val onReceiptPrinter: (String?, Boolean) -> Unit,
    val onTestReceipt: () -> Unit,
    val onBack: () -> Unit,
)

/**
 * Dispositivi della festa nella rete di casa e collegati alla TV: luci colorate (Hue, WLED,
 * Nanoleaf), stampante delle foto, stampante degli scontrini, fotocamere con il Wi‑Fi (FTP) e con
 * il cavo USB. Le luci e le stampanti si trovano da sole mentre la schermata è aperta.
 */
@Composable
fun DevicesScreen(devices: DevicesUiState, settings: SlideshowSettings, partyActive: Boolean, actions: DevicesActions) {
    val found = rememberFoundServices(SERVICE_TYPES)
    Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 27.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(stringResource(R.string.settings_title), stringResource(R.string.devices_title), stringResource(R.string.devices_subtitle), modifier = Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            TvButton(stringResource(R.string.action_back), actions.onBack, modifier = Modifier.initialFocus(), icon = Icons.AutoMirrored.Rounded.ArrowBack)
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LightsSection(devices, settings, found, actions)
            PrinterSection(devices, settings, found, actions)
            ReceiptSection(devices, settings, found, actions)
            CamerasSection(devices, settings, partyActive, actions)
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Kicker(text, Modifier.padding(top = 14.dp, bottom = 2.dp))
}

@Composable
private fun LightsSection(devices: DevicesUiState, settings: SlideshowSettings, found: List<FoundService>, actions: DevicesActions) {
    SectionHeader(stringResource(R.string.devices_lights))
    SettingsItem(
        stringResource(R.string.devices_lights_follow),
        { actions.onSettings(settings.copy(partyLights = !settings.partyLights)) },
        subtitle = stringResource(R.string.devices_lights_follow_body),
        checked = settings.partyLights,
    )
    devices.lights.forEach { light ->
        SettingsItem(
            light.name,
            { actions.onTestLight(light) },
            subtitle = stringResource(R.string.devices_light_connected, kindName(light.kind)),
            value = stringResource(R.string.devices_test),
        )
        SettingsItem(stringResource(R.string.devices_remove_named, light.name), { actions.onRemoveLight(light) })
    }
    devices.pairing?.let { name ->
        SettingsItem(
            stringResource(R.string.devices_pairing, name),
            actions.onCancelPairing,
            subtitle = stringResource(R.string.devices_pairing_body),
            value = stringResource(R.string.pin_cancel),
        )
    }
    val candidates = found.mapNotNull { it.light() }.filter { candidate -> devices.lights.none { it.host == candidate.host && it.kind == candidate.kind } }
    candidates.forEach { light ->
        SettingsItem(
            light.name,
            { actions.onPairLight(light) },
            subtitle = stringResource(
                when (light.kind) {
                    PartyLights.Kind.HUE -> R.string.devices_hue_hint
                    PartyLights.Kind.NANOLEAF -> R.string.devices_nanoleaf_hint
                    PartyLights.Kind.WLED -> R.string.devices_wled_hint
                }
            ),
            value = stringResource(R.string.devices_connect),
        )
    }
    if (devices.lights.isEmpty() && candidates.isEmpty()) Hint(stringResource(R.string.devices_lights_searching))
}

@Composable
private fun PrinterSection(devices: DevicesUiState, settings: SlideshowSettings, found: List<FoundService>, actions: DevicesActions) {
    SectionHeader(stringResource(R.string.devices_printer))
    val printer = devices.printer
    if (printer != null) {
        SettingsItem(printer.name, actions.onTestPrinter, subtitle = stringResource(R.string.devices_printer_test_body), value = stringResource(R.string.devices_test))
        SettingsItem(
            stringResource(R.string.devices_auto_print),
            { actions.onSettings(settings.copy(autoPrint = !settings.autoPrint)) },
            subtitle = stringResource(R.string.devices_auto_print_body),
            checked = settings.autoPrint,
        )
        SettingsItem(stringResource(R.string.devices_remove_named, printer.name), actions.onRemovePrinter)
    }
    val candidates = found.filter { it.type == Ipp.SERVICE_TYPE }.map { it.printer() }.filter { it.host != printer?.host }
    candidates.forEach { candidate ->
        SettingsItem(candidate.name, { actions.onChoosePrinter(candidate) }, subtitle = candidate.host, value = stringResource(R.string.devices_use))
    }
    if (printer == null && candidates.isEmpty()) Hint(stringResource(R.string.devices_printer_searching))
}

@Composable
private fun ReceiptSection(devices: DevicesUiState, settings: SlideshowSettings, found: List<FoundService>, actions: DevicesActions) {
    SectionHeader(stringResource(R.string.devices_receipt))
    val host = devices.receiptHost
    if (host != null) {
        SettingsItem(host, actions.onTestReceipt, subtitle = stringResource(R.string.devices_receipt_test_body), value = stringResource(R.string.devices_test))
        SettingsItem(
            stringResource(R.string.devices_receipt_paper),
            { actions.onReceiptPrinter(host, !devices.receiptWide) },
            value = if (devices.receiptWide) "80 mm" else "58 mm",
        )
        SettingsItem(
            stringResource(R.string.devices_auto_receipt),
            { actions.onSettings(settings.copy(autoReceipt = !settings.autoReceipt)) },
            subtitle = stringResource(R.string.devices_auto_receipt_body),
            checked = settings.autoReceipt,
        )
        SettingsItem(stringResource(R.string.devices_remove_named, host), { actions.onReceiptPrinter(null, false) })
    }
    found.filter { it.type == RAW_PRINTER_TYPE && it.host != host }.forEach { candidate ->
        SettingsItem(candidate.name, { actions.onReceiptPrinter(candidate.host, devices.receiptWide) }, subtitle = candidate.host, value = stringResource(R.string.devices_use))
    }
    var address by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TvTextField(
            value = address,
            onValueChange = { address = it.filter { char -> char.isLetterOrDigit() || char == '.' || char == ':' || char == '-' }.take(64) },
            label = stringResource(R.string.devices_receipt_address),
            placeholder = "192.168.1.50",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
        )
        TvButton(stringResource(R.string.devices_use), {
            if (address.isNotBlank()) {
                actions.onReceiptPrinter(address, devices.receiptWide)
                address = ""
            }
        })
    }
    Hint(stringResource(R.string.devices_receipt_hint, EscPos.PORT))
}

@Composable
private fun CamerasSection(devices: DevicesUiState, settings: SlideshowSettings, partyActive: Boolean, actions: DevicesActions) {
    SectionHeader(stringResource(R.string.devices_cameras))
    SettingsItem(
        stringResource(R.string.devices_ftp),
        { actions.onSettings(settings.copy(ftpServer = !settings.ftpServer)) },
        subtitle = stringResource(R.string.devices_ftp_body),
        checked = settings.ftpServer,
    )
    if (settings.ftpServer) {
        val address = devices.ftpAddress
        Hint(
            when {
                address != null -> stringResource(R.string.devices_ftp_details, address, devices.ftpPort, FTP_USER, devices.ftpPassword)
                !partyActive -> stringResource(R.string.devices_ftp_waiting)
                else -> stringResource(R.string.message_ftp_failed)
            }
        )
    }
    SettingsItem(
        stringResource(R.string.devices_usb_camera),
        { actions.onSettings(settings.copy(usbCamera = !settings.usbCamera)) },
        subtitle = devices.usbCamera?.let { stringResource(R.string.devices_usb_camera_connected, it) } ?: stringResource(R.string.devices_usb_camera_body),
        checked = settings.usbCamera,
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().background(SurfaceDark, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = TextSecondary,
    )
}

@Composable
private fun kindName(kind: PartyLights.Kind): String = when (kind) {
    PartyLights.Kind.HUE -> "Philips Hue"
    PartyLights.Kind.WLED -> "WLED"
    PartyLights.Kind.NANOLEAF -> "Nanoleaf"
}

/** Servizio trovato in rete con DNS-SD, già risolto in un indirizzo IPv4. */
private data class FoundService(val type: String, val name: String, val host: String, val port: Int, val attributes: Map<String, String>) {
    fun light(): PartyLights.Device? {
        val kind = PartyLights.Kind.entries.firstOrNull { PartyLights.serviceType(it) == type } ?: return null
        return PartyLights.Device(kind, host, port.takeIf { it > 0 } ?: PartyLights.defaultPort(kind), name = name)
    }

    fun printer(): Ipp.Printer = Ipp.Printer(host, port.takeIf { it > 0 } ?: Ipp.PORT, attributes["rp"]?.ifBlank { null } ?: "ipp/print", attributes["ty"]?.ifBlank { null } ?: name)
}

/**
 * Cerca i servizi di [types] mentre la schermata è aperta. Le risoluzioni si fanno una alla volta:
 * le versioni più vecchie di Android ne rifiutano due insieme.
 */
@Suppress("DEPRECATION")
@Composable
private fun rememberFoundServices(types: List<String>): List<FoundService> {
    val context = LocalContext.current
    val found = remember { mutableStateListOf<FoundService>() }
    DisposableEffect(types) {
        val nsd = context.getSystemService(NsdManager::class.java)
        val main = Handler(Looper.getMainLooper())
        val pending = ArrayDeque<Pair<String, NsdServiceInfo>>()
        var resolving = false
        var disposed = false
        fun next() {
            if (disposed || resolving || nsd == null) return
            val (type, info) = pending.removeFirstOrNull() ?: return
            resolving = true
            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    main.post {
                        resolving = false
                        next()
                    }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host?.hostAddress?.takeIf { '.' in it }
                    val attributes = runCatching {
                        serviceInfo.attributes.mapValues { (_, value) -> value?.toString(Charsets.UTF_8).orEmpty() }
                    }.getOrDefault(emptyMap())
                    main.post {
                        resolving = false
                        if (host != null && !disposed) {
                            found.removeAll { it.type == type && it.name == serviceInfo.serviceName }
                            found += FoundService(type, serviceInfo.serviceName, host, serviceInfo.port, attributes)
                        }
                        next()
                    }
                }
            }
            if (runCatching { nsd.resolveService(info, listener) }.isFailure) {
                resolving = false
                main.post { next() }
            }
        }
        val listeners = types.map { type ->
            type to object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onServiceFound(info: NsdServiceInfo) {
                    main.post {
                        pending.addLast(type to info)
                        next()
                    }
                }

                override fun onServiceLost(info: NsdServiceInfo) {
                    main.post { found.removeAll { it.type == type && it.name == info.serviceName } }
                }
            }
        }
        val started = listeners.filter { (type, listener) ->
            nsd != null && runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }.isSuccess
        }
        onDispose {
            disposed = true
            started.forEach { (_, listener) -> runCatching { nsd?.stopServiceDiscovery(listener) } }
        }
    }
    return found
}

/** Stampanti "raw" sulla porta 9100: molte stampanti termiche di rete si annunciano così. */
private const val RAW_PRINTER_TYPE = "_pdl-datastream._tcp"
private const val FTP_USER = "fotofesta"
private val SERVICE_TYPES = PartyLights.Kind.entries.map(PartyLights::serviceType) + listOf(Ipp.SERVICE_TYPE, RAW_PRINTER_TYPE)
