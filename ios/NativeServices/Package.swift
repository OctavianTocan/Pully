// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "PullyNative",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "PullyNative", targets: ["PullyNative"])],
    dependencies: [
        .package(url: "https://github.com/google/GoogleSignIn-iOS.git", exact: "10.0.0"),
    ],
    targets: [
        .target(
            name: "PullyNative",
            dependencies: [
                .product(name: "GoogleSignIn", package: "GoogleSignIn-iOS", condition: .when(platforms: [.iOS])),
            ]
        ),
        .testTarget(name: "PullyNativeTests", dependencies: ["PullyNative"]),
    ],
    swiftLanguageModes: [.v5]
)
