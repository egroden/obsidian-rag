package egroden.obsidian.rag.search

import egroden.obsidian.rag.indexing.MarkdownIndexingPipeline.Companion.METADATA_CHUNK_ID
import egroden.obsidian.rag.indexing.MarkdownIndexingPipeline.Companion.METADATA_CHUNK_INDEX
import egroden.obsidian.rag.indexing.MarkdownIndexingPipeline.Companion.METADATA_SOURCE_PATH
import egroden.obsidian.rag.persistence.ChunkRepository
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.UUID

enum class SearchMode {
    FTS,
    SEMANTIC,
    HYBRID
}

@Service
class SearchService(
    private val chunkRepository: ChunkRepository,
    private val vectorStore: VectorStore,
    @Value("\${search.hybrid.fts-weight:0.3}") private val ftsWeight: Float,
    @Value("\${search.hybrid.semantic-weight:0.7}") private val semanticWeight: Float
) {

    fun search(query: String, mode: SearchMode, limit: Int = 20): List<SearchResult> {
        return when (mode) {
            SearchMode.FTS -> searchFts(query, limit)
            SearchMode.SEMANTIC -> searchSemantic(query, limit)
            SearchMode.HYBRID -> searchHybrid(query, limit)
        }
    }

    private fun searchFts(query: String, limit: Int): List<SearchResult> {
        return chunkRepository.searchFullText(query, limit).map { result ->
            SearchResult(
                chunkId = result.id,
                path = result.path,
                chunkIndex = result.chunkIndex,
                content = result.content,
                score = result.rank
            )
        }
    }

    private fun searchSemantic(query: String, limit: Int): List<SearchResult> {
        val searchRequest = SearchRequest.builder()
            .query(query)
            .topK(limit)
            .similarityThreshold(0.7)
            .build()

        return vectorStore.similaritySearch(searchRequest).mapNotNull { doc ->
            val chunkIdStr = doc.metadata[METADATA_CHUNK_ID] as? String ?: return@mapNotNull null
            val path = doc.metadata[METADATA_SOURCE_PATH] as? String ?: return@mapNotNull null
            val chunkIndex = (doc.metadata[METADATA_CHUNK_INDEX] as? Number)?.toInt() ?: 0

            SearchResult(
                chunkId = UUID.fromString(chunkIdStr),
                path = path,
                chunkIndex = chunkIndex,
                content = doc.text ?: "",
                score = doc.score?.toFloat() ?: 0f
            )
        }
    }

    private fun searchHybrid(query: String, limit: Int): List<SearchResult> {
        val ftsResults = searchFts(query, limit * 2)
        val semanticResults = searchSemantic(query, limit * 2)

        val ftsScores = normalizeScores(ftsResults)
        val semanticScores = normalizeScores(semanticResults)

        val combinedScores = mutableMapOf<UUID, HybridScore>()

        for (result in ftsResults) {
            val normalizedFts = ftsScores[result.chunkId] ?: 0f
            combinedScores[result.chunkId] = HybridScore(
                result = result,
                ftsScore = normalizedFts,
                semanticScore = 0f
            )
        }

        for (result in semanticResults) {
            val normalizedSemantic = semanticScores[result.chunkId] ?: 0f
            val existing = combinedScores[result.chunkId]
            if (existing != null) {
                combinedScores[result.chunkId] = existing.copy(semanticScore = normalizedSemantic)
            } else {
                combinedScores[result.chunkId] = HybridScore(
                    result = result,
                    ftsScore = 0f,
                    semanticScore = normalizedSemantic
                )
            }
        }

        return combinedScores.values
            .map { hybrid ->
                hybrid.result.copy(
                    score = hybrid.ftsScore * ftsWeight + hybrid.semanticScore * semanticWeight
                )
            }
            .sortedByDescending { it.score }
            .take(limit)
    }

    private fun normalizeScores(results: List<SearchResult>): Map<UUID, Float> {
        if (results.isEmpty()) return emptyMap()

        val maxScore = results.maxOf { it.score }
        val minScore = results.minOf { it.score }
        val range = maxScore - minScore

        return if (range > 0) {
            results.associate { it.chunkId to (it.score - minScore) / range }
        } else {
            results.associate { it.chunkId to 1f }
        }
    }

    private data class HybridScore(
        val result: SearchResult,
        val ftsScore: Float,
        val semanticScore: Float
    )
}

data class SearchResult(
    val chunkId: UUID,
    val path: String,
    val chunkIndex: Int,
    val content: String,
    val score: Float
)
