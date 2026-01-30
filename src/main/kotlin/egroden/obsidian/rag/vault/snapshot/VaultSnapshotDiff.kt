package egroden.obsidian.rag.vault.snapshot

data class VaultDiff(
    val createdOrUpdated: List<VaultFileMeta>,
    val deleted: List<String>, // paths
)

object VaultSnapshotDiff {
    fun diff(prev: VaultSnapshot?, currentFiles: Map<String, VaultFileMeta>): VaultDiff {
        if (prev == null) return VaultDiff(currentFiles.values.toList(), emptyList())
        val prevFiles = prev.files

        val createdOrUpdated = currentFiles.values.filter { cur ->
            val old = prevFiles[cur.path]
            old == null || old.lastModifiedMillis != cur.lastModifiedMillis || old.size != cur.size
        }

        val deleted = prevFiles.keys.filter { it !in currentFiles.keys }

        return VaultDiff(createdOrUpdated, deleted)
    }
}