import { defineStore } from 'pinia'
import { agentApi } from '@/api/agent'
import type { DocumentDetail } from '@/types/api'

const documentMetadata = (detail: DocumentDetail): Record<string, unknown> => {
  const raw = detail.version?.metadata
  if (typeof raw === 'string') { try { return JSON.parse(raw) as Record<string, unknown> } catch { return {} } }
  return raw ?? {}
}
interface RecoveryDraft {
  documentId: string; title: string; content: string; savedTitle: string; savedContent: string
  docType: string; version: number; status: string; sourceSessionId: string; sourceMessageId: string
}
export const useDocumentCanvasStore = defineStore('documentCanvas', {
  state: () => ({
    open: false, documentId: '', title: '未命名文档', content: '', savedTitle: '', savedContent: '',
    docType: 'note', version: 0, status: 'draft', sourceSessionId: '', sourceMessageId: '',
    loading: false, saving: false, error: '', notice: '', requestId: 0,
    indexStatus: 'not_indexed', originalFilename: '', hasOriginal: false,
    draftKey: '', recovery: null as RecoveryDraft | null,
  }),
  getters: {
    dirty: (state) => state.documentId
      ? state.title !== state.savedTitle || state.content !== state.savedContent
      : Boolean(state.content.trim() || (state.title.trim() && state.title.trim() !== '未命名文档')),
  },
  actions: {
    initializeDraft(scope: string) {
      this.draftKey = scope + ':document-draft'
      try {
        const draft = JSON.parse(localStorage.getItem(this.draftKey) ?? 'null') as RecoveryDraft | null
        if (draft && typeof draft.content === 'string' && typeof draft.title === 'string') this.recovery = draft
      } catch { this.recovery = null }
    },
    persistDraft() {
      if (!this.draftKey || this.loading || !this.open) return
      try {
        if (!this.dirty) { localStorage.removeItem(this.draftKey); this.recovery = null; return }
        const draft: RecoveryDraft = {
          documentId: this.documentId, title: this.title, content: this.content, savedTitle: this.savedTitle, savedContent: this.savedContent,
          docType: this.docType, version: this.version, status: this.status,
          sourceSessionId: this.sourceSessionId, sourceMessageId: this.sourceMessageId,
        }
        localStorage.setItem(this.draftKey, JSON.stringify(draft))
        this.recovery = draft
      } catch { this.error = '浏览器草稿备份失败，请立即手动保存文档。' }
    },
    restoreDraft() {
      if (!this.recovery) return
      this.requestId++
      Object.assign(this, this.recovery)
      this.open = true
      this.loading = false
      this.saving = false
      this.error = ''
      this.notice = '已恢复本机草稿；保存时会核对服务端版本，避免覆盖其他位置的修改。'
      this.recovery = null
    },
    openDraft(title = '未命名文档', content = '', sourceSessionId = '', sourceMessageId = '') {
      this.requestId++
      this.open = true
      this.documentId = ''
      this.title = title
      this.content = content
      this.savedTitle = ''
      this.savedContent = ''
      this.docType = 'note'
      this.version = 0
      this.status = 'draft'
      this.sourceSessionId = sourceSessionId
      this.sourceMessageId = sourceMessageId
      this.loading = false
      this.saving = false
      this.error = ''
      this.indexStatus = 'not_indexed'
      this.hasOriginal = false
      this.originalFilename = ''
      this.notice = sourceMessageId ? '已从聊天回答创建草稿，可编辑后保存。' : '空白草稿已就绪。'
      this.persistDraft()
    },
    async openDocument(id: string | number) {
      const requestId = ++this.requestId
      this.open = true
      this.loading = true
      this.saving = false
      this.error = ''
      this.notice = ''
      this.documentId = ''
      this.title = ''
      this.content = ''
      this.savedTitle = ''
      this.savedContent = ''
      this.version = 0
      this.sourceSessionId = ''
      this.sourceMessageId = ''
      this.hasOriginal = false
      try {
        const detail = await agentApi.document(id)
        if (requestId !== this.requestId) return
        const metadata = documentMetadata(detail)
        this.documentId = String(detail.document.id)
        this.title = detail.document.title
        this.content = detail.version?.contentMd ?? ''
        this.savedTitle = this.title
        this.savedContent = this.content
        this.docType = detail.document.docType || 'note'
        this.status = detail.document.status || 'active'
        this.version = detail.version?.version ?? detail.document.latestVersion ?? 0
        this.sourceSessionId = String(metadata.chatSessionId ?? '')
        this.sourceMessageId = String(metadata.chatMessageId ?? '')
        this.indexStatus = String(metadata.index_status ?? 'not_indexed')
        this.hasOriginal = Boolean(metadata.original_attachment)
        this.originalFilename = String(metadata.original_filename ?? this.title)
      } catch (error) {
        if (requestId !== this.requestId) return
        this.error = error instanceof Error ? error.message : '加载文档失败'
      } finally {
        if (requestId === this.requestId) this.loading = false
      }
    },
    async save(automatic = false) {
      if (this.saving || this.loading) return false
      const title = this.title.trim()
      const content = this.content.trim()
      if (!title || !content) {
        if (!automatic) this.error = '请填写标题和正文后保存。'
        return false
      }
      this.saving = true
      this.error = ''
      this.notice = ''
      const requestId = this.requestId
      const nextStatus = automatic ? this.status : 'active'
      const payload = {
        title, content_md: content, doc_type: this.docType,
        draft: nextStatus === 'draft',
        ...(this.documentId ? { base_version: this.version } : {}),
        metadata: {
          ...(this.sourceSessionId ? { chatSessionId: this.sourceSessionId } : {}),
          ...(this.sourceMessageId ? { chatMessageId: this.sourceMessageId } : {}),
        },
      }
      try {
        const result = this.documentId
          ? await agentApi.updateDocument(this.documentId, payload)
          : await agentApi.createDocument(payload)
        if (requestId !== this.requestId) return false
        this.documentId = String(result.document.id)
        // Only mark the submitted snapshot saved. Never replace text typed during this request.
        this.savedTitle = title
        this.savedContent = content
        if (this.title.trim() === title) this.title = title
        if (this.content.trim() === content) this.content = content
        this.status = result.document.status || nextStatus
        this.version = result.version?.version ?? result.document.latestVersion ?? 1
        this.indexStatus = result.index_status ?? 'not_indexed'
        this.notice = result.index_error || (automatic ? '已自动保存草稿 · 版本 ' + this.version : '已保存为版本 ' + this.version)
        this.persistDraft()
        return true
      } catch (error) {
        if (requestId === this.requestId) {
          this.error = error instanceof Error ? error.message : '保存失败'
          this.persistDraft()
        }
        return false
      } finally {
        if (requestId === this.requestId) this.saving = false
      }
    },
    closeCanvas() {
      this.persistDraft()
      this.requestId++
      this.open = false
      this.loading = false
      this.saving = false
      this.error = ''
      this.notice = ''
    },
  },
})
