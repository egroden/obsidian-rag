package egroden.obsidian.rag.chunking

import org.springframework.ai.reader.markdown.MarkdownDocumentReader
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig
import org.springframework.ai.transformer.splitter.TokenTextSplitter
import org.springframework.core.io.FileSystemResource
import org.springframework.stereotype.Component
import org.springframework.beans.factory.annotation.Value
import java.nio.file.Path

@Component
class SpringAIMarkdownChunker(
    @Value("\${chunker.token.chunk-size:800}") private val chunkSize: Int,
    @Value("\${chunker.token.min-chunk-size-chars:200}") private val minChunkSizeChars: Int,
    @Value("\${chunker.token.min-chunk-length-to-embed:50}") private val minChunkLengthToEmbed: Int,
    @Value("\${chunker.token.max-num-chunks:1000}") private val maxNumChunks: Int,
    @Value("\${chunker.token.keep-separator:false}") private val keepSeparator: Boolean
) : MarkdownChunker {

    private val splitter: TokenTextSplitter = buildSplitter()

    override fun chunkFile(path: Path): List<MarkdownChunk> {
        val reader = MarkdownDocumentReader(FileSystemResource(path.toFile()), DEFAULT_MARKDOWN_CONFIG)
        return splitter.apply(reader.get())
            .mapNotNull { document ->
                val text = document.text?.trim() ?: return@mapNotNull null
                MarkdownChunk(path = path, text = text)
            }
    }

    private fun buildSplitter(): TokenTextSplitter {
        return TokenTextSplitter.builder()
            .withChunkSize(chunkSize)
            .withMinChunkSizeChars(minChunkSizeChars)
            .withMinChunkLengthToEmbed(minChunkLengthToEmbed)
            .withMaxNumChunks(maxNumChunks)
            .withKeepSeparator(keepSeparator)
            .build()
    }

    companion object {
        private val DEFAULT_MARKDOWN_CONFIG: MarkdownDocumentReaderConfig =
            MarkdownDocumentReaderConfig.builder()
                .withHorizontalRuleCreateDocument(true)
                .withIncludeCodeBlock(true)
                .withIncludeBlockquote(true)
                .build()
    }
}
