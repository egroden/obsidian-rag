package egroden.obsidian.rag.persistence

import java.time.OffsetDateTime
import java.util.UUID

data class ChunkEntity(
    val id: UUID,
    val path: String,
    val chunkIndex: Int,
    val content: String,
    val createdAt: OffsetDateTime? = null,
    val updatedAt: OffsetDateTime? = null
)
