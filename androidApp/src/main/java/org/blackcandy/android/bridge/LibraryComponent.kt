package org.blackcandy.android.bridge

import dev.hotwire.core.bridge.BridgeComponent
import dev.hotwire.core.bridge.BridgeDelegate
import dev.hotwire.core.bridge.Message
import dev.hotwire.navigation.destinations.HotwireDestination
import org.blackcandy.android.fragments.web.WebFragment
import org.blackcandy.shared.viewmodels.WebViewModel

/**
 * Handles the active-library change signal emitted by the server's
 * library-management / active-library-selection screens. When the active
 * library changes, the current playlist is refreshed so the player reflects
 * the newly scoped content (R5.3).
 */
class LibraryComponent(
    name: String,
    private val delegate: BridgeDelegate<HotwireDestination>,
) : BridgeComponent<HotwireDestination>(name, delegate) {
    private val viewModel: WebViewModel
        get() {
            val fragment = delegate.destination.fragment as WebFragment
            return fragment.viewModel
        }

    override fun onReceive(message: Message) {
        when (message.event) {
            "activeLibraryChanged" -> handleActiveLibraryChangedEvent()
        }
    }

    private fun handleActiveLibraryChangedEvent() {
        viewModel.refreshCurrentPlaylist()
    }
}
