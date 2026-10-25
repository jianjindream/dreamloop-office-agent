package com.jianjin.assistant.infrastructure.persistence;

import com.jianjin.assistant.config.AppConfig;
import com.jianjin.assistant.config.WorkspaceStorageConfig;
import com.jianjin.assistant.domain.document.*;
import com.jianjin.assistant.service.document.AttachmentStore;
import com.jianjin.assistant.service.document.DocumentLibraryService;
import com.jianjin.assistant.service.rag.RagService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkspacePersistenceTest {
    @TempDir Path directory;
    private WorkspaceStorageConfig config() {
        WorkspaceStorageConfig config=new WorkspaceStorageConfig(); config.setDirectory(directory.toString()); return config;
    }
    private WorkspaceDatabase open() { return new WorkspaceDatabase(config(),new AppConfig()); }
    private WriteRequest request(String content) {
        return new WriteRequest("项目周报","report",Document.SOURCE_UPLOAD,"ws:user",content,"摘要",new LinkedHashMap<>(Map.of("owner_key","ws:user")));
    }

    @Test void documentsVersionsOriginalsAndTrashSurviveRestart() throws Exception {
        String id,firstVersion;
        byte[] original="原始附件\n保留原始换行与字节".getBytes(StandardCharsets.UTF_8);
        String key=new AttachmentStore(config()).save(original);
        try(WorkspaceDatabase database=open()) {
            JdbcLibraryRepo repo=new JdbcLibraryRepo(database);
            WriteRequest first=request("第一版"); first.getMetadata().put("original_attachment",key); first.setStatus("draft");
            WriteResult result=repo.write(first); id=result.document.getId(); firstVersion=result.version.getId();
            WriteRequest second=request("第二版"); second.setDocumentId(id); second.getMetadata().put("original_attachment",key);
            repo.write(second);
            repo.putVersionMetadata(firstVersion,"index_status","indexed");
            assertEquals("not_indexed",repo.get(id).version.getMetadata().get("index_status"));
            assertTrue(repo.delete(id));
            assertTrue(repo.list().isEmpty());
        }
        try(WorkspaceDatabase database=open()) {
            JdbcLibraryRepo repo=new JdbcLibraryRepo(database);
            assertEquals(id,repo.listDeleted().get(0).getId()); assertTrue(repo.restore(id));
            assertEquals("第二版",repo.get(id).version.getContentMd()); assertEquals(2,repo.listVersions(id).size());
            assertEquals("第一版",repo.getVersion(firstVersion).getContentMd());
            assertEquals("ws:user",repo.get(id).version.getMetadata().get("owner_key"));
            String stored=String.valueOf(repo.get(id).version.getMetadata().get("original_attachment"));
            assertArrayEquals(original,new AttachmentStore(config()).read(stored).getContentAsByteArray());
        }
    }

    @Test void staleDraftIsRejectedAndConcurrentWritesKeepAllVersions() throws Exception {
        try(WorkspaceDatabase database=open()) {
            JdbcLibraryRepo repo=new JdbcLibraryRepo(database); String id=repo.write(request("第一版")).document.getId();
            ExecutorService executor=Executors.newFixedThreadPool(3);
            try {
                List<Future<?>> futures=new ArrayList<>();
                for(int i=0;i<3;i++) { int number=i; futures.add(executor.submit(() -> {
                    WriteRequest req=request("并发正文"+number); req.setDocumentId(id); repo.write(req);
                })); }
                for(Future<?> future:futures) future.get(10,TimeUnit.SECONDS);
            } finally { executor.shutdownNow(); }
            assertEquals(List.of(4,3,2,1),repo.listVersions(id).stream().map(DocumentVersion::getVersion).toList());
            WriteRequest stale=request("过期草稿"); stale.setDocumentId(id); stale.setBaseVersion(1);
            assertThrows(DocumentVersionConflictException.class,() -> repo.write(stale));
            assertEquals(4,repo.listVersions(id).size());
        }
    }

    @Test void failedIndexKeepsDocumentAndCanBeRetriedWithoutCreatingAnotherVersion() {
        RagService rag=mock(RagService.class);
        when(rag.ingest(anyString(),anyString())).thenThrow(new IllegalStateException("检索服务断开"));
        String id;
        try(WorkspaceDatabase database=open()) {
            JdbcLibraryRepo repo=new JdbcLibraryRepo(database);
            DocumentLibraryService service=new DocumentLibraryService(repo,rag);
            var result=service.writeDocument(request("已提交的正文"),true); id=result.document.getId();
            assertEquals("failed",result.indexStatus); assertNotNull(result.indexError);
            assertEquals("已提交的正文",repo.get(id).version.getContentMd());
        }
        try(WorkspaceDatabase database=open()) {
            JdbcLibraryRepo repo=new JdbcLibraryRepo(database);
            assertEquals("failed",repo.get(id).version.getMetadata().get("index_status"));
            doReturn(Map.entry(2,"hash")).when(rag).ingest(anyString(),anyString());
            new DocumentLibraryService(repo,rag).reingest(id,null);
            assertEquals("indexed",repo.get(id).version.getMetadata().get("index_status"));
            assertEquals(1,repo.listVersions(id).size());
        }
    }

    @Test void fullHistoryImportSearchAndDeletionAreDurableAndScoped() {
        List<ChatHistoryRepository.ImportedMessage> messages=new ArrayList<>();
        for(int i=0;i<503;i++) messages.add(new ChatHistoryRepository.ImportedMessage(i%2==0?"user":"assistant","第"+i+"条 75%_!",1000L+i));
        try(WorkspaceDatabase database=open()) {
            ChatHistoryRepository repo=new ChatHistoryRepository(database);
            assertTrue(repo.importSession("ws-a:user","legacy-1","周报",1000,messages));
            assertFalse(repo.importSession("ws-a:user","legacy-1","重复",1000,messages));
            repo.save("ws-b:user","legacy-1","user","另一个工作区");
        }
        try(WorkspaceDatabase database=open()) {
            ChatHistoryRepository repo=new ChatHistoryRepository(database);
            var first=repo.page("ws-a:user","legacy-1",0,500);
            assertEquals(500,first.size()); assertTrue(first.get(0).content.startsWith("第0条"));
            var second=repo.page("ws-a:user","legacy-1",first.get(499).id,500);
            assertEquals(3,second.size()); assertTrue(second.get(2).content.startsWith("第502条"));
            assertEquals(503,repo.listSessions("ws-a:user").get(0).messageCount);
            assertEquals(1,repo.listSessions("ws-a:user","75%_!").size());
            assertTrue(repo.listSessions("ws-b:user","75%_!").isEmpty());
            assertTrue(repo.renameSession("ws-a:user","legacy-1","季度汇报"));
            assertEquals("季度汇报",repo.listSessions("ws-a:user").get(0).title);
            assertTrue(repo.deleteSession("ws-a:user","legacy-1"));
            assertTrue(repo.page("ws-a:user","legacy-1",0,500).isEmpty());
            assertEquals(1,repo.listSessions("ws-b:user").size());
        }
        try(WorkspaceDatabase database=open()) { assertTrue(new ChatHistoryRepository(database).listSessions("ws-a:user").isEmpty()); }
    }

    @Test void invalidImportRollsBackAndUnsafeAttachmentKeysAreRejected() {
        try(WorkspaceDatabase database=open()) {
            ChatHistoryRepository repo=new ChatHistoryRepository(database);
            assertThrows(IllegalArgumentException.class,() -> repo.importSession("owner","s","title",1000,
                    List.of(new ChatHistoryRepository.ImportedMessage("system","伪造角色",1000))));
            assertTrue(repo.listSessions("owner").isEmpty());
        }
        assertThrows(IllegalArgumentException.class,() -> new AttachmentStore(config()).read("../workspace.mv.db"));
    }
}
