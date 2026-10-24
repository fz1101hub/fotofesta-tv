import FotoFestaCore
import SwiftUI
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif

/// Slideshow a schermo intero. Telecomando: ◀ ▶ foto precedente/successiva, centro o Play/Pausa
/// per mettere in pausa, su/giù per le informazioni, Menu per uscire.
struct SlideshowView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var systemReduceMotion
    @State private var infoVisible = true
    @State private var interactions = 0
    // Tempo già trascorso sulla foto corrente prima di una pausa: alla ripresa il cambio foto
    // avviene dopo il tempo restante, in sincronia con il movimento lento.
    @State private var shownSeconds: TimeInterval = 0
    @State private var shownPhotoID: String?
    /// Mosaico: le foto mostrate di recente, dalla più recente, per le caselle accanto a quella grande.
    @State private var recentIDs: [String] = []

    var body: some View {
        // "Riduci movimento" vale sia dalle impostazioni dell'app sia da quelle di sistema.
        let settings = effectiveSettings
        Button {
            model.togglePlayback()
            showInfo()
        } label: {
            ZStack {
                Color.black
                // I video si guardano a schermo intero anche con il mosaico.
                if let photo = model.currentPhoto, settings.layout == .mosaic, model.photos.count > 1, !photo.isVideo {
                    MosaicSlideView(
                        photo: photo,
                        side: Mosaic.side(of: model.photos, currentIndex: model.currentIndex, recentIDs: recentIDs),
                        settings: settings,
                        playing: model.isPlaying
                    )
                } else if let photo = model.currentPhoto {
                    slide(photo, settings: settings)
                        .id(photo.id)
                        .transition(transition(for: settings))
                } else if model.guests.active {
                    WaitingForGuestsView(guests: model.guests)
                } else {
                    Text(L("slideshow.empty")).font(.title2)
                }
            }
            .ignoresSafeArea()
            .animation(settings.reducedMotion ? nil : .easeInOut(duration: 0.8), value: model.currentPhoto?.id)
        }
        .buttonStyle(FullScreenButtonStyle())
        // Sul Mac: frecce, spazio ed Esc dalla tastiera appena si apre lo slideshow.
        .keyboardFocusOnMac()
        .ignoresSafeArea()
        .overlay(alignment: .bottom) {
            if model.currentPhoto != nil { captionArea(settings: settings) }
        }
        .overlay(alignment: .topTrailing) {
            if settings.showClock {
                ClockText()
                    .padding(.horizontal, 24)
                    .padding(.vertical, 10)
                    .background(.regularMaterial, in: Capsule())
                    .padding(.horizontal, 90)
                    .padding(.top, 60)
            }
        }
        .overlay {
            if settings.countdown { CountdownOverlay(settings: settings) }
        }
        // QR piccolo per gli invitati, che possono mandare foto in ogni momento.
        .overlay(alignment: .topLeading) {
            if model.guests.active, model.guests.qrInSlideshow, model.currentPhoto != nil {
                SlideshowGuestQR(guests: model.guests)
                    .padding(.horizontal, 90)
                    .padding(.top, 60)
            }
        }
        // Reazioni e dediche dei telefoni durante la festa.
        .overlay {
            if model.guests.active {
                if model.guests.messagesEnabled { MessageBanner(messages: model.guests.messages) }
                if model.guests.reactionsEnabled { ReactionsOverlay(hub: model.guests.partyHub) }
                AnnouncementOverlay(announcement: model.guests.announcement)
                if let until = model.guests.quietUntil { QuietOverlay(until: until) }
            }
        }
        .overlay(alignment: .top) {
            if model.guests.active, let photo = model.currentPhoto, let tag = model.photoTag(photo) {
                GameTagBadge(text: tag).padding(.top, 40)
            }
        }
        .overlay(alignment: .bottomLeading) {
            if let url = model.treasureURL {
                TreasureOverlay(url: url, finders: model.guests.treasureFinders)
                    .padding(.leading, 90)
                    .padding(.bottom, 60)
            }
        }
        .overlay(alignment: .bottomTrailing) {
            if model.guests.active, !model.guests.developed {
                DisposableBadge(shots: model.guests.shotsTaken)
                    .padding(.trailing, 90)
                    .padding(.bottom, 150)
            }
        }
        .overlay(alignment: .top) {
            if model.guests.active, let director = model.guests.director {
                DirectorBadge(director: director).padding(.top, 60)
            }
        }
        .onChange(of: model.currentPhoto?.id) { previous, _ in
            guard let previous else { return }
            recentIDs = Array(([previous] + recentIDs.filter { $0 != previous }).prefix(6))
        }
        // Le reazioni dei telefoni contano per la foto sullo schermo (e i passaggi, per le foto che
        // invecchiano o per la festa senza tracce).
        .onChange(of: model.currentPhoto?.id, initial: true) { _, _ in
            model.photoShown(model.currentPhoto)
        }
        .onDisappear { model.photoShown(nil) }
        .onMoveCommand { direction in
            switch direction {
            case .left: model.previous()
            case .right: model.next()
            default: break
            }
            showInfo()
        }
        .onPlayPause {
            model.togglePlayback()
            showInfo()
        }
        .onExitCommand {
            model.pause()
            dismiss()
        }
        // Cambio foto automatico; si riavvia a ogni foto, pausa o cambio di durata. Solo "più di una
        // foto" e non il numero: le foto che arrivano dagli invitati non fanno ripartire il conteggio.
        // Un video si guarda fino alla fine: lo slideshow va avanti quando finisce (VideoSlideView).
        .task(id: TimerKey(
            tick: model.slideTick,
            playing: model.isPlaying,
            interval: settings.intervalSeconds,
            canAdvance: model.photos.count > 1,
            video: model.currentPhoto?.isVideo == true
        )) {
            let photoID = model.currentPhoto?.id
            if photoID != shownPhotoID {
                shownPhotoID = photoID
                shownSeconds = 0
            }
            guard model.isPlaying, model.photos.count > 1, model.currentPhoto?.isVideo != true else { return }
            let startedAt = Date()
            let remaining = max(Double(settings.intervalSeconds) - shownSeconds, 0)
            do {
                try await Task.sleep(nanoseconds: UInt64(remaining * 1_000_000_000))
            } catch {
                // Pausa o cambio di durata: si conserva il tempo già mostrato per questa foto.
                if shownPhotoID == photoID { shownSeconds += Date().timeIntervalSince(startedAt) }
                return
            }
            model.next()
        }
        // Le informazioni si nascondono dopo qualche secondo di inattività.
        .task(id: interactions) {
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            guard !Task.isCancelled else { return }
            withAnimation { infoVisible = false }
        }
        .task(id: model.upcomingPhoto?.id) {
            if let upcoming = model.upcomingPhoto, !upcoming.isVideo {
                _ = await ImageLoader.shared.image(for: upcoming, maxPixelSize: 3840)
            }
        }
    }

    private var effectiveSettings: SlideshowSettings {
        var settings = model.settings
        if systemReduceMotion { settings.reducedMotion = true }
        return settings
    }

    @ViewBuilder
    private func slide(_ photo: Photo, settings: SlideshowSettings) -> some View {
        if photo.isVideo {
            VideoSlideView(
                photo: photo,
                settings: settings,
                playing: model.isPlaying,
                // Unico elemento della raccolta: il video ricomincia da capo.
                loop: model.photos.count < 2
            ) {
                if model.photos.count > 1 { model.next() }
            }
        } else {
            SlideView(photo: photo, settings: settings, playing: model.isPlaying)
                .aging(model.photoAge(photo))
        }
    }

    private func showInfo() {
        withAnimation { infoVisible = true }
        interactions += 1
    }

    private func transition(for settings: SlideshowSettings) -> AnyTransition {
        if settings.reducedMotion { return .identity }
        switch settings.transition {
        case .fade: return .opacity
        case .slide: return .asymmetric(insertion: .move(edge: .trailing), removal: .move(edge: .leading))
        case .zoom: return .asymmetric(insertion: .opacity.combined(with: .scale(scale: 0.94)), removal: .opacity.combined(with: .scale(scale: 1.04)))
        }
    }

    /// Didascalia sempre visibile: titolo, ora di arrivo, posizione e barra di avanzamento. Con le
    /// informazioni visibili (o in pausa) sopra compaiono i comandi del telecomando.
    private func captionArea(settings: SlideshowSettings) -> some View {
        VStack(alignment: .leading, spacing: 20) {
            if infoVisible || !model.isPlaying {
                Text(model.isPlaying ? L("slideshow.hint_playing") : L("slideshow.hint_paused"))
                    .font(.callout)
                    .padding(.horizontal, 24)
                    .padding(.vertical, 12)
                    .background(.regularMaterial, in: Capsule())
                    .transition(.opacity)
            }
            HStack(alignment: .bottom, spacing: 40) {
                VStack(alignment: .leading, spacing: 4) {
                    if let title = captionTitle {
                        Text(title).font(.title2.bold()).lineLimit(1)
                    }
                    if let receivedAt = model.currentPhoto?.receivedAt {
                        Text(L("slideshow.received_at", receivedAt.formatted(date: .omitted, time: .shortened)))
                            .font(.callout)
                            .foregroundStyle(Theme.secondaryText)
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
                if let sleepAt = model.sleepAt {
                    Text(L("slideshow.sleep_at", sleepAt.formatted(date: .omitted, time: .shortened)))
                        .font(.callout.weight(.semibold))
                        .foregroundStyle(Theme.highlight)
                }
                Text("\(model.currentIndex + 1) / \(model.photos.count)")
                    .font(.callout.monospacedDigit())
                    .foregroundStyle(Theme.secondaryText)
            }
            // La barra misura il tempo di una foto; un video dura quanto dura.
            if model.currentPhoto?.isVideo != true {
                SlideProgress(
                    tick: model.slideTick,
                    playing: model.isPlaying && model.photos.count > 1,
                    interval: Double(settings.intervalSeconds)
                )
            }
        }
        .padding(.horizontal, 90)
        .padding(.top, 140)
        .padding(.bottom, 60)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            LinearGradient(
                stops: [
                    .init(color: .clear, location: 0),
                    .init(color: .black.opacity(0.35), location: 0.45),
                    .init(color: .black.opacity(0.82), location: 1),
                ],
                startPoint: .top,
                endPoint: .bottom
            )
        )
        .animation(.easeInOut(duration: 0.3), value: infoVisible || !model.isPlaying)
        .allowsHitTesting(false)
    }

    /// Titolo della foto: il mittente per gli invitati, il nome delle demo, il nome del file solo se
    /// è significativo (non "IMG_2024…").
    private var captionTitle: String? {
        guard let photo = model.currentPhoto else { return nil }
        switch photo.source {
        case .guest, .bundled: return photo.title
        case .google, .network: return PhotoCaption.title(photo.title)
        // La libreria Foto non dà un nome utile da mostrare.
        case .library: return nil
        }
    }

    private struct TimerKey: Hashable {
        let tick: Int
        let playing: Bool
        let interval: Int
        let canAdvance: Bool
        let video: Bool
    }
}

/// Foto dello slideshow. Con lo sfondo sfocato la foto resta intera e le bande ai lati mostrano la
/// stessa foto ingrandita e sfocata; il movimento lento riguarda solo la foto.
private struct SlideView: View {
    let photo: Photo
    let settings: SlideshowSettings
    let playing: Bool

    var body: some View {
        ZStack {
            if settings.fitMode == .blur { BlurredBackdrop(photo: photo) }
            MovingPhoto(photo: photo, settings: settings, playing: playing)
        }
    }
}

/// Conto alla rovescia: nell'ultima ora un'etichetta in alto al centro, negli ultimi secondi numeri
/// grandi a tutto schermo, poi il messaggio del momento.
private struct CountdownOverlay: View {
    let settings: SlideshowSettings

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            switch Countdown.phase(at: context.date, minutesOfDay: settings.countdownMinutes, timeZone: .current) {
            case .hidden:
                Color.clear
            case let .showing(seconds):
                VStack {
                    Label(L("countdown.in", countdownName(settings.countdownEvent), Countdown.format(seconds)),
                          systemImage: countdownSymbol(settings.countdownEvent))
                        .font(.title3.weight(.semibold).monospacedDigit())
                        .padding(.horizontal, 28)
                        .padding(.vertical, 12)
                        .background(.regularMaterial, in: Capsule())
                        .padding(.top, 60)
                    Spacer()
                }
            case let .final(seconds):
                ZStack {
                    Color.black.opacity(0.55)
                    Text("\(seconds)").font(.system(size: 360, weight: .bold).monospacedDigit())
                }
                .ignoresSafeArea()
            case .celebrate:
                ZStack {
                    Color.black.opacity(0.55)
                    VStack(spacing: 32) {
                        Image(systemName: countdownSymbol(settings.countdownEvent)).font(.system(size: 160))
                        Text(countdownMessage(settings.countdownEvent)).font(.system(size: 96, weight: .bold))
                    }
                }
                .ignoresSafeArea()
            }
        }
        .allowsHitTesting(false)
    }
}

/// Mosaico: la foto attuale in grande a sinistra (due terzi), accanto in colonna le ultime mostrate.
private struct MosaicSlideView: View {
    let photo: Photo
    let side: [Photo]
    let settings: SlideshowSettings
    let playing: Bool

    var body: some View {
        GeometryReader { geometry in
            let spacing: CGFloat = 16
            let mainWidth = (geometry.size.width - spacing) * 2 / 3
            HStack(spacing: spacing) {
                SlideView(photo: photo, settings: settings, playing: playing)
                    .id(photo.id)
                    .transition(.opacity)
                    .frame(width: mainWidth)
                    .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                VStack(spacing: spacing) {
                    ForEach(Array(side.enumerated()), id: \.offset) { _, item in
                        PhotoImage(photo: item, fitMode: .crop, maxPixelSize: 1200, placeholder: false)
                            .id(item.id)
                            .transition(.opacity)
                            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                    }
                }
            }
        }
        .padding(16)
    }
}

/// Foto con eventuale movimento lento (Ken Burns), fermo quando lo slideshow è in pausa.
private struct MovingPhoto: View {
    let photo: Photo
    let settings: SlideshowSettings
    let playing: Bool

    @State private var accumulated: TimeInterval = 0
    @State private var resumedAt = Date()

    var body: some View {
        if settings.isKenBurnsActive {
            let motion = KenBurnsMotion.forPhoto(id: photo.id)
            let duration = Double(settings.intervalSeconds) + 1
            GeometryReader { geometry in
                TimelineView(.animation(minimumInterval: 1.0 / 30, paused: !playing)) { context in
                    let elapsed = accumulated + (playing ? context.date.timeIntervalSince(resumedAt) : 0)
                    let progress = min(elapsed / duration, 1)
                    PhotoImage(photo: photo, fitMode: settings.fitMode)
                        .scaleEffect(motion.scale(at: progress))
                        .offset(x: motion.offsetX(at: progress) * geometry.size.width, y: motion.offsetY(at: progress) * geometry.size.height)
                }
            }
            .clipped()
            .onChange(of: playing) { _, nowPlaying in
                if nowPlaying {
                    resumedAt = Date()
                } else {
                    accumulated += Date().timeIntervalSince(resumedAt)
                }
            }
        } else {
            PhotoImage(photo: photo, fitMode: settings.fitMode)
        }
    }
}

/// Barra sottile che si riempie fino al cambio di foto; in pausa si ferma e alla ripresa continua,
/// come il timer dello slideshow, che conserva il tempo già trascorso sulla foto.
private struct SlideProgress: View {
    let tick: Int
    let playing: Bool
    let interval: Double

    @State private var accumulated: TimeInterval = 0
    @State private var resumedAt = Date()

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 20, paused: !playing)) { context in
            let elapsed = accumulated + (playing ? context.date.timeIntervalSince(resumedAt) : 0)
            let progress = min(max(elapsed / max(interval, 1), 0), 1)
            GeometryReader { geometry in
                ZStack(alignment: .leading) {
                    Capsule().fill(.white.opacity(0.25))
                    Capsule().fill(.white).frame(width: geometry.size.width * progress)
                }
            }
            .frame(height: 8)
        }
        .onChange(of: tick) {
            accumulated = 0
            resumedAt = Date()
        }
        .onChange(of: playing) { _, nowPlaying in
            if nowPlaying {
                resumedAt = Date()
            } else {
                accumulated += Date().timeIntervalSince(resumedAt)
            }
        }
        .accessibilityHidden(true)
    }
}

/// Stile senza effetto di focus: l'intero schermo è il "pulsante" che riceve il tasto centrale.
private struct FullScreenButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
    }
}
