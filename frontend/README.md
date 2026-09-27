# DreamLoop Web

DreamLoop 的 Vue 3 前端工程。当前已经完成产品级视觉骨架、核心聊天体验与工作区管理，包括 SSE 对话、知识库、工具中心和系统状态。

## 本地开发

```bash
npm install
npm run dev
```

开发服务器默认运行在 `http://localhost:5173`，并将 `/api` 请求代理到 `http://localhost:8090`。

## 构建

```bash
npm run build
```

构建结果输出到 `dist/`。当前旧版页面仍保留在 Spring Boot 的 `src/main/resources/static/index.html`，生产集成将在后续阶段完成。

## 目录约定

- `src/api`：统一 HTTP 请求与后端接口
- `src/components`：跨页面组件和基础 UI
- `src/layouts`：应用级布局
- `src/pages`：路由页面
- `src/router`：路由定义
- `src/stores`：Pinia 状态
- `src/types`：接口和领域类型

## 当前页面

- `/chat`：AI 对话工作台
- `/knowledge`：知识库
- `/documents`：AI 文档
- `/tools`：工具中心
- `/status`：系统与基础设施状态
- `/settings`：工作区设置

## 聊天能力

- 使用 `POST /api/chat/stream` 接收语义事件流
- 支持停止生成、失败重试和服务状态探测
- 会话与消息保存到浏览器本地存储
- Markdown 内容经过 DOMPurify 清洗后渲染
- 常用语言使用 Shiki 高亮，并支持复制代码
- 工具调用、执行步骤和知识库引用可视化
- PDF、Markdown、TXT 通过 multipart 接口上传

## 工作区管理能力

- 知识库读取真实文档列表，支持拖拽/批量上传、上传进度、解析结果与正文详情
- 已上传文档可移除 RAG 索引；后端暂未提供删除文档记录接口
- 工具中心读取运行时工具清单，支持参数化注册 HTTP MCP 工具
- 系统状态展示模型、RAG、记忆、执行快照与五类基础设施连接状态
- 状态页每 30 秒自动刷新，并保留明确的空态、失败态与降级提示
