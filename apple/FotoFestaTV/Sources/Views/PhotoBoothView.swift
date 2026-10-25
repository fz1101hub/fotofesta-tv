import FotoFestaCore
import SwiftUI

/// La cabina fotografica: quattro scatti con il conto alla rovescia, poi la striscia entra nella
/// festa come una foto degli invitati. Con "Smorfie" ogni scatto chiede una faccia diversa.
struct PhotoBoothView: View {
    @Environment(AppModel.self) private var model
    @State private var camera = BoothCamera()
    @State private var countdown: Int?
    @State private var flash = false
    @State private var shots: [Data] = []
    @State private var prompt: String?
    @State private var faces = false
    @State private var running: Task<Void, Never>?
    @State private var strip: PlatformImage?
    #if os(tvOS)
    @State private var picking = false
    #endif

    private static let shotCount = 4
    private static var prompts: [String] {
        [L("booth.face_1"), L("booth.face_2"), L("booth.face_3"), L("booth.face_4"), L("booth.face_5"), L("booth.face_6")]
    }

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            switch camera.state {
            case .running:
                live
            case .denied:
                message(L("booth.denied"))
            case .noCamera:
                VStack(alignment: .leading, spacing: 24) {
                    message(L("booth.no_camera"))
                    #if os(tvOS)
                    Button(L("booth.connect_iphone")) { picking = true }
                    #endif
                }
            case .idle, .starting:
                ProgressView()
            }
        }
        #if os(tvOS)
        .sheet(isPresented: $picking) {
            ContinuityPicker {
                picking = false
                Task { await camera.start() }
            }
        }
        #endif
        .task { await camera.start() }
        .onDisappear {
            running?.cancel()
            camera.stop()
        }
    }

    private var live: some View {
        HStack(spacing: 48) {
            ZStack {
                CameraPreview(session: camera.session)
                    .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
                if let countdown {
                    Text("\(countdown)").font(.system(size: 260, weight: .bold).monospacedDigit())
                        .foregroundStyle(.white).shadow(radius: 20)
                }
                if let prompt {
                    Text(prompt).font(.title.bold()).padding(.horizontal, 28).padding(.vertical, 14)
                        .background(.regularMaterial, in: Capsule())
                        .frame(maxHeight: .infinity, alignment: .top).padding(.top, 30)
                }
                Color.white.opacity(flash ? 0.9 : 0).allowsHitTesting(false)
            }
            VStack(alignment: .leading, spacing: 24) {
                ScreenTitle(title: L("booth.title"), subtitle: L("booth.subtitle"))
                if let strip {
                    Image(platformImage: strip).resizable().scaledToFit().frame(maxHeight: 520)
                        .shadow(color: .black.opacity(0.5), radius: 16, y: 8)
                } else {
                    HStack(spacing: 12) {
                        ForEach(0..<Self.shotCount, id: \.self) { index in
                            RoundedRectangle(cornerRadius: 8)
                                .fill(index < shots.count ? Theme.highlight : Color.white.opacity(0.15))
                                .frame(width: 40, height: 28)
                        }
                    }
                }
                Toggle(L("booth.faces"), isOn: $faces).disabled(running != nil)
                Button {
                    startStrip()
                } label: {
                    Label(strip == nil ? L("booth.start") : L("booth.again"), systemImage: "camera.fill")
                }
                .buttonStyle(.borderedProminent)
                .disabled(running != nil)
                Text(model.guests.active ? L("booth.to_party") : L("booth.no_party"))
                    .font(.callout).foregroundStyle(Theme.secondaryText)
                Spacer(minLength: 0)
            }
            .frame(width: 560, alignment: .topLeading)
        }
        .animation(.easeOut(duration: 0.25), value: flash)
    }

    private func message(_ text: String) -> some View {
        VStack(alignment: .leading, spacing: 24) {
            ScreenTitle(title: L("booth.title"), subtitle: text)
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func startStrip() {
        strip = nil
        shots = []
        running = Task {
            defer { running = nil }
            let prompts = Self.prompts.shuffled()
            for index in 0..<Self.shotCount {
                prompt = faces ? prompts[index % prompts.count] : nil
                for second in stride(from: 3, through: 1, by: -1) {
                    countdown = second
                    try? await Task.sleep(for: .seconds(1))
                    if Task.isCancelled { return }
                }
                countdown = nil
                flash = true
                if let shot = await camera.capture() { shots.append(shot) }
                try? await Task.sleep(for: .milliseconds(250))
                flash = false
                try? await Task.sleep(for: .milliseconds(700))
            }
            prompt = nil
            let accent = model.guests.setup.brand.accent.flatMap { UInt32($0.dropFirst(), radix: 16) } ?? 0xFF4F9A
            let photos = shots
            let made = await Task.detached(priority: .userInitiated) { BoothStrip.make(photos, accent: accent) }.value
            guard let made else { return }
            strip = PlatformImage(data: made)
            if model.guests.active { model.guests.addCameraPhoto(made, sender: L("booth.sender")) }
        }
    }
}
