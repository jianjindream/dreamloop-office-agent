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
        if (ingestToRAG && rag != null) {
            try {
                Map.Entry<Integer, String> ingest = rag.ingest(wr.version.getContentMd());
                out.ingestChunks = ingest.getKey();
                out.ingestDocHash = ingest.getValue();
            } catch (Exception e) {
                log.warn("ingest doc to RAG failed: {}", e.getMessage());
            }
        }
        return out;
    }

    public List<Document> list() { return repo.list(); }

    public LibraryRepo.DocumentWithVersion get(String documentId) {
        return repo.get(documentId);
    }

    public List<DocumentVersion> listVersions(String documentId) {
        return repo.listVersions(documentId);
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
        return rag.ingest(ver.getContentMd());
    }

    /** 写入结果（合并 document.WriteResult + 可选的 RAG ingest 摘要）。 */
    public static class Result {
        public final Document document;
        public final DocumentVersion version;
        public final boolean created;
        public Integer ingestChunks;
        public String ingestDocHash;

        public Result(Document document, DocumentVersion version, boolean created) {
            this.document = document;
            this.version = version;
            this.created = created;
        }
    }
}
