<script setup lang="ts">
import {
  Activity,
  Blocks,
  ChevronDown,
  Files,
  LibraryBig,
  MessageSquareText,
  MoreHorizontal,
  PanelLeftClose,
  PanelLeftOpen,
  Plus,
  Settings,
  Sparkles,
} from 'lucide-vue-next'
import { RouterLink } from 'vue-router'
import { useRouter } from 'vue-router'
import { computed } from 'vue'
import { useChatStore } from '@/stores/chat'
import { useWorkspaceStore } from '@/stores/workspace'

const workspace = useWorkspaceStore()
const chatStore = useChatStore()
const router = useRouter()

const navigation = [
  { label: 'AI 对话', to: '/chat', icon: MessageSquareText },
  { label: '知识库', to: '/knowledge', icon: LibraryBig },
  { label: '文档', to: '/documents', icon: Files },
  { label: '工具中心', to: '/tools', icon: Blocks },
  { label: '系统状态', to: '/status', icon: Activity },
]

const recentChats = computed(() => chatStore.sortedSessions.slice(0, 5))

const newChat = () => {
  chatStore.createSession()
  router.push('/chat')
}

const openChat = (sessionId: string) => {
  chatStore.selectSession(sessionId)
  router.push('/chat')
}

const formatTime = (timestamp: number) => {
  const now = new Date()
  const value = new Date(timestamp)
  if (value.toDateString() === now.toDateString()) return value.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  return value.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' })
}
</script>

<template>
  <aside class="app-sidebar" :class="{ collapsed: workspace.sidebarCollapsed }">
    <div class="brand-row">
      <RouterLink to="/chat" class="brand" aria-label="DreamLoop 首页">
        <span class="brand-mark"><Sparkles :size="18" stroke-width="2.2" /></span>
        <span class="brand-copy">
          <strong>DreamLoop</strong>
          <small>AI Office</small>
        </span>
      </RouterLink>
      <button
        class="collapse-button"
        type="button"
        :aria-label="workspace.sidebarCollapsed ? '展开侧边栏' : '收起侧边栏'"
        @click="workspace.toggleSidebar"
      >
        <PanelLeftOpen v-if="workspace.sidebarCollapsed" :size="17" />
        <PanelLeftClose v-else :size="17" />
      </button>
    </div>

    <button class="workspace-switcher" type="button">
      <span class="workspace-avatar">DL</span>
      <span class="workspace-copy">
        <strong>我的工作空间</strong>
        <small>个人版</small>
      </span>
      <ChevronDown class="workspace-chevron" :size="15" />
    </button>

    <button class="new-chat" type="button" @click="newChat">
      <Plus :size="17" />
      <span>新建对话</span>
      <kbd>⌘ K</kbd>
    </button>

    <nav class="primary-nav" aria-label="主导航">
      <RouterLink
        v-for="item in navigation"
        :key="item.to"
        :to="item.to"
        class="nav-item"
        :title="workspace.sidebarCollapsed ? item.label : undefined"
      >
        <component :is="item.icon" :size="18" stroke-width="1.9" />
        <span>{{ item.label }}</span>
      </RouterLink>
    </nav>

    <div class="recent-section">
      <div class="section-label">
        <span>最近对话</span>
        <button type="button" aria-label="更多对话"><MoreHorizontal :size="15" /></button>
      </div>
      <button
        v-for="chat in recentChats"
        :key="chat.title"
        class="recent-item"
        :class="{ active: chat.id === chatStore.currentSessionId }"
        type="button"
        @click="openChat(chat.id)"
      >
        <span class="recent-dot"></span>
        <span class="recent-copy">
          <strong>{{ chat.title }}</strong>
          <small>{{ formatTime(chat.updatedAt) }}</small>
        </span>
      </button>
    </div>

    <div class="sidebar-footer">
      <RouterLink to="/settings" class="nav-item footer-settings">
        <Settings :size="18" stroke-width="1.9" />
        <span>设置</span>
      </RouterLink>
      <button class="profile" type="button">
        <span class="profile-avatar">陈</span>
        <span class="profile-copy">
          <strong>陈建金</strong>
          <small>jianjin@example.com</small>
        </span>
        <MoreHorizontal class="profile-more" :size="16" />
      </button>
    </div>
  </aside>
</template>

<style scoped>
.app-sidebar {
  position: relative;
  z-index: 10;
  display: flex;
  flex-direction: column;
  min-width: 0;
  height: 100%;
  padding: 16px 12px 12px;
  overflow: hidden;
  border-right: 1px solid var(--border);
  background: var(--bg-panel);
  backdrop-filter: blur(18px);
}

.brand-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 42px;
  padding: 0 4px 0 6px;
}

.brand {
  display: flex;
  align-items: center;
  min-width: 0;
  gap: 10px;
  color: inherit;
  text-decoration: none;
}

.brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  flex: 0 0 auto;
  border-radius: 11px;
  background: linear-gradient(145deg, #7168ee, #554bd1);
  color: white;
  box-shadow: 0 8px 18px rgba(97, 87, 230, 0.24);
}

.brand-copy,
.workspace-copy,
.profile-copy,
.recent-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
}

.brand-copy strong {
  color: var(--text-strong);
  font-size: 14px;
  font-weight: 720;
  letter-spacing: -0.01em;
}

.brand-copy small {
  margin-top: 1px;
  color: var(--text-faint);
  font-size: 9px;
  font-weight: 650;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.collapse-button,
.section-label button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 0;
  background: transparent;
  color: var(--text-faint);
  cursor: pointer;
}

.collapse-button {
  width: 30px;
  height: 30px;
  border-radius: 8px;
}

.collapse-button:hover {
  background: var(--bg-hover);
  color: var(--text-strong);
}

.workspace-switcher,
.profile {
  display: flex;
  align-items: center;
  width: 100%;
  border: 0;
  background: transparent;
  cursor: pointer;
}

.workspace-switcher {
  gap: 10px;
  margin-top: 18px;
  padding: 9px 10px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--bg-elevated);
  box-shadow: var(--shadow-sm);
  text-align: left;
}

.workspace-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  flex: 0 0 auto;
  border-radius: 9px;
  background: var(--brand-soft);
  color: var(--brand);
  font-size: 10px;
  font-weight: 750;
}

.workspace-copy {
  flex: 1;
}

.workspace-copy strong,
.profile-copy strong {
  overflow: hidden;
  color: var(--text-strong);
  font-size: 12px;
  font-weight: 620;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workspace-copy small,
.profile-copy small {
  margin-top: 2px;
  overflow: hidden;
  color: var(--text-faint);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workspace-chevron {
  color: var(--text-faint);
}

.new-chat {
  display: flex;
  align-items: center;
  width: 100%;
  min-height: 42px;
  gap: 9px;
  margin-top: 14px;
  padding: 0 11px;
  border: 1px solid color-mix(in srgb, var(--brand) 18%, var(--border));
  border-radius: 11px;
  background: var(--brand-softer);
  color: var(--brand);
  font-size: 12px;
  font-weight: 640;
  cursor: pointer;
  transition: 160ms ease;
}

.new-chat:hover {
  border-color: color-mix(in srgb, var(--brand) 36%, var(--border));
  background: var(--brand-soft);
}

.new-chat kbd {
  margin-left: auto;
  color: var(--text-faint);
  font-family: inherit;
  font-size: 9px;
  font-weight: 500;
}

.primary-nav {
  display: flex;
  flex-direction: column;
  gap: 3px;
  margin-top: 16px;
}

.nav-item {
  display: flex;
  align-items: center;
  min-height: 40px;
  gap: 11px;
  padding: 0 11px;
  border-radius: 10px;
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 540;
  text-decoration: none;
  transition: 140ms ease;
}

.nav-item:hover {
  background: var(--bg-hover);
  color: var(--text-strong);
}

.nav-item.router-link-active {
  background: var(--brand-soft);
  color: var(--brand);
  font-weight: 650;
}

.nav-item small {
  min-width: 20px;
  margin-left: auto;
  padding: 2px 5px;
  border-radius: 999px;
  background: var(--bg-subtle);
  color: var(--text-faint);
  font-size: 9px;
  text-align: center;
}

.recent-section {
  min-height: 0;
  margin-top: 24px;
  overflow: hidden;
}

.section-label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 9px 8px;
  color: var(--text-faint);
  font-size: 9px;
  font-weight: 680;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.recent-item {
  display: flex;
  align-items: center;
  width: 100%;
  gap: 10px;
  padding: 9px 10px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.recent-item:hover,
.recent-item.active {
  background: var(--bg-hover);
}

.recent-dot {
  width: 5px;
  height: 5px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: var(--border-strong);
}

.recent-item.active .recent-dot {
  background: var(--brand);
  box-shadow: 0 0 0 3px var(--brand-soft);
}

.recent-copy {
  flex: 1;
}

.recent-copy strong {
  overflow: hidden;
  color: var(--text);
  font-size: 11px;
  font-weight: 520;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.recent-copy small {
  margin-top: 2px;
  color: var(--text-faint);
  font-size: 9px;
}

.sidebar-footer {
  margin-top: auto;
  padding-top: 10px;
  border-top: 1px solid var(--border);
}

.footer-settings {
  margin-bottom: 6px;
}

.profile {
  gap: 9px;
  padding: 9px 8px;
  border-radius: 10px;
  text-align: left;
}

.profile:hover {
  background: var(--bg-hover);
}

.profile-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 31px;
  height: 31px;
  flex: 0 0 auto;
  border-radius: 10px;
  background: linear-gradient(145deg, #dedafc, #c5d9fa);
  color: #4c459f;
  font-size: 11px;
  font-weight: 700;
}

.profile-copy {
  flex: 1;
}

.profile-more {
  color: var(--text-faint);
}

.collapsed .brand-copy,
.collapsed .workspace-copy,
.collapsed .workspace-chevron,
.collapsed .new-chat span,
.collapsed .new-chat kbd,
.collapsed .nav-item span,
.collapsed .nav-item small,
.collapsed .recent-section,
.collapsed .profile-copy,
.collapsed .profile-more {
  display: none;
}

.collapsed .brand-row {
  justify-content: center;
  padding: 0;
}

.collapsed .collapse-button {
  position: absolute;
  top: 54px;
}

.collapsed .workspace-switcher,
.collapsed .new-chat,
.collapsed .nav-item,
.collapsed .profile {
  justify-content: center;
  padding-right: 0;
  padding-left: 0;
}

.collapsed .workspace-switcher {
  margin-top: 38px;
}

@media (max-width: 860px) {
  .app-sidebar {
    width: var(--sidebar-collapsed);
  }

  .brand-copy,
  .workspace-copy,
  .workspace-chevron,
  .new-chat span,
  .new-chat kbd,
  .nav-item span,
  .nav-item small,
  .recent-section,
  .profile-copy,
  .profile-more,
  .collapse-button {
    display: none;
  }

  .brand-row,
  .workspace-switcher,
  .new-chat,
  .nav-item,
  .profile {
    justify-content: center;
    padding-right: 0;
    padding-left: 0;
  }
}

@media (max-width: 600px) {
  .app-sidebar {
    position: fixed;
    right: 12px;
    bottom: 12px;
    left: 12px;
    width: auto;
    height: 62px;
    padding: 8px;
    flex-direction: row;
    border: 1px solid var(--border);
    border-radius: 18px;
    box-shadow: var(--shadow-md);
  }

  .brand-row,
  .workspace-switcher,
  .new-chat,
  .recent-section,
  .sidebar-footer {
    display: none;
  }

  .primary-nav {
    width: 100%;
    margin: 0;
    flex-direction: row;
    justify-content: space-around;
  }

  .nav-item {
    width: 44px;
    height: 44px;
    justify-content: center;
    padding: 0;
  }
}
</style>
