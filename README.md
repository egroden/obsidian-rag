# Obsidian RAG

A RAG (Retrieval-Augmented Generation) service for Obsidian vaults.

## Knowledge Base Service

The `KnowledgeBaseService` provides question-answering over your vault using RAG (Retrieval-Augmented Generation).

### How It Works

1. **Retrieval**: Uses `SearchService` with hybrid search to find the most relevant chunks for the question
2. **Context Building**: Assembles retrieved chunks into a context string with source references
3. **Generation**: Sends the question + context to Ollama LLM to generate an answer
4. **Response**: Returns the answer along with source references

### Configuration

```properties
spring.ai.ollama.chat.options.model=llama3.2
spring.ai.ollama.chat.options.temperature=0.7
knowledgebase.excerpt-length=200
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `spring.ai.ollama.chat.options.model` | llama3.2 | Ollama model for answer generation |
| `spring.ai.ollama.chat.options.temperature` | 0.7 | Controls response creativity (0-1) |
| `knowledgebase.excerpt-length` | 200 | Max characters for source excerpts in response |

### API

```
GET /api/kb/ask?question=<question>
```

Parameters:
- `question` (required): The question to ask about your vault

Response:
```json
{
  "answer": "Based on your notes...",
  "sources": [
    {
      "path": "notes/topic.md",
      "chunkIndex": 2,
      "relevanceScore": 0.85,
      "excerpt": "Preview of the source content..."
    }
  ],
  "modelUsed": "llama3.2"
}
```


## Chunking

The system uses token-based text chunking via Spring AI's `TokenTextSplitter` to split markdown files into smaller pieces for embedding and retrieval.

### How It Works

Chunking is a two-step process:

1. **Markdown Parsing**: `MarkdownDocumentReader` parses the file with configuration that:
   - Preserves code blocks
   - Preserves blockquotes
   - Creates document boundaries at horizontal rules

2. **Token Splitting**: `TokenTextSplitter` splits the parsed content into token-based chunks

Each chunk is stored with a sequential `chunkIndex` to preserve document order for reassembly.

### Configuration

```properties
chunker.token.chunk-size=800
chunker.token.min-chunk-size-chars=200
chunker.token.min-chunk-length-to-embed=50
chunker.token.max-num-chunks=1000
chunker.token.keep-separator=false
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `chunk-size` | 800 | Target chunk size in tokens |
| `min-chunk-size-chars` | 200 | Minimum character count for a chunk |
| `min-chunk-length-to-embed` | 50 | Minimum length required for embedding generation |
| `max-num-chunks` | 1000 | Maximum chunks allowed per document |
| `keep-separator` | false | Whether to preserve separators between chunks |

### Pipeline Integration

1. File is chunked via `MarkdownChunker.chunkFile()`
2. Each chunk becomes a `ChunkEntity` with sequential index
3. Chunks are persisted to PostgreSQL
4. Embeddings are generated for each chunk via Ollama
5. Embeddings are stored in pgvector for semantic search

## Vault Sync Service

The `VaultSyncService` keeps the search index synchronized with your Obsidian vault by detecting file changes and triggering re-indexing.

### How It Works

#### Startup (Full Reconcile)

On application startup, the service performs a full reconcile:

1. Scans all markdown files in the vault
2. Loads the previous snapshot (file metadata stored on disk)
3. Computes a diff to find created, updated, or deleted files
4. Deletes index entries for removed files
5. Re-indexes new or modified files
6. Saves the new snapshot
7. Starts the file watcher

#### Real-time Updates

After startup, the service watches for file changes:

1. **File Watcher** (`LocalVaultWatcher`): Monitors the vault directory for file system events
2. **Debouncer** (`VaultEventsDebouncer`): Batches rapid changes to avoid redundant indexing
3. **Batch Processing**: Applies updates in batches (create/update/delete)
4. **Overflow Handling**: If too many events occur, falls back to full reconcile

### Change Detection

Files are considered changed when either:
- `lastModifiedMillis` differs from snapshot
- `size` differs from snapshot

### Configuration

```properties
vault.path=/path/to/obsidian/vault
vault.debounce.ms=750
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `vault.path` | (required) | Absolute path to your Obsidian vault |
| `vault.debounce.ms` | 750 | Delay in ms to batch rapid file changes |

## Search Service

The `SearchService` provides three search modes for querying indexed markdown chunks.

### Search Modes

#### 1. FTS (Full-Text Search)

Uses PostgreSQL's built-in full-text search via `chunkRepository.searchFullText()`.

- Searches for exact/stemmed word matches in chunk content
- Good for finding specific terms
- Uses PostgreSQL's `ts_rank` for relevance scoring

#### 2. Semantic Search

Uses vector embeddings via `vectorStore.similaritySearch()`.

- Finds conceptually similar content (not just keyword matches)
- Example: searching "AI assistant" might find chunks about "chatbot" or "LLM"
- Uses Ollama with `nomic-embed-text` model for embeddings
- Configurable similarity threshold to filter low-relevance results

#### 3. Hybrid Search (default)

Combines FTS and semantic search for best results:

1. Runs both FTS and semantic search (each with `limit * 2`)
2. Normalizes scores to 0-1 range using min-max normalization
3. Combines scores with configurable weights:
   - FTS: 30% (default)
   - Semantic: 70% (default)
4. Returns top `limit` results sorted by combined score

### Scoring Algorithms

#### ts_rank (PostgreSQL Full-Text Search)

`ts_rank` is a PostgreSQL function that scores how well a document matches a text query.

It considers:
- **Term frequency**: How often query words appear in the document
- **Document length**: Normalizes for shorter vs longer documents
- **Word proximity**: How close matching words are to each other

Returns a float (usually 0.0 to ~0.1) - higher means better match.

#### Cosine Similarity (Vector Search)

Measures the angle between two vectors in high-dimensional space.

```
similarity = cos(θ) = (A · B) / (||A|| × ||B||)
```

How it works:
1. Text is converted to a vector (array of 768 numbers with `nomic-embed-text`)
2. Similar meanings → vectors point in similar directions
3. Cosine of angle between them = similarity score

Score range:
- `1.0` = identical direction (same meaning)
- `0.0` = perpendicular (unrelated)
- `-1.0` = opposite direction (opposite meaning)

In practice, text embeddings usually range 0.0 to 1.0.

#### Key Differences

| | ts_rank | Cosine Similarity |
|---|---------|-------------------|
| Matches | Exact words (stemmed) | Conceptual meaning |
| "AI assistant" finds "chatbot" | No | Yes |
| "claude" finds "Claude" | Yes | Yes |
| Speed | Fast (index lookup) | Slower (vector math) |

### Score Normalization

The hybrid search uses **min-max normalization** to combine FTS and semantic scores fairly.

#### Why normalize?

FTS and semantic search return scores on different scales:
- **FTS** (`ts_rank`): typically 0.0 to ~0.1
- **Semantic** (cosine similarity): typically 0.0 to 1.0

Without normalization, semantic scores would dominate even with lower weight.

#### How it works

```
normalized = (score - min) / (max - min)
```

Example with FTS scores `[0.02, 0.05, 0.08]`:
- min = 0.02, max = 0.08, range = 0.06
- 0.02 → (0.02 - 0.02) / 0.06 = **0.0**
- 0.05 → (0.05 - 0.02) / 0.06 = **0.5**
- 0.08 → (0.08 - 0.02) / 0.06 = **1.0**

Now both FTS and semantic scores are 0-1, making the weighted combination fair.

### Configuration

```properties
# Search weights for hybrid mode
search.hybrid.fts-weight=0.3
search.hybrid.semantic-weight=0.7
```

### API

```
GET /api/search?query=<query>&limit=<limit>
```

Parameters:
- `query` (required): Search query string
- `limit` (optional): Maximum results to return (default: `20`)
