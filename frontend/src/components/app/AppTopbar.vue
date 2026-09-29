<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
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
import { agentApi } from '@/api/agent'

const route = useRoute()
const workspace = useWorkspaceStore()
const canvas = useDocumentCanvasStore()
const title = computed(() => String(route.meta.title ?? 'DreamLoop'))
const eyebrow = computed(() => String(route.meta.eyebrow ?? 'AI Workspace'))
const supportsContext = computed(() => Boolean(route.meta.showContext) && !canvas.open)
const serviceStatus = ref<'checking' | 'online' | 'offline'>('checking')
let statusTimer: number | undefined

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
})

onBeforeUnmount(() => window.clearInterval(statusTimer))
</script>

<template>
  <header class="app-topbar">
    <div class="title-block">
      <span>{{ eyebrow }}</span>
      <h1>{{ title }}</h1>
    </div>

    <button class="command-search" type="button" aria-label="打开全局搜索">
      <Search :size="16" />
      <span>搜索对话、文档或工具</span>
      <kbd><Command :size="11" /> K</kbd>
    </button>

    <div class="top-actions">
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
}
</style>
