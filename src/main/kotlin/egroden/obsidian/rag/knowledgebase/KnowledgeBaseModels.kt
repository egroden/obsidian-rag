package egroden.obsidian.rag.knowledgebase

data class QuestionResponse(
    val answer: String,
    val sources: List<SourceReference>,
    val modelUsed: String
)

data class SourceReference(
    val path: String,
    val chunkIndex: Int,
    val relevanceScore: Float,
    val excerpt: String
)
