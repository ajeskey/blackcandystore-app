import HotwireNative
import sharedKit

/// Handles the active-library change signal emitted by the server's
/// library-management / active-library-selection screens. When the active
/// library changes, the current playlist is refreshed so the player reflects
/// the newly scoped content (R5.3).
class LibraryComponent: BridgeComponent {
    override class var name: String { "library" }

    private var viewModel: WebViewModel? {
        let viewController = delegate?.destination as? WebViewController
        return viewController?.viewModel
    }

    override func onReceive(message: Message) {
        switch message.event {
        case "activeLibraryChanged":
            handleActiveLibraryChangedEvent()
        default:
            break
        }
    }

    private func handleActiveLibraryChangedEvent() {
        viewModel?.refreshCurrentPlaylist()
    }
}
