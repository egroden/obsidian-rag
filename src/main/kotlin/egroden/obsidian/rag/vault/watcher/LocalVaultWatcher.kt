package egroden.obsidian.rag.vault.watcher

import egroden.obsidian.rag.vault.VaultScanner
import org.slf4j.LoggerFactory
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.extension
import kotlin.time.Duration.Companion.seconds

class LocalVaultWatcher(
    vaultPath: String,
    private val vaultScanner: VaultScanner
): VaultWatcher {

    private val logger = LoggerFactory.getLogger(LocalVaultWatcher::class.java)

    private val vaultRoot = Paths.get(vaultPath)

    private val watcherLoopExecutor = Executors.newSingleThreadScheduledExecutor()

    private val watcher: WatchService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        FileSystems.getDefault().newWatchService()
    }

    private val watcherStarted = AtomicBoolean(false)
    private val disposed = AtomicBoolean(false)

    @Volatile
    private var onVaultEvent: ((VaultUpdateEvent) -> Unit)? = null

    override fun start(subscriber: (VaultUpdateEvent) -> Unit) {
        if (disposed.get()) {
            throw IllegalStateException("Watcher already disposed")
        }
        if (watcherStarted.compareAndSet(false, true)) {
            logger.debug("Watcher started")
            this.onVaultEvent = subscriber
            watcherLoopExecutor.submit {
                vaultScanner.scanVaultDirectories(vaultRoot).forEach {
                    it.register(
                        watcher,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.OVERFLOW
                    )
                }
                watchLoop(watcher)
            }
        }
    }

    override fun dispose() {
        if (disposed.compareAndSet(false, true)) {
            onVaultEvent = null
            if (watcherStarted.get()) {
                watcher.close()
                watcherLoopExecutor.shutdownNow()
            }
        }
    }

    private fun watchLoop(watcher: WatchService) {
        while (!Thread.currentThread().isInterrupted || !disposed.get()) {
            val key: WatchKey
            try {
                key = watcher.take()
            } catch (e: ClosedWatchServiceException) {
                logger.debug("Watch loop completed", e)
                return
            } catch (e: InterruptedException) {
                logger.debug("Watch loop completed", e)
                throw e
            }
            try {
                for (event in key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        handleUpdatesOverflow()
                        key.reset()
                        logger.debug("Watch loop completed", IllegalStateException("Overflow updates"))
                        return
                    }

                    val path = event.context() as Path
                    if (path.isMarkdownFile()) {
                        handleMarkdownFileChange(key, event, path)
                    }
                }
                key.reset()
            } catch (e: Exception) {
                logger.error("Error in watch loop", e)
            }
        }
    }

    private fun handleMarkdownFileChange(key: WatchKey, event: WatchEvent<*>, markdownFile: Path) {
        val dir = vaultRoot.resolve(key.watchable() as Path)
        val changedFilePath = dir.resolve(markdownFile)
        logger.info("Vault markdown doc updated: {}", changedFilePath)
        when (event.kind()) {
            StandardWatchEventKinds.ENTRY_CREATE -> onVaultEvent?.invoke(VaultUpdateEvent.MarkdownDocUpsert(changedFilePath, System.currentTimeMillis().seconds))
            StandardWatchEventKinds.ENTRY_MODIFY -> onVaultEvent?.invoke(VaultUpdateEvent.MarkdownDocUpsert(changedFilePath, System.currentTimeMillis().seconds))
            StandardWatchEventKinds.ENTRY_DELETE -> onVaultEvent?.invoke(VaultUpdateEvent.MarkdownDocDeleted(changedFilePath, System.currentTimeMillis().seconds))
        }
    }

    private fun handleUpdatesOverflow() {
        onVaultEvent?.invoke(VaultUpdateEvent.OverflowUpdates(System.currentTimeMillis().seconds))
    }

    private fun Path.isMarkdownFile() = extension == "md"
}