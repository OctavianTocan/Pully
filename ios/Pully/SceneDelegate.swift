import PullyNative
import PullyShared
import UIKit

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?
    private var feedback: ControlFeedbackHost?
    private var homeActions: HomeActionsHost?

    func scene(
        _ scene: UIScene,
        willConnectTo session: UISceneSession,
        options connectionOptions: UIScene.ConnectionOptions
    ) {
        guard let windowScene = scene as? UIWindowScene else { return }
        let window = UIWindow(windowScene: windowScene)
        feedback = ControlFeedbackHost()
        let nativeHomeActions = HomeActionsHost(scene: windowScene)
        homeActions = nativeHomeActions
        #if DEBUG
        // UI tests select traits before Compose is attached, then await rendered content.
        switch ProcessInfo.processInfo.environment["PULLY_UI_TEST_APPEARANCE"] {
        case "light": window.overrideUserInterfaceStyle = .light
        case "dark": window.overrideUserInterfaceStyle = .dark
        default: break
        }
        #endif
        // Shared Compose owns layout, safe-area insets, state and navigation.
        let controller = MainViewControllerKt.MainViewController(homeActions: nativeHomeActions)
        window.rootViewController = controller
        self.window = window
        window.makeKeyAndVisible()
        nativeHomeActions.attach(to: controller.view)
    }

    func sceneWillEnterForeground(_ scene: UIScene) { homeActions?.update(.foregroundInactive) }
    func sceneDidBecomeActive(_ scene: UIScene) { feedback?.setActive(true); homeActions?.update(.foregroundActive) }
    func sceneWillResignActive(_ scene: UIScene) { feedback?.setActive(false); homeActions?.update(.foregroundInactive) }
    func sceneDidEnterBackground(_ scene: UIScene) { homeActions?.update(.background) }
    func sceneDidDisconnect(_ scene: UIScene) {
        feedback?.setActive(false)
        feedback = nil
        homeActions?.disconnect()
        homeActions = nil
    }

    func scene(_ scene: UIScene, openURLContexts contexts: Set<UIOpenURLContext>) {
        for context in contexts {
            if NativeGoogleSignIn.shared.handle(context.url) { break }
        }
    }
}
