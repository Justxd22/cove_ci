package org.bitcoinppl.cove.flows.SettingsFlow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bitcoinppl.cove_core.Node
import org.bitcoinppl.cove_core.NodeSelection
import org.bitcoinppl.cove_core.NodeSelector
import java.net.URI

fun isOnionNodeUrl(url: String): Boolean {
    return try {
        URI(url).host?.endsWith(".onion", ignoreCase = true) == true
    } catch (_: Exception) {
        false
    }
}

suspend fun switchToFirstClearnetPresetNode(nodeSelector: NodeSelector): Result<Node> =
    runCatching {
        withContext(Dispatchers.IO) {
            val fallbackNode =
                nodeSelector
                    .nodeList()
                    .asSequence()
                    .mapNotNull { selection -> (selection as? NodeSelection.Preset)?.v1 }
                    .firstOrNull { node -> !isOnionNodeUrl(node.url) }
                    ?: throw IllegalStateException("No clearnet preset node available for fallback")

            nodeSelector.checkAndSaveNode(fallbackNode)
            fallbackNode
        }
    }
