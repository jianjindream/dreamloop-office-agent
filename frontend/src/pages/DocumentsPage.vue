<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Clock3, FilePlus2, FileText, LayoutGrid, List, Plus, RefreshCw, Search, Sparkles, Trash2, Undo2 } from 'lucide-vue-next'
import { agentApi } from '@/api/agent'
import { useDocumentCanvasStore } from '@/stores/documentCanvas'
import type { DocumentSummary } from '@/types/api'

const canvas = useDocumentCanvasStore()
const documents = ref<DocumentSummary[]>([])
const loading = ref(false)
const error = ref('')
const query = ref('')
const view = ref<'grid' | 'list'>('grid')
const showTrash = ref(false)
const filteredDocuments = computed(() => documents.value.filter((doc) =>
  doc.title.toLocaleLowerCase().includes(query.value.trim().toLocaleLowerCase())))

const loadDocuments = async () => {
  loading.value = true
  error.value = ''
  try {
    documents.value = await agentApi.documents(showTrash.value ? 'deleted' : 'active')
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '加载文档失败，请检查后端服务。'
  } finally {
    loading.value = false
  }
}

const confirmReplace = () => {
  if (!canvas.open && canvas.recovery) return window.confirm('还有可恢复的本机草稿。继续会覆盖该草稿备份，确定继续吗？')
  return !canvas.open || !canvas.dirty || window.confirm('当前文档有未保存的修改，确定要切换吗？')
}
const createDocument = () => {
  if (confirmReplace()) canvas.openDraft()
}
const openDocument = (id: string | number) => {
  if (confirmReplace()) canvas.openDocument(id)
}
const dateLabel = (value?: string) => value ? new Date(value).toLocaleDateString('zh-CN') : '最近编辑'
const removeDocument = async (doc: DocumentSummary) => {
  if (!window.confirm(`将「${doc.title}」移入回收站？历史版本和原始附件会保留。`)) return
  try {
    await agentApi.deleteDocument(doc.id)
    if (canvas.documentId === String(doc.id)) canvas.closeCanvas()
    await loadDocuments()
  } catch (caught) { error.value = caught instanceof Error ? caught.message : '删除失败' }
}
const restoreDocument = async (doc: DocumentSummary) => {
  try { await agentApi.restoreDocument(doc.id); await loadDocuments() }
  catch (caught) { error.value = caught instanceof Error ? caught.message : '恢复失败' }
}
watch(showTrash, loadDocuments)

watch(() => [canvas.documentId, canvas.version], ([id, version], previous) => {
  if (id && version && (id !== previous?.[0] || version !== previous?.[1])) loadDocuments()
})
onMounted(loadDocuments)
</script>

<template>
  <div class="page-shell documents-page">
    <header class="page-heading">
      <div><h1>AI 文档</h1><p>将对话结果沉淀为可继续编辑和复用的工作成果。</p></div>
      <button class="primary-button" type="button" @click="createDocument"><Plus :size="17" /> 新建文档</button>
    </header>
    <div v-if="canvas.recovery && !canvas.open" class="documents-message" role="status">发现本机未保存草稿「{{ canvas.recovery.title }}」 <button type="button" @click="canvas.restoreDraft()">恢复草稿</button></div>

    <section class="template-banner">
      <div class="template-art"><Sparkles :size="24" /></div>
      <div><span>AI 文档工作台</span><h2>从一个想法开始，沉淀可编辑的成果</h2><p>新建草稿，或在对话中将 AI 回答转为文档；可用 AI 改写、扩写与总结。</p></div>
      <button class="secondary-button" type="button" @click="createDocument">开始写作</button>
    </section>

    <div class="documents-toolbar">
      <div><button type="button" :class="{ active: !showTrash }" @click="showTrash = false">全部文档</button><button type="button" :class="{ active: showTrash }" @click="showTrash = true">回收站</button><small>{{ documents.length }}</small></div>
      <div class="toolbar-right">
        <label><Search :size="15" /><input v-model="query" placeholder="搜索文档" /></label>
        <button class="view-button" :class="{ active: view === 'grid' }" type="button" aria-label="网格视图" @click="view = 'grid'"><LayoutGrid :size="16" /></button>
        <button class="view-button" :class="{ active: view === 'list' }" type="button" aria-label="列表视图" @click="view = 'list'"><List :size="16" /></button>
        <button class="view-button" type="button" aria-label="刷新文档列表" @click="loadDocuments"><RefreshCw :size="15" /></button>
      </div>
    </div>

    <div v-if="error" class="documents-message error" role="alert">{{ error }} <button type="button" @click="loadDocuments">重试</button></div>
    <div v-if="loading" class="documents-message">正在加载文档…</div>
    <section v-else class="document-grid" :class="{ 'list-view': view === 'list' }">
      <button v-if="!query && !showTrash" class="new-document-card" type="button" @click="createDocument">
        <span><FilePlus2 :size="22" /></span><strong>创建空白文档</strong><small>在右侧 Canvas 中编辑</small>
      </button>
      <article v-for="doc in filteredDocuments" :key="doc.id" class="document-card surface-card">
        <button class="document-open" type="button" :disabled="showTrash" @click="openDocument(doc.id)">
        <div class="doc-preview violet">
          <div class="preview-lines"><i></i><i></i><i></i><i></i></div>
          <span><FileText :size="20" /></span>
        </div>
        <div class="document-info">
          <div class="document-title"><div><span>{{ doc.status === 'draft' ? '草稿' : doc.docType || '文档' }}</span><h3>{{ doc.title }}</h3></div></div>
          <div class="document-meta"><span><Clock3 :size="12" />{{ dateLabel(doc.updatedAt) }}</span><span>版本 {{ doc.latestVersion || 1 }}</span></div>
        </div>
        </button>
        <div class="document-actions"><button v-if="showTrash" type="button" :aria-label="`恢复 ${doc.title}`" @click="restoreDocument(doc)"><Undo2 :size="13" />恢复文档</button><button v-else type="button" :aria-label="`删除 ${doc.title}`" @click="removeDocument(doc)"><Trash2 :size="13" />移入回收站</button></div>
      </article>
    </section>
    <div v-if="!loading && !error && query && filteredDocuments.length === 0" class="documents-message">没有找到匹配“{{ query }}”的文档。</div>
  </div>
</template>

<style scoped>
.document-open { width:100%; padding:0; border:0; background:transparent; text-align:left; cursor:pointer; }
.document-open:disabled { cursor:default; }
.document-actions { display:flex; padding:0 14px 12px; justify-content:flex-end; }
.document-actions button { display:flex; align-items:center; gap:4px; padding:5px 8px; border:1px solid var(--border); border-radius:6px; background:var(--bg-elevated); color:var(--text-muted); font-size:10px; cursor:pointer; }
.list-view .document-open { display:flex; flex:1; min-width:0; width:auto; align-items:center; }
.list-view .document-actions { flex:0 0 auto; padding:8px; }
.template-banner { position: relative; display: flex; align-items: center; gap: 18px; padding: 22px 24px; overflow: hidden; border: 1px solid color-mix(in srgb, var(--brand) 15%, var(--border)); border-radius: 17px; background: linear-gradient(115deg, var(--brand-softer), var(--bg-elevated)); }
.template-banner::after { position: absolute; top: -70px; right: 120px; width: 190px; height: 190px; border-radius: 50%; background: color-mix(in srgb, var(--brand) 8%, transparent); content: ''; }
.template-art { display: inline-flex; align-items: center; justify-content: center; width: 52px; height: 52px; flex: 0 0 auto; border-radius: 16px; background: var(--brand); color: white; box-shadow: 0 12px 26px rgba(97,87,230,.22); }
.template-banner > div:nth-child(2) { position: relative; z-index: 1; flex: 1; }
.template-banner span { color: var(--brand); font-size: 9px; font-weight: 700; }
.template-banner h2 { margin: 5px 0 0; color: var(--text-strong); font-size: 16px; font-weight: 650; }
.template-banner p { margin: 5px 0 0; color: var(--text-muted); font-size: 10px; }
.template-banner button { position: relative; z-index: 1; }
.documents-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 18px; margin: 25px 0 13px; }
.all-documents-label{color:var(--text-strong);font-size:11px;font-weight:660}.all-documents-label small{margin-left:5px;color:var(--text-faint);font-size:9px}.documents-message{padding:22px;border:1px solid var(--border);border-radius:12px;background:var(--bg-elevated);color:var(--text-muted);font-size:11px}.documents-message.error{color:var(--danger)}.documents-message button{margin-left:7px;border:0;background:transparent;color:var(--brand);cursor:pointer}
.documents-toolbar > div:first-child { display: flex; gap: 4px; }
.documents-toolbar > div:first-child button { padding: 8px 11px; border: 0; border-radius: 8px; background: transparent; color: var(--text-muted); font-size: 10px; cursor: pointer; }
.documents-toolbar > div:first-child button.active { background: var(--bg-elevated); color: var(--text-strong); box-shadow: var(--shadow-sm); font-weight: 620; }
.toolbar-right { display: flex; align-items: center; gap: 5px; }
.toolbar-right label { display: flex; align-items: center; gap: 7px; height: 34px; padding: 0 10px; border: 1px solid var(--border); border-radius: 9px; background: var(--bg-elevated); color: var(--text-faint); }
.toolbar-right input { width: 120px; border: 0; outline: 0; background: transparent; color: var(--text); font-size: 10px; }
.view-button { display: inline-flex; align-items: center; justify-content: center; width: 34px; height: 34px; padding: 0; border: 1px solid transparent; border-radius: 9px; background: transparent; color: var(--text-faint); cursor: pointer; }
.view-button.active { border-color: var(--border); background: var(--bg-elevated); color: var(--brand); }
.document-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 13px; }
.document-grid.list-view{grid-template-columns:1fr}.list-view .new-document-card{min-height:72px}.list-view .document-card{display:flex;min-height:72px}.list-view .doc-preview{width:85px;height:72px;flex:0 0 auto}.list-view .document-info{flex:1}.list-view .document-meta{margin-top:6px}
.new-document-card, .document-card { min-height: 246px; overflow: hidden; }
.new-document-card { display: flex; align-items: center; justify-content: center; flex-direction: column; border: 1px dashed var(--border-strong); border-radius: 16px; background: color-mix(in srgb, var(--bg-elevated) 55%, transparent); color: var(--text-muted); cursor: pointer; transition: 160ms ease; }
.new-document-card:hover { border-color: var(--brand); background: var(--brand-softer); color: var(--brand); }
.new-document-card span { display: inline-flex; align-items: center; justify-content: center; width: 45px; height: 45px; border-radius: 14px; background: var(--bg-elevated); box-shadow: var(--shadow-sm); }
.new-document-card strong { margin-top: 13px; color: var(--text-strong); font-size: 11px; font-weight: 620; }
.new-document-card small { margin-top: 5px; color: var(--text-faint); font-size: 9px; }
.document-card { padding:0;border:1px solid var(--border);background:var(--bg-elevated);text-align:left;cursor:pointer;transition: 170ms ease; }
.document-card:hover { border-color: var(--border-strong); box-shadow: var(--shadow-md); transform: translateY(-2px); }
.doc-preview { position: relative; display: flex; align-items: center; justify-content: center; height: 128px; overflow: hidden; }
.doc-preview.violet { background: linear-gradient(145deg, #efedff, #dad6ff); color: #6358d5; }
.doc-preview.blue { background: linear-gradient(145deg, #eaf5ff, #d5eaff); color: #3f79ad; }
.doc-preview.green { background: linear-gradient(145deg, #ebf8f2, #d4f0e3); color: #338966; }
.doc-preview.amber { background: linear-gradient(145deg, #fff8e8, #faeac5); color: #b07825; }
.doc-preview > span { position: absolute; top: 12px; right: 12px; display: inline-flex; padding: 7px; border-radius: 9px; background: rgba(255,255,255,.7); backdrop-filter: blur(8px); }
.preview-lines { width: 54%; padding: 14px; border-radius: 7px; background: rgba(255,255,255,.78); box-shadow: 0 9px 25px rgba(49,50,70,.09); transform: rotate(-2deg); }
.preview-lines i { display: block; width: 100%; height: 4px; margin: 6px 0; border-radius: 9px; background: currentColor; opacity: .16; }
.preview-lines i:nth-child(2) { width: 73%; } .preview-lines i:nth-child(4) { width: 48%; }
.document-info { padding: 14px; }
.document-title { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; }
.document-title span { color: var(--brand); font-size: 8px; font-weight: 650; }
.document-title h3 { margin: 4px 0 0; color: var(--text-strong); font-size: 11px; font-weight: 630; }
.document-title .icon-button { width: 28px; height: 28px; }
.document-meta { display: flex; justify-content: space-between; margin-top: 13px; color: var(--text-faint); font-size: 8px; }
.document-meta span { display: inline-flex; align-items: center; gap: 4px; }
.completion { display: flex; align-items: center; gap: 8px; margin-top: 12px; }
.completion > span { height: 3px; flex: 1; overflow: hidden; border-radius: 99px; background: var(--bg-hover); }
.completion i { display: block; height: 100%; border-radius: inherit; background: var(--brand); }
.completion small { color: var(--text-faint); font-size: 7px; }
@media (max-width: 940px) { .document-grid { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 650px) { .template-banner { align-items: flex-start; flex-wrap: wrap; gap:12px; padding:18px; } .template-banner > div:nth-child(2) { flex-basis:calc(100% - 68px); } .template-banner h2{font-size:14px}.template-banner p{font-size:10px;line-height:1.5}.template-banner button { margin-left: 64px; } .documents-toolbar { align-items: flex-start; flex-direction: column; } .document-grid { grid-template-columns: 1fr; } }
</style>
