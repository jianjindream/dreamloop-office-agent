export interface ToolSummary {
  name: string
  description: string
  is_mcp?: boolean
  params?: ToolParameter[]
}

export interface ToolParameter {
  name: string
  type?: string
  description?: string
  required?: boolean
}

export type EntityId = string | number

export interface DocumentSummary {
  id: EntityId
  title: string
  docType?: string
  source?: string
  status?: string
  createdBy?: string
  createdAt?: string
  updatedAt?: string
  latestVersion?: number
  latestVersionId?: EntityId
}

export interface DocumentVersion {
  id: EntityId
  documentId: EntityId
  version: number
  contentMd?: string
  summary?: string
  metadata?: Record<string, unknown> | string
  createdAt?: string
}

export interface DocumentDetail {
  document: DocumentSummary
  version?: DocumentVersion | null
}

export interface DocumentWritePayload {
  title: string
  content_md: string
  doc_type?: string
  metadata?: Record<string, unknown>
  ingest_to_rag?: boolean
  draft?: boolean
  base_version?: number
}

export interface DocumentWriteResult extends DocumentDetail {
  created: boolean
  document_saved?: boolean
  index_status?: string
  index_error?: string
}

export interface UploadResult {
  document_saved?: boolean
  index_status?: string
  index_error?: string
  upload_id?: string
  filename?: string
  content_type?: string
  parser?: string
  pages?: number
  text_chars?: number
  needs_ocr?: boolean
  document_id?: string
  version_id?: string
  chunk_count?: number
  doc_hash?: string
  error?: string
}

export interface WeeklyReportTemplate {
  id: string
  name: string
  description: string
  builtIn: boolean
  contentMd: string
}

export interface WeeklyReportIssue {
  type: 'conflict' | 'missing'
  field: string
  message: string
  sourceIds: string[]
  values: Record<string, string>
}

export interface WeeklyReportCalculation {
  metric: string
  inputs: number[]
  formula: string
  result: number | null
  unit: string
  sourceId: string
}

export interface WeeklyReportResult {
  documentId: string
  versionId: string
  version: number
  contentMd: string
  review: {
    conflicts: WeeklyReportIssue[]
    missing: WeeklyReportIssue[]
    calculations: WeeklyReportCalculation[]
  }
  sources: Array<{ sourceId: string; documentId: string; versionId: string; version: number; title: string; locator: string }>
  indexStatus: string
}

export interface ReviewDiff {
  added: number
  removed: number
  unchanged: number
  changes: Array<{ type: 'added' | 'removed' | 'unchanged'; originalLine: number; revisedLine: number; text: string }>
}

export interface McpRegistration {
  name: string
  description: string
  endpoint: string
  params: ToolParameter[]
}

export interface ApiOperationResult {
  ok?: boolean
  name?: string
  message?: string
  error?: string
}

export interface ServerSession {
  id: string
  title: string
  created_at: string
  updated_at: string
  message_count: number
}

export interface ServerMessage {
  id: number
  role: 'user' | 'assistant'
  content: string
  created_at: string
  metadata?: { mode?: string; intent?: string; knowledge_scope?: string; document_ids?: string[]; search_results?: SearchResult[] }
}

export interface SystemStatus {
  rag_loaded: boolean
  rag_mode: string
  rag_chunks?: number | Array<Record<string, unknown>>
  short_term_count?: number
  long_term_count?: number
  preferences?: Record<string, unknown>
  tools_count: number
  llm_model: string
  embedding_model: string
  is_mock: boolean
  infrastructure: Record<string, string | boolean | number | null>
}

export interface SnapshotSummary {
  id?: string
  sessionId?: string
  session_id?: string
  title?: string
  status?: string
  createdAt?: string
  created_at?: string
  [key: string]: unknown
}

export interface ChatRequest {
  message: string
  use_rag: boolean
  selected_tools: string[]
  explicit: boolean
  user_id: string
  session_id: string
  knowledge_scope?: 'none' | 'workspace' | 'selected'
  document_ids?: string[]
}

export interface ReActStep {
  type: string
  content: string
  tool?: string
  params?: Record<string, string>
}

export interface ToolCallResult {
  toolName?: string
  tool_name?: string
  params?: Record<string, unknown>
  toolResult?: string
  tool_result?: string
}

export interface RagChunk {
  id?: string
  content?: string
  docHash?: string
  doc_hash?: string
  metadata?: Record<string, unknown>
}

export interface SearchResult {
  chunk: RagChunk
  similarity: number
  context_id?: number
  document_id?: string
  version_id?: string
  document_title?: string
  version?: number
  locator?: string
  excerpt?: string
  retrieval_source?: string
  fallback?: boolean
}

export interface ChatResponse {
  task_id?: string
  query?: string
  answer?: string
  mode?: string
  intent?: string
  knowledge_scope?: string
  document_ids?: string[]
  steps?: ReActStep[]
  tool_call?: ToolCallResult
  search_results?: SearchResult[]
  extracted_info?: string
  interrupted?: boolean
}

export type StreamEventType =
  | 'start'
  | 'mode'
  | 'intent'
  | 'step'
  | 'tool_call'
  | 'observation'
  | 'rag_result'
  | 'token'
  | 'done'
  | 'error'
  | 'graph_ready'
  | 'node_start'
  | 'node_done'
  | 'race_won'

export interface ChatStreamEvent {
  type: StreamEventType | string
  data: Record<string, unknown> | ChatResponse
}
