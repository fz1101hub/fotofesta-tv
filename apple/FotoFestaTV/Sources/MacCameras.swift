#if os(macOS)
import FotoFestaCore
import Foundation
import ImageCaptureCore
import Observation
import SwiftUI
import UniformTypeIdentifiers

/// Fotocamera collegata al Mac con il cavo USB (PTP): ogni scatto nuovo entra nella festa. Gli
/// scatti già nella scheda quando la si collega restano dove sono.
@MainActor
@Observable
final class USBCameras: NSObject {
    private(set) var connected: [String] = []
    @ObservationIgnored private let browser = ICDeviceBrowser()
    @ObservationIgnored private var cameras: [ICCameraDevice] = []
    /// Fotocamere con il catalogo iniziale già letto: da qui in poi gli scatti sono nuovi.
    @ObservationIgnored private var ready: Set<ObjectIdentifier> = []
    @ObservationIgnored var onPhoto: ((Data) -> Void)?
    private(set) var running = false

    override init() {
        super.init()
        browser.delegate = self
        browser.browsedDeviceTypeMask = ICDeviceTypeMask(rawValue: ICDeviceTypeMask.camera.rawValue | ICDeviceLocationTypeMask.local.rawValue)!
    }

    func start() {
        guard !running else { return }
        running = true
        browser.start()
    }

    func stop() {
        running = false
        browser.stop()
        cameras.forEach { $0.requestCloseSession() }
        cameras = []
        ready = []
        connected = []
    }

    fileprivate func added(_ camera: ICCameraDevice) {
        guard !cameras.contains(where: { $0 === camera }) else { return }
        cameras.append(camera)
        connected.append(camera.name ?? "USB")
        camera.delegate = self
        camera.requestOpenSession()
    }

    fileprivate func removed(_ device: ICDevice) {
        cameras.removeAll { $0 === device }
        ready.remove(ObjectIdentifier(device))
        connected = cameras.map { $0.name ?? "USB" }
    }

    fileprivate func becameReady(_ camera: ICCameraDevice) {
        ready.insert(ObjectIdentifier(camera))
    }

    fileprivate func newItems(_ items: [ICCameraItem], on camera: ICCameraDevice) {
        guard ready.contains(ObjectIdentifier(camera)) else { return }
        for case let file as ICCameraFile in items {
            let name = (file.name ?? "").lowercased()
            guard name.hasSuffix(".jpg") || name.hasSuffix(".jpeg") || file.uti == UTType.jpeg.identifier,
                  file.fileSize > 0, file.fileSize < Int64(FtpServer.maxFileBytes) else { continue }
            file.requestReadData(atOffset: 0, length: file.fileSize) { [weak self] data, _ in
                guard let data else { return }
                Task { @MainActor [weak self] in self?.onPhoto?(data) }
            }
        }
    }
}

extension USBCameras: ICDeviceBrowserDelegate {
    nonisolated func deviceBrowser(_ browser: ICDeviceBrowser, didAdd device: ICDevice, moreComing: Bool) {
        guard let camera = device as? ICCameraDevice else { return }
        MainActor.assumeIsolated { added(camera) }
    }

    nonisolated func deviceBrowser(_ browser: ICDeviceBrowser, didRemove device: ICDevice, moreGoing: Bool) {
        MainActor.assumeIsolated { removed(device) }
    }
}

extension USBCameras: ICCameraDeviceDelegate {
    nonisolated func didRemove(_ device: ICDevice) {
        MainActor.assumeIsolated { removed(device) }
    }

    nonisolated func device(_ device: ICDevice, didOpenSessionWithError error: (any Error)?) {}
    nonisolated func device(_ device: ICDevice, didCloseSessionWithError error: (any Error)?) {}

    nonisolated func cameraDevice(_ camera: ICCameraDevice, didAdd items: [ICCameraItem]) {
        MainActor.assumeIsolated { newItems(items, on: camera) }
    }

    nonisolated func cameraDevice(_ camera: ICCameraDevice, didRemove items: [ICCameraItem]) {}
    nonisolated func cameraDevice(_ camera: ICCameraDevice, didReceiveThumbnail thumbnail: CGImage?, for item: ICCameraItem, error: (any Error)?) {}
    nonisolated func cameraDevice(_ camera: ICCameraDevice, didReceiveMetadata metadata: [AnyHashable: Any]?, for item: ICCameraItem, error: (any Error)?) {}
    nonisolated func cameraDevice(_ camera: ICCameraDevice, didRenameItems items: [ICCameraItem]) {}
    nonisolated func cameraDeviceDidChangeCapability(_ camera: ICCameraDevice) {}
    nonisolated func cameraDevice(_ camera: ICCameraDevice, didReceivePTPEvent eventData: Data) {}

    nonisolated func deviceDidBecomeReady(withCompleteContentCatalog device: ICCameraDevice) {
        MainActor.assumeIsolated { becameReady(device) }
    }

    nonisolated func cameraDeviceDidRemoveAccessRestriction(_ device: ICDevice) {}
    nonisolated func cameraDeviceDidEnableAccessRestriction(_ device: ICDevice) {}
}

/// Cartella a caldo del Mac: le foto nuove che arrivano in una cartella (per esempio dal programma
/// di scatto collegato alla fotocamera) entrano nella festa. Si controlla ogni 20 secondi.
@MainActor
@Observable
final class HotFolder {
    private(set) var folderName: String?
    @ObservationIgnored private var url: URL?
    @ObservationIgnored private var known: Set<String> = []
    @ObservationIgnored private var task: Task<Void, Never>?
    @ObservationIgnored var onPhoto: ((Data) -> Void)?
    private static let key = "hotFolderBookmark"

    init() {
        if let data = UserDefaults.standard.data(forKey: Self.key) {
            var stale = false
            if let resolved = try? URL(resolvingBookmarkData: data, options: .withSecurityScope, relativeTo: nil, bookmarkDataIsStale: &stale) {
                url = resolved
                folderName = resolved.lastPathComponent
            }
        }
    }

    func choose(_ folder: URL) {
        stop()
        guard folder.startAccessingSecurityScopedResource() else { return }
        defer { folder.stopAccessingSecurityScopedResource() }
        guard let bookmark = try? folder.bookmarkData(options: .withSecurityScope, includingResourceValuesForKeys: nil, relativeTo: nil) else { return }
        UserDefaults.standard.set(bookmark, forKey: Self.key)
        url = folder
        folderName = folder.lastPathComponent
    }

    func forget() {
        stop()
        UserDefaults.standard.removeObject(forKey: Self.key)
        url = nil
        folderName = nil
    }

    /// Le foto già nella cartella all'avvio non contano: entrano solo quelle nuove.
    func start() {
        guard task == nil, let url else { return }
        known = Set(Self.images(in: url).map(\.lastPathComponent))
        task = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(20))
                self?.scan()
            }
        }
    }

    func stop() {
        task?.cancel()
        task = nil
    }

    private func scan() {
        guard let url else { return }
        for file in Self.images(in: url) where !known.contains(file.lastPathComponent) {
            known.insert(file.lastPathComponent)
            let access = url.startAccessingSecurityScopedResource()
            let data = try? Data(contentsOf: file)
            if access { url.stopAccessingSecurityScopedResource() }
            if let data, GuestImageFormat.isDisplayable(data) { onPhoto?(data) }
        }
    }

    private static func images(in folder: URL) -> [URL] {
        let access = folder.startAccessingSecurityScopedResource()
        defer { if access { folder.stopAccessingSecurityScopedResource() } }
        let files = (try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil, options: [.skipsHiddenFiles])) ?? []
        return files.filter { ["jpg", "jpeg", "png", "heic"].contains($0.pathExtension.lowercased()) }
    }
}

/// Fotocamera USB e cartella a caldo nelle impostazioni dei dispositivi (solo Mac).
struct CameraSection: View {
    @Environment(AppModel.self) private var model
    @State private var choosing = false

    var body: some View {
        Toggle(isOn: Binding(get: { model.usbCameraEnabled }, set: { model.setUSBCamera($0) })) {
            VStack(alignment: .leading, spacing: 4) {
                Text(L("devices.usb_camera"))
                Text(model.usbCameras.connected.first.map { L("devices.usb_camera_connected", $0) } ?? L("devices.usb_camera_body"))
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        VStack(alignment: .leading, spacing: 4) {
            Text(L("settings.hot_folder"))
            Text(L("settings.hot_folder_body")).font(.caption).foregroundStyle(.secondary)
            HStack {
                Button(model.hotFolder.folderName ?? L("settings.hot_folder_choose")) { choosing = true }
                if model.hotFolder.folderName != nil {
                    Button(L("settings.hot_folder_forget"), role: .destructive) { model.hotFolder.forget() }
                }
            }
        }
        .fileImporter(isPresented: $choosing, allowedContentTypes: [.folder]) { result in
            if case let .success(folder) = result {
                model.hotFolder.choose(folder)
                model.syncDevices()
            }
        }
    }
}
#endif
