#if os(iOS)
import UIKit

/// The existing cue roles adapted to UIKit's system-gated feedback generators.
@MainActor
public final class NativeCueHaptics {
    private let selection = UISelectionFeedbackGenerator()
    private let click = UIImpactFeedbackGenerator(style: .light)
    private let heavy = UIImpactFeedbackGenerator(style: .heavy)
    private let outcome = UINotificationFeedbackGenerator()

    public init() {}

    public func play(cue: String, emphasis: String) {
        switch cue {
        case "tap":
            if emphasis == "subtle" { selection.selectionChanged() }
            else { click.impactOccurred() }
        case "toggle": click.impactOccurred()
        case "select": selection.selectionChanged()
        case "success", "ready": outcome.notificationOccurred(.success)
        case "error", "warning": heavy.impactOccurred()
        default: break
        }
    }

    public func hold() { heavy.impactOccurred() }

    /// Existing touch-only sheet/page thresholds have no audible cue.
    public func gesture(role: String) {
        switch role {
        case "sheet": click.impactOccurred()
        case "threshold", "page": selection.selectionChanged()
        default: assertionFailure("Unknown shared gesture feedback role")
        }
    }
}
#endif
