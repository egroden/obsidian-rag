package egroden.obsidian.rag.knowledgebase

import egroden.obsidian.rag.search.SearchMode
import egroden.obsidian.rag.search.SearchResult
import egroden.obsidian.rag.search.SearchService
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class KnowledgeBaseService(
    private val searchService: SearchService,
    private val chatClient: ChatClient,
    @Value("\${spring.ai.ollama.chat.options.model:llama3.2}") private val modelName: String,
    @Value("\${knowledgebase.excerpt-length:200}") private val excerptLength: Int
) {

    fun answer(question: String): QuestionResponse {
        val searchResults = searchService.search(
            query = question,
            mode = SearchMode.HYBRID,
            limit = 5
        )

        val context = buildContext(searchResults)
        val answer = generateAnswer(question, context)

        val sources = searchResults.map { result ->
            SourceReference(
                path = result.path,
                chunkIndex = result.chunkIndex,
                relevanceScore = result.score,
                excerpt = truncateContent(result.content, excerptLength)
            )
        }

        return QuestionResponse(
            answer = answer,
            sources = sources,
            modelUsed = modelName
        )
    }

    private fun buildContext(results: List<SearchResult>): String {
        return results.mapIndexed { index, result ->
            """
            [Source ${index + 1}: ${result.path}]
            ${result.content}
            """.trimIndent()
        }.joinToString("\n\n---\n\n")
    }

    private fun generateAnswer(question: String, context: String): String {
        val prompt = """
            You are a helpful assistant that answers questions based on the user's personal knowledge base (Obsidian vault).

            Use ONLY the following context to answer the question. If the context doesn't contain enough information to answer the question, say so clearly.

            Context:
            $context

            Question: $question

            Answer:
        """.trimIndent()

        return chatClient.prompt()
            .user(prompt)
            .call()
            .content() ?: ""
    }

    private fun truncateContent(content: String, maxLength: Int): String {
        return if (content.length <= maxLength) {
            content
        } else {
            content.take(maxLength - 3) + "..."
        }
    }
}
