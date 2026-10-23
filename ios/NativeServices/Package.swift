// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "PullyNative",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "PullyNative", targets: ["PullyNative"]),
        .library(name: "PullyShareSupport", targets: ["PullyShareSupport"]),
    ],
    dependencies: [
        .package(url: "https://github.com/google/GoogleSignIn-iOS.git", exact: "10.0.0"),
    ],
    targets: [
        .target(name: "PullyFileLock"),
        .target(name: "PullyShareSupport", dependencies: ["PullyFileLock"]),
        .target(
            name: "PullyNative",
            dependencies: [
                "PullyShareSupport",
                .product(name: "GoogleSignIn", package: "GoogleSignIn-iOS", condition: .when(platforms: [.iOS])),
            ]
        ),
        // Keychain requires an app's signed identity. The Xcode PullyNativeKeychain scheme
        // compiles that same source as an app-hosted unit test, without mocking Security.
        .testTarget(
            name: "PullyNativeTests",
            dependencies: ["PullyNative", "PullyShareSupport"],
            exclude: ["KeychainSecretStoreTests.swift"]
        ),
    ],
    swiftLanguageModes: [.v5]
)
