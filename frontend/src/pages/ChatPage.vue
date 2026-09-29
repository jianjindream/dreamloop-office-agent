<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  ArrowUp,
  AtSign,
  BarChart3,
  Check,
  ChevronDown,
  CircleAlert,
  FileText,
  Globe2,
  Lightbulb,
  LoaderCircle,
  Paperclip,
  PenLine,
  ShieldCheck,
  Square,
  Wrench,
  X,
} from 'lucide-vue-next'
import { agentApi } from '@/api/agent'
import ChatMessageView from '@/components/chat/ChatMessage.vue'
import { useChatStore, type ChatMessage } from '@/stores/chat'
import { useDocumentCanvasStore } from '@/stores/documentCanvas'
import { useWorkspaceStore } from '@/stores/workspace'
import type { ChatResponse, ChatStreamEvent, SearchResult, ToolSummary } from '@/types/api'

const chat = useChatStore()
const canvas = useDocumentCanvasStore()
const workspace = useWorkspaceStore()
const prompt = ref('')
const isStreaming = ref(false)
const activeMessageId = ref('')
const abortController = ref<AbortController | null>(null)
const messageList = ref<HTMLElement | null>(null)
const promptInput = ref<HTMLTextAreaElement | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const availableTools = ref<ToolSummary[]>([])
const selectedTools = ref<string[]>([])
const toolMenuOpen = ref(false)
const includeDocument = ref(true)
const attachment = ref<{ name: string; status: 'uploading' | 'ready' | 'error' } | null>(null)

const messages = computed(() => chat.currentSession?.messages ?? [])
const hasMessages = computed(() => messages.value.length > 0)

const suggestions = [
  { icon: FileText, title: '总结一份文档', description: '提炼重点、结论与后续行动', prompt: '请帮我总结这份文档，并列出关键结论和下一步行动。', tone: 'violet' },
  { icon: BarChart3, title: '撰写工作汇报', description: '把工作进展整理为专业汇报', prompt: '请根据我的工作内容，生成一份结构清晰的本周工作汇报。', tone: 'blue' },
  { icon: Lightbulb, title: '生成项目方案', description: '从目标到执行计划一次梳理', prompt: '请帮我制定一份项目实施方案，包含目标、里程碑、风险和人员分工。', tone: 'amber' },
  { icon: PenLine, title: '润色现有内容', description: '改善表达、结构和专业度', prompt: '请帮我润色以下内容，使表达更专业、简洁、自然。', tone: 'green' },
]

const fallbackTools: ToolSummary[] = [
  { name: 'search_web', description: '联网搜索实时信息' },
  { name: 'get_weather', description: '查询城市天气' },
  { name: 'get_time', description: '查询日期和时间' },
]

const scrollToBottom = async () => {
  await nextTick()
  messageList.value?.scrollTo({ top: messageList.value.scrollHeight, behavior: 'smooth' })
}

const useSuggestion = async (value: string) => {
  prompt.value = value
  await nextTick()
  promptInput.value?.focus()
}

const summarizeParams = (params: unknown) => {
  try {
    const value = JSON.stringify(params ?? {})
    return value.length > 180 ? `${value.slice(0, 180)}…` : value
  } catch {
    return ''
  }
}

const updateRunningEvents = (message: ChatMessage) => {
  message.execution.forEach((event) => {
    if (event.status === 'running' || event.status === 'pending') event.status = 'success'
  })
}

const handleStreamEvent = (messageId: string, event: ChatStreamEvent) => {
  const data = event.data as Record<string, unknown>
  const assistant = chat.currentSession?.messages.find((item) => item.id === messageId)
  if (!assistant) return

  switch (event.type) {
    case 'mode': {
      const mode = String(data.mode ?? 'chat')
      chat.updateMessage(messageId, { mode })
      chat.addExecution(messageId, { type: 'mode', title: `已选择 ${mode.toUpperCase()} 处理模式`, status: 'success' })
      break
    }
    case 'step':
      chat.addExecution(messageId, { type: 'step', title: String(data.name ?? '正在分析任务'), status: 'success' })
      break
    case 'tool_call': {
      const tool = String(data.tool ?? '工具')
      chat.addExecution(messageId, { type: 'tool', title: `正在调用 ${tool}`, detail: summarizeParams(data.params), tool, status: 'running' })
      break
    }
    case 'observation': {
      const tool = String(data.tool ?? '工具')
      const result = String(data.result ?? '')
      chat.completeLatestTool(messageId, tool, result, result.includes('失败'))
      break
    }
    case 'rag_result':
      chat.updateMessage(messageId, { sources: (data.chunks as SearchResult[]) ?? [] })
      break
    case 'token':
      chat.appendContent(messageId, String(data.chunk ?? ''))
      break
    case 'graph_ready':
      chat.addExecution(messageId, { type: 'graph', title: '已生成多步骤执行计划', detail: `${(data.levels as unknown[] | undefined)?.length ?? 0} 个执行层级`, status: 'success' })
      break
    case 'node_start':
      chat.addExecution(messageId, { type: 'tool', title: `正在执行 ${String(data.tool ?? data.id ?? '任务')}`, tool: String(data.tool ?? ''), status: 'running' })
      break
    case 'node_done':
      chat.completeLatestTool(messageId, String(data.tool ?? ''), `执行状态：${String(data.status ?? 'done')}`)
      break
    case 'race_won':
      chat.addExecution(messageId, { type: 'race', title: `${String(data.tool ?? '任务')} 返回了最佳结果`, detail: `执行节点 ${String(data.winner ?? '')}`, status: 'success' })
      break
    case 'done': {
      const response = event.data as ChatResponse
      updateRunningEvents(assistant)
      chat.updateMessage(messageId, {
        content: response.answer ?? assistant.content,
        mode: response.mode ?? assistant.mode,
        sources: response.search_results ?? assistant.sources,
        extractedInfo: response.extracted_info,
        status: response.interrupted ? 'stopped' : 'complete',
      })
      break
    }
    case 'error':
      throw new Error(String(data.message ?? '生成过程中发生错误'))
  }
  scrollToBottom()
}

const sendMessage = async (content = prompt.value, appendUser = true) => {
  const text = content.trim()
  if (!text || isStreaming.value) return
  const session = chat.ensureSession()
  if (appendUser) {
    chat.addMessage({ role: 'user', content: text, status: 'complete', execution: [], sources: [] })
  }
  const assistant = chat.addMessage({ role: 'assistant', content: '', status: 'streaming', execution: [], sources: [] })
  activeMessageId.value = assistant.id
  prompt.value = ''
  isStreaming.value = true
  abortController.value = new AbortController()
  await scrollToBottom()

  try {
    const documentContext = canvas.open && includeDocument.value && canvas.content.trim()
      ? `\n\n[当前关联文档：${canvas.title}]\n${canvas.content.slice(0, 12000)}\n[/当前关联文档]`
      : ''
    await agentApi.streamChat(
      {
        message: `${text}${documentContext}`,
        use_rag: workspace.knowledgeEnabled,
        selected_tools: selectedTools.value,
        explicit: selectedTools.value.length > 0,
        user_id: chat.userId,
        session_id: session.id,
      },
      {
        signal: abortController.value.signal,
        onEvent: (event) => handleStreamEvent(assistant.id, event),
      },
    )
    const latest = chat.currentSession?.messages.find((item) => item.id === assistant.id)
    if (latest?.status === 'streaming') {
      chat.updateMessage(assistant.id, {
        status: latest.content ? 'complete' : 'error',
        error: latest.content ? undefined : '连接已结束，但服务没有返回最终答案。',
      })
    }
  } catch (error) {
    if (abortController.value?.signal.aborted) {
      chat.updateMessage(assistant.id, { status: 'stopped' })
    } else {
      chat.updateMessage(assistant.id, {
        status: 'error',
        error: error instanceof Error ? error.message : '无法连接到 AI 服务，请检查后端是否已启动。',
      })
    }
  } finally {
    chat.persist()
    isStreaming.value = false
    activeMessageId.value = ''
    abortController.value = null
    scrollToBottom()
  }
}

const toDocument = (messageId: string) => {
  const session = chat.currentSession
  const index = session?.messages.findIndex((message) => message.id === messageId) ?? -1
  if (!session || index < 0) return
  const message = session.messages[index]
  if (!message?.content.trim()) return
  if (canvas.open && canvas.dirty && !window.confirm('当前文档有未保存的修改，确定要用此回答创建新草稿吗？')) return
  const question = [...session.messages.slice(0, index)].reverse().find((item) => item.role === 'user')?.content ?? ''
  const heading = message.content.match(/^#{1,3}\s+(.+)$/m)?.[1]
  const title = (heading || question.replace(/\s+/g, ' ').slice(0, 36) || '对话生成的文档').trim()
  canvas.openDraft(title, message.content, session.id, message.id)
}

const stopGeneration = async () => {
  if (!isStreaming.value) return
  abortController.value?.abort()
  if (activeMessageId.value) chat.updateMessage(activeMessageId.value, { status: 'stopped' })
  await agentApi.cancel().catch(() => undefined)
}

const retryMessage = (messageId: string) => {
  if (isStreaming.value) return
  const session = chat.currentSession
  const index = session?.messages.findIndex((message) => message.id === messageId) ?? -1
  if (!session || index < 0) return
  const userMessage = [...session.messages.slice(0, index)].reverse().find((message) => message.role === 'user')
  if (!userMessage) return
  chat.removeMessage(messageId)
  sendMessage(userMessage.content, false)
}

const toggleTool = (toolName: string) => {
  selectedTools.value = selectedTools.value.includes(toolName)
    ? selectedTools.value.filter((name) => name !== toolName)
    : [...selectedTools.value, toolName]
}

const handleKeydown = (event: KeyboardEvent) => {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    sendMessage()
  }
}

const chooseAttachment = () => fileInput.value?.click()
const uploadAttachment = async (event: Event) => {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  attachment.value = { name: file.name, status: 'uploading' }
  try {
    await agentApi.uploadFile(file)
    attachment.value = { name: file.name, status: 'ready' }
    workspace.knowledgeEnabled = true
  } catch {
    attachment.value = { name: file.name, status: 'error' }
  } finally {
    input.value = ''
  }
}

onMounted(async () => {
  if (chat.currentSessionId && !chat.currentSession) chat.currentSessionId = ''
  try {
    availableTools.value = await agentApi.tools()
  } catch {
    availableTools.value = fallbackTools
  }
})

onBeforeUnmount(() => abortController.value?.abort())
</script>

<template>
  <div class="chat-page" :class="{ 'has-messages': hasMessages }">
    <div v-if="!hasMessages" class="welcome-canvas">
      <section class="welcome-block">
        <div class="ai-orb"><span></span><ShieldCheck :size="25" stroke-width="1.7" /></div>
        <em>你的智能办公搭档</em>
        <h2>下午好，准备处理什么工作？</h2>
        <p>我可以结合工作区资料，帮你检索、分析、写作和执行任务。</p>
      </section>
      <section class="suggestion-grid" aria-label="快捷任务">
        <button v-for="item in suggestions" :key="item.title" class="suggestion-card" type="button" @click="useSuggestion(item.prompt)">
          <span class="suggestion-icon" :class="item.tone"><component :is="item.icon" :size="18" /></span>
          <span><strong>{{ item.title }}</strong><small>{{ item.description }}</small></span>
          <ArrowUp class="suggestion-arrow" :size="15" />
        </button>
      </section>
    </div>

    <section v-else ref="messageList" class="message-list" aria-live="polite">
      <ChatMessageView v-for="message in messages" :key="message.id" :message="message" @retry="retryMessage" @to-document="toDocument" />
    </section>

    <div class="composer-area">
      <button v-if="canvas.open && canvas.content.trim()" class="document-context-chip" :class="{ active: includeDocument }" type="button" @click="includeDocument = !includeDocument"><FileText :size="13" />{{ includeDocument ? '已引用' : '未引用' }}：{{ canvas.title }}<Check v-if="includeDocument" :size="12" /></button>
      <div v-if="attachment" class="attachment-chip" :class="attachment.status">
        <FileText :size="14" /><span>{{ attachment.name }}</span>
        <LoaderCircle v-if="attachment.status === 'uploading'" class="spinning" :size="13" />
        <Check v-else-if="attachment.status === 'ready'" :size="13" />
        <CircleAlert v-else :size="13" />
        <button type="button" aria-label="移除附件" @click="attachment = null"><X :size="12" /></button>
      </div>
      <section class="composer-wrap">
        <div class="composer-focus-line"></div>
        <textarea ref="promptInput" v-model="prompt" rows="2" aria-label="向 DreamLoop 提问" placeholder="描述你想完成的工作，或粘贴需要处理的内容……" @keydown="handleKeydown"></textarea>
        <div class="composer-toolbar">
          <div class="composer-tools">
            <input ref="fileInput" class="sr-only" type="file" accept=".pdf,.txt,.md,text/plain,text/markdown,application/pdf" @change="uploadAttachment" />
            <button type="button" aria-label="添加附件" title="添加附件" @click="chooseAttachment"><Paperclip :size="17" /></button>
            <button type="button" aria-label="引用当前文档" title="引用当前文档" :disabled="!canvas.open" @click="includeDocument = !includeDocument"><AtSign :size="17" /></button>
            <span class="toolbar-divider"></span>
            <button class="context-chip" :class="{ active: workspace.knowledgeEnabled }" type="button" @click="workspace.toggleKnowledge"><ShieldCheck :size="14" />知识库<Check v-if="workspace.knowledgeEnabled" :size="12" /></button>
            <div class="tool-selector">
              <button class="context-chip" :class="{ active: selectedTools.length }" type="button" @click="toolMenuOpen = !toolMenuOpen"><Wrench :size="14" />{{ selectedTools.length ? `${selectedTools.length} 个工具` : '自动选择工具' }}<ChevronDown :size="13" /></button>
              <div v-if="toolMenuOpen" class="tool-menu">
                <div><strong>选择工具</strong><small>由 AI 按需调用</small></div>
                <button v-for="tool in availableTools" :key="tool.name" type="button" @click="toggleTool(tool.name)"><span><Globe2 :size="14" /></span><span><strong>{{ tool.name }}</strong><small>{{ tool.description }}</small></span><i :class="{ checked: selectedTools.includes(tool.name) }"><Check v-if="selectedTools.includes(tool.name)" :size="10" /></i></button>
              </div>
            </div>
          </div>
          <button v-if="isStreaming" class="stop-button" type="button" aria-label="停止生成" @click="stopGeneration"><Square :size="13" fill="currentColor" /></button>
          <button v-else class="send-button" :disabled="!prompt.trim()" type="button" aria-label="发送消息" @click="sendMessage()"><ArrowUp :size="18" /></button>
        </div>
      </section>
      <div class="composer-note"><span><i></i> 企业数据仅在当前工作区内使用</span><span>Enter 发送 · Shift + Enter 换行</span></div>
    </div>
  </div>
</template>

<style scoped>
.chat-page { display: flex; width: 100%; min-height: 100%; padding: 34px 30px 30px; flex-direction: column; align-items: center; justify-content: center; }.chat-page.has-messages { height: 100%; min-height: 0; padding: 0; justify-content: flex-start; }.welcome-canvas { width: min(760px,100%); }.welcome-block { text-align: center; }.ai-orb { position: relative; display: inline-flex; align-items: center; justify-content: center; width: 54px; height: 54px; margin-bottom: 15px; border: 1px solid color-mix(in srgb,var(--brand) 20%,var(--border)); border-radius: 18px; background: linear-gradient(145deg,var(--bg-elevated),var(--brand-softer)); color: var(--brand); box-shadow: 0 14px 34px rgba(88,77,210,.12); }.ai-orb span { position: absolute; width: 32px; height: 32px; border-radius: 50%; background: color-mix(in srgb,var(--brand) 18%,transparent); filter: blur(12px); }.ai-orb svg { position: relative; }.welcome-block em { display: block; color: var(--brand); font-size: 10px; font-style: normal; font-weight: 700; letter-spacing: .08em; }.welcome-block h2 { margin: 8px 0 0; color: var(--text-strong); font-size: clamp(25px,3vw,32px); font-weight: 680; letter-spacing: -.045em; }.welcome-block p { margin: 10px 0 0; color: var(--text-muted); font-size: 13px; }
.document-context-chip{display:flex;align-items:center;max-width:100%;gap:5px;margin:0 0 7px 3px;padding:6px 9px;overflow:hidden;border:1px solid var(--border);border-radius:8px;background:var(--bg-elevated);color:var(--text-muted);font-size:9px;text-overflow:ellipsis;white-space:nowrap;cursor:pointer}.document-context-chip.active{border-color:color-mix(in srgb,var(--brand) 25%,var(--border));background:var(--brand-softer);color:var(--brand)}
.suggestion-grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 10px; margin-top: 32px; }.suggestion-card { display: flex; align-items: center; min-width: 0; gap: 11px; padding: 13px; border: 1px solid var(--border); border-radius: 14px; background: color-mix(in srgb,var(--bg-elevated) 90%,transparent); box-shadow: var(--shadow-sm); text-align: left; cursor: pointer; transition: 170ms; }.suggestion-card:hover { border-color: color-mix(in srgb,var(--brand) 22%,var(--border)); background: var(--bg-elevated); box-shadow: var(--shadow-md); transform: translateY(-2px); }.suggestion-icon { display: inline-flex; align-items: center; justify-content: center; width: 36px; height: 36px; flex: 0 0 auto; border-radius: 11px; }.suggestion-icon.violet{background:var(--brand-soft);color:var(--brand)}.suggestion-icon.blue{background:#eaf3ff;color:#3978bd}.suggestion-icon.amber{background:var(--warning-soft);color:var(--warning)}.suggestion-icon.green{background:var(--success-soft);color:var(--success)}.suggestion-card>span:nth-child(2){display:flex;min-width:0;flex:1;flex-direction:column}.suggestion-card strong{color:var(--text-strong);font-size:12px;font-weight:630}.suggestion-card small{margin-top:4px;overflow:hidden;color:var(--text-faint);font-size:9px;text-overflow:ellipsis;white-space:nowrap}.suggestion-arrow{color:var(--text-faint);opacity:0;transform:rotate(45deg);transition:160ms}.suggestion-card:hover .suggestion-arrow{opacity:1}
.message-list { width: 100%; min-height: 0; flex: 1; overflow-y: auto; padding: 14px 0 180px; scroll-behavior: smooth; }.message-list :deep(.message + .message.assistant) { border-top: 1px solid color-mix(in srgb,var(--border) 65%,transparent); background: color-mix(in srgb,var(--bg-elevated) 28%,transparent); }
.composer-area { width: min(820px,calc(100% - 48px)); margin-top: 16px; }.has-messages .composer-area { position: absolute; z-index: 5; right: 0; bottom: 0; left: 0; width: min(820px,calc(100% - 48px)); margin: 0 auto; padding: 20px 0 16px; background: linear-gradient(0deg,var(--bg-app) 72%,transparent); }.attachment-chip { display: flex; align-items: center; width: fit-content; max-width: 100%; gap: 6px; margin: 0 0 7px 3px; padding: 6px 8px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg-elevated); color: var(--text-muted); font-size: 8px; box-shadow: var(--shadow-sm); }.attachment-chip span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.attachment-chip.ready{color:var(--success)}.attachment-chip.error{color:var(--danger)}.attachment-chip button{display:inline-flex;padding:0;border:0;background:transparent;color:inherit;cursor:pointer}.spinning{animation:spin 1s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}
.composer-wrap { position: relative; border: 1px solid var(--border-strong); border-radius: 17px; background: var(--bg-elevated); box-shadow: 0 16px 44px rgba(32,34,48,.09); transition: 170ms; }.composer-wrap:focus-within { border-color: color-mix(in srgb,var(--brand) 50%,var(--border)); box-shadow: 0 18px 48px rgba(75,65,187,.12); }.composer-focus-line { position: absolute; top: 0; right: 24%; left: 24%; height: 1px; background: linear-gradient(90deg,transparent,var(--brand),transparent); opacity: 0; transition: 170ms; }.composer-wrap:focus-within .composer-focus-line{right:8%;left:8%;opacity:.7}.composer-wrap textarea { width: 100%; min-height: 70px; max-height: 180px; padding: 15px 17px 6px; resize: none; border: 0; outline: 0; background: transparent; color: var(--text-strong); font-size: 12px; line-height: 1.65; }.composer-wrap textarea::placeholder{color:var(--text-faint)}.composer-toolbar{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:7px 9px 9px 11px}.composer-tools{display:flex;min-width:0;align-items:center;gap:4px}.composer-tools>button:not(.context-chip){display:inline-flex;align-items:center;justify-content:center;width:30px;height:30px;padding:0;border:0;border-radius:8px;background:transparent;color:var(--text-muted);cursor:pointer}.composer-tools>button:hover{background:var(--bg-hover);color:var(--text-strong)}.toolbar-divider{width:1px;height:17px;margin:0 3px;background:var(--border)}.context-chip{display:inline-flex;align-items:center;min-height:29px;gap:5px;padding:0 8px;border:1px solid var(--border);border-radius:8px;background:var(--bg-subtle);color:var(--text-muted);font-size:8px;font-weight:570;cursor:pointer}.context-chip.active{border-color:color-mix(in srgb,var(--brand) 20%,var(--border));background:var(--brand-softer);color:var(--brand)}
.tool-selector{position:relative}.tool-menu{position:absolute;z-index:20;bottom:38px;left:0;width:270px;padding:8px;border:1px solid var(--border);border-radius:13px;background:var(--bg-elevated);box-shadow:var(--shadow-md)}.tool-menu>div{display:flex;padding:6px 7px 9px;flex-direction:column}.tool-menu>div strong{color:var(--text-strong);font-size:10px}.tool-menu>div small{margin-top:3px;color:var(--text-faint);font-size:8px}.tool-menu>button{display:flex;align-items:center;width:100%;gap:8px;padding:8px 7px;border:0;border-radius:9px;background:transparent;text-align:left;cursor:pointer}.tool-menu>button:hover{background:var(--bg-hover)}.tool-menu>button>span:first-child{display:inline-flex;align-items:center;justify-content:center;width:28px;height:28px;border-radius:8px;background:var(--brand-soft);color:var(--brand)}.tool-menu>button>span:nth-child(2){display:flex;min-width:0;flex:1;flex-direction:column}.tool-menu>button strong{color:var(--text);font-size:9px}.tool-menu>button small{margin-top:2px;overflow:hidden;color:var(--text-faint);font-size:7px;text-overflow:ellipsis;white-space:nowrap}.tool-menu i{display:inline-flex;align-items:center;justify-content:center;width:15px;height:15px;border:1px solid var(--border-strong);border-radius:5px}.tool-menu i.checked{border-color:var(--brand);background:var(--brand);color:white}
.send-button,.stop-button{display:inline-flex;align-items:center;justify-content:center;width:36px;height:36px;flex:0 0 auto;border:0;border-radius:11px;color:white;cursor:pointer;transition:150ms}.send-button{background:var(--brand);box-shadow:0 7px 16px rgba(97,87,230,.24)}.send-button:hover:not(:disabled){background:var(--brand-hover);transform:translateY(-1px)}.send-button:disabled{background:var(--border-strong);box-shadow:none;cursor:default}.stop-button{background:var(--text-strong);box-shadow:var(--shadow-sm)}.composer-note{display:flex;justify-content:space-between;gap:18px;padding:8px 4px 0;color:var(--text-faint);font-size:7px}.composer-note span{display:inline-flex;align-items:center;gap:6px}.composer-note i{width:5px;height:5px;border-radius:50%;background:var(--success)}
@media(max-width:720px){.chat-page{padding:25px 16px 110px;justify-content:flex-start}.chat-page.has-messages{padding:0}.suggestion-grid{grid-template-columns:1fr;margin-top:24px}.message-list{padding-bottom:165px}.composer-area,.has-messages .composer-area{width:calc(100% - 24px)}.has-messages .composer-area{bottom:74px;padding-bottom:8px}.context-chip{display:none}.tool-selector .context-chip{display:inline-flex}.composer-note span:last-child{display:none}.welcome-block h2{font-size:25px}}
</style>
