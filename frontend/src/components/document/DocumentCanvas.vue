<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft, Check, ChevronDown, Clock3, FileText, LoaderCircle,
  Maximize2, PenLine, Save, Sparkles, X,
} from 'lucide-vue-next'
import { agentApi } from '@/api/agent'
import MarkdownContent from '@/components/chat/MarkdownContent.vue'
import { useChatStore } from '@/stores/chat'
import { useDocumentCanvasStore } from '@/stores/documentCanvas'
import { useWorkspaceStore } from '@/stores/workspace'
import type { DocumentVersion } from '@/types/api'

type AiAction = 'rewrite' | 'expand' | 'summarize'

const route = useRoute()
const router = useRouter()
const canvas = useDocumentCanvasStore()
const chat = useChatStore()
const workspace = useWorkspaceStore()
const mode = ref<'edit' | 'preview'>('edit')
const aiMenuOpen = ref(false)
const aiBusy = ref(false)
const historyOpen = ref(false)
const versions = ref<DocumentVersion[]>([])
const historyLoading = ref(false)
const historyError = ref('')
const selectedVersion = ref<DocumentVersion | null>(null)
const aiLabels: Record<AiAction, string> = { rewrite: '改写', expand: '扩写', summarize: '总结' }

const wordCount = computed(() => canvas.content.replace(/\s/g, '').length)
const sourceSession = computed(() => chat.sessions.find((session) => session.id === canvas.sourceSessionId))
const sourceMessage = computed(() => sourceSession.value?.messages.find((message) => message.id === canvas.sourceMessageId))
const canSave = computed(() => !canvas.loading && !canvas.saving && canvas.dirty)

watch(() => canvas.requestId, () => {
  historyOpen.value = false
  selectedVersion.value = null
  versions.value = []
  aiMenuOpen.value = false
})

const discardConfirmed = () => !canvas.dirty || window.confirm('文档有未保存的修改，确定要放弃吗？')
const close = () => {
  if (discardConfirmed()) canvas.closeCanvas()
}

const save = async () => {
  await canvas.save()
}

const openHistory = async () => {
  if (!canvas.documentId) {
    canvas.notice = '保存文档后即可查看历史版本。'
    return
  }
  historyOpen.value = !historyOpen.value
  if (!historyOpen.value) return
  historyLoading.value = true
  historyError.value = ''
  const documentId = canvas.documentId
  try {
    const history = await agentApi.documentVersions(documentId)
    if (canvas.documentId === documentId) versions.value = history
  } catch (error) {
    if (canvas.documentId === documentId) historyError.value = error instanceof Error ? error.message : '加载历史版本失败'
  } finally {
    historyLoading.value = false
  }
}

const restoreVersion = () => {
  if (!selectedVersion.value) return
  canvas.content = selectedVersion.value?.contentMd ?? canvas.content
  canvas.notice = `版本 ${selectedVersion.value?.version} 已载入编辑器，保存后会创建新版本。`
  selectedVersion.value = null
  historyOpen.value = false
  mode.value = 'edit'
}

const applyAi = async (action: AiAction) => {
  if (aiBusy.value || !canvas.content.trim()) return
  aiBusy.value = true
  aiMenuOpen.value = false
  canvas.error = ''
  canvas.notice = ''
  const instructions: Record<AiAction, string> = {
    rewrite: '保持原意与关键信息，优化逻辑、结构和表达，让文档更专业。',
    expand: '补充必要背景、论据和可执行细节，保留已有内容并扩写为更完整的文档。',
    summarize: '提炼核心观点、结论和下一步行动，写成精炼的摘要文档。',
  }
  const sessionId = canvas.sourceSessionId || chat.currentSessionId || `document-${canvas.documentId || 'draft'}`
  const requestId = canvas.requestId
  const originalContent = canvas.content
  try {
    const response = await agentApi.chat({
      message: `你正在编辑一份名为「${canvas.title}」的 Markdown 文档。请${instructions[action]}只返回修改后的完整 Markdown 正文，不要解释、不加代码围栏。\n\n原文：\n${originalContent}`,
      use_rag: workspace.knowledgeEnabled,
      selected_tools: [],
      explicit: false,
      user_id: chat.userId,
      session_id: sessionId,
    })
    const answer = response.answer?.trim()
    if (!answer) throw new Error('AI 没有返回可用的文档内容，请重试。')
    if (canvas.requestId !== requestId || canvas.content !== originalContent) return
    canvas.content = answer.replace(/^```(?:markdown|md)?\s*\n/i, '').replace(/\n```\s*$/, '')
    canvas.notice = `AI ${aiLabels[action]}完成，内容尚未保存。`
    mode.value = 'edit'
  } catch (error) {
    if (canvas.requestId === requestId) canvas.error = error instanceof Error ? error.message : `AI ${aiLabels[action]}失败`
  } finally {
    aiBusy.value = false
  }
}

const jumpToChat = () => {
  if (canvas.sourceSessionId) chat.selectSession(canvas.sourceSessionId)
  if (route.name !== 'chat') router.push({ name: 'chat' })
}
</script>

<template>
  <aside class="document-canvas" aria-label="文档编辑画布">
    <header class="canvas-header">
      <div class="canvas-identity"><span class="canvas-icon"><FileText :size="17" /></span><div><small>DOCUMENT CANVAS</small><strong>{{ canvas.documentId ? `版本 ${canvas.version}` : '新草稿' }}</strong></div></div>
      <button class="icon-button" type="button" aria-label="关闭文档画布" @click="close"><X :size="18" /></button>
    </header>

    <div v-if="canvas.loading" class="canvas-loading"><LoaderCircle :size="20" class="spin" />正在加载文档…</div>
    <template v-else>
      <div class="canvas-toolbar">
        <div class="mode-switch"><button :class="{ active: mode === 'edit' }" type="button" @click="mode = 'edit'"><PenLine :size="13" />编辑</button><button :class="{ active: mode === 'preview' }" type="button" @click="mode = 'preview'"><Maximize2 :size="13" />预览</button></div>
        <div class="ai-selector">
          <button class="ai-trigger" type="button" :disabled="aiBusy || !canvas.content.trim()" @click="aiMenuOpen = !aiMenuOpen"><LoaderCircle v-if="aiBusy" class="spin" :size="14" /><Sparkles v-else :size="14" />{{ aiBusy ? '处理中' : 'AI 助手' }}<ChevronDown :size="12" /></button>
          <div v-if="aiMenuOpen" class="ai-menu"><button type="button" @click="applyAi('rewrite')">改写表达<small>改善语气与结构</small></button><button type="button" @click="applyAi('expand')">扩写内容<small>补充论据与细节</small></button><button type="button" @click="applyAi('summarize')">总结文档<small>提炼要点与行动</small></button></div>
        </div>
      </div>

      <div class="canvas-body">
        <div v-if="historyOpen" class="history-view">
          <button class="back-button" type="button" @click="selectedVersion ? selectedVersion = null : historyOpen = false"><ArrowLeft :size="14" />{{ selectedVersion ? '返回版本列表' : '返回编辑器' }}</button>
          <template v-if="selectedVersion"><h2>版本 {{ selectedVersion.version }}</h2><p>创建于 {{ selectedVersion.createdAt ? new Date(selectedVersion.createdAt).toLocaleString('zh-CN') : '未知时间' }}</p><div class="history-content"><MarkdownContent :content="selectedVersion.contentMd || ''" /></div><button class="secondary-button" type="button" @click="restoreVersion">将此版本载入编辑器</button></template>
          <template v-else><h2>历史版本</h2><p>每次保存都会保留一个独立版本。</p><div v-if="historyLoading" class="muted-line">正在加载…</div><div v-else-if="historyError" class="error-line">{{ historyError }}</div><button v-for="item in versions" :key="item.id" class="version-row" type="button" @click="selectedVersion = item"><span>版本 {{ item.version }}</span><small>{{ item.createdAt ? new Date(item.createdAt).toLocaleString('zh-CN') : '' }}</small></button></template>
        </div>
        <template v-else>
          <div class="document-head"><span class="eyebrow">{{ canvas.sourceMessageId ? '由聊天回答创建' : '工作区文档' }}</span><input v-model="canvas.title" aria-label="文档标题" placeholder="文档标题" :readonly="aiBusy || canvas.saving" /><div class="document-subtitle"><span>{{ wordCount }} 字</span><span>·</span><span>{{ canvas.dirty ? '有未保存的修改' : '所有修改已保存' }}</span></div></div>
          <div v-if="canvas.sourceSessionId" class="source-link"><span><Sparkles :size="13" />关联对话：{{ sourceSession?.title || '会话' }}<small v-if="sourceMessage">{{ sourceMessage.content.slice(0, 42) }}</small></span><button type="button" @click="jumpToChat">查看对话</button></div>
          <textarea v-if="mode === 'edit'" v-model="canvas.content" class="document-editor" aria-label="Markdown 文档正文" placeholder="开始撰写文档内容…\n\n支持 Markdown 标题、列表和引用。" :readonly="aiBusy || canvas.saving"></textarea>
          <div v-else class="document-preview"><MarkdownContent v-if="canvas.content" :content="canvas.content" /><p v-else class="empty-preview">正文为空，切换到编辑模式开始写作。</p></div>
        </template>
      </div>

      <div v-if="canvas.error" class="canvas-feedback error" role="alert">{{ canvas.error }}</div>
      <div v-else-if="canvas.notice" class="canvas-feedback"><Check :size="13" />{{ canvas.notice }}</div>
      <footer class="canvas-footer"><button class="history-button" type="button" @click="openHistory"><Clock3 :size="15" />历史版本</button><button class="primary-button" type="button" :disabled="!canSave || aiBusy" @click="save"><LoaderCircle v-if="canvas.saving" class="spin" :size="14" /><Save v-else :size="14" />{{ canvas.saving ? '保存中' : '保存文档' }}</button></footer>
    </template>
  </aside>
</template>

<style scoped>
.document-canvas{display:flex;min-width:0;height:100%;flex-direction:column;border-left:1px solid var(--border);background:var(--bg-elevated);box-shadow:-12px 0 38px rgba(27,29,54,.045)}
.canvas-header{display:flex;align-items:center;justify-content:space-between;height:var(--topbar-height);padding:0 20px;border-bottom:1px solid var(--border)}.canvas-identity{display:flex;align-items:center;gap:10px}.canvas-icon{display:flex;align-items:center;justify-content:center;width:34px;height:34px;border-radius:10px;background:var(--brand-soft);color:var(--brand)}.canvas-identity div{display:flex;flex-direction:column;gap:2px}.canvas-identity small{color:var(--text-faint);font-size:8px;letter-spacing:.1em}.canvas-identity strong{color:var(--text-strong);font-size:11px}
.canvas-toolbar{display:flex;align-items:center;justify-content:space-between;gap:8px;padding:12px 20px;border-bottom:1px solid var(--border)}.mode-switch{display:flex;gap:2px;padding:3px;border-radius:9px;background:var(--bg-hover)}.mode-switch button{display:flex;align-items:center;gap:5px;padding:6px 9px;border:0;border-radius:7px;background:transparent;color:var(--text-muted);font-size:10px;cursor:pointer}.mode-switch button.active{background:var(--bg-elevated);color:var(--brand);box-shadow:var(--shadow-sm)}.ai-selector{position:relative}.ai-trigger{display:flex;align-items:center;gap:5px;padding:8px 9px;border:1px solid color-mix(in srgb,var(--brand) 20%,var(--border));border-radius:8px;background:var(--brand-softer);color:var(--brand);font-size:10px;font-weight:650;cursor:pointer}.ai-trigger:disabled{opacity:.5;cursor:default}.ai-menu{position:absolute;z-index:20;top:37px;right:0;width:180px;padding:5px;border:1px solid var(--border);border-radius:11px;background:var(--bg-elevated);box-shadow:var(--shadow-md)}.ai-menu button{display:flex;width:100%;flex-direction:column;gap:3px;padding:9px;border:0;border-radius:8px;background:transparent;color:var(--text-strong);font-size:10px;text-align:left;cursor:pointer}.ai-menu button:hover{background:var(--bg-hover)}.ai-menu small{color:var(--text-faint);font-size:8px}
.canvas-body{min-height:0;flex:1;overflow-y:auto;padding:24px 24px 0}.document-head{display:flex;flex-direction:column}.eyebrow{color:var(--brand);font-size:9px;font-weight:700;letter-spacing:.04em}.document-head input{width:100%;margin:9px 0 5px;padding:0;border:0;outline:0;background:transparent;color:var(--text-strong);font-size:25px;font-weight:680;letter-spacing:-.035em}.document-head input::placeholder{color:var(--text-faint)}.document-subtitle{display:flex;gap:7px;color:var(--text-faint);font-size:9px}.source-link{display:flex;align-items:center;justify-content:space-between;gap:10px;margin:18px 0 0;padding:10px;border:1px solid var(--border);border-radius:10px;background:var(--brand-softer);font-size:9px}.source-link span{display:flex;min-width:0;align-items:center;gap:5px;color:var(--brand)}.source-link small{overflow:hidden;color:var(--text-muted);text-overflow:ellipsis;white-space:nowrap}.source-link button{flex:0 0 auto;border:0;background:transparent;color:var(--brand);font-size:9px;cursor:pointer}.document-editor{display:block;width:100%;min-height:calc(100% - 140px);margin-top:20px;padding:0 0 50px;resize:none;border:0;outline:0;background:transparent;color:var(--text);font-family:inherit;font-size:12px;line-height:1.9}.document-editor::placeholder{color:var(--text-faint)}.document-preview{padding:20px 0 50px;font-size:12px}.empty-preview{color:var(--text-faint)}
.history-view h2{margin:18px 0 4px;color:var(--text-strong);font-size:18px}.history-view p{margin:0 0 18px;color:var(--text-muted);font-size:10px}.back-button{display:flex;align-items:center;gap:5px;padding:0;border:0;background:transparent;color:var(--brand);font-size:10px;cursor:pointer}.version-row{display:flex;align-items:center;justify-content:space-between;width:100%;padding:13px 8px;border:0;border-bottom:1px solid var(--border);background:transparent;color:var(--text);font-size:11px;text-align:left;cursor:pointer}.version-row:hover{background:var(--bg-hover)}.version-row small{color:var(--text-faint);font-size:9px}.history-content{margin:20px 0;padding:14px;border:1px solid var(--border);border-radius:10px}.muted-line{color:var(--text-faint);font-size:10px}.error-line{color:var(--danger);font-size:10px}
.canvas-feedback{display:flex;align-items:center;gap:5px;padding:9px 20px;border-top:1px solid var(--border);color:var(--success);font-size:9px}.canvas-feedback.error{color:var(--danger)}.canvas-footer{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:14px 20px;border-top:1px solid var(--border)}.history-button{display:flex;align-items:center;gap:6px;padding:7px 0;border:0;background:transparent;color:var(--text-muted);font-size:10px;cursor:pointer}.history-button:hover{color:var(--brand)}.canvas-footer .primary-button{min-height:34px;font-size:10px}.canvas-footer .primary-button:disabled{opacity:.45;cursor:default;transform:none}.canvas-loading{display:flex;align-items:center;justify-content:center;flex:1;gap:8px;color:var(--text-muted);font-size:11px}.spin{animation:spin 1s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}
@media(max-width:700px){.canvas-body{padding:18px 16px 0}.canvas-header,.canvas-toolbar,.canvas-footer{padding-left:16px;padding-right:16px}.document-head input{font-size:22px}}
</style>
