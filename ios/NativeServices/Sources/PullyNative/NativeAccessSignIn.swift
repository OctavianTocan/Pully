import Foundation

/// The `CF_Authorization` token a Cloudflare Access sign-in ends with: a JWT, never printed or logged.
public enum NativeAccessToken {
    public static func isWellFormed(_ token: String) -> Bool {
        !token.isEmpty && token.utf8.count <= 16 * 1024 && !token.containsControlCharacters
            && token.split(separator: ".", omittingEmptySubsequences: false).count == 3
    }

    /// The signed-in email the token names, lowercased; nil when it names none. Used only to tell identities apart.
    public static func email(_ token: String) -> String? {
        let parts = token.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 3 else { return nil }
        var payload = parts[1].replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while payload.count % 4 != 0 { payload += "=" }
        guard let data = Data(base64Encoded: payload),
              let claims = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let email = claims["email"] as? String, !email.isEmpty else { return nil }
        return email.lowercased()
    }
}

#if os(iOS)
import UIKit
import WebKit

/// Cloudflare Access's own sign-in for the configured server, in a page inside Pully, as Android's
/// `AccessSignInSheet`. Once the page reaches the server through Access, the server's `CF_Authorization` cookie is
/// handed back; no other cookie or page content is read. Google refuses to sign in inside an app's page, so the hint
/// says to use the emailed code.
@MainActor
public final class NativeAccessSignInController: UIViewController, WKNavigationDelegate {
    private let server: URL
    private let host: String
    private let titleText: String
    private let hint: String
    private var finish: ((Result<String?, Error>) -> Void)?
    private var webView: WKWebView?

    /// [finish] gets the token, nil when the person closed the page, or an error; it is called once, after the page
    /// has gone.
    public init(server: String, title: String, hint: String, finish: @escaping (Result<String?, Error>) -> Void) throws {
        guard let url = URL(string: server), url.scheme?.lowercased() == "https", let host = url.host?.lowercased() else {
            throw NativeAccountError.invalidServer
        }
        self.server = url
        self.host = host
        self.titleText = title
        self.hint = hint
        self.finish = finish
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { return nil }

    /// The page wrapped for presentation: it closes only from its own Close button.
    public func presentable() -> UIViewController {
        let navigation = UINavigationController(rootViewController: self)
        navigation.isModalInPresentation = true
        return navigation
    }

    override public func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        title = titleText
        navigationItem.leftBarButtonItem = UIBarButtonItem(
            systemItem: .close, primaryAction: UIAction { [weak self] _ in self?.complete(.success(nil)) }
        )
        let label = UILabel()
        label.text = hint
        label.numberOfLines = 0
        label.font = .preferredFont(forTextStyle: .footnote)
        label.textColor = .secondaryLabel
        label.adjustsFontForContentSizeCategory = true
        let page = WKWebView(frame: .zero, configuration: WKWebViewConfiguration())
        page.navigationDelegate = self
        webView = page
        let stack = UIStackView(arrangedSubviews: [label, page])
        stack.axis = .vertical
        stack.spacing = 12
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
            stack.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            label.leadingAnchor.constraint(equalTo: view.layoutMarginsGuide.leadingAnchor),
            label.trailingAnchor.constraint(equalTo: view.layoutMarginsGuide.trailingAnchor),
            stack.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: view.trailingAnchor),
        ])
        // An old sign-in's token must not pass for a new one; Cloudflare's own session is kept, so a second sign-in
        // can go straight through.
        let cookies = page.configuration.websiteDataStore.httpCookieStore
        cookies.getAllCookies { [weak self] all in
            MainActor.assumeIsolated {
                guard let self else { return }
                let stale = all.filter { $0.name == "CF_Authorization" && self.belongsToServer($0) }
                if stale.isEmpty { self.loadServer(); return }
                // The store runs deletions in order, so the last one finishing means all have.
                for (index, cookie) in stale.enumerated() {
                    cookies.delete(cookie) { [weak self] in
                        MainActor.assumeIsolated { if index == stale.count - 1 { self?.loadServer() } }
                    }
                }
            }
        }
    }

    private func loadServer() {
        guard finish != nil else { return }
        webView?.load(URLRequest(url: server))
    }

    public func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard webView.url?.host?.lowercased() == host else { return }
        webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { [weak self] all in
            MainActor.assumeIsolated {
                guard let self,
                      let cookie = all.first(where: { $0.name == "CF_Authorization" && self.belongsToServer($0) }),
                      NativeAccessToken.isWellFormed(cookie.value) else { return }
                self.complete(.success(cookie.value))
            }
        }
    }

    /// Closes the page without an answer, when the shared caller has gone.
    public func cancel() {
        finish = nil
        close()
    }

    private func belongsToServer(_ cookie: HTTPCookie) -> Bool {
        let domain = cookie.domain.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "."))
        return host == domain || host.hasSuffix("." + domain)
    }

    private func complete(_ result: Result<String?, Error>) {
        guard let finish else { return }
        self.finish = nil
        close { finish(result) }
    }

    private func close(then: (() -> Void)? = nil) {
        webView?.stopLoading()
        webView?.navigationDelegate = nil
        let presented = navigationController ?? self
        if presented.presentingViewController != nil {
            presented.dismiss(animated: true) { then?() }
        } else {
            then?()
        }
    }
}
#endif
