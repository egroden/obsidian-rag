package egroden.obsidian.rag.chunking

import java.nio.file.Path

interface MarkdownChunker {
    fun chunkFile(path: Path): List<MarkdownChunk>
}