<script setup lang="ts">
import { Bell, Bot, ChevronRight, Database, KeyRound, Moon, Network, Palette, ShieldCheck, UserRound } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import { useWorkspaceStore } from '@/stores/workspace'

const workspace = useWorkspaceStore()
const router = useRouter()
const sections = [
  { icon: UserRound, title: '个人资料', desc: '头像、显示名称和个人偏好' },
  { icon: Bot, title: 'AI 模型', desc: '模型、生成参数和回答风格', value: 'qwen-plus' },
  { icon: Database, title: '知识库设置', desc: '检索策略、切片与重排参数' },
  { icon: Network, title: '基础设施', desc: 'Milvus、Elasticsearch、Neo4j 和 Kafka' },
  { icon: KeyRound, title: 'API 与密钥', desc: '模型与外部工具的访问凭证' },
  { icon: ShieldCheck, title: '隐私与安全', desc: '数据使用、安全策略和沙箱设置' },
  { icon: Bell, title: '通知', desc: '任务完成和系统异常提醒' },
]
</script>

<template>
  <div class="page-shell settings-page">
    <header class="page-heading"><div><h1>设置</h1><p>管理个人体验、AI 能力和工作区连接。</p></div></header>
    <div class="settings-layout">
      <nav class="settings-nav surface-card"><button class="active" type="button">常规设置</button><button type="button">AI 与知识库</button><button type="button">连接与工具</button><button type="button">安全与隐私</button><button type="button">高级设置</button></nav>
      <div class="settings-content">
        <section class="profile-card surface-card">
          <span class="large-avatar">陈</span><div><h2>陈建金</h2><p>jianjin@example.com</p><small>个人工作空间 · 所有者</small></div><button class="secondary-button" type="button">编辑资料</button>
        </section>
        <section class="surface-card settings-group">
          <div class="group-title"><div><h2>偏好设置</h2><p>调整 DreamLoop 的外观和基本行为。</p></div></div>
          <button class="setting-row" type="button" @click="workspace.toggleTheme">
            <span class="setting-icon"><Palette :size="18" /></span><span class="setting-copy"><strong>外观主题</strong><small>选择适合你的界面外观</small></span><span class="setting-value"><Moon :size="14" />{{ workspace.theme === 'dark' ? '深色' : '浅色' }}</span><ChevronRight :size="15" />
          </button>
          <button v-for="item in sections" :key="item.title" class="setting-row" type="button">
            <span class="setting-icon"><component :is="item.icon" :size="18" /></span><span class="setting-copy"><strong>{{ item.title }}</strong><small>{{ item.desc }}</small></span><span v-if="item.value" class="setting-value">{{ item.value }}</span><ChevronRight :size="15" />
          </button>
        </section>
        <section class="system-card surface-card"><div><span><i></i>系统状态与连接</span><p>查看模型、知识库与基础设施的实时运行信息。</p></div><button class="secondary-button" type="button" @click="router.push('/status')">查看系统状态</button></section>
      </div>
    </div>
  </div>
</template>

<style scoped>
.settings-layout { display: grid; grid-template-columns: 190px minmax(0, 1fr); align-items: start; gap: 18px; }
.settings-nav { display: flex; padding: 8px; flex-direction: column; gap: 3px; }
.settings-nav button { min-height: 37px; padding: 0 11px; border: 0; border-radius: 9px; background: transparent; color: var(--text-muted); font-size: 10px; text-align: left; cursor: pointer; }
.settings-nav button:hover { background: var(--bg-hover); color: var(--text-strong); }.settings-nav button.active { background: var(--brand-soft); color: var(--brand); font-weight: 650; }
.settings-content { display: flex; min-width: 0; flex-direction: column; gap: 13px; }
.profile-card { display: flex; align-items: center; gap: 14px; padding: 18px; }
.large-avatar { display: inline-flex; align-items: center; justify-content: center; width: 48px; height: 48px; flex: 0 0 auto; border-radius: 15px; background: linear-gradient(145deg,#dedafc,#c5d9fa); color: #4c459f; font-size: 16px; font-weight: 720; }
.profile-card > div { flex: 1; }.profile-card h2 { margin: 0; color: var(--text-strong); font-size: 13px; font-weight: 650; }.profile-card p { margin: 3px 0 0; color: var(--text-muted); font-size: 9px; }.profile-card small { display: block; margin-top: 5px; color: var(--text-faint); font-size: 8px; }
.settings-group { overflow: hidden; }.group-title { padding: 17px 18px; border-bottom: 1px solid var(--border); }.group-title h2 { margin: 0; color: var(--text-strong); font-size: 13px; font-weight: 650; }.group-title p { margin: 4px 0 0; color: var(--text-faint); font-size: 9px; }
.setting-row { display: flex; align-items: center; width: 100%; min-height: 62px; gap: 11px; padding: 0 17px; border: 0; border-bottom: 1px solid var(--border); background: transparent; text-align: left; cursor: pointer; }.setting-row:last-child { border: 0; }.setting-row:hover { background: var(--bg-subtle); }
.setting-icon { display: inline-flex; align-items: center; justify-content: center; width: 34px; height: 34px; flex: 0 0 auto; border-radius: 10px; background: var(--bg-hover); color: var(--text-muted); }.setting-row:hover .setting-icon { background: var(--brand-soft); color: var(--brand); }
.setting-copy { display: flex; min-width: 0; flex: 1; flex-direction: column; }.setting-copy strong { color: var(--text); font-size: 10px; font-weight: 610; }.setting-copy small { margin-top: 4px; color: var(--text-faint); font-size: 8px; }
.setting-value { display: inline-flex; align-items: center; gap: 5px; color: var(--text-muted); font-size: 9px; }.setting-row > svg { color: var(--text-faint); }
.system-card { display: flex; align-items: center; justify-content: space-between; gap: 18px; padding: 16px 18px; }.system-card span { display: inline-flex; align-items: center; gap: 7px; color: var(--success); font-size: 10px; font-weight: 640; }.system-card span i { width: 6px; height: 6px; border-radius: 50%; background: currentColor; box-shadow: 0 0 0 4px var(--success-soft); }.system-card p { margin: 5px 0 0; color: var(--text-faint); font-size: 8px; }
@media (max-width: 720px) { .settings-layout { grid-template-columns: 1fr; }.settings-nav { overflow-x: auto; flex-direction: row; }.settings-nav button { flex: 0 0 auto; }.profile-card { flex-wrap: wrap; }.profile-card button { margin-left: 62px; }.system-card { align-items: flex-start; flex-direction: column; } }
</style>
