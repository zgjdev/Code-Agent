package com.codeagent.memory;

import com.codeagent.rag.embedding.EmbeddingSpaceDescriptor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Rebuildable retrieval data, kept in the facts database and never used as an authority source. */
final class SqliteMemoryRetrievalIndex {
    private SqliteMemoryRetrievalIndex() {}

    static void initialize(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS long_term_memory_terms_fts
                    USING fts5(memory_id UNINDEXED, content_hash UNINDEXED, search_terms,
                               tokenize="unicode61 tokenchars '_'")
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS long_term_memory_embeddings (
                      memory_id TEXT NOT NULL REFERENCES long_term_memories(id) ON DELETE CASCADE,
                      embedding_space_id TEXT NOT NULL,
                      content_hash TEXT NOT NULL,
                      dimension INTEGER NOT NULL CHECK(dimension > 0),
                      vector BLOB NOT NULL,
                      PRIMARY KEY(memory_id, embedding_space_id)
                    )
                    """);
            statement.execute("""
                    CREATE TRIGGER IF NOT EXISTS memory_retrieval_delete AFTER DELETE ON long_term_memories
                    BEGIN
                      DELETE FROM long_term_memory_terms_fts WHERE memory_id=old.id;
                      DELETE FROM long_term_memory_embeddings WHERE memory_id=old.id;
                    END
                    """);
            statement.execute("""
                    CREATE TRIGGER IF NOT EXISTS memory_terms_invalidate
                    AFTER UPDATE OF content ON long_term_memories WHEN old.content <> new.content
                    BEGIN DELETE FROM long_term_memory_terms_fts WHERE memory_id=old.id; END
                    """);
            statement.execute("""
                    CREATE TRIGGER IF NOT EXISTS memory_embedding_invalidate
                    AFTER UPDATE OF content, status ON long_term_memories
                    WHEN old.content <> new.content OR new.status <> 'active'
                    BEGIN DELETE FROM long_term_memory_embeddings WHERE memory_id=old.id; END
                    """);
        }
    }

    static void backfill(Connection connection) throws SQLException {
        Map<String, String> indexed = new HashMap<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT memory_id, content_hash FROM long_term_memory_terms_fts")) {
            while (rows.next()) indexed.put(rows.getString(1), rows.getString(2));
        }
        List<String[]> missing = new ArrayList<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                SELECT id, content FROM long_term_memories WHERE status='active'
                """)) {
            while (rows.next()) {
                String id = rows.getString(1);
                String content = rows.getString(2);
                if (!contentHash(content).equals(indexed.get(id))) missing.add(new String[]{id, content});
            }
        }
        for (String[] entry : missing) {
            if (indexed.containsKey(entry[0])) index(connection, entry[0], entry[1]);
            else insert(connection, entry[0], entry[1], contentHash(entry[1]));
        }
    }

    static void index(Connection connection, String id, String content) throws SQLException {
        String hash = contentHash(content);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM long_term_memory_terms_fts WHERE memory_id=? AND content_hash=?")) {
            statement.setString(1, id);
            statement.setString(2, hash);
            try (var rows = statement.executeQuery()) { if (rows.next()) return; }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM long_term_memory_terms_fts WHERE memory_id=?")) {
            statement.setString(1, id);
            statement.executeUpdate();
        }
        insert(connection, id, content, hash);
    }

    private static void insert(Connection connection, String id, String content, String hash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO long_term_memory_terms_fts(memory_id, content_hash, search_terms) VALUES(?,?,?)
                """)) {
            statement.setString(1, id);
            statement.setString(2, hash);
            statement.setString(3, MemorySearchTerms.normalize(content));
            statement.executeUpdate();
        }
    }

    static List<String> lexicalMatches(Connection connection, String query, String projectKey, int limit)
            throws SQLException {
        String match = MemorySearchTerms.query(query);
        if (match.isEmpty() || limit <= 0) return List.of();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT m.id FROM long_term_memory_terms_fts f JOIN long_term_memories m ON m.id=f.memory_id
                WHERE long_term_memory_terms_fts MATCH ? AND m.status='active'
                  AND (m.scope='global' OR (m.scope='project' AND m.project_key=?))
                ORDER BY bm25(long_term_memory_terms_fts), m.id LIMIT ?
                """)) {
            statement.setString(1, match);
            statement.setString(2, projectKey);
            statement.setInt(3, limit);
            List<String> matches = new ArrayList<>();
            try (var rows = statement.executeQuery()) { while (rows.next()) matches.add(rows.getString(1)); }
            return List.copyOf(matches);
        }
    }

    static Optional<float[]> readEmbedding(Connection connection, MemoryEntry entry, EmbeddingSpaceDescriptor space)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT e.dimension, e.vector FROM long_term_memory_embeddings e
                JOIN long_term_memories m ON m.id=e.memory_id
                WHERE e.memory_id=? AND e.embedding_space_id=? AND e.content_hash=?
                  AND m.content=? AND m.status='active'
                """)) {
            statement.setString(1, entry.getId());
            statement.setString(2, space.embeddingSpaceId());
            statement.setString(3, contentHash(entry.getContent()));
            statement.setString(4, entry.getContent());
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getInt(1) != space.dimension()) return Optional.empty();
                byte[] bytes = rows.getBytes(2);
                if (bytes == null || bytes.length != (long) space.dimension() * Float.BYTES) return Optional.empty();
                float[] vector = new float[space.dimension()];
                ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < vector.length; i++) vector[i] = buffer.getFloat();
                return validVector(vector, space.dimension()) ? Optional.of(vector) : Optional.empty();
            }
        }
    }

    static boolean writeEmbedding(Connection connection, MemoryEntry entry, EmbeddingSpaceDescriptor space,
                                  float[] vector) throws SQLException {
        if (!validVector(vector, space.dimension())) return false;
        ByteBuffer bytes = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) bytes.putFloat(value);
        // One atomic conditional statement: inference never owns a transaction or a database lock.
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO long_term_memory_embeddings(memory_id, embedding_space_id, content_hash, dimension, vector)
                SELECT ?,?,?,?,? WHERE EXISTS(
                  SELECT 1 FROM long_term_memories WHERE id=? AND content=? AND status='active')
                ON CONFLICT(memory_id, embedding_space_id) DO UPDATE SET
                  content_hash=excluded.content_hash, dimension=excluded.dimension, vector=excluded.vector
                """)) {
            statement.setString(1, entry.getId());
            statement.setString(2, space.embeddingSpaceId());
            statement.setString(3, contentHash(entry.getContent()));
            statement.setInt(4, space.dimension());
            statement.setBytes(5, bytes.array());
            statement.setString(6, entry.getId());
            statement.setString(7, entry.getContent());
            return statement.executeUpdate() == 1;
        }
    }

    static boolean validVector(float[] vector, int dimension) {
        if (vector == null || vector.length != dimension) return false;
        double norm = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) return false;
            norm += (double) value * value;
        }
        return norm > 0;
    }

    static String contentHash(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }
}
