package com.jianjin.assistant.service.document;

import com.jianjin.assistant.domain.document.Document;
import com.jianjin.assistant.domain.document.DocumentVersion;
import com.jianjin.assistant.domain.document.LibraryRepo;
import com.jianjin.assistant.domain.document.WriteRequest;
import com.jianjin.assistant.domain.document.WriteResult;
import com.jianjin.assistant.service.rag.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

@Service
public class DocumentLibraryService {

    private static final Logger log = LoggerFactory.getLogger(DocumentLibraryService.class);

    private final LibraryRepo repo;
    private final RagService rag;

    public DocumentLibraryService(LibraryRepo repo, @Lazy RagService rag) {
        this.repo = repo;
        this.rag = rag;
    }

    /** 暴露 repo 给 sub-agents（避免它们直接持有 repo）。 */
    public LibraryRepo repo() { return repo; }

    /** 落库 + 可选 RAG 入库。返回结果包含 document/version/created/ingest。 */
    public Result writeDocument(WriteRequest req, boolean ingestToRAG) {
        WriteResult wr = repo.write(req);
        Result out = new Result(wr.document, wr.version, wr.created);
        out.indexStatus = "not_indexed";
        if (ingestToRAG && rag != null) {
            try {
                repo.putVersionMetadata(wr.version.getId(), "index_status", "indexing");
                Object owner = wr.version.getMetadata().get("owner_key");
                Map.Entry<Integer, String> ingest = rag.ingest(owner == null ? "default:default" : String.valueOf(owner),
                        wr.document.getId() + ":" + wr.version.getId(), wr.version.getContentMd());
                out.ingestChunks = ingest.getKey();
                out.ingestDocHash = ingest.getValue();
                repo.putVersionMetadata(wr.version.getId(), "doc_hash", ingest.getValue());
                repo.putVersionMetadata(wr.version.getId(), "index_status", "indexed");
                out.version.getMetadata().put("doc_hash", ingest.getValue());
                out.version.getMetadata().put("index_status", "indexed");
                out.indexStatus = "indexed";
            } catch (Exception e) {
                log.warn("ingest doc to RAG failed: {}", e.getMessage());
                out.indexStatus = "failed";
                out.indexError = "文档已保存，知识库索引失败，可重试";
                repo.putVersionMetadata(wr.version.getId(), "index_status", "failed");
                out.version.getMetadata().put("index_status", "failed");
            }
        }
        return out;
    }

    public List<Document> list() { return repo.list(); }
    public List<Document> listDeleted() { return repo.listDeleted(); }
    public boolean restore(String documentId) { return repo.restore(documentId); }

    public LibraryRepo.DocumentWithVersion get(String documentId) {
        return repo.get(documentId);
    }

    public List<DocumentVersion> listVersions(String documentId) {
        return repo.listVersions(documentId);
    }

    /** Resolves a RAG hash back to the immutable office-document version shown to the user. */
    public CitationTarget resolveCitation(String ownerKey, String docHash) {
        return resolveCitations(ownerKey, List.of(docHash == null ? "" : docHash)).get(docHash);
    }

    public Map<String, CitationTarget> resolveCitations(String ownerKey, Collection<String> docHashes) {
        LinkedHashSet<String> remaining = new LinkedHashSet<>();
        if (docHashes != null) for (String hash : docHashes) if (hash != null && !hash.isBlank()) remaining.add(hash);
        Map<String, CitationTarget> resolved = new LinkedHashMap<>();
        if (remaining.isEmpty()) return resolved;
        for (Document document : repo.list()) {
            for (DocumentVersion version : repo.listVersions(document.getId())) {
                Object owner = version.getMetadata().get("owner_key");
                Object hash = version.getMetadata().get("doc_hash");
                String value = String.valueOf(hash);
                boolean owned = ownerKey.equals(String.valueOf(owner)) || (owner == null && "default:default".equals(ownerKey));
                if (remaining.contains(value) && owned) {
                    resolved.put(value, new CitationTarget(document.getId(), document.getTitle(), version.getId(), version.getVersion()));
                    remaining.remove(value);
                    if (remaining.isEmpty()) return resolved;
                }
            }
        }
        return resolved;
    }

    public record CitationTarget(String documentId, String title, String versionId, int version) {}

    public boolean delete(String documentId) {
        LibraryRepo.DocumentWithVersion current = repo.get(documentId);
        Object owner = current.version.getMetadata().get("owner_key");
        // Fail visibly if index removal fails, instead of reporting a deletion while it is still searchable.
        java.util.Set<String> hashes = new java.util.LinkedHashSet<>();
        for (DocumentVersion version : repo.listVersions(documentId)) {
            Object hash = version.getMetadata().get("doc_hash");
            if (hash != null) hashes.add(String.valueOf(hash));
        }
        if (rag != null) for (String hash : hashes) {
            rag.delete(owner == null ? "default:default" : String.valueOf(owner), hash);
        }
        for (DocumentVersion version : repo.listVersions(documentId)) {
            repo.putVersionMetadata(version.getId(), "index_status", "not_indexed");
        }
        return repo.delete(documentId);
    }

    /** 重新把某个文档（或具体版本）写入 RAG。 */
    public Map.Entry<Integer, String> reingest(String documentId, String versionId) {
        DocumentVersion ver;
        if (versionId != null && !versionId.isEmpty()) {
            ver = repo.getVersion(versionId);
        } else {
            ver = repo.get(documentId).version;
        }
        if (rag == null) throw new IllegalStateException("RagService not available");
        Object owner = ver.getMetadata().get("owner_key");
        if (!documentId.equals(ver.getDocumentId())) throw new IllegalArgumentException("版本不属于当前文档");
        try {
            repo.putVersionMetadata(ver.getId(), "index_status", "indexing");
            Map.Entry<Integer, String> result = rag.ingest(owner == null ? "default:default" : String.valueOf(owner),
                    documentId + ":" + ver.getId(), ver.getContentMd());
            repo.putVersionMetadata(ver.getId(), "doc_hash", result.getValue());
            repo.putVersionMetadata(ver.getId(), "index_status", "indexed");
            return result;
        } catch (RuntimeException e) {
            repo.putVersionMetadata(ver.getId(), "index_status", "failed");
            throw e;
        }
    }

    /** 写入结果（合并 document.WriteResult + 可选的 RAG ingest 摘要）。 */
    public static class Result {
        public final Document document;
        public final DocumentVersion version;
        public final boolean created;
        public Integer ingestChunks;
        public String ingestDocHash;
        public String indexStatus;
        public String indexError;

        public Result(Document document, DocumentVersion version, boolean created) {
            this.document = document;
            this.version = version;
            this.created = created;
        }
    }
}
