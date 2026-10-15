import FotoFestaCore
import SwiftUI
import UIKit

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

    var body: some View {
        // "Riduci movimento" vale sia dalle impostazioni dell'app sia da quelle di sistema.
        let settings = effectiveSettings
        Button {
            model.togglePlayback()
            showInfo()
        } label: {
            ZStack {
                Color.black
                if let photo = model.currentPhoto {
                    SlideView(photo: photo, settings: settings, playing: model.isPlaying)
                        .id(photo.id)
                        .transition(transition(for: settings))
                } else {
                    Text(L("slideshow.empty")).font(.title2)
                }
            }
            .ignoresSafeArea()
            .animation(settings.reducedMotion ? nil : .easeInOut(duration: 0.8), value: model.currentPhoto?.id)
        }
        .buttonStyle(FullScreenButtonStyle())
        .ignoresSafeArea()
        .overlay(alignment: .bottom) {
            if infoVisible || !model.isPlaying { infoBar(settings: settings) }
        }
        .overlay(alignment: .topTrailing) {
            if settings.showClock { ClockView().padding(48) }
        }
        .onMoveCommand { direction in
            switch direction {
            case .left: model.previous()
            case .right: model.next()
            default: break
            }
            showInfo()
        }
        .onPlayPauseCommand {
            model.togglePlayback()
            showInfo()
        }
        .onExitCommand {
            model.pause()
            dismiss()
        }
        // Cambio foto automatico; si riavvia a ogni foto, pausa o cambio di durata.
        .task(id: TimerKey(index: model.currentIndex, playing: model.isPlaying, interval: settings.intervalSeconds, count: model.photos.count)) {
            let photoID = model.currentPhoto?.id
            if photoID != shownPhotoID {
                shownPhotoID = photoID
                shownSeconds = 0
            }
            guard model.isPlaying, model.photos.count > 1 else { return }
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
            if let upcoming = model.upcomingPhoto {
                _ = await ImageLoader.shared.image(for: upcoming, maxPixelSize: 3840)
            }
        }
        .onAppear { UIApplication.shared.isIdleTimerDisabled = model.isPlaying }
        .onChange(of: model.isPlaying) { _, playing in UIApplication.shared.isIdleTimerDisabled = playing }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
    }

    private var effectiveSettings: SlideshowSettings {
        var settings = model.settings
        if systemReduceMotion { settings.reducedMotion = true }
        return settings
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

    private func infoBar(settings: SlideshowSettings) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(model.currentPhoto?.title ?? "")
                .font(.title2.bold())
                .lineLimit(1)
            Text(L("slideshow.info", model.currentIndex + 1, model.photos.count, settings.intervalSeconds))
                .foregroundStyle(Theme.secondaryText)
            if let sleepAt = model.sleepAt {
                Text(L("slideshow.sleep_at", sleepAt.formatted(date: .omitted, time: .shortened)))
                    .foregroundStyle(Theme.amber)
            }
            Text(model.isPlaying ? L("slideshow.hint_playing") : L("slideshow.hint_paused"))
                .font(.callout)
                .foregroundStyle(Theme.secondaryText)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 80)
        .padding(.vertical, 48)
        .background(LinearGradient(colors: [.clear, .black.opacity(0.85)], startPoint: .top, endPoint: .bottom))
        .transition(.opacity)
    }

    private struct TimerKey: Hashable {
        let index: Int
        let playing: Bool
        let interval: Int
        let count: Int
    }
}

/// Foto con eventuale movimento lento (Ken Burns), fermo quando lo slideshow è in pausa.
private struct SlideView: View {
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

private struct ClockView: View {
    var body: some View {
        TimelineView(.everyMinute) { context in
            Text(context.date.formatted(date: .omitted, time: .shortened))
                .font(.title2.monospacedDigit())
                .padding(.horizontal, 20)
                .padding(.vertical, 10)
                .background(.black.opacity(0.5), in: RoundedRectangle(cornerRadius: 14))
        }
    }
}

/// Stile senza effetto di focus: l'intero schermo è il "pulsante" che riceve il tasto centrale.
private struct FullScreenButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
    }
}
