package egroden.obsidian.rag.vault.watcher

import java.nio.file.Path
import kotlin.time.Duration

interface VaultWatcher {
    fun start(subscriber: (VaultUpdateEvent) -> Unit)

    fun dispose()
}

sealed class VaultUpdateEvent(open val time: Duration) {
    data class MarkdownDocUpsert(val path: Path, override val time: Duration) : VaultUpdateEvent(time)
    data class MarkdownDocDeleted(val path: Path, override val time: Duration) : VaultUpdateEvent(time)
    data class OverflowUpdates(override val time: Duration) : VaultUpdateEvent(time)
}