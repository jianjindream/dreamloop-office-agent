import { fetchEventSource, type EventSourceMessage } from '@microsoft/fetch-event-source'
import { API_BASE_URL, ApiError, request } from './http'
import type {
  ApiOperationResult,
  ChatRequest,
  ChatStreamEvent,
  DocumentDetail,
  DocumentSummary,
  McpRegistration,
  SnapshotSummary,
  SystemStatus,
  ToolSummary,
  UploadResult,
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
    const request = new XMLHttpRequest()
    const form = new FormData()
    form.append('file', file)

    request.open('POST', `${API_BASE_URL}/api/upload/file`)
    request.responseType = 'json'
    request.upload.onprogress = (event) => {
      if (event.lengthComputable) onProgress(Math.round((event.loaded / event.total) * 100))
    }
    request.onerror = () => reject(new ApiError('文件上传失败，请检查网络连接', 0))
    request.onabort = () => reject(new ApiError('文件上传已取消', 0))
    request.onload = () => {
      const payload = (request.response ?? {}) as UploadResult
      if (request.status < 200 || request.status >= 300) {
        reject(new ApiError(payload.error || `文件上传失败（${request.status}）`, request.status, payload))
        return
      }
      if (payload.error) {
        reject(new ApiError(payload.error, request.status, payload))
        return
      }
      onProgress(100)
      resolve(payload)
    }
    request.send(form)
  })

export const agentApi = {
  status: () => request<SystemStatus>('/api/status'),
  tools: () => request<ToolSummary[]>('/api/tools'),
  documents: () => request<DocumentSummary[]>('/api/documents'),
  document: (id: string | number) => request<DocumentDetail>(`/api/documents/${encodeURIComponent(id)}`),
  snapshots: () => request<SnapshotSummary[]>('/api/snapshots'),
  registerMcp: (payload: McpRegistration) =>
    request<ApiOperationResult>('/api/tools/mcp', {
      method: 'POST',
      body: JSON.stringify(payload),
    }).then(assertOperation),
  removeRagIndex: (docHash: string) =>
    request<ApiOperationResult>('/api/docs/delete', {
      method: 'POST',
      body: JSON.stringify({ doc_hash: docHash }),
    }).then(assertOperation),
  chat: (payload: ChatRequest) =>
    request<Record<string, unknown>>('/api/chat', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),
  streamChat: (payload: ChatRequest, options: StreamOptions) =>
    fetchEventSource(`${API_BASE_URL}/api/chat/stream`, {
      method: 'POST',
      headers: {
        Accept: 'text/event-stream',
        'Content-Type': 'application/json',
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
  cancel: () => request<{ ok: boolean; message: string }>('/api/chat/cancel', { method: 'POST' }),
}
