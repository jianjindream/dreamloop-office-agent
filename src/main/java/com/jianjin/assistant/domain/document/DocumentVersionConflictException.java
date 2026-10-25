package com.jianjin.assistant.domain.document;

public class DocumentVersionConflictException extends RuntimeException {
    public DocumentVersionConflictException() { super("文档已在其他位置更新，当前草稿已保留，请重新打开文档核对后保存"); }
}
