package com.jianjin.assistant.infrastructure.persistence;

import com.jianjin.assistant.infrastructure.platform.PostgresConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

@Repository
public class RagChunkRepository {

    private static final Logger log = LoggerFactory.getLogger(RagChunkRepository.class);
    private final PostgresConnector pg;

    public RagChunkRepository(PostgresConnector pg) {
        this.pg = pg;
    }

    public static class Row {
        public long id;
        public String docHash;
        public int chunkIdx;
        public String content;
        public String embeddingJson;
    }

    public static class ContextRow {
        public long childId;
        public long contextId;
        public String content;
    }

    public static class ParentRow {
        public long id;
        public String docHash;
        public int parentIdx;
        public String content;
    }

    public long saveParent(String docHash, int parentIdx, String content) {
        Connection connection = pg.connection();
        if (connection == null) return -1;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO rag_parent_chunks (doc_hash, parent_idx, content) VALUES (?, ?, ?) " +
                        "ON CONFLICT (doc_hash, parent_idx) DO UPDATE SET content = EXCLUDED.content RETURNING id")) {
            statement.setString(1, docHash);
            statement.setInt(2, parentIdx);
            statement.setString(3, sanitize(content));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : -1;
            }
        } catch (SQLException e) {
            log.warn("RAG parent chunk save failed: {}", e.getMessage());
            return -1;
        }
    }

    public long save(String docHash, int chunkIdx, Long parentId, String content, String embeddingJson) {
        Connection connection = pg.connection();
        if (connection == null) return -1;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO rag_chunks (doc_hash, chunk_idx, parent_id, content, embedding) VALUES (?, ?, ?, ?, ?::jsonb) " +
                        "ON CONFLICT (doc_hash, chunk_idx) DO UPDATE SET parent_id = EXCLUDED.parent_id, " +
                        "content = EXCLUDED.content, embedding = EXCLUDED.embedding RETURNING id")) {
            statement.setString(1, docHash);
            statement.setInt(2, chunkIdx);
            if (parentId == null) statement.setNull(3, Types.BIGINT); else statement.setLong(3, parentId);
            statement.setString(4, sanitize(content));
            statement.setString(5, embeddingJson);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : -1;
            }
        } catch (SQLException e) {
            log.warn("RAG child chunk save failed: {}", e.getMessage());
            return -1;
        }
    }

    public List<Row> loadAll() {
        List<Row> chunks = new ArrayList<>();
        Connection connection = pg.connection();
        if (connection == null) return chunks;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT id, doc_hash, chunk_idx, content, embedding::text AS embedding FROM rag_chunks ORDER BY id")) {
            while (result.next()) chunks.add(row(result));
        } catch (SQLException e) {
            log.warn("RAG chunk load failed: {}", e.getMessage());
        }
        return chunks;
    }

    public List<Row> loadByIds(List<Long> ids) {
        List<Row> chunks = new ArrayList<>();
        Connection connection = pg.connection();
        if (connection == null || ids == null || ids.isEmpty()) return chunks;
        String placeholders = String.join(",", ids.stream().map(id -> "?").toArray(String[]::new));
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, doc_hash, chunk_idx, content, embedding::text AS embedding " +
                        "FROM rag_chunks WHERE id IN (" + placeholders + ")")) {
            for (int i = 0; i < ids.size(); i++) statement.setLong(i + 1, ids.get(i));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) chunks.add(row(result));
            }
        } catch (SQLException e) {
            log.warn("RAG chunk load by IDs failed: {}", e.getMessage());
        }
        return chunks;
    }

    /** Loads parent content for child hits; legacy rows fall back to their child content. */
    public List<ContextRow> loadContextsByChildIds(List<Long> ids) {
        List<ContextRow> contexts = new ArrayList<>();
        Connection connection = pg.connection();
        if (connection == null || ids == null || ids.isEmpty()) return contexts;
        String placeholders = String.join(",", ids.stream().map(id -> "?").toArray(String[]::new));
        String sql = "SELECT c.id AS child_id, COALESCE(p.id, c.id) AS context_id, " +
                "COALESCE(p.content, c.content) AS content FROM rag_chunks c " +
                "LEFT JOIN rag_parent_chunks p ON p.id = c.parent_id WHERE c.id IN (" + placeholders + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < ids.size(); i++) statement.setLong(i + 1, ids.get(i));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    ContextRow context = new ContextRow();
                    context.childId = result.getLong("child_id");
                    context.contextId = result.getLong("context_id");
                    context.content = result.getString("content");
                    contexts.add(context);
                }
            }
        } catch (SQLException e) {
            log.warn("RAG parent context load failed: {}", e.getMessage());
        }
        return contexts;
    }

    /** Stable parent contexts exposed for human golden-set labelling. */
    public List<ParentRow> loadAllParents() {
        List<ParentRow> contexts = new ArrayList<>();
        Connection connection = pg.connection();
        if (connection == null) return contexts;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT id, doc_hash, parent_idx, content FROM rag_parent_chunks ORDER BY doc_hash, parent_idx")) {
            while (result.next()) {
                ParentRow row = new ParentRow();
                row.id = result.getLong("id");
                row.docHash = result.getString("doc_hash");
                row.parentIdx = result.getInt("parent_idx");
                row.content = result.getString("content");
                contexts.add(row);
            }
        } catch (SQLException e) {
            log.warn("RAG parent context load failed: {}", e.getMessage());
        }
        return contexts;
    }

    public List<Long> deleteByDocHash(String docHash) {
        List<Long> ids = new ArrayList<>();
        Connection connection = pg.connection();
        if (connection == null) return ids;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM rag_chunks WHERE doc_hash = ?")) {
            statement.setString(1, docHash);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) ids.add(result.getLong(1));
            }
        } catch (SQLException e) {
            log.warn("RAG chunk ID lookup failed: {}", e.getMessage());
        }
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM rag_chunks WHERE doc_hash = ?")) {
            statement.setString(1, docHash);
            statement.executeUpdate();
        } catch (SQLException e) {
            log.warn("RAG child chunk delete failed: {}", e.getMessage());
        }
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM rag_parent_chunks WHERE doc_hash = ?")) {
            statement.setString(1, docHash);
            statement.executeUpdate();
        } catch (SQLException e) {
            log.warn("RAG parent chunk delete failed: {}", e.getMessage());
        }
        return ids;
    }

    private static Row row(ResultSet result) throws SQLException {
        Row row = new Row();
        row.id = result.getLong("id");
        row.docHash = result.getString("doc_hash");
        row.chunkIdx = result.getInt("chunk_idx");
        row.content = result.getString("content");
        row.embeddingJson = result.getString("embedding");
        return row;
    }

    private static String sanitize(String content) {
        return content == null ? null : content.replace("\u0000", "");
    }
}
