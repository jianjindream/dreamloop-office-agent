import { fetchEventSource, type EventSourceMessage } from '@microsoft/fetch-event-source'
import { API_BASE_URL, ApiError, identityHeaders, request } from './http'
import type {
  ApiOperationResult,
  ChatRequest,
  ChatResponse,
  ChatStreamEvent,
  DocumentDetail,
  DocumentSummary,
  DocumentVersion,
  DocumentWritePayload,
  DocumentWriteResult,
  McpRegistration,
  SnapshotSummary,
  SystemStatus,
  ToolSummary,
  UploadResult,
  ServerSession,
  ServerMessage,
} from '@/types/api'

interface StreamOptions {
  signal: AbortSignal
  onEvent: (event: ChatStreamEvent) => void
}

const parseEvent = (message: EventSourceMessage): ChatStreamEvent => {
  let data: Record<string, unknown> = {}
  if (message.data) {
    try {
      data = JSON.parse(message.data) as Record<string, unknown>
    } catch {
      data = { message: message.data }
    }
  }
  return { type: message.event || 'message', data }
}

const assertOperation = <T extends { error?: string }>(payload: T): T => {
  if (payload.error) throw new ApiError(payload.error, 200, payload)
  return payload
}

export const uploadFileWithProgress = (
  file: File,
  onProgress: (percent: number) => void,
): Promise<UploadResult> =>
  new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    const form = new FormData()
    const uploadId = `upload_${crypto.randomUUID().replaceAll('-', '')}`
    form.append('file', file)
    form.append('upload_id', uploadId)

    const progressTimer = window.setInterval(() => {
      request<{ percent: number }>(`/api/uploads/${encodeURIComponent(uploadId)}/progress`)
        .then((progress) => onProgress(progress.percent))
        .catch(() => undefined)
    }, 400)
    const stopPolling = () => window.clearInterval(progressTimer)

    xhr.open('POST', `${API_BASE_URL}/api/upload/file`)
    Object.entries(identityHeaders).forEach(([name, value]) => xhr.setRequestHeader(name, value))
    xhr.responseType = 'json'
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) onProgress(Math.round((event.loaded / event.total) * 100))
    }
    xhr.onerror = () => { stopPolling(); reject(new ApiError('文件上传失败，请检查网络连接', 0)) }
    xhr.onabort = () => { stopPolling(); reject(new ApiError('文件上传已取消', 0)) }
    xhr.onload = () => {
      stopPolling()
      const payload = (xhr.response ?? {}) as UploadResult
      if (xhr.status < 200 || xhr.status >= 300) {
        reject(new ApiError(payload.error || `文件上传失败（${xhr.status}）`, xhr.status, payload))
        return
      }
      if (payload.error) {
        reject(new ApiError(payload.error, xhr.status, payload))
        return
      }
      onProgress(100)
      resolve(payload)
    }
    xhr.send(form)
  })

export const agentApi = {
  status: () => request<SystemStatus>('/api/status'),
  tools: () => request<ToolSummary[]>('/api/tools'),
  documents: () => request<DocumentSummary[]>('/api/documents'),
  document: (id: string | number) => request<DocumentDetail>(`/api/documents/${encodeURIComponent(id)}`),
  documentVersions: (id: string | number) => request<DocumentVersion[]>(`/api/documents/${encodeURIComponent(id)}/versions`),
  createDocument: (payload: DocumentWritePayload) =>
    request<DocumentWriteResult>('/api/documents', { method: 'POST', body: JSON.stringify(payload) }),
  updateDocument: (id: string | number, payload: DocumentWritePayload) =>
    request<DocumentWriteResult>(`/api/documents/${encodeURIComponent(id)}`, {
      method: 'PUT',
      body: JSON.stringify(payload),
    }),
  deleteDocument: (id: string | number) =>
    request<ApiOperationResult>(`/api/documents/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  sessions: () => request<ServerSession[]>('/api/sessions'),
  sessionMessages: (id: string, afterId = 0) =>
    request<ServerMessage[]>(`/api/sessions/${encodeURIComponent(id)}/messages?after_id=${afterId}`),
  renameSession: (id: string, title: string) =>
    request<ApiOperationResult>(`/api/sessions/${encodeURIComponent(id)}`, { method: 'PATCH', body: JSON.stringify({ title }) }),
  deleteSession: (id: string) =>
    request<ApiOperationResult>(`/api/sessions/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  snapshots: () => request<SnapshotSummary[]>('/api/snapshots'),
  registerMcp: (payload: McpRegistration) =>
    request<ApiOperationResult>('/api/tools/mcp', {
      method: 'POST',
      body: JSON.stringify(payload),
    }).then(assertOperation),
  updateMcp: (name: string, payload: McpRegistration) =>
    request<ApiOperationResult>(`/api/tools/mcp/${encodeURIComponent(name)}`, {
      method: 'PUT', body: JSON.stringify(payload),
    }).then(assertOperation),
  deleteMcp: (name: string) =>
    request<ApiOperationResult>(`/api/tools/mcp/${encodeURIComponent(name)}`, { method: 'DELETE' }).then(assertOperation),
  removeRagIndex: (docHash: string) =>
    request<ApiOperationResult>('/api/docs/delete', {
      method: 'POST',
      body: JSON.stringify({ doc_hash: docHash }),
    }).then(assertOperation),
  chat: (payload: ChatRequest) =>
    request<ChatResponse>('/api/chat', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  streamChat: (payload: ChatRequest, options: StreamOptions) =>
    fetchEventSource(`${API_BASE_URL}/api/chat/stream`, {
      method: 'POST',
      headers: {
        Accept: 'text/event-stream',
        'Content-Type': 'application/json',
        ...identityHeaders,
      },
      body: JSON.stringify(payload),
      signal: options.signal,
      openWhenHidden: true,
      async onopen(response) {
        if (!response.ok) {
          const payload = await response.json().catch(() => null)
          const message =
            typeof payload === 'object' && payload && 'error' in payload
              ? String(payload.error)
              : `流式请求失败（${response.status}）`
          throw new ApiError(message, response.status, payload)
        }
        const contentType = response.headers.get('content-type') ?? ''
        if (!contentType.includes('text/event-stream')) {
          throw new ApiError('服务没有返回 SSE 数据流', response.status)
        }
      },
      onmessage(message) {
        options.onEvent(parseEvent(message))
      },
      onerror(error) {
        throw error
      },
    }),
  uploadFile: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return request<UploadResult>('/api/upload/file', { method: 'POST', body: form }).then(assertOperation)
  },
  cancel: (taskId: string) => request<{ ok: boolean; message: string }>(`/api/chat/tasks/${encodeURIComponent(taskId)}/cancel`, { method: 'POST' }),
}
