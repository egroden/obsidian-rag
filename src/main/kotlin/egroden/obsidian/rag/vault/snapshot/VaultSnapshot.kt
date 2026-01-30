package egroden.obsidian.rag.vault.snapshot

data class VaultFileMeta(
    val path: String,
    val lastModifiedMillis: Long,
    val size: Long,
)

data class VaultSnapshot(
    val createdAtMillis: Long = System.currentTimeMillis(),
    val files: Map<String, VaultFileMeta> = emptyMap()
)