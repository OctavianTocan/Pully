import PullyNative
import PullyShared
import UIKit

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?
    private var feedback: ControlFeedbackHost?
    private var homeActions: HomeActionsHost?
    private var accountStorage: AccountStorageHost?
    private var application: IosPullyApp?
    private var incomingLinks: NativeIncomingLinks?
    private var shareHandoff: ShareHandoffHost?

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
        // Ordinary launches use the actual secure account/shared client. Fixtures require an explicit Debug switch.
        let controller: UIViewController
        #if DEBUG
        if let fixtureID = ProcessInfo.processInfo.environment["PULLY_UI_TEST_ROOT_JOURNEY"] {
            controller = createRootJourneyApplication(homeActions: nativeHomeActions, fixtureID: fixtureID)
        } else if ProcessInfo.processInfo.environment["PULLY_UI_TEST_FIXTURES"] == "1" {
            controller = MainViewControllerKt.MainViewController(homeActions: nativeHomeActions)
        } else {
            controller = createApplication(homeActions: nativeHomeActions)
        }
        #else
        controller = createApplication(homeActions: nativeHomeActions)
        #endif
        window.rootViewController = controller
        incomingLinks = NativeIncomingLinks(
            google: { NativeGoogleSignIn.shared.handle($0) },
            configuredServer: { [weak self] in self?.accountStorage?.connection(verification: false)?.server },
            open: { [weak self] in self?.application?.receiveLink(text: $0) },
            rejected: { [weak self] in self?.application?.rejectIncomingLink() }
        )
        self.window = window
        window.makeKeyAndVisible()
        nativeHomeActions.attach(to: controller.view)
        incomingLinks?.receive(connectionOptions.urlContexts.map(\.url), activities: Array(connectionOptions.userActivities))
    }

    func sceneWillEnterForeground(_ scene: UIScene) { homeActions?.update(.foregroundInactive) }
    func sceneDidBecomeActive(_ scene: UIScene) { feedback?.setActive(true); homeActions?.update(.foregroundActive) }
    func sceneWillResignActive(_ scene: UIScene) { feedback?.setActive(false); homeActions?.update(.foregroundInactive) }
    func sceneDidEnterBackground(_ scene: UIScene) { homeActions?.update(.background) }
    func sceneDidDisconnect(_ scene: UIScene) {
        // Retire shared routes/work before releasing their native storage, clipboard and lifecycle adapters.
        incomingLinks?.close()
        incomingLinks = nil
        application?.close()
        application = nil
        accountStorage?.disconnect()
        accountStorage = nil
        shareHandoff = nil
        window?.rootViewController = nil
        window = nil
        feedback?.setActive(false)
        feedback = nil
        homeActions?.disconnect()
        homeActions = nil
    }

    private func createApplication(homeActions: HomeActionsHost) -> UIViewController {
        let share = ShareHandoffHost()
        let storage = AccountStorageHost(identityWillChange: { share.revoke() })
        let application = IosPullyApp(
            homeActions: homeActions,
            accountStorage: storage,
            shareHandoff: share,
            personalSessions: PersonalSessionServices.shared
        )
        let controller = application.viewController
        storage.attach(to: controller)
        accountStorage = storage
        shareHandoff = share
        self.application = application
        return controller
    }

    #if DEBUG
    /// The actual root uses an isolated real Keychain service, preferences and a transport with no network path.
    private func createRootJourneyApplication(homeActions: HomeActionsHost, fixtureID: String) -> UIViewController {
        guard let id = UUID(uuidString: fixtureID),
              let defaults = UserDefaults(suiteName: "com.octaviantocan.pully.root-fixture.\(id.uuidString)") else {
            preconditionFailure("The root journey fixture requires a unique UUID.")
        }
        // Seeded on this UUID's first launch only, so a relaunch keeps the model chosen in Settings.
        if defaults.object(forKey: "pulls-server.url") == nil {
            defaults.set("https://fixtures.pully.invalid", forKey: "pulls-server.url")
            defaults.set("claude-code", forKey: "pulls-server.engine")
            defaults.set("", forKey: "pulls-server.model")
        }
        let storage = AccountStorageHost(
            preferences: DefaultsServerPreferences(defaults: defaults),
            secrets: KeychainSecretStore(service: "com.octaviantocan.pully.root-fixture.\(id.uuidString).credentials")
        )
        let application = IosRootJourneyFixtureKt.rootJourneyFixtureApplication(
            homeActions: homeActions, accountStorage: storage, defaults: defaults
        )
        let controller = application.viewController
        storage.attach(to: controller)
        accountStorage = storage
        self.application = application
        return controller
    }
    #endif

    func scene(_ scene: UIScene, openURLContexts contexts: Set<UIOpenURLContext>) {
        incomingLinks?.receive(contexts.map(\.url))
    }

    func scene(_ scene: UIScene, continue userActivity: NSUserActivity) {
        incomingLinks?.receive(userActivity)
    }
}
