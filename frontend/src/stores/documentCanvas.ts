import { defineStore } from 'pinia'
import { agentApi } from '@/api/agent'
import type { DocumentDetail } from '@/types/api'

const documentMetadata = (detail: DocumentDetail) => {
  const metadata = detail.version?.metadata
  return metadata && typeof metadata === 'object' ? metadata : {}
}

export const useDocumentCanvasStore = defineStore('documentCanvas', {
  state: () => ({
    open: false,
    documentId: '' as string,
    title: '',
    content: '',
    savedTitle: '',
    savedContent: '',
    docType: 'note',
    version: 0,
    sourceSessionId: '',
    sourceMessageId: '',
    loading: false,
    saving: false,
    error: '',
    notice: '',
    requestId: 0,
  }),
  getters: {
    dirty: (state) => state.documentId
      ? state.title !== state.savedTitle || state.content !== state.savedContent
      : Boolean(state.content.trim() || (state.title.trim() && state.title.trim() !== '未命名文档')),
  },
  actions: {
    openDraft(title = '未命名文档', content = '', sourceSessionId = '', sourceMessageId = '') {
      this.requestId++
      this.open = true
      this.documentId = ''
      this.title = title
      this.content = content
      this.savedTitle = title
      this.savedContent = content
      this.docType = 'note'
      this.version = 0
      this.sourceSessionId = sourceSessionId
      this.sourceMessageId = sourceMessageId
      this.loading = false
      this.error = ''
      this.notice = sourceMessageId ? '已从聊天回答创建草稿，可编辑后保存。' : '空白草稿已就绪。'
    },
    async openDocument(id: string | number) {
      const requestId = ++this.requestId
      this.open = true
      this.loading = true
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
        this.version = detail.version?.version ?? detail.document.latestVersion ?? 0
        this.sourceSessionId = String(metadata.chatSessionId ?? '')
        this.sourceMessageId = String(metadata.chatMessageId ?? '')
      } catch (error) {
        if (requestId !== this.requestId) return
        this.error = error instanceof Error ? error.message : '加载文档失败'
      } finally {
        if (requestId === this.requestId) this.loading = false
      }
    },
    async save() {
      if (this.saving || this.loading) return false
      const title = this.title.trim()
      const content = this.content.trim()
      if (!title || !content) {
        this.error = '请填写标题和正文后保存。'
        return false
      }
      this.saving = true
      this.error = ''
      this.notice = ''
      const requestId = this.requestId
      const payload = {
        title,
        content_md: content,
        doc_type: this.docType,
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
        this.title = title
        this.content = content
        this.savedTitle = title
        this.savedContent = content
        this.version = result.version?.version ?? result.document.latestVersion ?? 1
        this.notice = `已保存为版本 ${this.version}`
        return true
      } catch (error) {
        if (requestId === this.requestId) this.error = error instanceof Error ? error.message : '保存失败'
        return false
      } finally {
        if (requestId === this.requestId) this.saving = false
      }
    },
    closeCanvas() {
      this.requestId++
      this.open = false
      this.loading = false
      this.saving = false
      this.error = ''
      this.notice = ''
    },
  },
})
