package com.jianjin.assistant.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jianjin.assistant.domain.document.*;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Repository
public class JdbcLibraryRepo implements LibraryRepo {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final WorkspaceDatabase database;

    public JdbcLibraryRepo(WorkspaceDatabase database) { this.database = database; }

    @Override public WriteResult write(WriteRequest input) {
        WriteRequest req = WriteRequest.normalize(input);
        if (req.getTitle().isBlank() || req.getContentMd().isBlank()) throw new IllegalArgumentException("title 和 content_md 必填");
        return database.transaction(c -> {
            boolean created = req.getDocumentId().isBlank();
            Document doc;
            if (created) {
                doc = new Document();
                doc.setId(Ids.newId("doc")); doc.setCreatedAt(Instant.now());
                doc.setCreatedBy(req.getCreatedBy()); doc.setSource(req.getSource());
            } else {
                doc = readDocument(c, req.getDocumentId(), true);
                if (req.getBaseVersion() != null && req.getBaseVersion() != doc.getLatestVersion()) throw new DocumentVersionConflictException();
                if ("deleted".equals(doc.getStatus())) throw new IllegalArgumentException("文档已在回收站中");
            }
            DocumentVersion version = new DocumentVersion();
            version.setId(Ids.newId("ver")); version.setDocumentId(doc.getId());
            version.setVersion(doc.getLatestVersion() + 1); version.setContentMd(req.getContentMd());
            version.setSummary(req.getSummary()); version.setCreatedAt(Instant.now());
            Map<String, Object> metadata = new LinkedHashMap<>(req.getMetadata());
            // An old index must never be advertised as the current content's index.
            metadata.remove("doc_hash"); metadata.remove("index_error");
            metadata.put("index_status", "not_indexed");
            version.setMetadata(metadata);
            doc.setTitle(req.getTitle()); doc.setDocType(req.getDocType()); doc.setStatus(req.getStatus());
            doc.setUpdatedAt(version.getCreatedAt()); doc.setLatestVersion(version.getVersion()); doc.setLatestVersionId(version.getId());
            String sql = created
                    ? "INSERT INTO office_documents(title,doc_type,status,updated_at,latest_version,latest_version_id,source,created_by,created_at,id) VALUES(?,?,?,?,?,?,?,?,?,?)"
                    : "UPDATE office_documents SET title=?,doc_type=?,status=?,updated_at=?,latest_version=?,latest_version_id=? WHERE id=?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, doc.getTitle()); ps.setString(2, doc.getDocType()); ps.setString(3, doc.getStatus());
                ps.setObject(4, timestamp(doc.getUpdatedAt())); ps.setInt(5, doc.getLatestVersion()); ps.setString(6, doc.getLatestVersionId());
                if (created) {
                    ps.setString(7, doc.getSource()); ps.setString(8, doc.getCreatedBy()); ps.setObject(9, timestamp(doc.getCreatedAt())); ps.setString(10, doc.getId());
                } else ps.setString(7, doc.getId());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO office_document_versions(id,document_id,version,content_md,summary,metadata,created_at) VALUES(?,?,?,?,?,?,?)")) {
                ps.setString(1,version.getId()); ps.setString(2,doc.getId()); ps.setInt(3,version.getVersion());
                ps.setString(4,version.getContentMd()); ps.setString(5,version.getSummary());
                ps.setString(6,JSON.writeValueAsString(metadata)); ps.setObject(7,timestamp(version.getCreatedAt())); ps.executeUpdate();
            }
            return new WriteResult(doc, version, created);
        });
    }

    @Override public List<Document> list() { return list(false); }
    @Override public List<Document> listDeleted() { return list(true); }
    private List<Document> list(boolean deleted) {
        return database.transaction(c -> {
            List<Document> result = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM office_documents WHERE status " + (deleted ? "=" : "<>") + " 'deleted' ORDER BY updated_at DESC"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(document(rs));
            }
            return result;
        });
    }

    @Override public DocumentWithVersion get(String id) {
        return database.transaction(c -> {
            Document doc = readDocument(c, id, false);
            return new DocumentWithVersion(doc, readVersion(c,doc.getLatestVersionId()));
        });
    }
    @Override public DocumentVersion getVersion(String id) { return database.transaction(c -> readVersion(c,id)); }
    @Override public List<DocumentVersion> listVersions(String id) {
        return database.transaction(c -> {
            readDocument(c,id,false);
            List<DocumentVersion> result = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM office_document_versions WHERE document_id=? ORDER BY version DESC")) {
                ps.setString(1,id);
                try (ResultSet rs=ps.executeQuery()) { while (rs.next()) result.add(version(rs)); }
            }
            return result;
        });
    }
    @Override public boolean delete(String id) { return status(id,"deleted",false); }
    @Override public boolean restore(String id) { return status(id,Document.STATUS_ACTIVE,true); }
    private boolean status(String id,String status,boolean restore) {
        return database.transaction(c -> {
            try (PreparedStatement ps=c.prepareStatement("UPDATE office_documents SET status=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status " + (restore ? "=" : "<>") + " 'deleted'")) {
                ps.setString(1,status); ps.setString(2,id); return ps.executeUpdate()>0;
            }
        });
    }
    @Override public void putLatestMetadata(String id,String key,Object value) {
        database.transaction(c -> { updateMetadata(c,readDocument(c,id,true).getLatestVersionId(),key,value); return null; });
    }
    @Override public void putVersionMetadata(String id,String key,Object value) {
        database.transaction(c -> { updateMetadata(c,id,key,value); return null; });
    }
    private void updateMetadata(Connection c,String id,String key,Object value) throws Exception {
        // Lock the version so concurrent metadata updates cannot overwrite each other.
        DocumentVersion version;
        try (PreparedStatement ps=c.prepareStatement("SELECT * FROM office_document_versions WHERE id=? FOR UPDATE")) {
            ps.setString(1,id);
            try (ResultSet rs=ps.executeQuery()) { if(!rs.next()) throw new IllegalArgumentException("版本不存在"); version=version(rs); }
        }
        version.getMetadata().put(key,value);
        try (PreparedStatement ps=c.prepareStatement("UPDATE office_document_versions SET metadata=? WHERE id=?")) {
            ps.setString(1,JSON.writeValueAsString(version.getMetadata())); ps.setString(2,id); ps.executeUpdate();
        }
    }
    private Document readDocument(Connection c,String id,boolean lock) throws SQLException {
        try (PreparedStatement ps=c.prepareStatement("SELECT * FROM office_documents WHERE id=?" + (lock ? " FOR UPDATE" : ""))) {
            ps.setString(1,id); try(ResultSet rs=ps.executeQuery()) { if(!rs.next()) throw new IllegalArgumentException("文档不存在"); return document(rs); }
        }
    }
    private DocumentVersion readVersion(Connection c,String id) throws Exception {
        try (PreparedStatement ps=c.prepareStatement("SELECT * FROM office_document_versions WHERE id=?")) {
            ps.setString(1,id); try(ResultSet rs=ps.executeQuery()) { if(!rs.next()) throw new IllegalArgumentException("版本不存在"); return version(rs); }
        }
    }
    private Document document(ResultSet rs) throws SQLException {
        Document d=new Document(); d.setId(rs.getString("id")); d.setTitle(rs.getString("title"));
        d.setDocType(rs.getString("doc_type")); d.setSource(rs.getString("source")); d.setStatus(rs.getString("status"));
        d.setCreatedBy(rs.getString("created_by")); d.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        d.setUpdatedAt(rs.getTimestamp("updated_at").toInstant()); d.setLatestVersion(rs.getInt("latest_version")); d.setLatestVersionId(rs.getString("latest_version_id")); return d;
    }
    private DocumentVersion version(ResultSet rs) throws Exception {
        DocumentVersion v=new DocumentVersion(); v.setId(rs.getString("id")); v.setDocumentId(rs.getString("document_id"));
        v.setVersion(rs.getInt("version")); v.setContentMd(rs.getString("content_md")); v.setSummary(rs.getString("summary"));
        v.setMetadata(JSON.readValue(rs.getString("metadata"),new TypeReference<LinkedHashMap<String,Object>>(){}));
        v.setCreatedAt(rs.getTimestamp("created_at").toInstant()); return v;
    }
    private static OffsetDateTime timestamp(Instant value) { return value.atOffset(ZoneOffset.UTC); }
}
