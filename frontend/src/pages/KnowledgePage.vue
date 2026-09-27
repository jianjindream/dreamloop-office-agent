<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  AlertCircle, CheckCircle2, ChevronRight, Database, FileText, HardDrive,
  LoaderCircle, RefreshCw, Search, Trash2, UploadCloud, X,
} from 'lucide-vue-next'
import { agentApi, uploadFileWithProgress } from '@/api/agent'
import { ApiError } from '@/api/http'
import MarkdownContent from '@/components/chat/MarkdownContent.vue'
import type { DocumentDetail, DocumentSummary, UploadResult } from '@/types/api'

type UploadState = 'uploading' | 'processing' | 'done' | 'error'
interface UploadItem { id: string; name: string; size: number; progress: number; state: UploadState; message?: string }
interface UploadMeta { fileName: string; size: number; parser?: string; pages?: number; textChars?: number; chunkCount?: number; docHash?: string; indexed?: boolean }

const META_KEY = 'dreamloop-upload-meta-v1'
const documents = ref<DocumentSummary[]>([])
const uploadItems = ref<UploadItem[]>([])
const uploadMeta = ref<Record<string, UploadMeta>>({})
const search = ref('')
const loading = ref(true)
const refreshing = ref(false)
const loadError = ref('')
const dragActive = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)
const selected = ref<DocumentDetail | null>(null)
const detailLoading = ref(false)
const detailError = ref('')
const deleteTarget = ref<DocumentSummary | null>(null)
const deleting = ref(false)
const toast = ref('')
const serviceChunkCount = ref(0)

const filteredDocuments = computed(() => {
  const query = search.value.trim().toLowerCase()
  if (!query) return documents.value
  return documents.value.filter((doc) => [doc.title, doc.docType, doc.source].some((value) => value?.toLowerCase().includes(query)))
})
const localChunkCount = computed(() => Object.values(uploadMeta.value).reduce((total, item) => total + (item.indexed === false ? 0 : item.chunkCount ?? 0), 0))
const totalChunks = computed(() => Math.max(localChunkCount.value, serviceChunkCount.value))
const processingCount = computed(() => uploadItems.value.filter((item) => item.state === 'uploading' || item.state === 'processing').length)
const indexedCount = computed(() => Object.values(uploadMeta.value).filter((item) => item.indexed !== false && item.docHash).length)

const errorMessage = (error: unknown) => error instanceof ApiError || error instanceof Error ? error.message : '请求失败，请稍后重试'
const loadMeta = () => {
  try { uploadMeta.value = JSON.parse(localStorage.getItem(META_KEY) || '{}') as Record<string, UploadMeta> }
  catch { uploadMeta.value = {} }
}
const saveMeta = () => localStorage.setItem(META_KEY, JSON.stringify(uploadMeta.value))

const loadDocuments = async (manual = false) => {
  if (manual) refreshing.value = true
  else loading.value = true
  loadError.value = ''
  try {
    const [documentResult, statusResult] = await Promise.allSettled([agentApi.documents(), agentApi.status()])
    if (documentResult.status === 'rejected') throw documentResult.reason
    documents.value = documentResult.value
    if (statusResult.status === 'fulfilled') {
      serviceChunkCount.value = Array.isArray(statusResult.value.rag_chunks)
        ? statusResult.value.rag_chunks.length
        : Number(statusResult.value.rag_chunks || 0)
    }
  }
  catch (error) { loadError.value = errorMessage(error) }
  finally { loading.value = false; refreshing.value = false }
}

const formatSize = (bytes?: number) => {
  if (!bytes) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
const formatDate = (value?: string) => {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(date)
}
const docMeta = (doc: DocumentSummary) => uploadMeta.value[String(doc.id)]

const rememberUpload = (result: UploadResult, file: File) => {
  if (result.document_id === undefined) return
  uploadMeta.value[String(result.document_id)] = {
    fileName: result.filename || file.name, size: file.size, parser: result.parser, pages: result.pages,
    textChars: result.text_chars, chunkCount: result.chunk_count, docHash: result.doc_hash, indexed: Boolean(result.doc_hash),
  }
  saveMeta()
}

const acceptFiles = async (files: FileList | File[]) => {
  for (const file of Array.from(files)) {
    const item: UploadItem = { id: `${Date.now()}-${Math.random()}`, name: file.name, size: file.size, progress: 0, state: 'uploading' }
    uploadItems.value.unshift(item)
    try {
      const result = await uploadFileWithProgress(file, (progress) => {
        item.progress = progress
        if (progress === 100) item.state = 'processing'
      })
      item.state = 'done'
      item.message = result.needs_ocr ? '文件需要 OCR 处理' : `已生成 ${result.chunk_count ?? 0} 个知识片段`
      rememberUpload(result, file)
      await loadDocuments(true)
    } catch (error) {
      item.state = 'error'
      item.message = errorMessage(error)
    }
  }
  if (fileInput.value) fileInput.value.value = ''
}

const onDrop = (event: DragEvent) => {
  dragActive.value = false
  if (event.dataTransfer?.files) void acceptFiles(event.dataTransfer.files)
}
const openDocument = async (doc: DocumentSummary) => {
  selected.value = { document: doc, version: null }
  detailLoading.value = true
  detailError.value = ''
  try { selected.value = await agentApi.document(doc.id) }
  catch (error) { detailError.value = errorMessage(error) }
  finally { detailLoading.value = false }
}
const askRemoveIndex = (doc: DocumentSummary) => { if (docMeta(doc)?.docHash) deleteTarget.value = doc }
const removeIndex = async () => {
  if (!deleteTarget.value) return
  const key = String(deleteTarget.value.id)
  const meta = uploadMeta.value[key]
  if (!meta?.docHash) return
  deleting.value = true
  try {
    await agentApi.removeRagIndex(meta.docHash)
    uploadMeta.value[key] = { ...meta, indexed: false, chunkCount: 0, docHash: undefined }
    saveMeta()
    toast.value = '知识索引已移除，文档记录仍保留在资料库中。'
    deleteTarget.value = null
    window.setTimeout(() => (toast.value = ''), 3600)
  } catch (error) { toast.value = errorMessage(error) }
  finally { deleting.value = false }
}

onMounted(() => { loadMeta(); void loadDocuments() })
</script>

<template>
  <div class="page-shell knowledge-page">
    <header class="page-heading">
      <div><h1>工作区知识库</h1><p>上传资料、跟踪解析进度，并查看 AI 实际可检索的内容。</p></div>
      <button class="primary-button" type="button" @click="fileInput?.click()"><UploadCloud :size="17" /> 上传资料</button>
    </header>

    <section class="stats-grid">
      <article class="surface-card stat-card"><span class="stat-icon violet"><Database :size="19" /></span><div><small>知识片段</small><strong>{{ totalChunks }}</strong><em>当前 RAG 索引</em></div></article>
      <article class="surface-card stat-card"><span class="stat-icon blue"><FileText :size="19" /></span><div><small>资料总数</small><strong>{{ documents.length }}</strong><em>{{ indexedCount }} 份有索引记录</em></div></article>
      <article class="surface-card stat-card"><span class="stat-icon" :class="processingCount ? 'amber' : 'green'"><LoaderCircle v-if="processingCount" :size="19" class="spin" /><CheckCircle2 v-else :size="19" /></span><div><small>处理队列</small><strong>{{ processingCount ? `${processingCount} 项` : '空闲' }}</strong><em>上传后自动建立索引</em></div></article>
    </section>

    <input ref="fileInput" class="sr-only" type="file" multiple accept=".pdf,.txt,.md,text/plain,text/markdown,application/pdf" @change="acceptFiles(($event.target as HTMLInputElement).files || [])" />
    <section class="upload-card" :class="{ active: dragActive }" @dragenter.prevent="dragActive = true" @dragover.prevent="dragActive = true" @dragleave.prevent="dragActive = false" @drop.prevent="onDrop" @click="fileInput?.click()">
      <span class="upload-icon"><UploadCloud :size="24" /></span>
      <div><strong>{{ dragActive ? '松开即可上传' : '把资料拖到这里，或点击选择文件' }}</strong><p>当前解析器支持 PDF、Markdown 和 TXT；扫描版 PDF 会提示需要 OCR。</p></div>
      <span class="upload-action">选择文件</span>
    </section>

    <section v-if="uploadItems.length" class="surface-card upload-queue">
      <div class="queue-heading"><strong>上传与解析</strong><span>{{ uploadItems.length }} 项</span></div>
      <div v-for="item in uploadItems" :key="item.id" class="queue-item">
        <span class="queue-status" :class="item.state"><LoaderCircle v-if="item.state === 'uploading' || item.state === 'processing'" :size="15" class="spin" /><CheckCircle2 v-else-if="item.state === 'done'" :size="15" /><AlertCircle v-else :size="15" /></span>
        <div class="queue-copy"><strong>{{ item.name }}</strong><small>{{ item.state === 'uploading' ? `正在上传 · ${item.progress}%` : item.state === 'processing' ? '服务端正在解析并建立索引' : item.message }}</small><i v-if="item.state === 'uploading' || item.state === 'processing'"><b :style="{ width: `${item.progress}%` }"></b></i></div>
        <span>{{ formatSize(item.size) }}</span>
        <button class="icon-button" type="button" aria-label="移除记录" @click="uploadItems = uploadItems.filter((entry) => entry.id !== item.id)"><X :size="15" /></button>
      </div>
    </section>

    <section class="surface-card document-panel">
      <div class="panel-heading"><div><h2>全部资料</h2><span>{{ filteredDocuments.length }} / {{ documents.length }} 个文件</span></div><div class="panel-tools"><label class="table-search"><Search :size="15" /><input v-model="search" type="search" placeholder="搜索标题或类型" /></label><button class="icon-button" type="button" aria-label="刷新资料" :disabled="refreshing" @click="loadDocuments(true)"><RefreshCw :size="16" :class="{ spin: refreshing }" /></button></div></div>
      <div v-if="loading" class="panel-state"><LoaderCircle :size="20" class="spin" /><span>正在读取资料库…</span></div>
      <div v-else-if="loadError" class="panel-state error"><AlertCircle :size="20" /><strong>无法读取资料</strong><span>{{ loadError }}</span><button class="secondary-button" type="button" @click="loadDocuments()">重试</button></div>
      <div v-else-if="!filteredDocuments.length" class="panel-state"><FileText :size="23" /><strong>{{ search ? '没有匹配的资料' : '资料库还是空的' }}</strong><span>{{ search ? '换个关键词试试。' : '上传第一份文件，AI 就能在对话中检索它。' }}</span></div>
      <div v-else class="documents-table">
        <div class="table-row table-header"><span>资料名称</span><span>类型</span><span>索引片段</span><span>大小</span><span>更新时间</span><span></span></div>
        <div v-for="doc in filteredDocuments" :key="String(doc.id)" class="table-row document-row" role="button" tabindex="0" @click="openDocument(doc)" @keydown.enter="openDocument(doc)" @keydown.space.prevent="openDocument(doc)">
          <span class="doc-name"><i><FileText :size="16" /></i><span><strong>{{ doc.title }}</strong><small>{{ doc.source || '资料库' }}</small></span></span>
          <span><b class="type-pill">{{ doc.docType || '文档' }}</b></span><span>{{ docMeta(doc)?.indexed === false ? '已移除' : docMeta(doc)?.chunkCount ?? '—' }}</span><span>{{ formatSize(docMeta(doc)?.size) }}</span><span class="doc-time"><i></i>{{ formatDate(doc.updatedAt || doc.createdAt) }}</span>
          <span class="row-actions"><button v-if="docMeta(doc)?.docHash" class="icon-button danger-action" type="button" title="移除知识索引" aria-label="移除知识索引" @click.stop="askRemoveIndex(doc)" @keydown.stop><Trash2 :size="15" /></button><ChevronRight v-else :size="15" /></span>
        </div>
      </div>
    </section>

    <Transition name="fade"><div v-if="selected" class="drawer-backdrop" @click.self="selected = null"><aside class="detail-drawer" aria-label="文档详情">
      <header><div><small>文档详情</small><h2>{{ selected.document.title }}</h2></div><button class="icon-button" type="button" aria-label="关闭" @click="selected = null"><X :size="18" /></button></header>
      <div v-if="detailLoading" class="drawer-state"><LoaderCircle :size="21" class="spin" /> 正在读取最新版本…</div><div v-else-if="detailError" class="drawer-state error"><AlertCircle :size="21" /> {{ detailError }}</div>
      <div v-else class="drawer-content"><div class="detail-grid"><div><small>类型</small><strong>{{ selected.document.docType || '文档' }}</strong></div><div><small>版本</small><strong>v{{ selected.document.latestVersion || selected.version?.version || 1 }}</strong></div><div><small>来源</small><strong>{{ selected.document.source || '未知' }}</strong></div><div><small>更新时间</small><strong>{{ formatDate(selected.document.updatedAt) }}</strong></div></div>
        <section v-if="selected.version?.summary" class="detail-section"><h3>摘要</h3><p>{{ selected.version.summary }}</p></section><section class="detail-section content-preview"><h3>正文预览</h3><MarkdownContent v-if="selected.version?.contentMd" :content="selected.version.contentMd" /><p v-else>当前版本没有可预览的正文。</p></section><section v-if="docMeta(selected.document)" class="detail-section upload-facts"><h3>上传信息</h3><p><HardDrive :size="14" /> {{ formatSize(docMeta(selected.document)?.size) }} · {{ docMeta(selected.document)?.parser || '自动解析' }} · {{ docMeta(selected.document)?.chunkCount ?? 0 }} 个片段</p></section>
      </div>
    </aside></div></Transition>

    <Transition name="fade"><div v-if="deleteTarget" class="modal-backdrop" @click.self="deleteTarget = null"><section class="confirm-card" role="dialog" aria-modal="true" aria-labelledby="remove-index-title"><span class="confirm-icon"><Trash2 :size="21" /></span><h2 id="remove-index-title">移除知识索引？</h2><p>“{{ deleteTarget.title }}”将不再被 RAG 检索。文档记录和正文仍会保留，因为当前服务尚未提供删除文档接口。</p><div><button class="secondary-button" type="button" @click="deleteTarget = null">取消</button><button class="danger-button" type="button" :disabled="deleting" @click="removeIndex"><LoaderCircle v-if="deleting" :size="15" class="spin" />确认移除</button></div></section></div></Transition>
    <Transition name="toast"><div v-if="toast" class="toast-message">{{ toast }}</div></Transition>
  </div>
</template>

<style scoped>
.stats-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:12px}.stat-card{display:flex;align-items:center;gap:14px;padding:18px}.stat-icon{display:inline-flex;align-items:center;justify-content:center;width:42px;height:42px;flex:0 0 auto;border-radius:13px}.stat-icon.violet{background:var(--brand-soft);color:var(--brand)}.stat-icon.blue{background:#eaf3ff;color:#3978bd}.stat-icon.green{background:var(--success-soft);color:var(--success)}.stat-icon.amber{background:var(--warning-soft);color:var(--warning)}:root[data-theme='dark'] .stat-icon.blue{background:#1d3146;color:#83b8ef}.stat-card div{display:grid;grid-template-columns:auto 1fr;align-items:baseline;flex:1}.stat-card small{grid-column:1/-1;color:var(--text-muted);font-size:10px}.stat-card strong{margin-top:5px;color:var(--text-strong);font-size:21px;font-weight:690;letter-spacing:-.03em}.stat-card em{justify-self:end;color:var(--text-faint);font-size:8px;font-style:normal}
.upload-card{display:flex;align-items:center;gap:14px;margin-top:14px;padding:17px 19px;border:1px dashed color-mix(in srgb,var(--brand) 35%,var(--border));border-radius:15px;background:var(--brand-softer);cursor:pointer;transition:160ms ease}.upload-card:hover,.upload-card.active{border-color:var(--brand);background:var(--brand-soft)}.upload-card.active{transform:scale(1.005)}.upload-icon{display:inline-flex;align-items:center;justify-content:center;width:43px;height:43px;flex:0 0 auto;border-radius:13px;background:var(--bg-elevated);color:var(--brand);box-shadow:var(--shadow-sm)}.upload-card>div{flex:1}.upload-card strong{color:var(--text-strong);font-size:11px;font-weight:630}.upload-card p{margin:4px 0 0;color:var(--text-faint);font-size:9px}.upload-action{padding:8px 11px;border:1px solid var(--border);border-radius:9px;background:var(--bg-elevated);color:var(--text);font-size:10px;font-weight:620}
.upload-queue{margin-top:14px;overflow:hidden}.queue-heading{display:flex;justify-content:space-between;padding:13px 17px;border-bottom:1px solid var(--border)}.queue-heading strong{color:var(--text-strong);font-size:11px}.queue-heading span{color:var(--text-faint);font-size:9px}.queue-item{display:grid;grid-template-columns:30px minmax(0,1fr) 65px 32px;align-items:center;gap:9px;min-height:58px;padding:8px 14px 8px 17px;border-bottom:1px solid var(--border)}.queue-item:last-child{border:0}.queue-status{display:inline-flex;align-items:center;justify-content:center;width:26px;height:26px;border-radius:8px;background:var(--brand-soft);color:var(--brand)}.queue-status.done{background:var(--success-soft);color:var(--success)}.queue-status.error{background:#fff0f1;color:var(--danger)}.queue-copy{min-width:0}.queue-copy strong,.queue-copy small{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.queue-copy strong{color:var(--text);font-size:9px;font-weight:620}.queue-copy small{margin-top:3px;color:var(--text-faint);font-size:8px}.queue-copy i{display:block;height:3px;margin-top:7px;overflow:hidden;border-radius:3px;background:var(--bg-hover)}.queue-copy b{display:block;height:100%;border-radius:inherit;background:var(--brand);transition:width 180ms ease}.queue-item>span:nth-last-child(2){color:var(--text-faint);font-size:8px;text-align:right}
.document-panel{margin-top:20px;overflow:hidden}.panel-heading{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:18px 20px;border-bottom:1px solid var(--border)}.panel-heading>div:first-child{display:flex;align-items:baseline;gap:9px}.panel-heading h2{margin:0;color:var(--text-strong);font-size:14px;font-weight:650}.panel-heading span{color:var(--text-faint);font-size:9px}.panel-tools{display:flex;align-items:center;gap:6px}.table-search{display:flex;align-items:center;gap:7px;height:34px;padding:0 10px;border:1px solid var(--border);border-radius:9px;background:var(--bg-subtle);color:var(--text-faint)}.table-search input{width:145px;border:0;outline:0;background:transparent;color:var(--text);font-size:10px}.icon-button:disabled{opacity:.45;cursor:default}.panel-state{display:flex;min-height:210px;align-items:center;justify-content:center;flex-direction:column;gap:8px;padding:32px;color:var(--text-faint);font-size:10px;text-align:center}.panel-state strong{color:var(--text);font-size:12px}.panel-state.error svg{color:var(--danger)}.panel-state .secondary-button{min-height:34px;margin-top:4px;font-size:10px}
.documents-table{overflow-x:auto}.table-row{display:grid;grid-template-columns:minmax(230px,2fr) .65fr .68fr .6fr .9fr 52px;min-width:780px;align-items:center;padding:0 12px 0 20px;border:0;border-bottom:1px solid var(--border);color:var(--text-muted);font-size:10px;text-align:left}.table-header{min-height:36px;background:var(--bg-subtle);color:var(--text-faint);font-size:8px;font-weight:650;letter-spacing:.04em;text-transform:uppercase}.document-row{width:100%;min-height:64px;background:transparent;cursor:pointer}.document-row:hover{background:var(--bg-subtle)}.document-row:last-child{border-bottom:0}.doc-name{display:flex;min-width:0;align-items:center;gap:10px}.doc-name>i{display:inline-flex;align-items:center;justify-content:center;width:31px;height:31px;flex:0 0 auto;border-radius:9px;background:var(--brand-softer);color:var(--brand)}.doc-name>span{min-width:0}.doc-name strong,.doc-name small{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.doc-name strong{color:var(--text);font-size:10px;font-weight:590}.doc-name small{margin-top:3px;color:var(--text-faint);font-size:8px}.type-pill{padding:4px 7px;border-radius:6px;background:var(--bg-hover);color:var(--text-muted);font-size:8px;font-weight:600}.doc-time{display:inline-flex;align-items:center;gap:6px}.doc-time i{width:5px;height:5px;border-radius:50%;background:var(--success)}.row-actions{display:flex;justify-content:flex-end;color:var(--text-faint)}.danger-action:hover{color:var(--danger)}
.drawer-backdrop,.modal-backdrop{position:fixed;z-index:70;inset:0;background:rgba(21,22,30,.28);backdrop-filter:blur(3px)}.detail-drawer{position:absolute;top:0;right:0;width:min(520px,100%);height:100%;overflow:auto;border-left:1px solid var(--border);background:var(--bg-elevated);box-shadow:-24px 0 70px rgba(20,22,32,.13)}.detail-drawer>header{position:sticky;z-index:2;top:0;display:flex;align-items:flex-start;justify-content:space-between;gap:16px;padding:23px 25px 18px;border-bottom:1px solid var(--border);background:color-mix(in srgb,var(--bg-elevated) 90%,transparent);backdrop-filter:blur(14px)}.detail-drawer header small{color:var(--brand);font-size:8px;font-weight:700;letter-spacing:.1em;text-transform:uppercase}.detail-drawer h2{margin:5px 0 0;color:var(--text-strong);font-size:19px;font-weight:680;line-height:1.35}.drawer-content{padding:22px 25px 42px}.drawer-state{display:flex;align-items:center;justify-content:center;gap:8px;min-height:240px;color:var(--text-muted);font-size:10px}.drawer-state.error{color:var(--danger)}.detail-grid{display:grid;grid-template-columns:1fr 1fr;gap:10px}.detail-grid>div{padding:12px;border:1px solid var(--border);border-radius:11px;background:var(--bg-subtle)}.detail-grid small,.detail-grid strong{display:block}.detail-grid small{color:var(--text-faint);font-size:8px}.detail-grid strong{margin-top:5px;color:var(--text);font-size:9px;font-weight:620}.detail-section{margin-top:23px}.detail-section h3{margin:0 0 9px;color:var(--text-strong);font-size:11px;font-weight:650}.detail-section>p{margin:0;color:var(--text-muted);font-size:10px;line-height:1.8}.content-preview{padding:16px;border:1px solid var(--border);border-radius:13px;background:var(--bg-subtle)}.content-preview :deep(.markdown-body){font-size:11px}.upload-facts p{display:flex;align-items:center;gap:6px}
.modal-backdrop{display:grid;place-items:center;padding:20px}.confirm-card{width:min(400px,100%);padding:25px;border:1px solid var(--border);border-radius:18px;background:var(--bg-elevated);box-shadow:var(--shadow-md)}.confirm-icon{display:inline-flex;align-items:center;justify-content:center;width:42px;height:42px;border-radius:13px;background:#fff0f1;color:var(--danger)}.confirm-card h2{margin:16px 0 8px;color:var(--text-strong);font-size:16px}.confirm-card p{margin:0;color:var(--text-muted);font-size:10px;line-height:1.75}.confirm-card>div{display:flex;justify-content:flex-end;gap:8px;margin-top:22px}.danger-button{display:inline-flex;min-height:40px;align-items:center;gap:7px;padding:0 14px;border:1px solid var(--danger);border-radius:10px;background:var(--danger);color:white;font-size:11px;font-weight:620;cursor:pointer}.danger-button:disabled{opacity:.55}.toast-message{position:fixed;z-index:90;right:24px;bottom:24px;max-width:380px;padding:12px 15px;border:1px solid var(--border);border-radius:11px;background:var(--text-strong);color:var(--bg-elevated);box-shadow:var(--shadow-md);font-size:10px}.spin{animation:spin 1.4s linear infinite}.fade-enter-active,.fade-leave-active,.toast-enter-active,.toast-leave-active{transition:180ms ease}.fade-enter-from,.fade-leave-to{opacity:0}.toast-enter-from,.toast-leave-to{opacity:0;transform:translateY(8px)}@keyframes spin{to{transform:rotate(360deg)}}
@media(max-width:820px){.stats-grid{grid-template-columns:1fr}}@media(max-width:620px){.upload-card{align-items:flex-start;flex-wrap:wrap}.upload-action{margin-left:57px}.panel-heading{align-items:flex-start;flex-direction:column}.table-search input{width:170px}.toast-message{right:14px;bottom:84px;left:14px}.detail-grid{grid-template-columns:1fr}}
</style>
