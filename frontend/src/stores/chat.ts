import { defineStore } from 'pinia'
import { agentApi } from '@/api/agent'
import { API_BASE_URL } from '@/api/http'
import type { SearchResult, ServerMessage, ServerSession } from '@/types/api'

export type MessageRole = 'user' | 'assistant'
export type MessageStatus = 'complete' | 'streaming' | 'error' | 'stopped'
export type ExecutionStatus = 'pending' | 'running' | 'success' | 'error'

export interface ExecutionEvent {
  id: string
  type: 'mode' | 'step' | 'tool' | 'observation' | 'graph' | 'race'
  title: string
  detail?: string
  tool?: string
  status: ExecutionStatus
  startedAt: number
}
export interface ChatMessage {
  id: string
  serverId?: number
  role: MessageRole
  content: string
  createdAt: number
  status: MessageStatus
  mode?: string
  execution: ExecutionEvent[]
  sources: SearchResult[]
  extractedInfo?: string
  error?: string
}
export interface ChatSession {
  id: string
  title: string
  createdAt: number
  updatedAt: number
  messages: ChatMessage[]
  server?: boolean
  loaded?: boolean
  loading?: boolean
  loadError?: string
}
const LEGACY_KEY = 'dreamloop-chat-sessions-v2'
const LEGACY_CURRENT = 'dreamloop-current-session-v2'
const USER_KEY = 'dreamloop-user-id'
const uid = (prefix: string) => prefix + '-' + crypto.randomUUID()
const readJson = <T>(key: string, fallback: T): T => {
  try { return JSON.parse(localStorage.getItem(key) ?? 'null') as T ?? fallback } catch { return fallback }
}
const legacySessions = (): ChatSession[] => {
  const value = readJson<ChatSession[]>(LEGACY_KEY, [])
  return Array.isArray(value) ? value.filter((s) => typeof s.id === 'string' && Array.isArray(s.messages)) : []
}
const sessionFromServer = (row: ServerSession, cached?: ChatSession): ChatSession => ({
  id: row.id, title: row.title,
  createdAt: Date.parse(row.created_at), updatedAt: Date.parse(row.updated_at),
  messages: cached?.messages ?? [], server: true, loaded: false,
})
const errorText = (error: unknown) => error instanceof Error ? error.message : '会话同步失败'

export const useChatStore = defineStore('chat', {
  state: () => ({
    sessions: [] as ChatSession[],
    currentSessionId: '',
    userId: '',
    cacheScope: '',
    initialized: false,
    initializing: false,
    syncing: false,
    error: '',
    migrationError: '',
    migrationAvailable: false,
    searchIds: null as string[] | null,
    searchVersion: 0,
    messageVersions: {} as Record<string, number>,
    activeSessionId: '',
  }),
  getters: {
    currentSession(state): ChatSession | undefined { return state.sessions.find((s) => s.id === state.currentSessionId) },
    sortedSessions(state): ChatSession[] { return [...state.sessions].sort((a, b) => b.updatedAt - a.updatedAt) },
    historyLoading(): boolean { return Boolean(this.currentSession?.loading) },
  },
  actions: {
    persist() {
      if (!this.cacheScope) return
      try {
        // Cache is scoped to the authenticated owner; it is never imported as new server history.
        localStorage.setItem(this.cacheScope, JSON.stringify(this.sessions))
        localStorage.setItem(this.cacheScope + ':current', this.currentSessionId)
      } catch { /* Server history remains authoritative if browser storage is full. */ }
    },
    async initialize() {
      if (this.initialized || this.initializing) return
      this.initializing = true
      this.error = ''
      try {
        const identity = await agentApi.identity()
        this.userId = identity.user_id
        this.cacheScope = 'dreamloop-chat-v3:' + encodeURIComponent(JSON.stringify([API_BASE_URL, identity.workspace_id, identity.user_id]))
        const cached = readJson<ChatSession[]>(this.cacheScope, [])
        this.sessions = Array.isArray(cached) ? cached.map((s) => ({ ...s, loaded: false, loading: false })) : []
        this.currentSessionId = localStorage.getItem(this.cacheScope + ':current') ?? ''
        this.migrationAvailable = legacySessions().some((s) => s.messages.length > 0)
          && localStorage.getItem(this.cacheScope + ':legacy-imported') !== 'true'
        // Automatic migration is limited to the verifiable original local identity and default workspace.
        if (this.migrationAvailable && localStorage.getItem(USER_KEY) === identity.user_id && identity.workspace_id === 'default') {
          await this.migrateLegacy()
          if (!this.currentSessionId) this.currentSessionId = localStorage.getItem(LEGACY_CURRENT) ?? ''
        }
        await this.refreshSessions()
        this.initialized = true
        if (this.currentSessionId) await this.loadMessages(this.currentSessionId)
      } catch (error) { this.error = errorText(error) }
      finally { this.initializing = false }
    },
    async migrateLegacy() {
      if (!this.cacheScope) return
      this.migrationError = ''
      try {
        for (const session of legacySessions()) {
          const messages = session.messages.filter((m) =>
            (m.role === 'user' || m.role === 'assistant') && typeof m.content === 'string' && m.content.trim())
          if (!messages.length) continue
          await agentApi.importSession({
            id: session.id, title: session.title, createdAt: session.createdAt,
            messages: messages.map((m) => ({ role: m.role, content: m.content, createdAt: m.createdAt })),
          })
        }
        // Keep the original browser data as a backup. Mark only after every session has been acknowledged.
        localStorage.setItem(this.cacheScope + ':legacy-imported', 'true')
        this.migrationAvailable = false
        if (this.initialized) await this.refreshSessions()
      } catch (error) { this.migrationError = '旧对话迁移未完成，原始记录仍保留：' + errorText(error) }
    },
    async refreshSessions() {
      this.syncing = true
      this.error = ''
      try {
        const rows = await agentApi.sessions()
        const previous = new Map(this.sessions.map((s) => [s.id, s]))
        const remoteIds = new Set(rows.map((s) => s.id))
        const remote = rows.map((row) => {
          const cached = previous.get(row.id)
          if (cached) return Object.assign(cached, {
            title: row.title, createdAt: Date.parse(row.created_at), updatedAt: Date.parse(row.updated_at), server: true,
          })
          return sessionFromServer(row)
        })
        const drafts = this.sessions.filter((s) => !s.server && !remoteIds.has(s.id))
        this.sessions = [...remote, ...drafts]
        if (!this.sessions.some((s) => s.id === this.currentSessionId)) this.currentSessionId = this.sortedSessions[0]?.id ?? ''
        this.persist()
      } catch (error) { this.error = errorText(error); throw error }
      finally { this.syncing = false }
    },
    async retrySync() {
      if (!this.initialized) { await this.initialize(); return }
      try { await this.refreshSessions(); if (this.currentSessionId) await this.loadMessages(this.currentSessionId, true) }
      catch { /* refreshSessions exposes the error for retry. */ }
    },
    async loadMessages(sessionId: string, force = false) {
      const session = this.sessions.find((s) => s.id === sessionId)
      if (sessionId === this.activeSessionId) return
      if (!session?.server || (session.loaded && !force)) return
      const version = (this.messageVersions[sessionId] ?? 0) + 1
      this.messageVersions[sessionId] = version
      session.loading = true
      session.loadError = ''
      try {
        const rows: ServerMessage[] = []
        let cursor = 0
        while (true) {
          const page = await agentApi.sessionMessages(sessionId, cursor)
          if (this.messageVersions[sessionId] !== version) return
          rows.push(...page)
          if (page.length < 500) break
          const next = page.at(-1)?.id ?? 0
          if (next <= cursor) throw new Error('消息分页未推进，请刷新重试')
          cursor = next
        }
        const cached = session.messages
        session.messages = rows.map((row, index) => {
          const existing = cached.find((m) => m.serverId === row.id)
            ?? (cached[index]?.role === row.role && cached[index]?.content === row.content ? cached[index] : undefined)
          return {
            id: existing?.id ?? 'server-' + row.id, serverId: row.id, role: row.role, content: row.content,
            createdAt: Date.parse(row.created_at), status: existing?.status === 'streaming' ? 'stopped' : existing?.status ?? 'complete',
            execution: existing?.execution ?? [], sources: existing?.sources ?? [], mode: existing?.mode,
          }
        })
        session.loaded = true
        this.persist()
      } catch (error) {
        if (this.messageVersions[sessionId] === version) session.loadError = errorText(error)
      } finally {
        if (this.messageVersions[sessionId] === version) session.loading = false
      }
    },
    async searchSessions(query: string) {
      const version = ++this.searchVersion
      if (!query.trim()) { this.searchIds = null; return }
      try {
        const rows = await agentApi.sessions(query.trim())
        if (version === this.searchVersion) this.searchIds = rows.map((s) => s.id)
      } catch (error) {
        if (version === this.searchVersion) { this.searchIds = []; this.error = errorText(error) }
      }
    },
    ensureSession() { return this.currentSession ?? this.createSession() },
    createSession() {
      const now = Date.now()
      const session: ChatSession = { id: uid('session'), title: '新对话', createdAt: now, updatedAt: now, messages: [], server: false, loaded: true }
      this.sessions.unshift(session)
      this.currentSessionId = session.id
      this.persist()
      return session
    },
    async selectSession(id: string) {
      if (!this.sessions.some((s) => s.id === id)) return
      this.currentSessionId = id
      this.persist()
      await this.loadMessages(id, true)
    },
    async renameSession(id: string, title: string) {
      const session = this.sessions.find((s) => s.id === id)
      if (!session || !title.trim()) return false
      try {
        if (session.server) await agentApi.renameSession(id, title.trim())
        session.title = title.trim()
        session.updatedAt = Date.now()
        this.persist()
        return true
      } catch (error) { this.error = errorText(error); return false }
    },
    async deleteSession(id: string) {
      const session = this.sessions.find((s) => s.id === id)
      if (!session) return false
      try {
        if (session.server) await agentApi.deleteSession(id)
        this.messageVersions[id] = (this.messageVersions[id] ?? 0) + 1
        this.sessions = this.sessions.filter((s) => s.id !== id)
        if (this.currentSessionId === id) this.currentSessionId = this.sortedSessions[0]?.id ?? ''
        this.persist()
        if (this.currentSessionId) await this.loadMessages(this.currentSessionId)
        return true
      } catch (error) { this.error = errorText(error); return false }
    },
    findMessage(id: string) { return this.sessions.flatMap((s) => s.messages).find((m) => m.id === id) },
    addMessage(message: Omit<ChatMessage, 'id' | 'createdAt'>) {
      const session = this.ensureSession()
      const created: ChatMessage = { ...message, id: uid('message'), createdAt: Date.now() }
      session.messages.push(created)
      session.updatedAt = Date.now()
      if (message.role === 'user' && session.title === '新对话') session.title = message.content.replace(/\s+/g, ' ').trim().slice(0, 40) || '新对话'
      this.persist()
      return created
    },
    updateMessage(id: string, patch: Partial<ChatMessage>) {
      const message = this.findMessage(id)
      if (message) Object.assign(message, patch)
      this.persist()
    },
    appendContent(id: string, chunk: string) { const message = this.findMessage(id); if (message) message.content += chunk },
    addExecution(id: string, event: Omit<ExecutionEvent, 'id' | 'startedAt'>) {
      this.findMessage(id)?.execution.push({ ...event, id: uid('event'), startedAt: Date.now() })
    },
    completeLatestTool(id: string, tool: string, detail: string, failed = false) {
      const message = this.findMessage(id)
      if (!message) return
      const item = [...message.execution].reverse().find((e) => e.type === 'tool' && e.tool === tool)
      if (item) { item.status = failed ? 'error' : 'success'; item.detail = detail }
      else this.addExecution(id, { type: 'observation', title: tool + ' 已完成', detail, tool, status: failed ? 'error' : 'success' })
    },
    removeMessage(id: string) {
      for (const session of this.sessions) session.messages = session.messages.filter((m) => m.id !== id)
      this.persist()
    },
  },
})
