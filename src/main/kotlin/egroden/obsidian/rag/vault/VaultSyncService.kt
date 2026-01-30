package egroden.obsidian.rag.vault

import egroden.obsidian.rag.indexing.FileIndexingData
import egroden.obsidian.rag.indexing.FilesIndexingPipeline
import egroden.obsidian.rag.vault.snapshot.VaultFileMeta
import egroden.obsidian.rag.vault.snapshot.VaultSnapshot
import egroden.obsidian.rag.vault.snapshot.VaultSnapshotDiff
import egroden.obsidian.rag.vault.snapshot.VaultSnapshotStore
import egroden.obsidian.rag.vault.watcher.LocalVaultWatcher
import egroden.obsidian.rag.vault.watcher.VaultEventsDebouncer
import egroden.obsidian.rag.vault.watcher.VaultUpdateEvent
import egroden.obsidian.rag.vault.watcher.VaultWatcher
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.Executors

@Component
class VaultSyncService(
    @Value("\${vault.path}") private val vaultPath: String,
    @Value("\${vault.debounce.ms:750}") private val updatesDebounceMs: Long,
    private val snapshotStore: VaultSnapshotStore,
    private val vaultScanner: VaultScanner,
    private val indexingPipeline: FilesIndexingPipeline,
) {

    private val logger = LoggerFactory.getLogger(VaultSyncService::class.java)

    private val vaultRoot = Paths.get(vaultPath)

    @Volatile
    private var lastSnapshot: VaultSnapshot? = null

    @Volatile
    private var watcher: VaultWatcher? = null

    @Volatile
    private var debouncer: VaultEventsDebouncer? = null

    private val syncExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "vault-sync-executor")
    }

    @PostConstruct
    fun init() {
        syncExecutor.submit { fullReconcile("startup") }
    }

    @PreDestroy
    fun destroy() {
        watcher?.dispose()
        debouncer?.dispose()
        syncExecutor.shutdownNow()
    }

    private fun fullReconcile(reason: String) {
        logger.info("Full reconcile started (reason={})", reason)

        watcher?.dispose()
        debouncer?.dispose()

        val currentFiles = vaultScanner.scanMarkdownFiles(vaultRoot)
        val snapshot = lastSnapshot ?: snapshotStore.load() ?: VaultSnapshot(files = emptyMap())
        logger.info("Loaded snapshot: {}", snapshot)
        val diff = VaultSnapshotDiff.diff(snapshot, currentFiles)

        logger.info("Delete indexes: {}", diff.deleted)
        if (diff.deleted.isNotEmpty()) {
            indexingPipeline.deleteFiles(diff.deleted)
        }

        logger.info("Index files: {}", diff.createdOrUpdated.map { it.path })
        diff.createdOrUpdated
            .map { meta ->
                val abs = vaultRoot.resolve(meta.path)
                FileIndexingData(abs, meta.path)
            }
            .filter { Files.exists(it.absolutePath) }
            .toList()
            .let {
                if (it.isNotEmpty()) {
                    indexingPipeline.indexFiles(it)
                }
            }

        lastSnapshot = VaultSnapshot(files = currentFiles).apply {
            snapshotStore.save(this)
        }

        logger.info(
            "Full reconcile done (reason={}), indexed={}, deleted={}, total={}",
            reason, diff.createdOrUpdated.size, diff.deleted.size, snapshot.files.size
        )

        debouncer = VaultEventsDebouncer(updatesDebounceMs).apply {
            subscribe {
                syncExecutor.submit { applyBatchUpdates(it) }
            }
        }
        watcher = LocalVaultWatcher(vaultPath, vaultScanner).apply {
            start { updateEvent ->
                if (updateEvent is VaultUpdateEvent.OverflowUpdates) {
                    watcher?.dispose()
                    debouncer?.dispose()
                    syncExecutor.submit { fullReconcile("overflow") }
                } else {
                    debouncer?.push(updateEvent)
                }
            }
        }
    }

    private fun applyBatchUpdates(updates: List<VaultUpdateEvent>) {
        logger.info("Vault batch update evens: {}", updates)
        if (updates.isEmpty()) return

        val currentSnapshot = lastSnapshot
        if (currentSnapshot == null) {
            logger.info("No snapshot found, cannot apply batch updates")
            return
        }

        val currentFiles = currentSnapshot.files.toMutableMap()
        val deleted = mutableListOf<String>()
        val createdOrUpdated = mutableListOf<VaultFileMeta>()
        updates.forEach { update ->
            when (update) {
                is VaultUpdateEvent.MarkdownDocDeleted -> {
                    val key = update.path.toFileMetaKey(vaultRoot)
                    currentFiles.remove(key)
                    deleted.add(key)
                }

                is VaultUpdateEvent.MarkdownDocUpsert -> {
                    val (key, meta) = update.path.toFileMetaEntry(vaultRoot)
                    currentFiles[key] = meta
                    logger.info(
                        "Updated index: {}, lastModifiedMillis={}, size={}",
                        update.path, meta.lastModifiedMillis, meta.size
                    )
                    createdOrUpdated.add(meta)
                }

                is VaultUpdateEvent.OverflowUpdates -> throw IllegalStateException("Unexpected overflow event")
            }
        }
        lastSnapshot = VaultSnapshot(files = currentFiles).apply {
            logger.info("Batch updates save new snapshot: {}", this)
            snapshotStore.save(this)
        }

        if (deleted.isNotEmpty()) {
            indexingPipeline.deleteFiles(deleted)
        }

        if (createdOrUpdated.isNotEmpty()) {
            indexingPipeline.indexFiles(createdOrUpdated.map { FileIndexingData(vaultRoot.resolve(it.path), it.path) })
        }
    }
}