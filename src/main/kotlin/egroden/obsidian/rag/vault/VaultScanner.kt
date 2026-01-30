package egroden.obsidian.rag.vault

import egroden.obsidian.rag.vault.snapshot.VaultFileMeta
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

@Component
class VaultScanner {

    fun scanVaultDirectories(vaultRoot: Path): List<Path> {
        return Files.walk(vaultRoot)
            .filter { Files.isDirectory(it) && it.fileName.toString() != ".obsidian" }
            .toList()
    }

    fun scanMarkdownFiles(vaultRoot: Path): Map<String, VaultFileMeta> {
        return Files.walk(vaultRoot)
            .filter { Files.isRegularFile(it) }
            .filter { it.extension == "md" }
            .filter { !isInsideObsidianFolder(vaultRoot, it) }
            .toList()
            .associate { it.toFileMetaEntry(vaultRoot) }
    }

    private fun isInsideObsidianFolder(vaultRoot: Path, abs: Path): Boolean {
        val rel = vaultRoot.relativize(abs.normalize())
        return rel.iterator().asSequence().any { it.toString() == ".obsidian" }
    }
}

fun Path.toFileMetaKey(vaultRoot: Path): String = vaultRoot.relativize(this.normalize()).toString()

fun Path.toFileMetaEntry(vaultRoot: Path): Pair<String, VaultFileMeta> {
    return toFileMetaKey(vaultRoot) to toFileMeta(vaultRoot)
}

fun Path.toFileMeta(vaultRoot: Path): VaultFileMeta {
    return VaultFileMeta(
        path = toFileMetaKey(vaultRoot),
        lastModifiedMillis = toFile().lastModified(),
        size = Files.size(this)
    )
}