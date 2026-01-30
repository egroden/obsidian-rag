package egroden.obsidian.rag.indexing

import java.nio.file.Path

data class FileIndexingData(
    val absolutePath: Path,
    val vaultRelativePath: String
)

interface FilesIndexingPipeline {
    fun indexFiles(data: List<FileIndexingData>)

    fun deleteFiles(vaultRelativePaths: List<String>)
}