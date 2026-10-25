import AVFoundation
import AVKit
import FotoFestaCore
import ImageIO
import Observation
import SwiftUI
import UniformTypeIdentifiers

/// Fotocamera della cabina: la webcam (o l'iPhone con Continuity Camera) sul Mac, l'iPhone o l'iPad
/// collegato con Continuity Camera sulla Apple TV.
@MainActor
@Observable
final class BoothCamera: NSObject {
    enum State: Equatable {
        case idle, starting, running, noCamera, denied
    }

    private(set) var state: State = .idle
    @ObservationIgnored let session = AVCaptureSession()
    @ObservationIgnored private let output = AVCapturePhotoOutput()
    @ObservationIgnored private let queue = DispatchQueue(label: "it.fotofesta.booth")
    @ObservationIgnored private var pending: CheckedContinuation<Data?, Never>?

    func start() async {
        guard state != .running, state != .starting else { return }
        state = .starting
        var status = AVCaptureDevice.authorizationStatus(for: .video)
        if status == .notDetermined {
            status = await AVCaptureDevice.requestAccess(for: .video) ? .authorized : .denied
        }
        guard status == .authorized else {
            state = .denied
            return
        }
        guard let device = Self.camera(), let input = try? AVCaptureDeviceInput(device: device) else {
            state = .noCamera
            return
        }
        session.beginConfiguration()
        session.sessionPreset = .photo
        session.inputs.forEach { session.removeInput($0) }
        if session.canAddInput(input) { session.addInput(input) }
        if !session.outputs.contains(output), session.canAddOutput(output) { session.addOutput(output) }
        session.commitConfiguration()
        let session = session
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            queue.async {
                session.startRunning()
                continuation.resume()
            }
        }
        state = session.isRunning ? .running : .noCamera
    }

    func stop() {
        let session = session
        queue.async { session.stopRunning() }
        state = .idle
    }

    /// Uno scatto in JPEG; nil se la fotocamera non risponde.
    func capture() async -> Data? {
        guard state == .running, pending == nil else { return nil }
        return await withCheckedContinuation { continuation in
            pending = continuation
            let settings = output.availablePhotoCodecTypes.contains(.jpeg)
                ? AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg])
                : AVCapturePhotoSettings()
            output.capturePhoto(with: settings, delegate: self)
        }
    }

    fileprivate func finish(_ data: Data?) {
        pending?.resume(returning: data)
        pending = nil
    }

    private static func camera() -> AVCaptureDevice? {
        #if os(tvOS)
        // Sulla Apple TV c'è solo Continuity Camera: l'iPhone o l'iPad scelto nel pannello di sistema.
        return AVCaptureDevice.systemPreferredCamera
            ?? AVCaptureDevice.DiscoverySession(deviceTypes: [.continuityCamera], mediaType: .video, position: .unspecified).devices.first
        #else
        return AVCaptureDevice.systemPreferredCamera ?? AVCaptureDevice.default(for: .video)
        #endif
    }
}

extension BoothCamera: AVCapturePhotoCaptureDelegate {
    nonisolated func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: (any Error)?) {
        let data = photo.fileDataRepresentation()
        Task { @MainActor in self.finish(data) }
    }
}

/// Anteprima della fotocamera, a specchio come in una cabina vera.
struct CameraPreview: View {
    let session: AVCaptureSession

    var body: some View {
        PreviewLayerView(session: session).scaleEffect(x: -1, y: 1)
    }
}

#if canImport(UIKit)
private struct PreviewLayerView: UIViewRepresentable {
    let session: AVCaptureSession

    final class Holder: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var preview: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }

    func makeUIView(context: Context) -> Holder {
        let view = Holder()
        view.preview.session = session
        view.preview.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ view: Holder, context: Context) {}
}
#else
private struct PreviewLayerView: NSViewRepresentable {
    let session: AVCaptureSession

    func makeNSView(context: Context) -> NSView {
        let view = NSView()
        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.videoGravity = .resizeAspectFill
        preview.autoresizingMask = [.layerWidthSizable, .layerHeightSizable]
        view.layer = preview
        view.wantsLayer = true
        return view
    }

    func updateNSView(_ view: NSView, context: Context) {}
}
#endif

#if os(tvOS)
/// Pannello di sistema per collegare l'iPhone o l'iPad come fotocamera della Apple TV.
struct ContinuityPicker: UIViewControllerRepresentable {
    let onConnect: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onConnect: onConnect) }

    func makeUIViewController(context: Context) -> AVContinuityDevicePickerViewController {
        let picker = AVContinuityDevicePickerViewController()
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: AVContinuityDevicePickerViewController, context: Context) {}

    final class Coordinator: NSObject, AVContinuityDevicePickerViewControllerDelegate {
        let onConnect: () -> Void

        init(onConnect: @escaping () -> Void) {
            self.onConnect = onConnect
        }

        func continuityDevicePicker(_ pickerViewController: AVContinuityDevicePickerViewController, didConnect device: AVContinuityDevice) {
            onConnect()
        }
    }
}
#endif

/// Striscia della cabina: quattro scatti uno sotto l'altro con una fascia del colore dell'evento.
enum BoothStrip {
    static let width = 1080
    static let shotHeight = 720
    static let margin = 40
    static let band = 140

    static func make(_ shots: [Data], accent: UInt32) -> Data? {
        let images = shots.compactMap { data -> CGImage? in
            guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
            return CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: 1600,
            ] as CFDictionary)
        }
        guard !images.isEmpty else { return nil }
        let inner = width - margin * 2
        let height = margin + images.count * (shotHeight + margin) + band
        guard let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        context.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        let r = CGFloat((accent >> 16) & 0xFF) / 255
        let g = CGFloat((accent >> 8) & 0xFF) / 255
        let b = CGFloat(accent & 0xFF) / 255
        context.setFillColor(CGColor(red: r, green: g, blue: b, alpha: 1))
        context.fill(CGRect(x: 0, y: 0, width: width, height: band))
        context.interpolationQuality = .high
        for (index, image) in images.enumerated() {
            // Dall'alto: in Core Graphics l'origine è in basso.
            let top = height - margin - index * (shotHeight + margin) - shotHeight
            let frame = CGRect(x: margin, y: top, width: inner, height: shotHeight)
            let scale = max(frame.width / CGFloat(image.width), frame.height / CGFloat(image.height))
            let size = CGSize(width: CGFloat(image.width) * scale, height: CGFloat(image.height) * scale)
            context.saveGState()
            context.clip(to: frame)
            // A specchio, come nell'anteprima.
            context.translateBy(x: frame.midX, y: 0)
            context.scaleBy(x: -1, y: 1)
            context.draw(image, in: CGRect(x: -size.width / 2, y: frame.midY - size.height / 2, width: size.width, height: size.height))
            context.restoreGState()
        }
        guard let strip = context.makeImage() else { return nil }
        let output = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(output as CFMutableData, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, strip, [kCGImageDestinationLossyCompressionQuality: 0.9] as CFDictionary)
        return CGImageDestinationFinalize(destination) ? output as Data : nil
    }
}
