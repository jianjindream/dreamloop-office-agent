<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Bell,
  Command,
  Moon,
  PanelRight,
  Search,
  Sun,
} from 'lucide-vue-next'
import { useWorkspaceStore } from '@/stores/workspace'
import { useDocumentCanvasStore } from '@/stores/documentCanvas'
import { useChatStore } from '@/stores/chat'
import { agentApi } from '@/api/agent'
import type { DocumentSummary } from '@/types/api'

const route = useRoute()
const router = useRouter()
const workspace = useWorkspaceStore()
const canvas = useDocumentCanvasStore()
const chat = useChatStore()
const title = computed(() => String(route.meta.title ?? 'DreamLoop'))
const eyebrow = computed(() => String(route.meta.eyebrow ?? 'AI Workspace'))
const supportsContext = computed(() => Boolean(route.meta.showContext) && !canvas.open)
const serviceStatus = ref<'checking' | 'online' | 'offline'>('checking')
const paletteOpen = ref(false)
const search = ref('')
const searchInput = ref<HTMLInputElement | null>(null)
const searchDialog = ref<HTMLElement | null>(null)
const documents = ref<DocumentSummary[]>([])
const pages = [
  { label: 'AI 对话', description: '打开聊天工作台', to: '/chat' },
  { label: '知识库', description: '浏览和上传资料', to: '/knowledge' },
  { label: 'AI 文档', description: '打开文档工作台', to: '/documents' },
  { label: '工具中心', description: '查看可用工具', to: '/tools' },
  { label: '系统状态', description: '查看服务运行情况', to: '/status' },
  { label: '设置', description: '工作区偏好', to: '/settings' },
]
const results = computed(() => {
  const query = search.value.trim().toLocaleLowerCase()
  const items = [
    ...pages.map((item) => ({ ...item, kind: 'page', id: item.to })),
    ...chat.sortedSessions.slice(0, 10).map((item) => ({ label: item.title, description: '最近对话', to: '/chat', kind: 'chat', id: item.id })),
    ...documents.value.slice(0, 20).map((item) => ({ label: item.title, description: '文档', to: '/documents', kind: 'document', id: String(item.id) })),
  ]
  return items.filter((item) => !query || `${item.label} ${item.description}`.toLocaleLowerCase().includes(query)).slice(0, 12)
})
let statusTimer: number | undefined
let paletteTrigger: HTMLElement | null = null

const openPalette = async () => {
  paletteTrigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
  paletteOpen.value = true
  search.value = ''
  await nextTick()
  searchInput.value?.focus()
  try { documents.value = await agentApi.documents() } catch { documents.value = [] }
}

const closePalette = async () => {
  paletteOpen.value = false
  await nextTick()
  paletteTrigger?.focus()
  paletteTrigger = null
}

const selectResult = async (item: (typeof results.value)[number]) => {
  if (item.kind === 'document' && canvas.open && canvas.dirty && !window.confirm('当前文档有未保存的修改，确定要切换吗？')) return
  if (item.kind === 'chat') chat.selectSession(item.id)
  closePalette()
  await router.push(item.to)
  if (item.kind === 'document') canvas.openDocument(item.id)
}

const onGlobalKeydown = (event: KeyboardEvent) => {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    if (!paletteOpen.value) openPalette()
    else closePalette()
  } else if (event.key === 'Escape' && paletteOpen.value) {
    event.preventDefault()
    closePalette()
  }
}

const onPaletteKeydown = (event: KeyboardEvent) => {
  const buttons = Array.from(searchDialog.value?.querySelectorAll<HTMLButtonElement>('.search-result') ?? [])
  if (event.key === 'Enter' && document.activeElement === searchInput.value && buttons[0]) {
    event.preventDefault()
    buttons[0].click()
  } else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault()
    const index = buttons.indexOf(document.activeElement as HTMLButtonElement)
    const next = event.key === 'ArrowDown' ? (index + 1) % buttons.length : (index - 1 + buttons.length) % buttons.length
    if (buttons.length) buttons[next]?.focus()
  } else if (event.key === 'Tab') {
    const focusable = [searchInput.value, ...buttons].filter(Boolean) as HTMLElement[]
    const index = focusable.indexOf(document.activeElement as HTMLElement)
    if (event.shiftKey && index === 0) { event.preventDefault(); focusable.at(-1)?.focus() }
    if (!event.shiftKey && index === focusable.length - 1) { event.preventDefault(); focusable[0]?.focus() }
  }
}

const checkService = async () => {
  try {
    await agentApi.status()
    serviceStatus.value = 'online'
  } catch {
    serviceStatus.value = 'offline'
  }
}

onMounted(() => {
  checkService()
  statusTimer = window.setInterval(checkService, 30_000)
  window.addEventListener('keydown', onGlobalKeydown)
})

onBeforeUnmount(() => {
  window.clearInterval(statusTimer)
  window.removeEventListener('keydown', onGlobalKeydown)
})
</script>

<template>
  <header class="app-topbar">
    <div class="title-block">
      <span>{{ eyebrow }}</span>
      <h1>{{ title }}</h1>
    </div>

    <button class="command-search" type="button" aria-label="打开全局搜索" @click="openPalette">
      <Search :size="16" />
      <span>搜索对话、文档或工具</span>
      <kbd>Ctrl / <Command :size="11" /> K</kbd>
    </button>

    <div class="top-actions">
      <button class="icon-button mobile-search" type="button" aria-label="打开全局搜索" @click="openPalette"><Search :size="18" /></button>
      <span class="service-status" :class="serviceStatus"><i></i>{{ serviceStatus === 'online' ? '服务正常' : serviceStatus === 'offline' ? '服务离线' : '正在检查' }}</span>
      <button class="icon-button" type="button" aria-label="通知">
        <Bell :size="18" />
        <span class="notification-dot"></span>
      </button>
      <button class="icon-button" type="button" aria-label="切换主题" @click="workspace.toggleTheme">
        <Sun v-if="workspace.theme === 'dark'" :size="18" />
        <Moon v-else :size="18" />
      </button>
      <button
        v-if="supportsContext"
        class="icon-button context-button"
        :class="{ active: workspace.contextOpen }"
        type="button"
        aria-label="切换上下文面板"
        @click="workspace.toggleContext"
      >
        <PanelRight :size="18" />
      </button>
    </div>
  </header>
  <div v-if="paletteOpen" class="search-overlay" @click.self="closePalette">
    <section ref="searchDialog" class="search-dialog" role="dialog" aria-modal="true" aria-label="全局搜索" @keydown="onPaletteKeydown">
      <label class="search-field"><Search :size="18" /><input ref="searchInput" v-model="search" aria-label="搜索页面、对话和文档" placeholder="搜索页面、对话和文档…" /><kbd>Esc</kbd></label>
      <div class="search-results"><button v-for="item in results" :key="`${item.kind}-${item.id}`" class="search-result" type="button" @click="selectResult(item)"><span>{{ item.label }}</span><small>{{ item.description }}</small></button><p v-if="!results.length">没有找到匹配项</p></div>
    </section>
  </div>
</template>

<style scoped>
.app-topbar {
  display: grid;
  grid-template-columns: minmax(140px, 1fr) minmax(280px, 480px) minmax(200px, 1fr);
  align-items: center;
  min-width: 0;
  height: var(--topbar-height);
  gap: 20px;
  padding: 0 22px 0 28px;
  border-bottom: 1px solid var(--border);
  background: color-mix(in srgb, var(--bg-panel) 90%, transparent);
  backdrop-filter: blur(18px);
}

.title-block {
  min-width: 0;
}

.title-block span {
  display: block;
  overflow: hidden;
  color: var(--text-faint);
  font-size: 8px;
  font-weight: 700;
  letter-spacing: 0.11em;
  text-overflow: ellipsis;
  text-transform: uppercase;
  white-space: nowrap;
}

.title-block h1 {
  margin: 3px 0 0;
  overflow: hidden;
  color: var(--text-strong);
  font-size: 15px;
  font-weight: 650;
  letter-spacing: -0.015em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.command-search {
  display: flex;
  align-items: center;
  width: 100%;
  height: 38px;
  gap: 9px;
  padding: 0 10px 0 13px;
  border: 1px solid var(--border);
  border-radius: 11px;
  background: var(--bg-subtle);
  color: var(--text-faint);
  font-size: 11px;
  text-align: left;
  cursor: pointer;
  transition: 150ms ease;
}

.command-search:hover {
  border-color: var(--border-strong);
  background: var(--bg-elevated);
  box-shadow: var(--shadow-sm);
}

.command-search span {
  flex: 1;
}

.command-search kbd {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 3px 6px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--bg-elevated);
  color: var(--text-faint);
  font-family: inherit;
  font-size: 9px;
  box-shadow: 0 1px 0 var(--border);
}

.top-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 4px;
}

.service-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-right: 6px;
  padding: 6px 9px;
  border-radius: 999px;
  background: var(--success-soft);
  color: var(--success);
  font-size: 9px;
  font-weight: 650;
}

.service-status i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--success) 12%, transparent);
}

.service-status.offline {
  background: color-mix(in srgb, var(--danger) 10%, var(--bg-elevated));
  color: var(--danger);
}

.service-status.checking {
  background: var(--bg-hover);
  color: var(--text-faint);
}

.icon-button {
  position: relative;
}

.icon-button.active {
  border-color: color-mix(in srgb, var(--brand) 20%, var(--border));
  background: var(--brand-soft);
  color: var(--brand);
}

.notification-dot {
  position: absolute;
  top: 7px;
  right: 7px;
  width: 5px;
  height: 5px;
  border: 1.5px solid var(--bg-panel);
  border-radius: 50%;
  background: var(--danger);
}

.mobile-search{display:none}.search-overlay{position:fixed;z-index:80;inset:0;display:flex;align-items:flex-start;justify-content:center;padding:13vh 16px 20px;background:rgba(15,17,31,.45);backdrop-filter:blur(5px)}.search-dialog{width:min(560px,100%);overflow:hidden;border:1px solid var(--border);border-radius:16px;background:var(--bg-elevated);box-shadow:0 24px 70px rgba(15,17,31,.22)}.search-field{display:flex;align-items:center;gap:10px;padding:16px;border-bottom:1px solid var(--border);color:var(--text-faint)}.search-field:focus-within{box-shadow:inset 0 -2px var(--brand)}.search-field input{min-width:0;flex:1;border:0;outline:0;background:transparent;color:var(--text-strong);font-size:14px}.search-field kbd{padding:4px 6px;border:1px solid var(--border);border-radius:6px;font-size:9px}.search-results{max-height:min(55vh,440px);overflow-y:auto;padding:7px}.search-result{display:flex;align-items:center;justify-content:space-between;width:100%;gap:12px;padding:11px 12px;border:0;border-radius:9px;background:transparent;text-align:left;cursor:pointer}.search-result:hover,.search-result:focus-visible{background:var(--brand-softer);outline:none}.search-result span{color:var(--text-strong);font-size:12px}.search-result small,.search-results p{color:var(--text-faint);font-size:10px}.search-results p{padding:12px}

@media (max-width: 960px) {
  .app-topbar {
    grid-template-columns: minmax(130px, 1fr) minmax(180px, 320px) auto;
  }

  .service-status {
    display: none;
  }
}

@media (max-width: 700px) {
  .app-topbar {
    grid-template-columns: 1fr auto;
    padding: 0 14px 0 18px;
  }

  .command-search,
  .notification-dot + span {
    display: none;
  }

  .mobile-search{display:inline-flex}
}
</style>
