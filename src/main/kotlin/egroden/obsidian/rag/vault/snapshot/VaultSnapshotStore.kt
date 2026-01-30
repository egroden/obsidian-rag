package egroden.obsidian.rag.vault.snapshot

interface VaultSnapshotStore {
    fun load(): VaultSnapshot?
    fun save(snapshot: VaultSnapshot)
}