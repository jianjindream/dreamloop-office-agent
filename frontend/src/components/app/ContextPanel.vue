<script setup lang="ts">
import {
  Check,
  CircleCheck,
  FileText,
  Globe2,
  LibraryBig,
  MoreHorizontal,
  RefreshCw,
  Settings2,
} from 'lucide-vue-next'
import { computed, onMounted, ref } from 'vue'
import { agentApi } from '@/api/agent'
import { useWorkspaceStore } from '@/stores/workspace'
import type { DocumentSummary } from '@/types/api'

const workspace = useWorkspaceStore()
const documents = ref<DocumentSummary[]>([])
const loading = ref(false)
const error = ref('')
const selected = computed(() => documents.value.filter((doc) => workspace.selectedDocumentIds.includes(String(doc.id))))
const scopeLabel = computed(() => !workspace.knowledgeEnabled
  ? '本次不使用资料'
  : workspace.selectedDocumentIds.length ? `仅检索已选 ${workspace.selectedDocumentIds.length} 份资料` : '检索整个工作区')
const loadDocuments = async () => {
  loading.value = true
  error.value = ''
  try {
    documents.value = (await agentApi.documents()).filter((doc) => doc.status !== 'deleted')
    workspace.retainDocuments(documents.value.map((doc) => String(doc.id)))
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '资料加载失败' }
  finally { loading.value = false }
}
onMounted(loadDocuments)
</script>

<template>
  <aside class="context-panel">
    <div class="context-heading">
      <div>
        <span>CONTEXT</span>
        <h2>对话上下文</h2>
      </div>
      <button class="icon-button" type="button" aria-label="上下文设置">
        <Settings2 :size="17" />
      </button>
    </div>

    <section class="context-section">
      <div class="section-title">
        <span>正在使用</span>
        <button type="button" aria-label="更多"><MoreHorizontal :size="16" /></button>
      </div>
      <button
        class="context-toggle"
        :class="{ enabled: workspace.knowledgeEnabled }"
        type="button"
        @click="workspace.toggleKnowledge"
      >
        <span class="resource-icon knowledge"><LibraryBig :size="16" /></span>
        <span class="toggle-copy">
          <strong>工作区知识库</strong>
          <small>{{ scopeLabel }}</small>
        </span>
        <span class="switch"><i></i></span>
      </button>
      <div class="context-toggle enabled">
        <span class="resource-icon web"><Globe2 :size="16" /></span>
        <span class="toggle-copy">
          <strong>联网搜索</strong>
          <small>需要时自动调用</small>
        </span>
        <span class="switch"><i></i></span>
      </div>
    </section>

    <section class="context-section">
      <div class="section-title">
        <span>精确资料范围</span>
        <button v-if="workspace.selectedDocumentIds.length" type="button" @click="workspace.clearDocuments">清空</button>
        <button v-else type="button" :disabled="loading" aria-label="刷新资料" @click="loadDocuments"><RefreshCw :size="13" /></button>
      </div>
      <p v-if="error" class="context-error">{{ error }}</p>
      <p v-else-if="loading" class="context-empty">正在加载资料…</p>
      <p v-else-if="!documents.length" class="context-empty">暂无资料，可在输入框上传附件。</p>
      <button v-for="item in documents" :key="item.id" class="resource-item" :class="{ selected: workspace.selectedDocumentIds.includes(String(item.id)) }" type="button" @click="workspace.toggleDocument(String(item.id))">
        <span class="resource-icon"><FileText :size="15" /></span>
        <span class="resource-copy">
          <strong>{{ item.title }}</strong>
          <small>版本 {{ item.latestVersion || 1 }} · {{ item.status === 'draft' ? '草稿' : '已保存' }}</small>
        </span>
        <span class="select-mark"><Check v-if="workspace.selectedDocumentIds.includes(String(item.id))" :size="12" /></span>
      </button>
    </section>

    <section class="context-section activity-section">
      <div class="section-title"><span>本次会话</span></div>
      <div class="activity-card">
        <div><CircleCheck :size="15" /> 上下文已就绪</div>
        <dl>
          <div><dt>已选资料</dt><dd>{{ selected.length }}</dd></div>
          <div><dt>检索范围</dt><dd>{{ !workspace.knowledgeEnabled ? '关闭' : selected.length ? '精确' : '工作区' }}</dd></div>
          <div><dt>引用定位</dt><dd>开启</dd></div>
        </dl>
      </div>
    </section>

    <div class="context-tip">
      <span>提示</span>
      <p>选择资料后，本次提问只会检索这些文档；清空选择后恢复整个工作区。</p>
    </div>
  </aside>
</template>

<style scoped>
.context-panel {
  display: flex;
  min-width: 0;
  height: 100%;
  padding: 0 18px 18px;
  flex-direction: column;
  overflow: auto;
  border-left: 1px solid var(--border);
  background: var(--bg-panel);
  backdrop-filter: blur(18px);
}

.context-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: var(--topbar-height);
  border-bottom: 1px solid var(--border);
}

.context-heading span {
  color: var(--text-faint);
  font-size: 8px;
  font-weight: 700;
  letter-spacing: 0.12em;
}

.context-heading h2 {
  margin: 3px 0 0;
  color: var(--text-strong);
  font-size: 14px;
  font-weight: 650;
}

.context-section {
  padding: 20px 0;
  border-bottom: 1px solid var(--border);
}

.section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
  color: var(--text-faint);
  font-size: 9px;
  font-weight: 680;
  letter-spacing: 0.07em;
  text-transform: uppercase;
}

.section-title button {
  display: inline-flex;
  align-items: center;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--brand);
  font-size: 9px;
  cursor: pointer;
}

.context-toggle,
.resource-item {
  display: flex;
  align-items: center;
  width: 100%;
  gap: 9px;
  padding: 9px 8px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: var(--text-muted);
  text-align: left;
  cursor: pointer;
}

.context-toggle:hover,
.resource-item:hover {
  background: var(--bg-hover);
}

.resource-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  flex: 0 0 auto;
  border: 1px solid var(--border);
  border-radius: 9px;
  background: var(--bg-elevated);
  color: var(--text-muted);
}

.resource-icon.knowledge {
  border-color: transparent;
  background: var(--brand-soft);
  color: var(--brand);
}

.resource-icon.web {
  border-color: transparent;
  background: var(--success-soft);
  color: var(--success);
}

.toggle-copy,
.resource-copy {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
}

.toggle-copy strong,
.resource-copy strong {
  overflow: hidden;
  color: var(--text);
  font-size: 10px;
  font-weight: 610;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.toggle-copy small,
.resource-copy small {
  margin-top: 3px;
  overflow: hidden;
  color: var(--text-faint);
  font-size: 8px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.resource-item.selected { background: var(--brand-softer); color: var(--brand); }
.select-mark { display:inline-flex;align-items:center;justify-content:center;width:17px;height:17px;border:1px solid var(--border-strong);border-radius:5px; }
.resource-item.selected .select-mark { border-color:var(--brand);background:var(--brand);color:white; }
.context-empty,.context-error { margin:8px;padding:8px;border-radius:8px;color:var(--text-faint);font-size:9px;line-height:1.5; }
.context-error { color:var(--danger);background:color-mix(in srgb,var(--danger) 6%,transparent); }

.switch {
  position: relative;
  width: 26px;
  height: 16px;
  flex: 0 0 auto;
  border-radius: 999px;
  background: var(--border-strong);
  transition: 150ms ease;
}

.switch i {
  position: absolute;
  top: 3px;
  left: 3px;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.2);
  transition: 150ms ease;
}

.enabled .switch {
  background: var(--brand);
}

.enabled .switch i {
  transform: translateX(10px);
}

.activity-card {
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--bg-subtle);
}

.activity-card > div {
  display: flex;
  align-items: center;
  gap: 7px;
  color: var(--success);
  font-size: 10px;
  font-weight: 620;
}

.activity-card dl {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin: 13px 0 0;
}

.activity-card dl div {
  display: flex;
  flex-direction: column;
}

.activity-card dt {
  color: var(--text-faint);
  font-size: 8px;
}

.activity-card dd {
  margin: 3px 0 0;
  color: var(--text-strong);
  font-size: 12px;
  font-weight: 680;
}

.context-tip {
  margin-top: auto;
  padding: 13px;
  border-radius: 12px;
  background: var(--brand-softer);
}

.context-tip span {
  color: var(--brand);
  font-size: 9px;
  font-weight: 700;
}

.context-tip p {
  margin: 5px 0 0;
  color: var(--text-muted);
  font-size: 9px;
  line-height: 1.65;
}

@media (max-width: 1180px) {
  .context-panel {
    display: none;
  }
}
</style>
