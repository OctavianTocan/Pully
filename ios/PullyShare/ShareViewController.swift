import Foundation
import PullyLinks
import PullyShareSupport
import UIKit
import UniformTypeIdentifiers

/// A system share adapter only. PullyShared owns the destination screens, account and navigation after import.
final class ShareViewController: UIViewController {
    private let message = UILabel()
    private let saveButton = UIButton(type: .system)
    private let cancelButton = UIButton(type: .system)
    private var storage: ShareHandoffStorage?
    private var source: ShareSourceContext?
    private var payload: String?
    private var active = true

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        let title = UILabel()
        title.text = "Pully internal preview"
        title.font = .preferredFont(forTextStyle: .title2)
        title.adjustsFontForContentSizeCategory = true
        title.accessibilityTraits.insert(.header)
        message.numberOfLines = 0
        message.font = .preferredFont(forTextStyle: .body)
        message.adjustsFontForContentSizeCategory = true
        message.text = "Reading the shared link…"
        saveButton.setTitle("Save for Pully", for: .normal)
        saveButton.isHidden = true
        saveButton.addTarget(self, action: #selector(save), for: .touchUpInside)
        cancelButton.setTitle("Cancel", for: .normal)
        cancelButton.addTarget(self, action: #selector(cancel), for: .touchUpInside)
        let column = UIStackView(arrangedSubviews: [title, message, saveButton, cancelButton])
        column.axis = .vertical
        column.spacing = 20
        column.translatesAutoresizingMaskIntoConstraints = false
        let scroll = UIScrollView()
        scroll.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scroll)
        scroll.addSubview(column)
        NSLayoutConstraint.activate([
            scroll.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor),
            scroll.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scroll.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            column.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor, constant: 24),
            column.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor, constant: -24),
            column.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 24),
            column.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -24),
            column.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor, constant: -48),
            saveButton.heightAnchor.constraint(greaterThanOrEqualToConstant: 44),
            cancelButton.heightAnchor.constraint(greaterThanOrEqualToConstant: 44),
        ])
        preferredContentSize = CGSize(width: 360, height: 360)
        guard let storage = ShareGroupSetup.storage() else { fail(ShareHandoffError.unavailable); return }
        do {
            guard let source = try storage.context() else { fail(ShareHandoffError.noSource); return }
            self.storage = storage
            self.source = source
            let items = extensionContext?.inputItems as? [NSExtensionItem] ?? []
            var requests: [(NSItemProvider, String)] = []
            var links: [String] = []
            for item in items.prefix(16) {
                if let text = item.attributedContentText?.string {
                    links += NativeShareCandidates.links(in: text, configuredServer: source.serverOrigin)
                }
                for provider in item.attachments ?? [] where requests.count < 16 {
                    if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) { requests.append((provider, UTType.url.identifier)) }
                    else if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) { requests.append((provider, UTType.plainText.identifier)) }
                }
            }
            load(requests, index: 0, links: links)
        } catch { fail(error) }
    }

    private func load(_ requests: [(NSItemProvider, String)], index: Int, links: [String]) {
        guard active, let source else { return }
        guard index < requests.count else {
            let text = links.prefix(16).joined(separator: "\n")
            guard text.utf8.count <= NativeIncomingLink.maximumBytes,
                  let payload = SharedLinkHandoffKt.encodeSharedLink(text: text) else {
                failMessage("Share a GitHub pull request, repository, commit, compare or a supported link to your Pully server.")
                return
            }
            self.payload = payload
            message.text = "Save this link, then open Pully to continue with your current account. Saved links expire after 24 hours."
            saveButton.isHidden = false
            return
        }
        let (provider, type) = requests[index]
        provider.loadItem(forTypeIdentifier: type, options: nil) { [weak self] value, _ in
            let text: String?
            if let url = value as? URL { text = url.absoluteString }
            else if let string = value as? String { text = string }
            else if let data = value as? Data, data.count <= NativeIncomingLink.maximumBytes { text = String(data: data, encoding: .utf8) }
            else { text = nil }
            Task { @MainActor [weak self] in
                guard let self, self.active else { return }
                let incoming = text.map { NativeShareCandidates.links(in: $0, configuredServer: source.serverOrigin) } ?? []
                self.load(requests, index: index + 1, links: links + incoming)
            }
        }
    }

    @objc private func save() {
        guard active, let storage, let source, let payload else { return }
        do {
            try storage.save(payload: payload, for: source)
            active = false
            self.payload = nil
            extensionContext?.completeRequest(returningItems: nil)
        } catch { fail(error) }
    }

    @objc private func cancel() {
        guard active else { return }
        active = false
        payload = nil
        extensionContext?.cancelRequest(withError: NSError(domain: NSCocoaErrorDomain, code: NSUserCancelledError))
    }

    private func fail(_ error: Error) {
        failMessage((error as? ShareHandoffError)?.errorDescription ?? "Pully could not save this link. Unlock the device and share it again.")
    }

    private func failMessage(_ text: String) {
        payload = nil
        saveButton.isHidden = true
        message.text = text
    }
}
