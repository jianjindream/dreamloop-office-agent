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

构建结果输出到 `dist/`。Docker 多阶段构建会将该目录复制进 Spring Boot 的静态资源目录，再打包为单个后端镜像；直接在仓库根目录运行 Maven 构建不会自动编译 Vue，仍会使用旧版静态入口。

## 质量检查

```bash
npm test
npx playwright install chromium
npm run test:e2e
npm run build
npm audit --registry=https://registry.npmjs.org --audit-level=moderate
```

端到端测试使用模拟 API，覆盖桌面、平板、手机三个视口的导航、主题、搜索、文档编辑/保存/历史版本及聊天转文档流程。首次运行需要安装 Chromium；后端不必启动。

## Docker 交付

在仓库根目录运行 `docker build -t dreamloop-office-agent .`。镜像内包含 Vue 前端与 Spring Boot 后端；访问 `/chat` 等前端路由时可直接刷新。运行时仍需按项目后端配置提供所需环境变量和基础设施。

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

## AI 文档工作台

- `/documents` 展示后端真实文档，可搜索、切换列表/网格并在右侧 Canvas 编辑 Markdown
- 聊天回答可转为文档草稿；打开文档时可在聊天输入区选择是否引用当前正文
- AI 改写、扩写、总结会先更新草稿，由用户确认后手动保存
- 新建使用 `POST /api/documents`，保存更新使用 `PUT /api/documents/{id}`，每次保存创建新版本
- 历史版本通过 `GET /api/documents/{id}/versions` 查看；可载入旧版正文再另存为新版本
- 当前文档库使用后端内存仓储，重启后历史不会保留；持久化存储留待后续阶段接入
