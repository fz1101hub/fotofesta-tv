// swift-tools-version:5.10
import PackageDescription

// Logica dell'app Apple TV senza dipendenze da UIKit/SwiftUI: si testa con `swift test` anche su macOS.
let package = Package(
    name: "FotoFestaCore",
    defaultLocalization: "en",
    platforms: [.tvOS(.v17), .macOS(.v14), .iOS(.v17)],
    products: [
        .library(name: "FotoFestaCore", targets: ["FotoFestaCore"]),
    ],
    targets: [
        .target(name: "FotoFestaCore"),
        .testTarget(name: "FotoFestaCoreTests", dependencies: ["FotoFestaCore"]),
    ]
)
