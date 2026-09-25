package com.codeagent.rag;

import com.codeagent.rag.embedding.EmbeddingSpaceDescriptor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public final class SqliteRetrievalIndex implements AutoCloseable {
    public static final int SCHEMA_VERSION = 2;

    private final Connection connection;
    private final Path legacyDatabase;

    public SqliteRetrievalIndex(Path database) throws SQLException {
        this(database, database.toAbsolutePath().normalize().resolveSibling("codebase.db"));
    }

    public SqliteRetrievalIndex(Path database, Path legacyDatabase) throws SQLException {
        Path normalized = database.toAbsolutePath().normalize();
        try {
            Path parent = normalized.getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (Exception e) {
            throw new SQLException("Unable to create retrieval index directory", e);
        }
        this.legacyDatabase = legacyDatabase == null ? null : legacyDatabase.toAbsolutePath().normalize();
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + normalized);
        configureConnection();
        initializeSchema();
    }

    public Optional<FileSnapshot> findFile(Path projectRoot, Path relativePath) throws SQLException {
        String sql = """
                SELECT file_path, content_hash, size_bytes, modified_millis, language, index_status, last_error
                FROM indexed_files_v2 WHERE project_path=? AND file_path=?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectKey(projectRoot));
            statement.setString(2, relativeKey(relativePath));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new FileSnapshot(
                        result.getString("file_path"), result.getString("content_hash"),
                        result.getLong("size_bytes"), result.getLong("modified_millis"),
                        result.getString("language"), result.getString("index_status"),
                        result.getString("last_error")));
            }
        }
    }

    public List<FileSnapshot> listFiles(Path projectRoot) throws SQLException {
        String sql = """
                SELECT file_path, content_hash, size_bytes, modified_millis, language, index_status, last_error
                FROM indexed_files_v2 WHERE project_path=? ORDER BY file_path
                """;
        List<FileSnapshot> files = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectKey(projectRoot));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    files.add(new FileSnapshot(result.getString("file_path"),
                            result.getString("content_hash"), result.getLong("size_bytes"),
                            result.getLong("modified_millis"), result.getString("language"),
                            result.getString("index_status"), result.getString("last_error")));
                }
            }
        }
        return List.copyOf(files);
    }

    public List<IndexedChunk> listChunks(Path projectRoot, Path relativePath) throws SQLException {
        String sql = """
                SELECT start_line,end_line,chunk_type,symbol,symbol_id,content,search_terms,content_hash
                FROM code_chunks_v2 WHERE project_path=? AND file_path=? ORDER BY start_line,end_line,id
                """;
        List<IndexedChunk> chunks = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectKey(projectRoot));
            statement.setString(2, relativeKey(relativePath));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    chunks.add(new IndexedChunk(result.getInt("start_line"), result.getInt("end_line"),
                            result.getString("chunk_type"), result.getString("symbol"),
                            result.getString("symbol_id"), result.getString("content"),
                            result.getString("search_terms"), result.getString("content_hash")));
                }
            }
        }
        return List.copyOf(chunks);
    }

    public List<RepositorySymbol> listSymbols(Path projectRoot) throws SQLException {
        String sql = """
                SELECT file_path,symbol_id,qualified_name,simple_name,signature,symbol_kind,start_line,end_line
                FROM code_symbols_v2 WHERE project_path=?
                ORDER BY file_path,start_line,qualified_name,signature
                """;
        List<RepositorySymbol> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectKey(projectRoot));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new RepositorySymbol(rows.getString("file_path"),
                        rows.getString("symbol_id"), rows.getString("qualified_name"),
                        rows.getString("simple_name"), rows.getString("signature"),
                        rows.getString("symbol_kind"), rows.getInt("start_line"), rows.getInt("end_line")));
            }
        }
        return List.copyOf(result);
    }

    public List<RepositoryRelation> listRelations(Path projectRoot) throws SQLException {
        String sql = """
                SELECT file_path,from_symbol_id,to_symbol_id,target_text,relation_type,line_number
                FROM code_relations_v2 WHERE project_path=? ORDER BY file_path,line_number,id
                """;
        List<RepositoryRelation> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectKey(projectRoot));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new RepositoryRelation(rows.getString("file_path"),
                        rows.getString("from_symbol_id"), rows.getString("to_symbol_id"),
                        rows.getString("target_text"), rows.getString("relation_type"),
                        rows.getInt("line_number")));
            }
        }
        return List.copyOf(result);
    }

    public boolean hasCompleteEmbeddings(Path projectRoot, Path relativePath,
            String embeddingSpaceId, int expectedCount) throws SQLException {
        String sql = """
                SELECT COUNT(*) FROM chunk_embeddings_v2 e JOIN code_chunks_v2 c ON c.id=e.chunk_id
                WHERE c.project_path=? AND c.file_path=? AND e.embedding_space_id=?
                  AND e.source_content_hash=c.content_hash
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectKey(projectRoot));
            statement.setString(2, relativeKey(relativePath));
            statement.setString(3, embeddingSpaceId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) == expectedCount;
            }
        }
    }

    public void clearProject(Path projectRoot) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM indexed_files_v2 WHERE project_path=?")) {
            statement.setString(1, projectKey(projectRoot));
            statement.executeUpdate();
        }
    }

    public void replaceLexicalFile(FileIndexBatch batch) throws SQLException {
        String project = projectKey(batch.projectRoot());
        String file = relativeKey(batch.relativePath());
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            upsertFile(batch, project, file);
            deleteFileContents(project, file);
            insertSymbols(batch.symbols(), project, file);
            insertChunks(batch.chunks(), project, file);
            insertRelations(batch.relations(), project, file);
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    public void replaceFileEmbeddings(FileEmbeddingBatch batch) throws SQLException {
        String project = projectKey(batch.projectRoot());
        String file = relativeKey(batch.relativePath());
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            upsertEmbeddingSpace(batch.space());
            try (PreparedStatement delete = connection.prepareStatement("""
                    DELETE FROM chunk_embeddings_v2
                    WHERE embedding_space_id=? AND chunk_id IN (
                        SELECT id FROM code_chunks_v2 WHERE project_path=? AND file_path=?
                    )
                    """)) {
                delete.setString(1, batch.space().embeddingSpaceId());
                delete.setString(2, project);
                delete.setString(3, file);
                delete.executeUpdate();
            }
            for (ChunkEmbedding embedding : batch.embeddings()) {
                long chunkId = findChunkId(project, file, embedding);
                insertEmbedding(chunkId, batch.space(), embedding);
            }
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    public void deleteFile(Path projectRoot, Path relativePath) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM indexed_files_v2 WHERE project_path=? AND file_path=?")) {
            statement.setString(1, projectKey(projectRoot));
            statement.setString(2, relativeKey(relativePath));
            statement.executeUpdate();
        }
    }

    public List<RetrievalCandidate> searchTerms(
            Path projectRoot, String normalizedQuery, int limit) throws SQLException {
        String match = ftsMatch(normalizedQuery);
        if (match.isBlank() || limit <= 0) return List.of();
        String sql = """
                SELECT c.id, c.file_path, c.start_line, c.end_line, c.chunk_type,
                       c.symbol, c.symbol_id, c.content, bm25(code_chunks_terms_fts_v2) AS score
                FROM code_chunks_terms_fts_v2
                JOIN code_chunks_v2 c ON c.id=code_chunks_terms_fts_v2.rowid
                WHERE c.project_path=? AND code_chunks_terms_fts_v2 MATCH ?
                ORDER BY score ASC, c.file_path ASC, c.start_line ASC LIMIT ?
                """;
        return searchCandidates(sql, statement -> {
            statement.setString(1, projectKey(projectRoot));
            statement.setString(2, match);
            statement.setInt(3, limit);
        }, true);
    }

    public List<RetrievalCandidate> searchTrigram(
            Path projectRoot, String query, int limit) throws SQLException {
        if (query == null || query.isBlank() || limit <= 0) return List.of();
        int codePoints = query.codePointCount(0, query.length());
        if (codePoints < 3) {
            String sql = """
                    SELECT id, file_path, start_line, end_line, chunk_type, symbol, symbol_id, content, 0.0 AS score
                    FROM code_chunks_v2
                    WHERE project_path=? AND (lower(symbol) LIKE ? OR lower(search_terms) LIKE ?)
                    ORDER BY file_path ASC, start_line ASC LIMIT ?
                    """;
            String pattern = "%" + escapeLike(query.toLowerCase(Locale.ROOT)) + "%";
            return searchCandidates(sql, statement -> {
                statement.setString(1, projectKey(projectRoot));
                statement.setString(2, pattern);
                statement.setString(3, pattern);
                statement.setInt(4, limit);
            }, false);
        }
        String sql = """
                SELECT c.id, c.file_path, c.start_line, c.end_line, c.chunk_type,
                       c.symbol, c.symbol_id, c.content, bm25(code_chunks_trigram_fts_v2) AS score
                FROM code_chunks_trigram_fts_v2
                JOIN code_chunks_v2 c ON c.id=code_chunks_trigram_fts_v2.rowid
                WHERE c.project_path=? AND code_chunks_trigram_fts_v2 MATCH ?
                ORDER BY score ASC, c.file_path ASC, c.start_line ASC LIMIT ?
                """;
        return searchCandidates(sql, statement -> {
            statement.setString(1, projectKey(projectRoot));
            statement.setString(2, quoteFts(query));
            statement.setInt(3, limit);
        }, true);
    }

    public List<RetrievalCandidate> searchSymbols(
            Path projectRoot, String query, int limit) throws SQLException {
        if (query == null || query.isBlank() || limit <= 0) return List.of();
        String sql = """
                SELECT COALESCE(c.id, 0) AS id, s.file_path, s.start_line, s.end_line,
                       lower(s.symbol_kind) AS chunk_type, s.simple_name AS symbol,
                       s.symbol_id, COALESCE(c.content, '') AS content,
                       CASE WHEN s.simple_name=? THEN 0.0
                            WHEN lower(s.simple_name)=lower(?) THEN 0.1
                            WHEN lower(s.simple_name) LIKE lower(?) THEN 0.2 ELSE 0.3 END AS score
                FROM code_symbols_v2 s
                LEFT JOIN code_chunks_v2 c ON c.project_path=s.project_path AND c.file_path=s.file_path
                     AND c.symbol_id=s.symbol_id
                WHERE s.project_path=? AND lower(s.simple_name) LIKE lower(?)
                ORDER BY score ASC, s.file_path ASC, s.start_line ASC LIMIT ?
                """;
        String prefix = escapeLike(query) + "%";
        String contains = "%" + escapeLike(query) + "%";
        return searchCandidates(sql, statement -> {
            statement.setString(1, query);
            statement.setString(2, query);
            statement.setString(3, prefix);
            statement.setString(4, projectKey(projectRoot));
            statement.setString(5, contains);
            statement.setInt(6, limit);
        }, false);
    }

    public List<RetrievalCandidate> searchRelations(
            Path projectRoot, Set<String> seedSymbolIds, int limit) throws SQLException {
        if (seedSymbolIds == null || seedSymbolIds.isEmpty() || limit <= 0) return List.of();
        String placeholders = String.join(",", seedSymbolIds.stream().map(ignored -> "?").toList());
        String sql = """
                SELECT COALESCE(c.id, 0) AS id, r.file_path,
                       COALESCE(c.start_line, r.line_number) AS start_line,
                       COALESCE(c.end_line, r.line_number) AS end_line,
                       COALESCE(c.chunk_type, 'relation') AS chunk_type,
                       s.simple_name AS symbol, r.from_symbol_id AS symbol_id,
                       COALESCE(c.content, '') AS content, 0.0 AS score,
                       r.to_symbol_id, r.relation_type, r.target_text
                FROM code_relations_v2 r
                JOIN code_symbols_v2 s ON s.symbol_id=r.from_symbol_id
                LEFT JOIN code_chunks_v2 c ON c.symbol_id=r.from_symbol_id
                WHERE r.project_path=? AND (r.from_symbol_id IN (%s) OR r.to_symbol_id IN (%s))
                ORDER BY r.file_path ASC, r.line_number ASC, r.id ASC LIMIT ?
                """.formatted(placeholders, placeholders);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            statement.setString(parameter++, projectKey(projectRoot));
            for (String seed : seedSymbolIds) statement.setString(parameter++, seed);
            for (String seed : seedSymbolIds) statement.setString(parameter++, seed);
            statement.setInt(parameter, limit);
            List<RetrievalCandidate> results = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    results.add(candidate(result, 0.0,
                            result.getString("to_symbol_id") != null,
                            result.getString("relation_type"), result.getString("target_text")));
                }
            }
            return results;
        }
    }

    public List<RetrievalCandidate> searchVector(
            Path projectRoot, String embeddingSpaceId, float[] query, int limit) throws SQLException {
        if (query == null || query.length == 0 || limit <= 0) return List.of();
        String sql = """
                SELECT c.id, c.file_path, c.start_line, c.end_line, c.chunk_type,
                       c.symbol, c.symbol_id, c.content, c.content_hash,
                       e.dimension, e.vector_blob, e.source_content_hash
                FROM chunk_embeddings_v2 e
                JOIN code_chunks_v2 c ON c.id=e.chunk_id
                WHERE c.project_path=? AND e.embedding_space_id=?
                """;
        List<RetrievalCandidate> results = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectKey(projectRoot));
            statement.setString(2, embeddingSpaceId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    int dimension = result.getInt("dimension");
                    byte[] bytes = result.getBytes("vector_blob");
                    if (dimension != query.length || bytes == null || bytes.length != dimension * Float.BYTES
                            || !result.getString("content_hash").equals(result.getString("source_content_hash"))) {
                        continue;
                    }
                    float[] vector = decodeVector(bytes, dimension);
                    results.add(candidate(result, cosine(query, vector), false, null, null));
                }
            }
        }
        results.sort(Comparator.comparingDouble(RetrievalCandidate::score).reversed()
                .thenComparing(RetrievalCandidate::filePath)
                .thenComparingInt(RetrievalCandidate::startLine));
        return results.size() > limit ? List.copyOf(results.subList(0, limit)) : List.copyOf(results);
    }

    public RetrievalIndexStatus status(Path projectRoot) throws SQLException {
        String project = projectKey(projectRoot);
        return new RetrievalIndexStatus(true,
                legacyDatabase != null && Files.exists(legacyDatabase),
                count("SELECT COUNT(*) FROM indexed_files_v2 WHERE project_path=?", project),
                count("SELECT COUNT(*) FROM code_chunks_v2 WHERE project_path=?", project));
    }

    private void configureConnection() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA busy_timeout=5000");
        }
    }

    private void initializeSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS rag_schema (singleton INTEGER PRIMARY KEY CHECK(singleton=1), version INTEGER NOT NULL)");
            statement.execute("INSERT INTO rag_schema(singleton,version) VALUES(1,2) ON CONFLICT(singleton) DO UPDATE SET version=excluded.version");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS indexed_files_v2 (
                      project_path TEXT NOT NULL, file_path TEXT NOT NULL, content_hash TEXT NOT NULL,
                      size_bytes INTEGER NOT NULL, modified_millis INTEGER NOT NULL, language TEXT NOT NULL,
                      index_status TEXT NOT NULL, last_error TEXT, PRIMARY KEY(project_path,file_path))
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS code_chunks_v2 (
                      id INTEGER PRIMARY KEY AUTOINCREMENT, project_path TEXT NOT NULL, file_path TEXT NOT NULL,
                      start_line INTEGER NOT NULL, end_line INTEGER NOT NULL, chunk_type TEXT NOT NULL,
                      symbol TEXT NOT NULL, symbol_id TEXT, content TEXT NOT NULL, search_terms TEXT NOT NULL,
                      content_hash TEXT NOT NULL,
                      FOREIGN KEY(project_path,file_path) REFERENCES indexed_files_v2(project_path,file_path) ON DELETE CASCADE)
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS code_symbols_v2 (
                      symbol_id TEXT PRIMARY KEY, project_path TEXT NOT NULL, file_path TEXT NOT NULL,
                      qualified_name TEXT NOT NULL, simple_name TEXT NOT NULL, signature TEXT NOT NULL,
                      symbol_kind TEXT NOT NULL, owner_symbol_id TEXT, start_line INTEGER NOT NULL, end_line INTEGER NOT NULL,
                      UNIQUE(project_path,file_path,qualified_name,signature),
                      FOREIGN KEY(project_path,file_path) REFERENCES indexed_files_v2(project_path,file_path) ON DELETE CASCADE)
                    """);
            statement.execute("CREATE VIRTUAL TABLE IF NOT EXISTS code_chunks_terms_fts_v2 USING fts5(symbol,search_terms,content='code_chunks_v2',content_rowid='id',tokenize=\"unicode61 tokenchars '_-'\")");
            statement.execute("CREATE VIRTUAL TABLE IF NOT EXISTS code_chunks_trigram_fts_v2 USING fts5(content,content='code_chunks_v2',content_rowid='id',tokenize='trigram')");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS code_relations_v2 (
                      id INTEGER PRIMARY KEY AUTOINCREMENT, project_path TEXT NOT NULL, file_path TEXT NOT NULL,
                      from_symbol_id TEXT NOT NULL, to_symbol_id TEXT, target_text TEXT NOT NULL,
                      relation_type TEXT NOT NULL, line_number INTEGER NOT NULL,
                      FOREIGN KEY(project_path,file_path) REFERENCES indexed_files_v2(project_path,file_path) ON DELETE CASCADE,
                      FOREIGN KEY(from_symbol_id) REFERENCES code_symbols_v2(symbol_id) ON DELETE CASCADE,
                      FOREIGN KEY(to_symbol_id) REFERENCES code_symbols_v2(symbol_id) ON DELETE SET NULL)
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS embedding_spaces_v2 (
                      embedding_space_id TEXT PRIMARY KEY, provider_id TEXT NOT NULL, model_id TEXT NOT NULL,
                      endpoint_fingerprint TEXT NOT NULL, artifact_revision TEXT NOT NULL, dimension INTEGER NOT NULL,
                      pooling TEXT NOT NULL, normalized INTEGER NOT NULL, preprocessing_version INTEGER NOT NULL,
                      chunker_version INTEGER NOT NULL)
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS chunk_embeddings_v2 (
                      chunk_id INTEGER NOT NULL, embedding_space_id TEXT NOT NULL, dimension INTEGER NOT NULL,
                      vector_blob BLOB NOT NULL, source_content_hash TEXT NOT NULL,
                      PRIMARY KEY(chunk_id,embedding_space_id),
                      FOREIGN KEY(chunk_id) REFERENCES code_chunks_v2(id) ON DELETE CASCADE,
                      FOREIGN KEY(embedding_space_id) REFERENCES embedding_spaces_v2(embedding_space_id))
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS index_manifest_v2 (
                      project_path TEXT PRIMARY KEY, schema_version INTEGER NOT NULL, chunker_version INTEGER NOT NULL,
                      preprocessing_version INTEGER NOT NULL, lexical_state TEXT NOT NULL, embedding_state TEXT NOT NULL,
                      active_embedding_space_id TEXT, updated_at TEXT NOT NULL)
                    """);
            createIndexesAndTriggers(statement);
            statement.execute("INSERT INTO code_chunks_terms_fts_v2(code_chunks_terms_fts_v2) VALUES('rebuild')");
            statement.execute("INSERT INTO code_chunks_trigram_fts_v2(code_chunks_trigram_fts_v2) VALUES('rebuild')");
        }
    }

    private static void createIndexesAndTriggers(Statement statement) throws SQLException {
        statement.execute("CREATE INDEX IF NOT EXISTS idx_chunks_project_file_v2 ON code_chunks_v2(project_path,file_path)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_symbols_project_name_v2 ON code_symbols_v2(project_path,simple_name)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_relations_from_v2 ON code_relations_v2(from_symbol_id)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_relations_to_v2 ON code_relations_v2(to_symbol_id)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_embeddings_space_chunk_v2 ON chunk_embeddings_v2(embedding_space_id,chunk_id)");
        statement.execute("""
                CREATE TRIGGER IF NOT EXISTS chunks_ai_v2 AFTER INSERT ON code_chunks_v2 BEGIN
                  INSERT INTO code_chunks_terms_fts_v2(rowid,symbol,search_terms) VALUES(new.id,new.symbol,new.search_terms);
                  INSERT INTO code_chunks_trigram_fts_v2(rowid,content) VALUES(new.id,new.content);
                END
                """);
        statement.execute("""
                CREATE TRIGGER IF NOT EXISTS chunks_ad_v2 AFTER DELETE ON code_chunks_v2 BEGIN
                  INSERT INTO code_chunks_terms_fts_v2(code_chunks_terms_fts_v2,rowid,symbol,search_terms) VALUES('delete',old.id,old.symbol,old.search_terms);
                  INSERT INTO code_chunks_trigram_fts_v2(code_chunks_trigram_fts_v2,rowid,content) VALUES('delete',old.id,old.content);
                END
                """);
        statement.execute("""
                CREATE TRIGGER IF NOT EXISTS chunks_au_v2 AFTER UPDATE ON code_chunks_v2 BEGIN
                  INSERT INTO code_chunks_terms_fts_v2(code_chunks_terms_fts_v2,rowid,symbol,search_terms) VALUES('delete',old.id,old.symbol,old.search_terms);
                  INSERT INTO code_chunks_terms_fts_v2(rowid,symbol,search_terms) VALUES(new.id,new.symbol,new.search_terms);
                  INSERT INTO code_chunks_trigram_fts_v2(code_chunks_trigram_fts_v2,rowid,content) VALUES('delete',old.id,old.content);
                  INSERT INTO code_chunks_trigram_fts_v2(rowid,content) VALUES(new.id,new.content);
                END
                """);
    }

    private void upsertFile(FileIndexBatch batch, String project, String file) throws SQLException {
        String sql = """
                INSERT INTO indexed_files_v2(project_path,file_path,content_hash,size_bytes,modified_millis,language,index_status,last_error)
                VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(project_path,file_path) DO UPDATE SET
                  content_hash=excluded.content_hash,size_bytes=excluded.size_bytes,modified_millis=excluded.modified_millis,
                  language=excluded.language,index_status=excluded.index_status,last_error=excluded.last_error
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, project); statement.setString(2, file);
            statement.setString(3, batch.contentHash()); statement.setLong(4, batch.sizeBytes());
            statement.setLong(5, batch.modifiedMillis()); statement.setString(6, batch.language());
            statement.setString(7, batch.indexStatus()); statement.setString(8, batch.lastError());
            statement.executeUpdate();
        }
    }

    private void deleteFileContents(String project, String file) throws SQLException {
        for (String table : List.of("code_chunks_v2", "code_relations_v2", "code_symbols_v2")) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM " + table + " WHERE project_path=? AND file_path=?")) {
                statement.setString(1, project); statement.setString(2, file); statement.executeUpdate();
            }
        }
    }

    private void insertSymbols(List<IndexedSymbol> symbols, String project, String file) throws SQLException {
        String sql = "INSERT INTO code_symbols_v2(symbol_id,project_path,file_path,qualified_name,simple_name,signature,symbol_kind,owner_symbol_id,start_line,end_line) VALUES(?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (IndexedSymbol symbol : symbols) {
                statement.setString(1, symbol.symbolId()); statement.setString(2, project); statement.setString(3, file);
                statement.setString(4, symbol.qualifiedName()); statement.setString(5, symbol.simpleName());
                statement.setString(6, symbol.signature()); statement.setString(7, symbol.symbolKind());
                statement.setString(8, symbol.ownerSymbolId()); statement.setInt(9, symbol.startLine());
                statement.setInt(10, symbol.endLine()); statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertChunks(List<IndexedChunk> chunks, String project, String file) throws SQLException {
        String sql = "INSERT INTO code_chunks_v2(project_path,file_path,start_line,end_line,chunk_type,symbol,symbol_id,content,search_terms,content_hash) VALUES(?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (IndexedChunk chunk : chunks) {
                statement.setString(1, project); statement.setString(2, file);
                statement.setInt(3, chunk.startLine()); statement.setInt(4, chunk.endLine());
                statement.setString(5, chunk.chunkType()); statement.setString(6, chunk.symbol());
                statement.setString(7, chunk.symbolId()); statement.setString(8, chunk.content());
                statement.setString(9, chunk.searchTerms()); statement.setString(10, chunk.contentHash());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertRelations(List<IndexedRelation> relations, String project, String file) throws SQLException {
        String sql = "INSERT INTO code_relations_v2(project_path,file_path,from_symbol_id,to_symbol_id,target_text,relation_type,line_number) VALUES(?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (IndexedRelation relation : relations) {
                statement.setString(1, project); statement.setString(2, file);
                statement.setString(3, relation.fromSymbolId()); statement.setString(4, relation.toSymbolId());
                statement.setString(5, relation.targetText()); statement.setString(6, relation.relationType());
                statement.setInt(7, relation.lineNumber()); statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void upsertEmbeddingSpace(EmbeddingSpaceDescriptor space) throws SQLException {
        String sql = "INSERT INTO embedding_spaces_v2 VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(embedding_space_id) DO NOTHING";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, space.embeddingSpaceId()); statement.setString(2, space.providerId());
            statement.setString(3, space.modelId()); statement.setString(4, space.endpointFingerprint());
            statement.setString(5, space.artifactRevision()); statement.setInt(6, space.dimension());
            statement.setString(7, space.pooling()); statement.setInt(8, space.normalized() ? 1 : 0);
            statement.setInt(9, space.preprocessingVersion()); statement.setInt(10, space.chunkerVersion());
            statement.executeUpdate();
        }
    }

    private long findChunkId(String project, String file, ChunkEmbedding embedding) throws SQLException {
        String sql = "SELECT id FROM code_chunks_v2 WHERE project_path=? AND file_path=? AND start_line=? AND end_line=? AND symbol_id IS ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, project); statement.setString(2, file);
            statement.setInt(3, embedding.startLine()); statement.setInt(4, embedding.endLine());
            statement.setString(5, embedding.symbolId());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("Embedding references an unknown chunk");
                long id = result.getLong(1);
                if (result.next()) throw new SQLException("Embedding chunk identity is ambiguous");
                return id;
            }
        }
    }

    private void insertEmbedding(long chunkId, EmbeddingSpaceDescriptor space,
                                 ChunkEmbedding embedding) throws SQLException {
        if (embedding.vector().length != space.dimension()) {
            throw new SQLException("Embedding dimension does not match space descriptor");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO chunk_embeddings_v2(chunk_id,embedding_space_id,dimension,vector_blob,source_content_hash) VALUES(?,?,?,?,?)")) {
            statement.setLong(1, chunkId); statement.setString(2, space.embeddingSpaceId());
            statement.setInt(3, embedding.vector().length); statement.setBytes(4, encodeVector(embedding.vector()));
            statement.setString(5, embedding.sourceContentHash()); statement.executeUpdate();
        }
    }

    private List<RetrievalCandidate> searchCandidates(
            String sql, StatementBinder binder, boolean negateBm25) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            List<RetrievalCandidate> results = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    double raw = result.getDouble("score");
                    results.add(candidate(result, negateBm25 ? -raw : raw, false, null, null));
                }
            }
            return results;
        }
    }

    private static RetrievalCandidate candidate(ResultSet result, double score,
                                                boolean resolved, String relationType,
                                                String targetText) throws SQLException {
        return new RetrievalCandidate(result.getLong("id"), result.getString("file_path"),
                result.getInt("start_line"), result.getInt("end_line"),
                result.getString("chunk_type"), result.getString("symbol"),
                result.getString("symbol_id"), result.getString("content"), score,
                resolved, relationType, targetText);
    }

    private int count(String sql, String project) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, project);
            try (ResultSet result = statement.executeQuery()) { return result.getInt(1); }
        }
    }

    private static String projectKey(Path projectRoot) {
        return VectorStore.normalizeProjectKey(projectRoot.toString());
    }

    private static String relativeKey(Path relativePath) {
        return relativePath.normalize().toString().replace('\\', '/');
    }

    private static String ftsMatch(String query) {
        if (query == null) return "";
        return query.trim().isEmpty() ? "" : String.join(" AND ",
                java.util.Arrays.stream(query.trim().split("\\s+"))
                        .filter(token -> !token.isBlank()).map(SqliteRetrievalIndex::quoteFts).toList());
    }

    private static String quoteFts(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static byte[] encodeVector(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) buffer.putFloat(value);
        return buffer.array();
    }

    private static float[] decodeVector(byte[] bytes, int dimension) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[dimension];
        for (int i = 0; i < dimension; i++) vector[i] = buffer.getFloat();
        return vector;
    }

    private static double cosine(float[] left, float[] right) {
        double dot = 0, leftNorm = 0, rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i]; leftNorm += left[i] * left[i]; rightNorm += right[i] * right[i];
        }
        return leftNorm == 0 || rightNorm == 0 ? 0 : dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
