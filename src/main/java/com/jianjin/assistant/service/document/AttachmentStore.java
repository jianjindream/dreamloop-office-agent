package com.jianjin.assistant.service.document;

import com.jianjin.assistant.config.WorkspaceStorageConfig;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

/** Original bytes live outside the web root; access is authorized through the owning document. */
@Service
public class AttachmentStore {
    private final Path directory;
    public AttachmentStore(WorkspaceStorageConfig config) {
        directory=Path.of(config.getDirectory()).toAbsolutePath().normalize().resolve("attachments");
        try { Files.createDirectories(directory); }
        catch(IOException e) { throw new IllegalStateException("无法创建附件存储目录",e); }
    }
    public String save(byte[] bytes) {
        String key=UUID.randomUUID().toString().replace("-","");
        Path temporary=null;
        try {
            temporary=Files.createTempFile(directory,"upload-",".tmp");
            Files.write(temporary,bytes);
            try { Files.move(temporary,path(key),StandardCopyOption.ATOMIC_MOVE); }
            catch(AtomicMoveNotSupportedException e) { Files.move(temporary,path(key)); }
            return key;
        } catch(IOException e) { throw new IllegalStateException("原始附件保存失败",e); }
        finally { if(temporary!=null) try { Files.deleteIfExists(temporary); } catch(IOException ignored) {} }
    }
    public Resource read(String key) {
        Path file=path(key);
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("原始附件不存在");
        return new FileSystemResource(file);
    }
    public void discard(String key) {
        try { Files.deleteIfExists(path(key)); }
        catch(IOException e) { throw new IllegalStateException("附件清理失败",e); }
    }
    private Path path(String key) {
        if(key==null || !key.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("附件标识不合法");
        return directory.resolve(key);
    }
}
