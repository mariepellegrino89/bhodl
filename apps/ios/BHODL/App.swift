// BHODL spike — iOS host app.
//
// SCAFFOLD ONLY: this file traces the integration pattern but is NOT verified.
// Phase-0 was developed on Windows; iOS targets cannot be built without macOS.
//
// To wire this app on a Mac:
//   1. From repo root run `./gradlew :shared:embedAndSignAppleFrameworkForXcode`
//      after declaring the iOS framework in shared/build.gradle.kts (left as a
//      Phase-1 follow-up because the Ubique uniffi plugin produces the native
//      framework artefact differently from the JetBrains assemble task).
//   2. Open `apps/ios/BHODL.xcodeproj` in Xcode 16+.
//   3. Drag the produced framework into the project and link it.
//   4. Build & run on the iOS Simulator.
//
// This file uses Compose Multiplatform's UIKit interop entry point
// (`MainViewController`) so the UI stays 1:1 with the Android/Desktop screen.

import SwiftUI
import UIKit

@main
struct BHODLApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeContainerView()
                .ignoresSafeArea(.keyboard)
        }
    }
}

/// Bridges the Compose Multiplatform UIViewController returned by
/// `Shared.MainViewControllerKt.MainViewController()` into SwiftUI.
///
/// The Kotlin side (apps/ios/Main.kt — see below) exposes a top-level
/// `MainViewController()` function which CMP turns into a Swift symbol on
/// the framework: `MainViewControllerKt.MainViewController()`.
struct ComposeContainerView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        // TODO(spike): replace with the actual import once shared.framework is
        // produced. The line below is what the call looks like in production:
        // return MainViewControllerKt.MainViewController()
        return placeholderController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}

    private func placeholderController() -> UIViewController {
        let vc = UIViewController()
        vc.view.backgroundColor = .systemBackground
        let label = UILabel()
        label.translatesAutoresizingMaskIntoConstraints = false
        label.text = "BHODL spike\n(Compose framework not yet built — see App.swift TODO)"
        label.numberOfLines = 0
        label.textAlignment = .center
        vc.view.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: vc.view.centerXAnchor),
            label.centerYAnchor.constraint(equalTo: vc.view.centerYAnchor),
            label.leadingAnchor.constraint(equalTo: vc.view.leadingAnchor, constant: 24),
            label.trailingAnchor.constraint(equalTo: vc.view.trailingAnchor, constant: -24),
        ])
        return vc
    }
}
