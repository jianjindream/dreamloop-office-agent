import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useChatStore } from '@/stores/chat'
import { agentApi } from '@/api/agent'

vi.mock('@/api/agent', () => ({ agentApi: {
  identity: vi.fn(), sessions: vi.fn(), sessionMessages: vi.fn(), importSession: vi.fn(), renameSession: vi.fn(), deleteSession: vi.fn(),
} }))
vi.mock('@/api/http', () => ({ API_BASE_URL: '' }))
const remote = { id: 's1', title: '服务端周报', created_at: '2026-10-23T08:00:00Z', updated_at: '2026-10-23T09:00:00Z', message_count: 1 }
const message = { id: 1, role: 'user' as const, content: '来自服务端', created_at: '2026-10-23T08:00:00Z' }
const legacy = [{ id: 'old-1', title: '旧周报', createdAt: 1000, updatedAt: 1000, messages: [
  { id: 'm1', role: 'user', content: '旧消息', createdAt: 1000, status: 'complete', execution: [], sources: [] },
] }]

describe('server conversation history', () => {
  beforeEach(() => {
    setActivePinia(createPinia()); localStorage.clear(); vi.resetAllMocks()
    vi.mocked(agentApi.identity).mockResolvedValue({ user_id: 'user-a', workspace_id: 'default' })
    vi.mocked(agentApi.sessions).mockResolvedValue([remote])
    vi.mocked(agentApi.sessionMessages).mockResolvedValue([message])
    vi.mocked(agentApi.importSession).mockResolvedValue({ ok: true, imported: true })
  })

  it('rehydrates from the server instead of trusting stale cached messages', async () => {
    const store = useChatStore(); await store.initialize()
    expect(store.currentSession?.messages[0]?.content).toBe('来自服务端')
    const key = store.cacheScope
    const cached = JSON.parse(localStorage.getItem(key)!)
    cached[0].messages[0].content = '过期浏览器缓存'; cached[0].loaded = true
    localStorage.setItem(key, JSON.stringify(cached))
    setActivePinia(createPinia())
    const next = useChatStore(); await next.initialize()
    expect(next.currentSession?.messages[0]?.content).toBe('来自服务端')
    expect(next.currentSession?.messages[0]?.id).toBe('server-1')
  })

  it('loads every page of a long conversation', async () => {
    vi.mocked(agentApi.sessionMessages)
      .mockResolvedValueOnce(Array.from({ length: 500 }, (_, index) => ({ ...message, id: index + 1 })))
      .mockResolvedValueOnce([{ ...message, id: 501 }])
    const store = useChatStore(); await store.initialize()
    expect(agentApi.sessionMessages).toHaveBeenNthCalledWith(2, 's1', 500)
    expect(store.currentSession?.messages).toHaveLength(501)
  })

  it('migrates verifiable legacy history once and preserves the original backup', async () => {
    localStorage.setItem('dreamloop-user-id', 'user-a')
    localStorage.setItem('dreamloop-chat-sessions-v2', JSON.stringify(legacy))
    const store = useChatStore(); await store.initialize()
    expect(agentApi.importSession).toHaveBeenCalledWith(expect.objectContaining({ id: 'old-1', messages: [{ role: 'user', content: '旧消息', createdAt: 1000 }] }))
    expect(localStorage.getItem('dreamloop-chat-sessions-v2')).toBe(JSON.stringify(legacy))
    setActivePinia(createPinia()); await useChatStore().initialize()
    expect(agentApi.importSession).toHaveBeenCalledTimes(1)
  })

  it('keeps failed migration retryable without erasing the original records', async () => {
    localStorage.setItem('dreamloop-user-id', 'user-a')
    localStorage.setItem('dreamloop-chat-sessions-v2', JSON.stringify(legacy))
    vi.mocked(agentApi.importSession).mockRejectedValueOnce(new Error('网络断开'))
    const store = useChatStore(); await store.initialize()
    expect(store.migrationError).toContain('原始记录仍保留')
    expect(localStorage.getItem(store.cacheScope + ':legacy-imported')).toBeNull()
    expect(store.initialized).toBe(true)
    await store.migrateLegacy()
    expect(localStorage.getItem(store.cacheScope + ':legacy-imported')).toBe('true')
  })

  it('does not automatically import legacy records into another identity', async () => {
    localStorage.setItem('dreamloop-user-id', 'someone-else')
    localStorage.setItem('dreamloop-chat-sessions-v2', JSON.stringify(legacy))
    const store = useChatStore(); await store.initialize()
    expect(agentApi.importSession).not.toHaveBeenCalled()
    expect(store.migrationAvailable).toBe(true)
    expect(store.cacheScope).toContain('user-a')
  })

  it('does not hide sessions when deletion fails and persists a successful rename', async () => {
    const store = useChatStore(); await store.initialize()
    vi.mocked(agentApi.deleteSession).mockRejectedValueOnce(new Error('删除失败'))
    expect(await store.deleteSession('s1')).toBe(false)
    expect(store.sessions).toHaveLength(1)
    vi.mocked(agentApi.renameSession).mockResolvedValue({ ok: true })
    expect(await store.renameSession('s1', '季度周报')).toBe(true)
    expect(store.currentSession?.title).toBe('季度周报')
    expect(agentApi.renameSession).toHaveBeenCalledWith('s1', '季度周报')
  })

  it('routes streaming updates to the original conversation after selection changes', async () => {
    const store = useChatStore(); await store.initialize()
    const original = store.addMessage({ role: 'assistant', content: '', status: 'streaming', execution: [], sources: [] })
    store.createSession()
    store.appendContent(original.id, '原会话回复')
    expect(store.findMessage(original.id)?.content).toBe('原会话回复')
    expect(store.currentSession?.messages).toHaveLength(0)
  })
})
