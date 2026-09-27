<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  AlertCircle, Braces, CheckCircle2, ChevronRight, CloudSun, Code2, Globe2,
  LoaderCircle, Network, Plus, RefreshCw, Search, ServerCog, Sparkles, Trash2, Wrench, X,
} from 'lucide-vue-next'
import { agentApi } from '@/api/agent'
import { ApiError } from '@/api/http'
import type { McpRegistration, SystemStatus, ToolParameter, ToolSummary } from '@/types/api'

type Filter = 'all' | 'builtin' | 'mcp'
interface McpMeta { endpoint: string; registeredAt: string }

const META_KEY = 'dreamloop-mcp-meta-v1'
const tools = ref<ToolSummary[]>([])
const systemStatus = ref<SystemStatus | null>(null)
const loading = ref(true)
const refreshing = ref(false)
const loadError = ref('')
const search = ref('')
const filter = ref<Filter>('all')
const selected = ref<ToolSummary | null>(null)
const modalOpen = ref(false)
const submitting = ref(false)
const formError = ref('')
const toast = ref('')
const mcpMeta = ref<Record<string, McpMeta>>({})
const form = ref<McpRegistration>({ name: '', description: '', endpoint: '', params: [] })

const serviceOnline = computed(() => Boolean(systemStatus.value))
const mcpCount = computed(() => tools.value.filter((tool) => tool.is_mcp).length)
const filteredTools = computed(() => {
  const query = search.value.trim().toLowerCase()
  return tools.value.filter((tool) => {
    const matchesFilter = filter.value === 'all' || (filter.value === 'mcp' ? tool.is_mcp : !tool.is_mcp)
    const matchesSearch = !query || tool.name.toLowerCase().includes(query) || (tool.description || '').toLowerCase().includes(query)
    return matchesFilter && matchesSearch
  })
})

const errorMessage = (error: unknown) => error instanceof ApiError || error instanceof Error ? error.message : '请求失败，请稍后重试'
const loadMeta = () => {
  try { mcpMeta.value = JSON.parse(localStorage.getItem(META_KEY) || '{}') as Record<string, McpMeta> }
  catch { mcpMeta.value = {} }
}
const saveMeta = () => localStorage.setItem(META_KEY, JSON.stringify(mcpMeta.value))

const loadTools = async (manual = false) => {
  if (manual) refreshing.value = true
  else loading.value = true
  loadError.value = ''
  try {
    const [toolResult, statusResult] = await Promise.allSettled([agentApi.tools(), agentApi.status()])
    if (toolResult.status === 'rejected') throw toolResult.reason
    tools.value = toolResult.value
    systemStatus.value = statusResult.status === 'fulfilled' ? statusResult.value : null
  } catch (error) { loadError.value = errorMessage(error) }
  finally { loading.value = false; refreshing.value = false }
}

const resetForm = () => {
  form.value = { name: '', description: '', endpoint: '', params: [] }
  formError.value = ''
}
const openRegistration = () => { resetForm(); modalOpen.value = true }
const addParameter = () => form.value.params.push({ name: '', type: 'string', description: '', required: false })
const removeParameter = (index: number) => form.value.params.splice(index, 1)

const registerMcp = async () => {
  const name = form.value.name.trim()
  const endpoint = form.value.endpoint.trim()
  if (!name || !endpoint) { formError.value = '请填写工具名称和 HTTP 端点。'; return }
  try {
    const url = new URL(endpoint)
    if (!['http:', 'https:'].includes(url.protocol)) throw new Error('unsupported protocol')
  } catch { formError.value = '请输入完整、有效的 HTTP 或 HTTPS 地址。'; return }
  if (form.value.params.some((param) => !param.name.trim())) { formError.value = '参数名称不能为空。'; return }
  submitting.value = true
  formError.value = ''
  const payload: McpRegistration = {
    name, endpoint, description: form.value.description.trim(),
    params: form.value.params.map((param) => ({ ...param, name: param.name.trim(), description: param.description?.trim() })),
  }
  try {
    await agentApi.registerMcp(payload)
    mcpMeta.value[name] = { endpoint, registeredAt: new Date().toISOString() }
    saveMeta()
    modalOpen.value = false
    toast.value = `MCP 工具“${name}”已注册到当前服务进程。`
    window.setTimeout(() => (toast.value = ''), 3600)
    await loadTools(true)
  } catch (error) { formError.value = errorMessage(error) }
  finally { submitting.value = false }
}

const iconFor = (tool: ToolSummary) => {
  const value = `${tool.name} ${tool.description}`.toLowerCase()
  if (tool.is_mcp) return Network
  if (value.includes('weather') || value.includes('天气')) return CloudSun
  if (value.includes('search') || value.includes('搜索')) return Globe2
  if (value.includes('code') || value.includes('代码')) return Code2
  return Sparkles
}
const colorFor = (tool: ToolSummary) => tool.is_mcp ? 'rose' : ['blue', 'amber', 'violet', 'green'][Math.abs(tool.name.length) % 4]
const endpointFor = (tool: ToolSummary) => mcpMeta.value[tool.name]?.endpoint

onMounted(() => { loadMeta(); void loadTools() })
</script>

<template>
  <div class="page-shell tools-page">
    <header class="page-heading">
      <div><h1>工具中心</h1><p>查看服务实际加载的能力，并通过 HTTP 端点注册 MCP 工具。</p></div>
      <button class="primary-button" type="button" @click="openRegistration"><Plus :size="17" /> 接入 MCP 工具</button>
    </header>

    <section class="tools-overview">
      <div><span class="overview-icon"><Wrench :size="21" /></span><div><small>已加载工具</small><strong>{{ tools.length }} 个</strong></div></div><i></i>
      <div><span class="status-dot" :class="{ offline: !serviceOnline }"></span><div><small>Agent 服务</small><strong>{{ serviceOnline ? '连接正常' : '状态未知' }}</strong></div></div><i></i>
      <div><span class="calls-icon"><Network :size="18" /></span><div><small>MCP 工具</small><strong>{{ mcpCount }} 个</strong></div></div>
      <button class="overview-refresh" type="button" :disabled="refreshing" @click="loadTools(true)"><RefreshCw :size="15" :class="{ spin: refreshing }" />刷新</button>
    </section>

    <div class="tools-filter">
      <div><button type="button" :class="{ active: filter === 'all' }" @click="filter = 'all'">全部</button><button type="button" :class="{ active: filter === 'builtin' }" @click="filter = 'builtin'">内置工具</button><button type="button" :class="{ active: filter === 'mcp' }" @click="filter = 'mcp'">MCP</button></div>
      <label><Search :size="15" /><input v-model="search" placeholder="搜索工具" /></label>
    </div>

    <section v-if="loading" class="surface-card page-state"><LoaderCircle :size="21" class="spin" />正在读取工具清单…</section>
    <section v-else-if="loadError" class="surface-card page-state error"><AlertCircle :size="22" /><strong>工具清单读取失败</strong><span>{{ loadError }}</span><button class="secondary-button" type="button" @click="loadTools()">重新连接</button></section>
    <section v-else-if="!filteredTools.length" class="surface-card page-state"><Wrench :size="23" /><strong>{{ search ? '没有匹配的工具' : '还没有这类工具' }}</strong><span>你可以注册一个 MCP 服务扩展 Agent 的能力。</span></section>
    <section v-else class="tool-grid">
      <button v-for="tool in filteredTools" :key="tool.name" class="surface-card tool-card" type="button" @click="selected = tool">
        <div class="tool-card-top"><span class="tool-icon" :class="colorFor(tool)"><component :is="iconFor(tool)" :size="20" /></span><span class="tool-category">{{ tool.is_mcp ? 'MCP' : '内置工具' }}</span><ChevronRight :size="16" /></div>
        <h2>{{ tool.name }}</h2><p>{{ tool.description || '暂无工具说明' }}</p>
        <div class="tool-card-footer"><span :class="{ offline: !serviceOnline }"><i></i>{{ serviceOnline ? (tool.is_mcp ? '已注册' : '已加载') : '服务未连接' }}</span><small>{{ tool.params?.length || 0 }} 个参数</small></div>
      </button>
      <button class="add-tool-card" type="button" @click="openRegistration"><span><Plus :size="20" /></span><strong>添加新工具</strong><small>通过 HTTP 端点连接 MCP 服务</small></button>
    </section>

    <section class="capability-note"><ServerCog :size="17" /><div><strong>当前是运行时注册</strong><p>MCP 工具会立即加入当前 Agent，但服务重启后需要重新注册。后端暂未提供连接测试、编辑或删除接口。</p></div></section>

    <Transition name="fade"><div v-if="selected" class="drawer-backdrop" @click.self="selected = null"><aside class="tool-drawer">
      <header><div><small>{{ selected.is_mcp ? 'MCP Tool' : 'Built-in Tool' }}</small><h2>{{ selected.name }}</h2></div><button class="icon-button" type="button" aria-label="关闭" @click="selected = null"><X :size="18" /></button></header>
      <div class="drawer-content"><span class="drawer-icon" :class="colorFor(selected)"><component :is="iconFor(selected)" :size="24" /></span><h3>能力说明</h3><p>{{ selected.description || '服务没有返回工具说明。' }}</p>
        <div class="status-line"><CheckCircle2 :size="16" /><span><strong>{{ serviceOnline ? '已在 Agent 中加载' : '无法确认运行状态' }}</strong><small>{{ selected.is_mcp ? '已注册不代表远端端点健康，当前后端未提供连通性探测。' : '状态来自 /api/tools 工具清单。' }}</small></span></div>
        <section v-if="selected.is_mcp && endpointFor(selected)" class="endpoint-block"><small>本机注册端点</small><code>{{ endpointFor(selected) }}</code></section>
        <section class="parameter-section"><div><h3>输入参数</h3><span>{{ selected.params?.length || 0 }} 个</span></div><p v-if="!selected.params?.length" class="empty-copy">此工具没有声明参数。</p><div v-for="param in selected.params" :key="param.name" class="parameter-row"><code>{{ param.name }}</code><span>{{ param.type || 'string' }}</span><b v-if="param.required">必填</b><small>{{ param.description || '—' }}</small></div></section>
      </div>
    </aside></div></Transition>

    <Transition name="fade"><div v-if="modalOpen" class="modal-backdrop" @click.self="modalOpen = false"><section class="mcp-modal" role="dialog" aria-modal="true" aria-labelledby="mcp-title">
      <header><div><span><Network :size="20" /></span><div><small>HTTP CONNECTOR</small><h2 id="mcp-title">接入 MCP 工具</h2></div></div><button class="icon-button" type="button" aria-label="关闭" @click="modalOpen = false"><X :size="18" /></button></header>
      <div class="form-body"><div class="form-grid"><label><span>工具名称 <b>*</b></span><input v-model="form.name" placeholder="例如：团队日历" /></label><label><span>HTTP 端点 <b>*</b></span><input v-model="form.endpoint" placeholder="https://service.example.com/mcp" /></label></div><label><span>能力说明</span><textarea v-model="form.description" rows="3" placeholder="说明这个工具可以为 Agent 做什么"></textarea></label>
        <section class="params-editor"><div><div><strong>输入参数</strong><small>这些字段会出现在工具调用协议中。</small></div><button type="button" @click="addParameter"><Plus :size="14" />添加参数</button></div>
          <div v-if="!form.params.length" class="params-empty"><Braces :size="18" /><span>没有参数时，Agent 会直接调用端点。</span></div>
          <div v-for="(param, index) in form.params" :key="index" class="param-editor"><input v-model="param.name" aria-label="参数名称" placeholder="参数名" /><select v-model="param.type" aria-label="参数类型"><option value="string">string</option><option value="number">number</option><option value="boolean">boolean</option><option value="object">object</option><option value="array">array</option></select><input v-model="param.description" aria-label="参数说明" placeholder="参数说明" /><label><input v-model="param.required" type="checkbox" />必填</label><button type="button" aria-label="删除参数" @click="removeParameter(index)"><Trash2 :size="14" /></button></div>
        </section><p v-if="formError" class="form-error"><AlertCircle :size="14" />{{ formError }}</p><p class="security-note">请只连接你信任的端点。注册后，Agent 可以把工具参数发送到该服务。</p>
      </div>
      <footer><button class="secondary-button" type="button" @click="modalOpen = false">取消</button><button class="primary-button" type="button" :disabled="submitting" @click="registerMcp"><LoaderCircle v-if="submitting" :size="15" class="spin" /><Network v-else :size="15" />注册工具</button></footer>
    </section></div></Transition>
    <Transition name="toast"><div v-if="toast" class="toast-message">{{ toast }}</div></Transition>
  </div>
</template>

<style scoped>
.tools-overview{display:flex;align-items:center;gap:28px;padding:17px 21px;border:1px solid var(--border);border-radius:15px;background:linear-gradient(105deg,var(--bg-elevated),var(--brand-softer));box-shadow:var(--shadow-sm)}.tools-overview>div{display:flex;align-items:center;gap:11px}.tools-overview>i{width:1px;height:32px;background:var(--border)}.tools-overview span{display:inline-flex;align-items:center;justify-content:center}.overview-icon,.calls-icon{width:37px;height:37px;border-radius:11px;background:var(--brand-soft);color:var(--brand)}.calls-icon{background:var(--success-soft);color:var(--success)}.status-dot{width:10px;height:10px;margin:0 13px;border-radius:50%;background:var(--success);box-shadow:0 0 0 6px var(--success-soft)}.status-dot.offline{background:var(--text-faint);box-shadow:0 0 0 6px var(--bg-hover)}.tools-overview div div{display:flex;flex-direction:column}.tools-overview small{color:var(--text-faint);font-size:8px}.tools-overview strong{margin-top:3px;color:var(--text-strong);font-size:13px;font-weight:660}.overview-refresh{display:inline-flex;align-items:center;gap:6px;margin-left:auto;padding:7px 9px;border:1px solid var(--border);border-radius:8px;background:var(--bg-elevated);color:var(--text-muted);font-size:9px;cursor:pointer}.overview-refresh:disabled{opacity:.5}
.tools-filter{display:flex;align-items:center;justify-content:space-between;margin:24px 0 13px}.tools-filter>div{display:flex;gap:4px}.tools-filter button{padding:8px 12px;border:0;border-radius:8px;background:transparent;color:var(--text-muted);font-size:10px;cursor:pointer}.tools-filter button.active{background:var(--bg-elevated);color:var(--text-strong);box-shadow:var(--shadow-sm);font-weight:620}.tools-filter>label{display:flex;align-items:center;gap:7px;height:34px;padding:0 10px;border:1px solid var(--border);border-radius:9px;background:var(--bg-elevated);color:var(--text-faint)}.tools-filter input{width:150px;border:0;outline:0;background:transparent;color:var(--text);font-size:10px}.page-state{display:flex;min-height:260px;align-items:center;justify-content:center;flex-direction:column;gap:8px;color:var(--text-faint);font-size:10px}.page-state strong{color:var(--text);font-size:12px}.page-state.error>svg{color:var(--danger)}.page-state .secondary-button{min-height:34px;margin-top:3px;font-size:10px}
.tool-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.tool-card{display:block;padding:16px;border:1px solid var(--border);background:var(--bg-elevated);text-align:left;cursor:pointer;transition:160ms ease}.tool-card:hover{border-color:var(--border-strong);box-shadow:var(--shadow-md);transform:translateY(-2px)}.tool-card-top{display:flex;align-items:center}.tool-icon,.drawer-icon{display:inline-flex;align-items:center;justify-content:center;width:39px;height:39px;border-radius:12px}.tool-icon.blue,.drawer-icon.blue{background:#eaf3ff;color:#3978bd}.tool-icon.amber,.drawer-icon.amber{background:var(--warning-soft);color:var(--warning)}.tool-icon.violet,.drawer-icon.violet{background:var(--brand-soft);color:var(--brand)}.tool-icon.green,.drawer-icon.green{background:var(--success-soft);color:var(--success)}.tool-icon.rose,.drawer-icon.rose{background:#fff0f3;color:#bd5c70}:root[data-theme='dark'] .tool-icon.blue,:root[data-theme='dark'] .drawer-icon.blue{background:#1d3146;color:#83b8ef}:root[data-theme='dark'] .tool-icon.rose,:root[data-theme='dark'] .drawer-icon.rose{background:#3d242d;color:#ef9bae}.tool-category{margin-left:9px;padding:4px 7px;border-radius:6px;background:var(--bg-hover);color:var(--text-faint);font-size:8px;font-weight:600}.tool-card-top>svg{margin-left:auto;color:var(--text-faint)}.tool-card h2{margin:15px 0 0;color:var(--text-strong);font-size:13px;font-weight:650}.tool-card>p{min-height:34px;margin:6px 0 16px;color:var(--text-muted);font-size:9px;line-height:1.65}.tool-card-footer{display:flex;align-items:center;justify-content:space-between;padding-top:12px;border-top:1px solid var(--border)}.tool-card-footer>span{display:inline-flex;align-items:center;gap:6px;color:var(--success);font-size:8px}.tool-card-footer>span i{width:5px;height:5px;border-radius:50%;background:currentColor}.tool-card-footer>span.offline{color:var(--text-faint)}.tool-card-footer small{color:var(--text-faint);font-size:8px}.add-tool-card{display:flex;align-items:center;justify-content:center;min-height:202px;flex-direction:column;border:1px dashed var(--border-strong);border-radius:16px;background:transparent;cursor:pointer}.add-tool-card:hover{border-color:var(--brand);background:var(--brand-softer)}.add-tool-card span{display:inline-flex;align-items:center;justify-content:center;width:40px;height:40px;border-radius:12px;background:var(--bg-elevated);color:var(--brand);box-shadow:var(--shadow-sm)}.add-tool-card strong{margin-top:11px;color:var(--text-strong);font-size:11px}.add-tool-card small{margin-top:5px;color:var(--text-faint);font-size:8px}
.capability-note{display:flex;align-items:flex-start;gap:10px;margin-top:18px;padding:13px 15px;border:1px solid var(--border);border-radius:12px;background:var(--bg-subtle);color:var(--text-faint)}.capability-note>svg{flex:0 0 auto;color:var(--brand)}.capability-note strong{display:block;color:var(--text);font-size:9px}.capability-note p{margin:4px 0 0;font-size:8px;line-height:1.6}
.drawer-backdrop,.modal-backdrop{position:fixed;z-index:70;inset:0;background:rgba(21,22,30,.3);backdrop-filter:blur(3px)}.tool-drawer{position:absolute;top:0;right:0;width:min(440px,100%);height:100%;overflow:auto;border-left:1px solid var(--border);background:var(--bg-elevated);box-shadow:-24px 0 70px rgba(20,22,32,.13)}.tool-drawer>header{display:flex;align-items:flex-start;justify-content:space-between;padding:23px 24px 18px;border-bottom:1px solid var(--border)}.tool-drawer header small{color:var(--brand);font-size:8px;font-weight:700;letter-spacing:.1em}.tool-drawer h2{margin:5px 0 0;color:var(--text-strong);font-size:20px}.drawer-content{padding:24px}.drawer-icon{width:48px;height:48px}.drawer-content>h3,.parameter-section h3{margin:22px 0 7px;color:var(--text-strong);font-size:11px}.drawer-content>p{margin:0;color:var(--text-muted);font-size:10px;line-height:1.75}.status-line{display:flex;align-items:flex-start;gap:10px;margin-top:20px;padding:13px;border:1px solid var(--border);border-radius:12px;background:var(--success-soft);color:var(--success)}.status-line>span{display:flex;flex-direction:column}.status-line strong{font-size:9px}.status-line small{margin-top:4px;color:var(--text-muted);font-size:8px;line-height:1.5}.endpoint-block{margin-top:14px;padding:12px;border:1px solid var(--border);border-radius:11px;background:var(--bg-subtle)}.endpoint-block small,.endpoint-block code{display:block}.endpoint-block small{color:var(--text-faint);font-size:8px}.endpoint-block code{margin-top:6px;overflow-wrap:anywhere;color:var(--text);font-size:9px}.parameter-section{margin-top:25px}.parameter-section>div:first-child{display:flex;align-items:center;justify-content:space-between;border:0;padding:0}.parameter-section>div:first-child h3{margin:0}.parameter-section>div:first-child span{color:var(--text-faint);font-size:8px}.empty-copy{padding:18px!important;border:1px dashed var(--border);border-radius:11px;text-align:center}.parameter-row{display:grid;grid-template-columns:1fr 60px 40px;gap:7px;margin-top:8px;padding:11px;border:1px solid var(--border);border-radius:10px}.parameter-row code{color:var(--brand);font-size:9px}.parameter-row>span,.parameter-row b{font-size:8px}.parameter-row>span{color:var(--text-faint)}.parameter-row b{color:var(--warning)}.parameter-row small{grid-column:1/-1;color:var(--text-muted);font-size:8px}
.modal-backdrop{display:grid;place-items:center;padding:20px;overflow:auto}.mcp-modal{width:min(680px,100%);max-height:calc(100vh - 40px);overflow:auto;border:1px solid var(--border);border-radius:19px;background:var(--bg-elevated);box-shadow:var(--shadow-md)}.mcp-modal>header{display:flex;align-items:center;justify-content:space-between;padding:20px 22px;border-bottom:1px solid var(--border)}.mcp-modal>header>div{display:flex;align-items:center;gap:11px}.mcp-modal>header>div>span{display:inline-flex;align-items:center;justify-content:center;width:41px;height:41px;border-radius:12px;background:var(--brand-soft);color:var(--brand)}.mcp-modal header small{color:var(--brand);font-size:7px;font-weight:700;letter-spacing:.1em}.mcp-modal h2{margin:3px 0 0;color:var(--text-strong);font-size:16px}.form-body{padding:21px 22px}.form-grid{display:grid;grid-template-columns:1fr 1.35fr;gap:12px}.form-body>label,.form-grid label{display:block}.form-body label>span{display:block;margin-bottom:6px;color:var(--text-muted);font-size:9px;font-weight:600}.form-body label>span b{color:var(--danger)}.form-body input:not([type='checkbox']),.form-body textarea,.form-body select{width:100%;border:1px solid var(--border);border-radius:9px;outline:0;background:var(--bg-subtle);color:var(--text);font-size:10px}.form-body input:not([type='checkbox']),.form-body select{height:38px;padding:0 10px}.form-body textarea{padding:10px;resize:vertical}.form-body input:focus,.form-body textarea:focus,.form-body select:focus{border-color:var(--brand);box-shadow:0 0 0 3px var(--brand-soft)}.form-body>.form-grid+label{margin-top:13px}.params-editor{margin-top:20px}.params-editor>div:first-child{display:flex;align-items:center;justify-content:space-between}.params-editor>div:first-child>div{display:flex;flex-direction:column}.params-editor strong{color:var(--text-strong);font-size:10px}.params-editor small{margin-top:3px;color:var(--text-faint);font-size:8px}.params-editor>div:first-child>button{display:inline-flex;align-items:center;gap:5px;padding:6px 8px;border:1px solid var(--border);border-radius:8px;background:var(--bg-elevated);color:var(--brand);font-size:8px;cursor:pointer}.params-empty{display:flex!important;min-height:62px;align-items:center!important;justify-content:center!important;gap:7px;margin-top:10px;border:1px dashed var(--border);border-radius:10px;color:var(--text-faint);font-size:8px}.param-editor{display:grid!important;grid-template-columns:1fr 95px 1.4fr 58px 28px;align-items:center!important;gap:7px;margin-top:8px}.param-editor>label{display:flex;align-items:center;gap:5px;color:var(--text-muted);font-size:8px}.param-editor>button{display:inline-flex;align-items:center;justify-content:center;width:28px;height:28px;border:0;border-radius:7px;background:transparent;color:var(--text-faint);cursor:pointer}.param-editor>button:hover{background:#fff0f1;color:var(--danger)}.form-error{display:flex;align-items:center;gap:6px;margin:14px 0 0;color:var(--danger);font-size:9px}.security-note{margin:14px 0 0;padding:10px 12px;border-radius:9px;background:var(--warning-soft);color:var(--text-muted);font-size:8px;line-height:1.55}.mcp-modal>footer{display:flex;justify-content:flex-end;gap:8px;padding:15px 22px;border-top:1px solid var(--border);background:var(--bg-subtle)}.mcp-modal button:disabled{opacity:.55;cursor:default}.toast-message{position:fixed;z-index:90;right:24px;bottom:24px;max-width:380px;padding:12px 15px;border:1px solid var(--border);border-radius:11px;background:var(--text-strong);color:var(--bg-elevated);box-shadow:var(--shadow-md);font-size:10px}.spin{animation:spin 1.4s linear infinite}.fade-enter-active,.fade-leave-active,.toast-enter-active,.toast-leave-active{transition:180ms ease}.fade-enter-from,.fade-leave-to{opacity:0}.toast-enter-from,.toast-leave-to{opacity:0;transform:translateY(8px)}@keyframes spin{to{transform:rotate(360deg)}}
@media(max-width:900px){.tool-grid{grid-template-columns:repeat(2,1fr)}}@media(max-width:720px){.form-grid{grid-template-columns:1fr}.param-editor{grid-template-columns:1fr 90px 28px}.param-editor>input:nth-child(3){grid-column:1/3}.param-editor>label{grid-column:1/3;grid-row:3}.param-editor>button{grid-column:3;grid-row:1}}@media(max-width:620px){.tools-overview{align-items:flex-start;flex-direction:column;gap:14px}.tools-overview>i{width:100%;height:1px}.overview-refresh{margin-left:0}.tools-filter{align-items:flex-start;flex-direction:column;gap:10px}.tool-grid{grid-template-columns:1fr}.toast-message{right:14px;bottom:84px;left:14px}}
</style>
