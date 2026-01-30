package egroden.obsidian.rag.indexing

import egroden.obsidian.rag.chunking.MarkdownChunker
import egroden.obsidian.rag.persistence.ChunkEntity
import egroden.obsidian.rag.persistence.ChunkRepository
import jakarta.annotation.PreDestroy
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ForkJoinPool
import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import kotlin.coroutines.cancellation.CancellationException

@Component
class MarkdownIndexingPipeline(
    private val chunker: MarkdownChunker,
    private val chunkRepository: ChunkRepository,
    private val vectorStore: VectorStore,
    @Value("\${indexing.parallelism:4}") private val parallelism: Int
) : FilesIndexingPipeline {

    private val logger = LoggerFactory.getLogger(MarkdownIndexingPipeline::class.java)
    private val forkJoinPool = ForkJoinPool(parallelism)

    override fun indexFiles(data: List<FileIndexingData>) {
        if (data.isEmpty()) return
        logger.info("Indexing {} files with parallelism={}", data.size, parallelism)

        val futures = data.map { fileData ->
            forkJoinPool.submit(Callable { processFile(fileData) })
        }

        futures.forEach { future ->
            try {
                future.get()
            } catch (e: InterruptedException) {
                throw e
            } catch (e: CancellationException) {
                logger.debug("Indexing cancelled")
            } catch (e: ExecutionException) {
                logger.error("Failed to process file", e)
            }
        }
    }

    private fun processFile(fileData: FileIndexingData) {
        val chunks = chunker.chunkFile(fileData.absolutePath)
        logger.debug("File {} produced {} chunks", fileData.vaultRelativePath, chunks.size)

        val entities = chunks.mapIndexed { index, chunk ->
            ChunkEntity(
                id = UUID.randomUUID(),
                path = fileData.vaultRelativePath,
                chunkIndex = index,
                content = chunk.text
            )
        }

        chunkRepository.saveChunksForPath(fileData.vaultRelativePath, entities)
        logger.info("Indexed file: {} ({} chunks)", fileData.vaultRelativePath, entities.size)

        generateEmbeddings(entities)
    }

    private fun generateEmbeddings(chunks: List<ChunkEntity>) {
        val path = chunks.firstOrNull()?.path ?: return
        deleteFromVectorStore(path)
        if (chunks.isEmpty()) return
        val documents = chunks.map { chunk ->
            Document(
                chunk.id.toString(),
                chunk.content,
                mapOf(
                    METADATA_SOURCE_PATH to path,
                    METADATA_CHUNK_ID to chunk.id.toString(),
                    METADATA_CHUNK_INDEX to chunk.chunkIndex
                )
            )
        }

        vectorStore.add(documents)
        logger.debug("Added {} documents to vector store for path: {}", documents.size, path)
    }

    override fun deleteFiles(vaultRelativePaths: List<String>) {
        if (vaultRelativePaths.isEmpty()) return
        logger.info("Deleting chunks for {} files", vaultRelativePaths.size)

        vaultRelativePaths.forEach { path ->
            deleteFromVectorStore(path)
        }
        val deletedCount = chunkRepository.deleteByPaths(vaultRelativePaths)
        logger.info("Deleted {} chunks for files: {}", deletedCount, vaultRelativePaths)
    }

    private fun deleteFromVectorStore(path: String) {
        try {
            vectorStore.delete("$METADATA_SOURCE_PATH == '$path'")
            logger.debug("Deleted documents from vector store for path: {}", path)
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to delete documents from vector store for path: {}", path, e)
        }
    }

    @PreDestroy
    fun shutdown() {
        forkJoinPool.shutdown()
    }

    companion object {
        const val METADATA_SOURCE_PATH = "source_path"
        const val METADATA_CHUNK_ID = "chunk_id"
        const val METADATA_CHUNK_INDEX = "chunk_index"
    }
}
