import PullyNative
import PullyShared
import UIKit

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        guard let windowScene = scene as? UIWindowScene else { return }
        let window = UIWindow(windowScene: windowScene)
        #if DEBUG
        // UI tests select traits before Compose is attached, then await rendered content.
        switch ProcessInfo.processInfo.environment["PULLY_UI_TEST_APPEARANCE"] {
        case "light": window.overrideUserInterfaceStyle = .light
        case "dark": window.overrideUserInterfaceStyle = .dark
        default: break
        }
        #endif
        // Shared Compose owns layout, safe-area insets, state and navigation.
        window.rootViewController = MainViewControllerKt.MainViewController()
        self.window = window
        window.makeKeyAndVisible()
    }

    func scene(_ scene: UIScene, openURLContexts contexts: Set<UIOpenURLContext>) {
        for context in contexts {
            if NativeGoogleSignIn.shared.handle(context.url) { break }
        }
    }
}
