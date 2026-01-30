package egroden.obsidian.rag.vault.watcher

import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class VaultEventsDebouncer(private val debounceMs: Long) {
    private val logger = LoggerFactory.getLogger(VaultEventsDebouncer::class.java)

    private val lock = ReentrantLock()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    private var scheduled: ScheduledFuture<*>? = null

    private val pending = LinkedHashMap<String, VaultUpdateEvent>()

    private var subscribers = CopyOnWriteArraySet<(List<VaultUpdateEvent>) -> Unit>()

    private val disposed = AtomicBoolean(false)

    fun subscribe(subscriber: (List<VaultUpdateEvent>) -> Unit) {
        subscribers.add(subscriber)
    }

    fun unsubscribe(subscriber: (List<VaultUpdateEvent>) -> Unit) {
        subscribers.remove(subscriber)
    }

    fun push(vaultUpdateEvent: VaultUpdateEvent) {
        if (disposed.get()) return
        val key = when(vaultUpdateEvent) {
            is VaultUpdateEvent.MarkdownDocUpsert -> vaultUpdateEvent.path.toString()
            is VaultUpdateEvent.MarkdownDocDeleted -> vaultUpdateEvent.path.toString()
            is VaultUpdateEvent.OverflowUpdates -> {
                logger.warn("OverflowUpdates passed into debouncer. Prefer handling it outside.")
                return
            }
        }

        lock.withLock {
            pending[key] = vaultUpdateEvent

            scheduled?.cancel(true)
            scheduled = scheduler.schedule(
                { flushSafely() },
                debounceMs,
                TimeUnit.MILLISECONDS
            )
        }
    }

    fun clearEvents() = lock.withLock {
        pending.clear()
        scheduled?.cancel(true)
        scheduled = null
    }

    fun dispose() {
        if (disposed.compareAndSet(false, true)) {
            clearEvents()
            subscribers.clear()
            scheduler.shutdownNow()
        }
    }

    private fun flushSafely() {
        val batch: List<VaultUpdateEvent> = lock.withLock {
            if (pending.isEmpty()) return
            val out = pending.values.toList()
            pending.clear()
            scheduled = null
            out
        }

        subscribers.forEach { it(batch) }
    }
}