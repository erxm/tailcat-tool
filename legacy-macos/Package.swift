// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "TailcatManager",
    platforms: [.macOS(.v13)],
    products: [
        .library(name: "TailcatCore", targets: ["TailcatCore"]),
        .executable(name: "TailcatManager", targets: ["TailcatManager"]),
        .executable(name: "TailcatCredentialHelper", targets: ["TailcatCredentialHelper"]),
        .executable(name: "TailcatChecks", targets: ["TailcatChecks"])
    ],
    targets: [
        .target(name: "TailcatCore"),
        .executableTarget(name: "TailcatManager", dependencies: ["TailcatCore"]),
        .executableTarget(name: "TailcatCredentialHelper"),
        .executableTarget(name: "TailcatChecks", dependencies: ["TailcatCore"], path: "Tests/TailcatCoreTests")
    ]
)
