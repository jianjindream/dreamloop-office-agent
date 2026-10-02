# 产品化后端接口

所有接口位于 `/api`。默认开发模式不强制登录，通过 `X-User-Id` 与 `X-Workspace-Id` 建立隔离作用域；生产环境应设置 `APP_AUTH_ENABLED=true` 并在 `app.auth.tokens` 中配置 Bearer token。token 对应值格式为 `userId:workspaceId`。前端可使用 `VITE_API_TOKEN` 提交 token。

## 会话与消息

- `GET /sessions`：当前用户在当前工作区中的会话列表。
- `GET /sessions/{sessionId}/messages?after_id=0&limit=200`：完整消息历史，最多 500 条。
- `PATCH /sessions/{sessionId}`：请求体 `{ "title": "新标题" }`。
- `DELETE /sessions/{sessionId}`：删除会话与消息。

PostgreSQL 可用时使用 `chat_sessions` 与 `chat_history`；不可用时自动退化到进程内存。对话摘要只影响模型上下文，不再删除产品消息历史。

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
- `DELETE /documents/{id}`：按 ID 删除文档及全部版本。
- `POST /upload/file`：可附带 `upload_id`。
- `GET /uploads/{uploadId}/progress`：返回 `received`、`reading`、`parsing`、`indexing`、`completed` 或 `failed` 阶段及百分比。

文档列表、详情、更新、版本和删除均按当前工作区用户校验。

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
