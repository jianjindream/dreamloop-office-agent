# 产品化后端接口

所有接口位于 `/api`。默认开发模式不强制登录，通过 `X-User-Id` 与 `X-Workspace-Id` 建立隔离作用域；生产环境应设置 `APP_AUTH_ENABLED=true` 并在 `app.auth.tokens` 中配置 Bearer token。token 对应值格式为 `userId:workspaceId`。前端可使用 `VITE_API_TOKEN` 提交 token。

## 会话与消息

- `GET /sessions?q=关键词`：当前用户在当前工作区中的会话列表；可按标题或消息正文搜索，特殊字符按字面匹配。
- `GET /sessions/{sessionId}/messages?after_id=0&limit=200`：完整消息历史，最多 500 条。
- `PATCH /sessions/{sessionId}`：请求体 `{ "title": "新标题" }`。
- `DELETE /sessions/{sessionId}`：删除会话与消息。

会话使用工作成果数据库，默认是 `data/workspace/workspace.mv.db`，显式配置 `APP_STORAGE_BACKEND=postgres` 时使用 PostgreSQL。数据库错误会明确失败，不会退化到内存。对话摘要只影响模型上下文，不删除产品消息历史。

消息接口按 ID 正序返回最早未读取的消息。使用最后一条 ID 作为下一页 `after_id`，直到返回数量小于 `limit`；每页最多 500 条。模型上下文仍独立读取最近消息。

- `POST /sessions/import`：幂等迁移旧浏览器会话。请求为 `{ "id":"session-...", "title":"旧周报", "createdAt":1792742400000, "messages":[{"role":"user","content":"原始消息","createdAt":1792742400000}] }`。身份始终来自请求认证；服务端已有同 ID 会话时保持服务端记录，不重复追加。每个会话最多 10000 条，非法输入整体拒绝。

## 生成任务与 SSE

同步 `POST /chat` 的响应包含 `task_id`。流式 `POST /chat/stream` 的每个 SSE 事件都包含：

```json
{
  "task_id": "task_...",
  "event_type": "start",
  "sequence": 1,
  "emitted_at": "2026-09-24T09:00:00Z"
}
```

各事件原有业务字段与上述字段位于同一层，以兼容现有客户端。取消指定任务使用 `POST /chat/tasks/{taskId}/cancel`；服务端同时校验任务所属用户和工作区。

## 文档与上传

- `POST /documents`、`PUT /documents/{id}`：创建或更新文档；更新标题即重命名。
- `DELETE /documents/{id}`：移入回收站，保留版本和原始附件，同时移除该文档已知的知识库索引；移除索引失败会明确报错。
- `GET /documents?status=deleted`：当前用户的回收站。
- `POST /documents/{id}/restore`：恢复文档，保留全部版本和附件；需要检索时重新建立索引。
- `POST /documents/{id}/reindex`：对最新版本重建索引，不创建新正文版本。
- `GET /documents/{id}/original`：下载已授权文档的原始上传字节；AI 创建的文档返回 `ATTACHMENT_NOT_FOUND`。
- `POST /upload/file`：可附带 `upload_id`。
- `GET /uploads/{uploadId}/progress`：返回 `received`、`reading`、`parsing`、`indexing`、`completed` 或 `failed` 阶段及百分比。

文档列表、详情、更新、版本和删除均按当前工作区用户校验。

创建和更新支持 `draft: true` 标记自动保存草稿。更新支持 `base_version`，事务中发现版本不一致返回 `409 / DOCUMENT_VERSION_CONFLICT`，客户端保留草稿。

上传及保存返回 `document_saved: true` 和 `index_status`（`not_indexed`、`indexed`、`failed`）。索引失败仍保留正文并返回 `index_error`；不能只依据“上传进度 100%”判断检索已可用。索引状态记录在具体版本元数据中。

## MCP 工具

- `POST /tools/mcp`：注册。
- `PUT /tools/mcp/{name}`：编辑。
- `DELETE /tools/mcp/{name}`：删除。

MCP 工具按工作区隔离。默认拒绝指向本机、链路本地或私网地址的 endpoint，以降低 SSRF 风险；可信内网部署可显式设置 `APP_SECURITY_ALLOW_PRIVATE_MCP_ENDPOINTS=true`。

## 错误格式

普通 HTTP 错误统一返回适当的 4xx/5xx 状态和以下结构：

```json
{
  "code": "DOCUMENT_NOT_FOUND",
  "message": "文档不存在",
  "error": "文档不存在",
  "requestId": "...",
  "timestamp": "...",
  "details": {}
}
```

`error` 是旧前端兼容字段。SSE 错误事件使用同类错误码，并继续携带任务元数据。
