import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useDocumentCanvasStore } from '@/stores/documentCanvas'
import { agentApi } from '@/api/agent'

vi.mock('@/api/agent', () => ({
  agentApi: {
    createDocument: vi.fn(),
    updateDocument: vi.fn(),
    document: vi.fn(),
  },
}))

const savedResult = (version: number) => ({
  document: { id: 'doc-1', title: '周报', latestVersion: version },
  version: { id: `ver-${version}`, documentId: 'doc-1', version, contentMd: `正文 ${version}` },
  created: version === 1,
})

describe('document canvas', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    localStorage.clear()
  })

  it('does not mark a blank draft dirty', () => {
    const store = useDocumentCanvasStore()
    store.openDraft()
    expect(store.dirty).toBe(false)
    store.content = '第一段'
    expect(store.dirty).toBe(true)
  })

  it('creates a document with chat provenance and appends a version on edit', async () => {
    vi.mocked(agentApi.createDocument).mockResolvedValue(savedResult(1))
    vi.mocked(agentApi.updateDocument).mockResolvedValue(savedResult(2))
    const store = useDocumentCanvasStore()
    store.openDraft('周报', '正文 1', 'session-1', 'message-1')
    expect(await store.save()).toBe(true)
    expect(agentApi.createDocument).toHaveBeenCalledWith(expect.objectContaining({
      title: '周报',
      content_md: '正文 1',
      metadata: { chatSessionId: 'session-1', chatMessageId: 'message-1' },
    }))
    expect(store.version).toBe(1)
    expect(store.dirty).toBe(false)

    store.content = '正文 2'
    expect(await store.save()).toBe(true)
    expect(agentApi.updateDocument).toHaveBeenCalledWith('doc-1', expect.objectContaining({ content_md: '正文 2' }))
    expect(store.version).toBe(2)
  })

  it('keeps unsaved text when the server rejects a save', async () => {
    vi.mocked(agentApi.createDocument).mockRejectedValue(new Error('服务不可用'))
    const store = useDocumentCanvasStore()
    store.openDraft('周报', '重要正文')
    expect(await store.save()).toBe(false)
    expect(store.content).toBe('重要正文')
    expect(store.error).toBe('服务不可用')
  })

  it('preserves typing that happens while a save is in flight', async () => {
    let resolve!: (result: ReturnType<typeof savedResult>) => void
    vi.mocked(agentApi.createDocument).mockImplementation(() => new Promise((done) => { resolve = done }))
    const store = useDocumentCanvasStore()
    store.openDraft('周报', '提交的正文')
    const saving = store.save(true)
    store.content = '保存期间继续输入的正文'
    resolve(savedResult(1))
    expect(await saving).toBe(true)
    expect(store.content).toBe('保存期间继续输入的正文')
    expect(store.savedContent).toBe('提交的正文')
    expect(store.dirty).toBe(true)
  })

  it('restores a scoped local draft and retains it on version conflict', async () => {
    const store = useDocumentCanvasStore()
    store.initializeDraft('workspace:user')
    store.openDraft('周报', '待恢复的正文')
    store.documentId = 'doc-1'; store.version = 2; store.persistDraft()
    setActivePinia(createPinia())
    const restored = useDocumentCanvasStore()
    restored.initializeDraft('workspace:user')
    expect(restored.recovery?.content).toBe('待恢复的正文')
    restored.restoreDraft()
    vi.mocked(agentApi.updateDocument).mockRejectedValue(new Error('文档已在其他位置更新'))
    expect(await restored.save()).toBe(false)
    expect(agentApi.updateDocument).toHaveBeenCalledWith('doc-1', expect.objectContaining({ base_version: 2 }))
    expect(restored.content).toBe('待恢复的正文')
    expect(localStorage.getItem(restored.draftKey)).toContain('待恢复的正文')
  })
})
