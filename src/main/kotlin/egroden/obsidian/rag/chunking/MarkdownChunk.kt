package egroden.obsidian.rag.chunking

import java.nio.file.Path

data class MarkdownChunk(
    val path: Path,
    val text: String
)
