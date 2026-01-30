package egroden.obsidian.rag.vault.snapshot

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

@Component
class FileVaultSnapshotStore(
    @Value("\${vault.snapshot.path}")
    private val snapshotFilePath: String,
) : VaultSnapshotStore {

    private val logger = LoggerFactory.getLogger(FileVaultSnapshotStore::class.java)
    private val mapper: ObjectMapper = jacksonObjectMapper()

    private val snapshotPath: Path by lazy { Paths.get(snapshotFilePath) }

    override fun load(): VaultSnapshot? {
        return try {
            if (!Files.exists(snapshotPath)) return null
            Files.newBufferedReader(snapshotPath).use { reader ->
                mapper.readValue<VaultSnapshot>(reader)
            }
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to load snapshot from {}", snapshotPath, e)
            null
        }
    }

    override fun save(snapshot: VaultSnapshot) {
        try {
            Files.createDirectories(snapshotPath.parent ?: Paths.get("."))
            val tmp = snapshotPath.resolveSibling(snapshotPath.fileName.toString() + ".tmp")
            Files.newBufferedWriter(tmp).use { writer ->
                mapper.writerWithDefaultPrettyPrinter().writeValue(writer, snapshot)
            }
            Files.move(tmp, snapshotPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to save snapshot to {}", snapshotPath, e)
        }
    }
}