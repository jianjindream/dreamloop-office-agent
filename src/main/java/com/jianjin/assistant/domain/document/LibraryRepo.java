package com.jianjin.assistant.domain.document;

import java.util.List;

public interface LibraryRepo {

    /** 写入新文档或追加新版本。{@link WriteRequest#getDocumentId()} 为空 → 创建。 */
    WriteResult write(WriteRequest req);

    /** 列出所有非删除文档（按 updatedAt 降序）。 */
    List<Document> list();

    /** 按 documentId 取最新版本（document + version 一同返回）。 */
    DocumentWithVersion get(String documentId);

    /** 按 versionId 取指定历史版本。 */
    DocumentVersion getVersion(String versionId);

    /** 按版本号降序列出文档的历史版本。 */
    List<DocumentVersion> listVersions(String documentId);

    /** Permanently remove a document and all of its versions. */
    boolean delete(String documentId);

    /** Attach server-owned metadata to the latest immutable content version. */
    void putLatestMetadata(String documentId, String key, Object value);

    /** Document + 对应版本的元组（避免 Pair 依赖）。 */
    final class DocumentWithVersion {
        public final Document document;
        public final DocumentVersion version;
        public DocumentWithVersion(Document document, DocumentVersion version) {
            this.document = document;
            this.version = version;
        }
    }
}
