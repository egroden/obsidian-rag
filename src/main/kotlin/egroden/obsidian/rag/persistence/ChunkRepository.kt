package egroden.obsidian.rag.persistence

import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID

@Repository
class ChunkRepository(
    private val jdbcTemplate: JdbcTemplate
) {
    @Transactional
    fun saveChunksForPath(path: String, chunks: List<ChunkEntity>) {
        deleteByPath(path)
        if (chunks.isEmpty()) return

        jdbcTemplate.batchUpdate(INSERT_SQL, object : BatchPreparedStatementSetter {
            override fun setValues(ps: PreparedStatement, i: Int) {
                val chunk = chunks[i]
                ps.setObject(1, chunk.id)
                ps.setString(2, chunk.path)
                ps.setInt(3, chunk.chunkIndex)
                ps.setString(4, chunk.content)
            }
            override fun getBatchSize() = chunks.size
        })
    }

    fun deleteByPath(path: String): Int {
        return jdbcTemplate.update(PreparedStatementCreator { conn: Connection ->
            conn.prepareStatement(DELETE_BY_PATH_SQL).apply {
                setString(1, path)
            }
        })
    }

    fun deleteByPaths(paths: List<String>): Int {
        if (paths.isEmpty()) return 0

        return jdbcTemplate.update(PreparedStatementCreator { conn: Connection ->
            conn.prepareStatement(DELETE_BY_PATHS_SQL).apply {
                setArray(1, conn.createArrayOf("text", paths.toTypedArray()))
            }
        })
    }

    fun searchFullText(query: String, limit: Int = 20): List<ChunkSearchResult> {
        return jdbcTemplate.query(
            PreparedStatementCreator { conn: Connection ->
                conn.prepareStatement(SEARCH_SQL).apply {
                    setString(1, query)
                    setString(2, query)
                    setString(3, query)
                    setString(4, query)
                    setInt(5, limit)
                }
            },
            SearchResultRowMapper
        )
    }

    fun findByPath(path: String): List<ChunkEntity> {
        return jdbcTemplate.query(
            PreparedStatementCreator { conn: Connection ->
                conn.prepareStatement(FIND_BY_PATH_SQL).apply {
                    setString(1, path)
                }
            },
            ChunkEntityRowMapper
        )
    }

    private object ChunkEntityRowMapper : RowMapper<ChunkEntity> {
        override fun mapRow(rs: ResultSet, rowNum: Int): ChunkEntity {
            return ChunkEntity(
                id = rs.getObject("id", UUID::class.java),
                path = rs.getString("path"),
                chunkIndex = rs.getInt("chunk_index"),
                content = rs.getString("content"),
                createdAt = rs.getObject("created_at", java.time.OffsetDateTime::class.java),
                updatedAt = rs.getObject("updated_at", java.time.OffsetDateTime::class.java)
            )
        }
    }

    private object SearchResultRowMapper : RowMapper<ChunkSearchResult> {
        override fun mapRow(rs: ResultSet, rowNum: Int): ChunkSearchResult {
            return ChunkSearchResult(
                id = rs.getObject("id", UUID::class.java),
                path = rs.getString("path"),
                chunkIndex = rs.getInt("chunk_index"),
                content = rs.getString("content"),
                rank = rs.getFloat("rank")
            )
        }
    }

    companion object {
        private const val INSERT_SQL = "INSERT INTO markdown_chunks (id, path, chunk_index, content) VALUES (?, ?, ?, ?)"
        private const val DELETE_BY_PATH_SQL = "DELETE FROM markdown_chunks WHERE path = ?"
        private const val DELETE_BY_PATHS_SQL = "DELETE FROM markdown_chunks WHERE path = ANY(?)"
        private const val SEARCH_SQL = """
            SELECT
                id, path, chunk_index, content,
                ts_rank(content_tsv, plainto_tsquery('english', ?) || plainto_tsquery('russian', ?)) AS rank
            FROM markdown_chunks
            WHERE content_tsv @@ (plainto_tsquery('english', ?) || plainto_tsquery('russian', ?))
            ORDER BY rank DESC
            LIMIT ?
        """
        private const val FIND_BY_PATH_SQL = """
            SELECT id, path, chunk_index, content, created_at, updated_at
            FROM markdown_chunks
            WHERE path = ?
            ORDER BY chunk_index
        """
    }
}

data class ChunkSearchResult(
    val id: UUID,
    val path: String,
    val chunkIndex: Int,
    val content: String,
    val rank: Float
)

