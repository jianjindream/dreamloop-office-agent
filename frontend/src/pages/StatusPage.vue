<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import {
  Activity, AlertCircle, Bot, BrainCircuit, CheckCircle2, CircleDot, Clock3,
  Database, HardDrive, Layers3, LoaderCircle, RefreshCw, Server, Wrench,
} from 'lucide-vue-next'
import { agentApi } from '@/api/agent'
import { ApiError } from '@/api/http'
import type { SnapshotSummary, SystemStatus } from '@/types/api'

const status = ref<SystemStatus | null>(null)
const snapshots = ref<SnapshotSummary[]>([])
const loading = ref(true)
const refreshing = ref(false)
const loadError = ref('')
const lastUpdated = ref<Date | null>(null)
let refreshTimer: number | undefined

const infraLabels: Record<string, { name: string; role: string }> = {
  milvus: { name: 'Milvus', role: '向量检索' },
  pg: { name: 'PostgreSQL', role: '持久化存储' },
  elasticsearch: { name: 'Elasticsearch', role: '全文检索' },
  kafka: { name: 'Kafka', role: '事件总线' },
  neo4j: { name: 'Neo4j', role: '知识图谱' },
}

const infrastructure = computed(() => Object.entries(status.value?.infrastructure ?? {}).map(([key, value]) => ({
  key, value: String(value ?? 'unknown'), name: infraLabels[key]?.name || key, role: infraLabels[key]?.role || '基础设施',
})))
const disconnectedCount = computed(() => infrastructure.value.filter((item) => item.value.toLowerCase() === 'disconnected').length)
const overall = computed(() => {
  if (!status.value) return { label: '无法连接', detail: '尚未取得服务状态', tone: 'error' }
  if (disconnectedCount.value) return { label: '部分能力降级', detail: `${disconnectedCount.value} 个基础设施连接不可用`, tone: 'warning' }
  return { label: '系统运行正常', detail: 'Agent API 与已配置的基础设施均已响应', tone: 'success' }
})
const ragChunkCount = computed(() => Array.isArray(status.value?.rag_chunks) ? status.value.rag_chunks.length : Number(status.value?.rag_chunks || 0))

const errorMessage = (error: unknown) => error instanceof ApiError || error instanceof Error ? error.message : '状态请求失败'
const loadStatus = async (manual = false) => {
  if (manual) refreshing.value = true
  else if (!status.value) loading.value = true
  loadError.value = ''
  try {
    const [statusResult, snapshotResult] = await Promise.allSettled([agentApi.status(), agentApi.snapshots()])
    if (statusResult.status === 'rejected') throw statusResult.reason
    status.value = statusResult.value
    snapshots.value = snapshotResult.status === 'fulfilled' ? snapshotResult.value : []
    lastUpdated.value = new Date()
  } catch (error) { loadError.value = errorMessage(error) }
  finally { loading.value = false; refreshing.value = false }
}
const isConnected = (value: string) => value.toLowerCase() === 'connected'
const isManaged = (value: string) => value.toLowerCase().includes('managed')
const statusText = (value: string) => isConnected(value) ? '已连接' : isManaged(value) ? '托管模式' : value.toLowerCase() === 'disconnected' ? '未连接' : value
const formatTime = (value: unknown) => {
  if (value === undefined || value === null) return '—'
  const date = new Date(String(value))
  if (Number.isNaN(date.getTime())) return String(value)
  return new Intl.DateTimeFormat('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(date)
}

onMounted(() => {
  void loadStatus()
  refreshTimer = window.setInterval(() => void loadStatus(), 30_000)
})
onUnmounted(() => { if (refreshTimer) window.clearInterval(refreshTimer) })
</script>

<template>
  <div class="page-shell status-page">
    <header class="page-heading">
      <div><h1>系统状态</h1><p>查看 Agent、模型、知识库与外部基础设施的实时状态。</p></div>
      <button class="secondary-button" type="button" :disabled="refreshing" @click="loadStatus(true)"><RefreshCw :size="16" :class="{ spin: refreshing }" /> 刷新状态</button>
    </header>

    <section v-if="loading" class="surface-card page-state"><LoaderCircle :size="22" class="spin" />正在连接 DreamLoop 服务…</section>
    <section v-else-if="loadError && !status" class="surface-card page-state error"><AlertCircle :size="24" /><strong>Agent 服务暂时不可达</strong><span>{{ loadError }}</span><button class="primary-button" type="button" @click="loadStatus()">重新连接</button></section>
    <template v-else-if="status">
      <section class="health-hero" :class="overall.tone">
        <span class="health-icon"><CheckCircle2 v-if="overall.tone === 'success'" :size="25" /><AlertCircle v-else :size="25" /></span>
        <div><small>REAL-TIME HEALTH</small><h2>{{ overall.label }}</h2><p>{{ overall.detail }}</p></div>
        <time><Clock3 :size="13" />{{ lastUpdated ? `${lastUpdated.toLocaleTimeString('zh-CN')} 更新` : '等待更新' }}</time>
      </section>

      <section class="metrics-grid">
        <article class="surface-card metric-card"><span class="metric-icon violet"><Bot :size="20" /></span><div><small>语言模型</small><strong>{{ status.llm_model || '未配置' }}</strong><em>{{ status.is_mock ? '模拟模式' : '真实调用' }}</em></div></article>
        <article class="surface-card metric-card"><span class="metric-icon blue"><BrainCircuit :size="20" /></span><div><small>嵌入模型</small><strong>{{ status.embedding_model || '未配置' }}</strong><em>{{ status.rag_mode || 'unknown' }}</em></div></article>
        <article class="surface-card metric-card"><span class="metric-icon green"><Layers3 :size="20" /></span><div><small>RAG 索引</small><strong>{{ ragChunkCount }} 个片段</strong><em>{{ status.rag_loaded ? '已加载' : '尚未加载' }}</em></div></article>
        <article class="surface-card metric-card"><span class="metric-icon amber"><Wrench :size="20" /></span><div><small>工具能力</small><strong>{{ status.tools_count }} 个工具</strong><em>运行时已注册</em></div></article>
      </section>

      <div class="status-layout">
        <section class="surface-card infrastructure-panel">
          <div class="section-heading"><div><h2>基础设施</h2><p>状态由后端连接器实时返回。</p></div><span>{{ infrastructure.length - disconnectedCount }} / {{ infrastructure.length }} 可用</span></div>
          <div v-if="!infrastructure.length" class="empty-state"><Server :size="22" />服务没有返回基础设施信息。</div>
          <div v-for="item in infrastructure" :key="item.key" class="infra-row">
            <span class="infra-icon"><Database v-if="item.key === 'pg'" :size="18" /><HardDrive v-else :size="18" /></span>
            <div><strong>{{ item.name }}</strong><small>{{ item.role }}</small></div>
            <span class="infra-status" :class="{ connected: isConnected(item.value), managed: isManaged(item.value), disconnected: !isConnected(item.value) && !isManaged(item.value) }"><i></i>{{ statusText(item.value) }}</span>
          </div>
        </section>

        <aside class="side-stack">
          <section class="surface-card memory-panel"><div class="section-heading"><div><h2>记忆空间</h2><p>当前默认作用域</p></div></div><div class="memory-row"><span><Activity :size="17" /></span><div><small>短期记忆</small><strong>{{ status.short_term_count ?? 0 }} 条</strong></div></div><div class="memory-row"><span><BrainCircuit :size="17" /></span><div><small>长期记忆</small><strong>{{ status.long_term_count ?? 0 }} 条</strong></div></div><div class="memory-row"><span><CircleDot :size="17" /></span><div><small>偏好设置</small><strong>{{ Object.keys(status.preferences || {}).length }} 项</strong></div></div></section>
          <section class="surface-card snapshot-panel"><div class="section-heading"><div><h2>执行快照</h2><p>最近的 Agent 状态记录</p></div><span>{{ snapshots.length }}</span></div><div v-if="!snapshots.length" class="empty-state compact">还没有任务快照。</div><div v-for="snapshot in snapshots.slice(-4).reverse()" :key="String(snapshot.index ?? snapshot.id ?? snapshot.timestamp)" class="snapshot-row"><span><Activity :size="14" /></span><div><strong>快照 #{{ snapshot.index ?? snapshot.id ?? '—' }}</strong><small>{{ formatTime(snapshot.timestamp ?? snapshot.createdAt ?? snapshot.created_at) }}</small></div><b>{{ snapshot.steps ?? 0 }} 步</b></div></section>
        </aside>
      </div>

      <p class="auto-refresh"><RefreshCw :size="12" /> 页面每 30 秒自动刷新；“已连接”表示后端连接器状态，不等同于完整读写自检。</p>
    </template>
  </div>
</template>

<style scoped>
.page-heading .secondary-button:disabled{opacity:.55;cursor:default}.page-state{display:flex;min-height:320px;align-items:center;justify-content:center;flex-direction:column;gap:9px;color:var(--text-faint);font-size:10px}.page-state strong{color:var(--text);font-size:13px}.page-state.error>svg{color:var(--danger)}.page-state .primary-button{min-height:35px;margin-top:5px;font-size:10px}
.health-hero{display:flex;align-items:center;gap:15px;padding:20px 22px;border:1px solid color-mix(in srgb,var(--success) 22%,var(--border));border-radius:17px;background:linear-gradient(105deg,var(--success-soft),var(--bg-elevated));box-shadow:var(--shadow-sm)}.health-hero.warning{border-color:color-mix(in srgb,var(--warning) 30%,var(--border));background:linear-gradient(105deg,var(--warning-soft),var(--bg-elevated))}.health-hero.error{border-color:color-mix(in srgb,var(--danger) 25%,var(--border))}.health-icon{display:inline-flex;align-items:center;justify-content:center;width:48px;height:48px;flex:0 0 auto;border-radius:15px;background:var(--bg-elevated);color:var(--success);box-shadow:var(--shadow-sm)}.warning .health-icon{color:var(--warning)}.error .health-icon{color:var(--danger)}.health-hero>div{flex:1}.health-hero small{color:var(--success);font-size:7px;font-weight:750;letter-spacing:.12em}.warning small{color:var(--warning)}.health-hero h2{margin:4px 0 3px;color:var(--text-strong);font-size:17px;font-weight:680}.health-hero p{margin:0;color:var(--text-muted);font-size:9px}.health-hero time{display:flex;align-items:center;gap:6px;color:var(--text-faint);font-size:8px}
.metrics-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:11px;margin-top:16px}.metric-card{display:flex;min-width:0;align-items:center;gap:11px;padding:15px}.metric-icon{display:inline-flex;align-items:center;justify-content:center;width:39px;height:39px;flex:0 0 auto;border-radius:12px}.metric-icon.violet{background:var(--brand-soft);color:var(--brand)}.metric-icon.blue{background:#eaf3ff;color:#3978bd}.metric-icon.green{background:var(--success-soft);color:var(--success)}.metric-icon.amber{background:var(--warning-soft);color:var(--warning)}:root[data-theme='dark'] .metric-icon.blue{background:#1d3146;color:#83b8ef}.metric-card>div{min-width:0}.metric-card small,.metric-card strong,.metric-card em{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.metric-card small{color:var(--text-faint);font-size:8px}.metric-card strong{margin-top:4px;color:var(--text-strong);font-size:11px;font-weight:650}.metric-card em{margin-top:3px;color:var(--text-muted);font-size:8px;font-style:normal}
.status-layout{display:grid;grid-template-columns:minmax(0,1.45fr) minmax(270px,.75fr);align-items:start;gap:14px;margin-top:18px}.infrastructure-panel,.memory-panel,.snapshot-panel{overflow:hidden}.section-heading{display:flex;align-items:center;justify-content:space-between;padding:16px 18px;border-bottom:1px solid var(--border)}.section-heading h2{margin:0;color:var(--text-strong);font-size:12px;font-weight:650}.section-heading p{margin:4px 0 0;color:var(--text-faint);font-size:8px}.section-heading>span{padding:4px 7px;border-radius:6px;background:var(--bg-hover);color:var(--text-muted);font-size:8px}.infra-row{display:flex;min-height:64px;align-items:center;gap:11px;padding:0 18px;border-bottom:1px solid var(--border)}.infra-row:last-child{border:0}.infra-icon{display:inline-flex;align-items:center;justify-content:center;width:34px;height:34px;border-radius:10px;background:var(--bg-subtle);color:var(--text-muted)}.infra-row>div{display:flex;flex:1;flex-direction:column}.infra-row strong{color:var(--text);font-size:10px;font-weight:620}.infra-row small{margin-top:3px;color:var(--text-faint);font-size:8px}.infra-status{display:inline-flex;align-items:center;gap:6px;color:var(--text-muted);font-size:8px}.infra-status i{width:6px;height:6px;border-radius:50%;background:currentColor}.infra-status.connected{color:var(--success)}.infra-status.managed{color:var(--brand)}.infra-status.disconnected{color:var(--danger)}.side-stack{display:flex;flex-direction:column;gap:14px}.memory-row{display:flex;align-items:center;gap:10px;padding:12px 16px;border-bottom:1px solid var(--border)}.memory-row:last-child{border:0}.memory-row>span{display:inline-flex;align-items:center;justify-content:center;width:32px;height:32px;border-radius:9px;background:var(--brand-softer);color:var(--brand)}.memory-row>div{display:flex;flex:1;align-items:center;justify-content:space-between}.memory-row small{color:var(--text-muted);font-size:9px}.memory-row strong{color:var(--text-strong);font-size:10px}.snapshot-row{display:grid;grid-template-columns:27px 1fr 38px;align-items:center;gap:8px;min-height:51px;padding:0 15px;border-bottom:1px solid var(--border)}.snapshot-row:last-child{border:0}.snapshot-row>span{display:inline-flex;align-items:center;justify-content:center;width:26px;height:26px;border-radius:8px;background:var(--bg-subtle);color:var(--brand)}.snapshot-row>div{display:flex;flex-direction:column}.snapshot-row strong{color:var(--text);font-size:8px}.snapshot-row small{margin-top:3px;color:var(--text-faint);font-size:7px}.snapshot-row b{color:var(--text-muted);font-size:8px;font-weight:550;text-align:right}.empty-state{display:flex;min-height:180px;align-items:center;justify-content:center;flex-direction:column;gap:8px;color:var(--text-faint);font-size:9px}.empty-state.compact{min-height:90px}.auto-refresh{display:flex;align-items:center;justify-content:center;gap:6px;margin:17px 0 0;color:var(--text-faint);font-size:8px}.spin{animation:spin 1.4s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}
@media(max-width:980px){.metrics-grid{grid-template-columns:repeat(2,1fr)}}@media(max-width:760px){.status-layout{grid-template-columns:1fr}}@media(max-width:560px){.health-hero{align-items:flex-start;flex-wrap:wrap}.health-hero time{width:100%;margin-left:63px}.metrics-grid{grid-template-columns:1fr}}
</style>
