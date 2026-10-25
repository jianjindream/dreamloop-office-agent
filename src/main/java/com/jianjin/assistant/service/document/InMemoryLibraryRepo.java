package com.jianjin.assistant.service.document;

import com.jianjin.assistant.domain.document.Document;
import com.jianjin.assistant.domain.document.DocumentVersion;
import com.jianjin.assistant.domain.document.Ids;
import com.jianjin.assistant.domain.document.LibraryRepo;
import com.jianjin.assistant.domain.document.WriteRequest;
import com.jianjin.assistant.domain.document.WriteResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Test-only implementation. Production uses JdbcLibraryRepo. */
public class InMemoryLibraryRepo implements LibraryRepo {

    private final Object mu = new Object();
    private final Map<String, Document> docs = new LinkedHashMap<>();
    /** documentId → versions（按 version 升序追加） */
    private final Map<String, List<DocumentVersion>> versions = new LinkedHashMap<>();
    /** versionId → version 引用（便于 GetVersion） */
    private final Map<String, DocumentVersion> versionIndex = new LinkedHashMap<>();

    @Override
    public WriteResult write(WriteRequest in) {
        WriteRequest req = WriteRequest.normalize(in);
        if (req.getTitle().isEmpty()) throw new IllegalArgumentException("title is required");
        if (req.getContentMd().isEmpty()) throw new IllegalArgumentException("content_md is required");

        synchronized (mu) {
            Instant now = Instant.now();
            boolean created = req.getDocumentId().isEmpty();
            String docId = req.getDocumentId();
            Document doc;
            int versionNo;
            if (created) {
                docId = Ids.newId("doc");
                doc = new Document();
                doc.setId(docId);
                doc.setTitle(req.getTitle());
                doc.setDocType(req.getDocType());
                doc.setSource(req.getSource());
                doc.setStatus(req.getStatus());
                doc.setCreatedBy(req.getCreatedBy());
                doc.setCreatedAt(now);
                doc.setUpdatedAt(now);
                docs.put(docId, doc);
                versions.put(docId, new ArrayList<>());
                versionNo = 1;
            } else {
                doc = docs.get(docId);
                if (doc == null) throw new IllegalArgumentException("document not found: " + docId);
                if ("deleted".equals(doc.getStatus())) throw new IllegalArgumentException("document is deleted");
                doc.setTitle(req.getTitle());
                doc.setDocType(req.getDocType());
                doc.setSource(req.getSource());
                doc.setStatus(req.getStatus());
                doc.setUpdatedAt(now);
                versionNo = versions.get(docId).size() + 1;
            }

            DocumentVersion v = new DocumentVersion();
            v.setId(Ids.newId("ver"));
            v.setDocumentId(docId);
            v.setVersion(versionNo);
            v.setContentMd(req.getContentMd());
            v.setSummary(req.getSummary());
            v.setMetadata(req.getMetadata());
            v.setCreatedAt(now);
            versions.get(docId).add(v);
            versionIndex.put(v.getId(), v);

            doc.setLatestVersion(versionNo);
            doc.setLatestVersionId(v.getId());

            return new WriteResult(copyOf(doc), copyOf(v), created);
        }
    }

    @Override
    public List<Document> list() {
        synchronized (mu) {
            List<Document> result = new ArrayList<>(docs.size());
            for (Document d : docs.values()) {
                if (!"deleted".equals(d.getStatus())) result.add(copyOf(d));
            }
            result.sort(Comparator.comparing(Document::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
            return result;
        }
    }

    @Override
    public DocumentWithVersion get(String documentId) {
        if (documentId == null || documentId.isEmpty()) {
            throw new IllegalArgumentException("document_id is required");
        }
        synchronized (mu) {
            Document d = docs.get(documentId);
            if (d == null) throw new IllegalArgumentException("document not found: " + documentId);
            DocumentVersion v = versionIndex.get(d.getLatestVersionId());
            if (v == null) throw new IllegalStateException("latest version missing for " + documentId);
            return new DocumentWithVersion(copyOf(d), copyOf(v));
        }
    }

    @Override
    public DocumentVersion getVersion(String versionId) {
        if (versionId == null || versionId.isEmpty()) {
            throw new IllegalArgumentException("version_id is required");
        }
        synchronized (mu) {
            DocumentVersion v = versionIndex.get(versionId);
            if (v == null) throw new IllegalArgumentException("version not found: " + versionId);
            return copyOf(v);
        }
    }

    @Override
    public List<DocumentVersion> listVersions(String documentId) {
        if (documentId == null || documentId.isEmpty()) {
            throw new IllegalArgumentException("document_id is required");
        }
        synchronized (mu) {
            List<DocumentVersion> history = versions.get(documentId);
            if (history == null) throw new IllegalArgumentException("document not found: " + documentId);
            List<DocumentVersion> result = new ArrayList<>(history.size());
            for (int i = history.size() - 1; i >= 0; i--) result.add(copyOf(history.get(i)));
            return result;
        }
    }

    @Override
    public boolean delete(String documentId) {
        if (documentId == null || documentId.isBlank()) throw new IllegalArgumentException("document_id is required");
        synchronized (mu) {
            Document doc = docs.get(documentId);
            if (doc == null || "deleted".equals(doc.getStatus())) return false;
            doc.setStatus("deleted");
            return true;
        }
    }

    @Override public List<Document> listDeleted() {
        synchronized (mu) { return docs.values().stream().filter(d -> "deleted".equals(d.getStatus())).map(InMemoryLibraryRepo::copyOf).toList(); }
    }

    @Override public boolean restore(String id) {
        synchronized (mu) {
            Document doc = docs.get(id);
            if (doc == null || !"deleted".equals(doc.getStatus())) return false;
            doc.setStatus(Document.STATUS_ACTIVE);
            return true;
        }
    }

    @Override public void putVersionMetadata(String id, String key, Object value) {
        synchronized (mu) {
            DocumentVersion version = versionIndex.get(id);
            if (version == null) throw new IllegalArgumentException("version not found");
            version.getMetadata().put(key, value);
        }
    }

    @Override
    public void putLatestMetadata(String documentId, String key, Object value) {
        synchronized (mu) {
            Document document = docs.get(documentId);
            if (document == null) throw new IllegalArgumentException("document not found: " + documentId);
            DocumentVersion version = versionIndex.get(document.getLatestVersionId());
            if (version == null) throw new IllegalStateException("latest version missing for " + documentId);
            Map<String,Object> metadata = new LinkedHashMap<>(version.getMetadata());
            metadata.put(key, value); version.setMetadata(metadata);
        }
    }

    // ─────────────────────────── 浅拷贝（避免外部修改内部状态） ───────────────────────────

    private static Document copyOf(Document d) {
        Document c = new Document();
        c.setId(d.getId());
        c.setTitle(d.getTitle());
        c.setDocType(d.getDocType());
        c.setSource(d.getSource());
        c.setStatus(d.getStatus());
        c.setCreatedBy(d.getCreatedBy());
        c.setCreatedAt(d.getCreatedAt());
        c.setUpdatedAt(d.getUpdatedAt());
        c.setLatestVersion(d.getLatestVersion());
        c.setLatestVersionId(d.getLatestVersionId());
        return c;
    }

    private static DocumentVersion copyOf(DocumentVersion v) {
        DocumentVersion c = new DocumentVersion();
        c.setId(v.getId());
        c.setDocumentId(v.getDocumentId());
        c.setVersion(v.getVersion());
        c.setContentMd(v.getContentMd());
        c.setSummary(v.getSummary());
        c.setMetadata(new LinkedHashMap<>(v.getMetadata()));
        c.setCreatedAt(v.getCreatedAt());
        return c;
    }
}
