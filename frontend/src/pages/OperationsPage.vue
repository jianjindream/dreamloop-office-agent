<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Activity, Cable, CircleAlert, LoaderCircle, Play, RefreshCw, RotateCcw, ShieldCheck, Square, Trash2, UserPlus, Users } from 'lucide-vue-next'
import { agentApi } from '@/api/agent'
import type { ConnectionProvider, OfficeConnection, OfficeTask, PilotDashboard, WorkspaceMember } from '@/types/api'

type Tab = 'tasks' | 'connections' | 'team' | 'pilot'
const tab = ref<Tab>('tasks')
const me = ref<WorkspaceMember | null>(null)
const tasks = ref<OfficeTask[]>([])
const providers = ref<ConnectionProvider[]>([])
const connections = ref<OfficeConnection[]>([])
const members = ref<WorkspaceMember[]>([])
const dashboard = ref<PilotDashboard | null>(null)
const loading = ref(true)
const error = ref('')
const notice = ref('')
const isAdmin = computed(() => Boolean(me.value?.administrator))
let timer: number | undefined

const provider = ref('manual_upload')
const connectionName = ref('')
const credentialRef = ref('')
const rootFolder = ref('')
const inviteUser = ref('')
const inviteName = ref('')
const inviteEmail = ref('')
const inviteRole = ref<WorkspaceMember['role']>('editor')
const rating = ref(5)
const factErrors = ref(0)
const feedbackReportId = ref('')
const feedback = ref('')

const refreshTasks = async () => { tasks.value = await agentApi.tasks() }
const load = async () => {
  loading.value = true; error.value = ''
  try {
    me.value = await agentApi.teamMe()
    ;[tasks.value, providers.value, connections.value] = await Promise.all([agentApi.tasks(), agentApi.connectionProviders(), agentApi.connections()])
    if (me.value.administrator) [members.value, dashboard.value] = await Promise.all([agentApi.teamMembers(), agentApi.pilotDashboard()])
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '运营数据加载失败' }
  finally { loading.value = false }
}

const createConnection = async () => {
  try {
    await agentApi.createConnection({ provider: provider.value, display_name: connectionName.value, credential_ref: credentialRef.value, config: rootFolder.value ? { root_folder: rootFolder.value } : {} })
    connections.value = await agentApi.connections(); connectionName.value = ''; credentialRef.value = ''; rootFolder.value = ''; notice.value = '连接配置已保存。'
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '连接创建失败' }
}
const testConnection = async (connection: OfficeConnection) => {
  try { const result = await agentApi.testConnection(connection.id); notice.value = result.message; connections.value = await agentApi.connections() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '连接验证失败' }
}
const syncConnection = async (connection: OfficeConnection) => {
  try { await agentApi.syncConnection(connection.id); tab.value = 'tasks'; await refreshTasks(); notice.value = '同步检查已加入后台任务。' }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '无法创建同步任务' }
}
const deleteConnection = async (connection: OfficeConnection) => {
  if (!window.confirm(`删除连接“${connection.displayName}”？`)) return
  try { await agentApi.deleteConnection(connection.id); connections.value = await agentApi.connections() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '连接删除失败' }
}

const invite = async () => {
  try {
    await agentApi.inviteMember({ user_id: inviteUser.value, display_name: inviteName.value, email: inviteEmail.value, role: inviteRole.value })
    members.value = await agentApi.teamMembers(); inviteUser.value = ''; inviteName.value = ''; inviteEmail.value = ''; notice.value = '成员邀请已创建。'
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '邀请失败' }
}
const updateMember = async (member: WorkspaceMember) => {
  try { await agentApi.updateMember(member.userId, { role: member.role, status: member.status }); members.value = await agentApi.teamMembers(); notice.value = '成员权限已更新。' }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '成员更新失败' }
}
const removeMember = async (member: WorkspaceMember) => {
  if (!window.confirm(`移除成员“${member.displayName}”？`)) return
  try { await agentApi.removeMember(member.userId); members.value = await agentApi.teamMembers() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '成员移除失败' }
}

const cancelTask = async (task: OfficeTask) => { try { await agentApi.cancelOfficeTask(task.id); await refreshTasks() } catch (cause) { error.value = cause instanceof Error ? cause.message : '取消失败' } }
const retryTask = async (task: OfficeTask) => { try { await agentApi.retryOfficeTask(task.id); await refreshTasks() } catch (cause) { error.value = cause instanceof Error ? cause.message : '重试失败' } }
const submitFeedback = async () => {
  try {
    await agentApi.recordPilotEvent({ eventType: 'feedback_submitted', reportId: feedbackReportId.value || undefined, rating: rating.value, factErrors: factErrors.value, comment: feedback.value })
    feedback.value = ''; feedbackReportId.value = ''; factErrors.value = 0; notice.value = '试点反馈已记录。'
    if (isAdmin.value) dashboard.value = await agentApi.pilotDashboard()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '反馈提交失败' }
}

const statusLabel = (status: string) => ({ queued: '排队中', running: '执行中', succeeded: '已完成', failed: '失败', canceled: '已取消' }[status] ?? status)
const formatTime = (value?: string) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'

onMounted(() => { void load(); timer = window.setInterval(() => { if (tab.value === 'tasks') void refreshTasks() }, 2000) })
onBeforeUnmount(() => window.clearInterval(timer))
</script>

<template>
  <div class="page-shell operations-page">
    <header class="page-heading"><div><h1>运营中心</h1><p>集中管理后台任务、办公平台连接、团队权限和试点反馈。</p></div><span v-if="me" class="role-chip"><ShieldCheck :size="15" />{{ me.role }}</span></header>
    <div v-if="loading" class="surface-card page-state"><LoaderCircle class="spin" :size="20" />正在读取工作区运营数据…</div>
    <template v-else>
      <nav class="tabs surface-card" aria-label="运营模块">
        <button :class="{ active: tab === 'tasks' }" type="button" @click="tab = 'tasks'"><Activity :size="16" />后台任务</button>
        <button :class="{ active: tab === 'connections' }" type="button" @click="tab = 'connections'"><Cable :size="16" />平台连接</button>
        <button :class="{ active: tab === 'team' }" type="button" @click="tab = 'team'"><Users :size="16" />团队权限</button>
        <button :class="{ active: tab === 'pilot' }" type="button" @click="tab = 'pilot'"><Play :size="16" />试点数据</button>
      </nav>
      <p v-if="error" class="message error" role="alert"><CircleAlert :size="15" />{{ error }}</p>
      <p v-if="notice" class="message success" @click="notice = ''">{{ notice }}</p>

      <section v-if="tab === 'tasks'" class="surface-card panel">
        <div class="panel-heading"><div><h2>后台任务</h2><p>页面关闭后任务仍会执行；服务重启中断的任务可重试。</p></div><button class="secondary-button" type="button" @click="refreshTasks"><RefreshCw :size="15" />刷新</button></div>
        <div v-if="!tasks.length" class="empty">还没有后台任务。</div>
        <div v-else class="task-list"><article v-for="task in tasks" :key="task.id" class="task-row"><span class="task-state" :class="task.status"><LoaderCircle v-if="task.status === 'running' || task.status === 'queued'" class="spin" :size="16" /><Activity v-else :size="16" /></span><div><strong>{{ task.title }}</strong><small>{{ task.taskType }} · {{ formatTime(task.createdAt) }}</small><i><b :style="{ width: `${task.progress}%` }"></b></i><em>{{ task.message }}<template v-if="task.error"> · {{ task.error }}</template></em></div><span class="status" :class="task.status">{{ statusLabel(task.status) }}</span><button v-if="task.status === 'queued' || task.status === 'running'" class="icon-button" type="button" title="取消任务" @click="cancelTask(task)"><Square :size="14" /></button><button v-else-if="task.status === 'failed' || task.status === 'canceled'" class="icon-button" type="button" title="重试任务" @click="retryTask(task)"><RotateCcw :size="14" /></button></article></div>
      </section>

      <section v-else-if="tab === 'connections'" class="surface-card panel">
        <div class="panel-heading"><div><h2>办公平台连接</h2><p>数据库只保存凭证引用，不保存访问令牌。</p></div></div>
        <div v-if="isAdmin" class="inline-form connection-form"><select v-model="provider" aria-label="办公平台"><option v-for="item in providers" :key="item.id" :value="item.id">{{ item.name }}</option></select><input v-model="connectionName" aria-label="连接名称" placeholder="连接名称" /><input v-model="credentialRef" aria-label="凭证引用" :disabled="provider === 'manual_upload'" placeholder="env:OFFICE_TOKEN" /><input v-model="rootFolder" aria-label="根目录" placeholder="根目录（可选）" /><button class="primary-button" type="button" @click="createConnection">添加连接</button></div>
        <p v-else class="readonly-hint">只有管理员可以修改连接配置。</p>
        <div class="card-grid"><article v-for="connection in connections" :key="connection.id" class="connection-card"><div><span class="provider-icon"><Cable :size="17" /></span><span><strong>{{ connection.displayName }}</strong><small>{{ connection.providerName }}</small></span><b :class="connection.status">{{ connection.status === 'connected' ? '已连接' : '待授权' }}</b></div><p>{{ connection.capabilities.join(' · ') }}</p><small>上次验证：{{ formatTime(connection.lastTestedAt) }} · 上次同步：{{ formatTime(connection.lastSyncAt) }}</small><footer v-if="isAdmin"><button class="secondary-button" type="button" @click="testConnection(connection)">验证配置</button><button class="secondary-button" type="button" :disabled="connection.status !== 'connected'" @click="syncConnection(connection)">同步检查</button><button class="icon-button danger" type="button" @click="deleteConnection(connection)"><Trash2 :size="15" /></button></footer></article></div>
      </section>

      <section v-else-if="tab === 'team'" class="surface-card panel">
        <div class="panel-heading"><div><h2>团队成员与权限</h2><p>只读成员不可修改文档、周报或后台任务；管理员可以管理连接与成员。</p></div></div>
        <div v-if="isAdmin" class="inline-form member-form"><input v-model="inviteUser" aria-label="成员用户 ID" placeholder="用户 ID" /><input v-model="inviteName" aria-label="成员姓名" placeholder="显示名称" /><input v-model="inviteEmail" aria-label="成员邮箱" placeholder="邮箱（可选）" /><select v-model="inviteRole" aria-label="邀请角色"><option value="editor">编辑者</option><option value="viewer">只读成员</option><option v-if="me?.role === 'owner'" value="admin">管理员</option></select><button class="primary-button" type="button" @click="invite"><UserPlus :size="15" />邀请</button></div>
        <p v-else class="readonly-hint">你可以查看自己的角色；成员列表仅管理员可见。</p>
        <div v-if="isAdmin" class="member-list"><div v-for="member in members" :key="member.userId" class="member-row"><span class="avatar">{{ member.displayName.slice(0, 1).toUpperCase() }}</span><div><strong>{{ member.displayName }}</strong><small>{{ member.userId }}<template v-if="member.email"> · {{ member.email }}</template></small></div><select v-model="member.role" :disabled="member.role === 'owner' && me?.role !== 'owner'" aria-label="成员角色" @change="updateMember(member)"><option value="owner">所有者</option><option value="admin">管理员</option><option value="editor">编辑者</option><option value="viewer">只读成员</option></select><select v-model="member.status" aria-label="成员状态" @change="updateMember(member)"><option value="active">有效</option><option value="invited">待加入</option><option value="suspended">已停用</option></select><button class="icon-button danger" type="button" :disabled="member.userId === me?.userId" @click="removeMember(member)"><Trash2 :size="15" /></button></div></div>
      </section>

      <section v-else class="pilot-layout">
        <div v-if="dashboard && isAdmin" class="metric-grid"><article class="surface-card"><small>完成周报</small><strong>{{ dashboard.completedReports }}</strong></article><article class="surface-card"><small>平均完成时间</small><strong>{{ dashboard.averageCompletionSeconds.toFixed(0) }} 秒</strong></article><article class="surface-card"><small>事实错误率</small><strong>{{ (dashboard.factErrorRate * 100).toFixed(1) }}%</strong></article><article class="surface-card"><small>次周复用率</small><strong>{{ (dashboard.reuseRate * 100).toFixed(1) }}%</strong></article><article class="surface-card"><small>平均评分</small><strong>{{ dashboard.averageRating.toFixed(1) }} / 5</strong></article></div>
        <section class="surface-card panel feedback-panel"><div class="panel-heading"><div><h2>提交试点反馈</h2><p>真实反馈用于评估完成时间、修改量、事实错误和次周复用。</p></div></div><div class="feedback-form"><label>整体评分<select v-model.number="rating"><option v-for="value in 5" :key="value" :value="value">{{ value }} 分</option></select></label><label>发现的事实错误数<input v-model.number="factErrors" type="number" min="0" /></label><label v-if="factErrors > 0" class="wide">周报 ID<input v-model="feedbackReportId" placeholder="有事实错误时必填" /></label><label class="wide">反馈<textarea v-model="feedback" rows="5" placeholder="哪里节省了时间，哪里仍需人工修正？"></textarea></label><button class="primary-button" type="button" @click="submitFeedback">提交反馈</button></div></section>
        <section v-if="dashboard?.recentComments.length && isAdmin" class="surface-card panel comments"><h2>最近反馈</h2><p v-for="(comment,index) in dashboard.recentComments" :key="index">{{ comment }}</p></section>
      </section>
    </template>
  </div>
</template>

<style scoped>
.operations-page{max-width:1260px}.role-chip{display:inline-flex;align-items:center;gap:6px;padding:8px 11px;border-radius:999px;background:var(--brand-soft);color:var(--brand);font-size:10px;font-weight:700;text-transform:uppercase}.page-state{display:flex;min-height:250px;align-items:center;justify-content:center;gap:8px;color:var(--text-muted);font-size:11px}.tabs{display:flex;gap:5px;padding:6px;overflow:auto}.tabs button{display:inline-flex;min-height:39px;align-items:center;gap:7px;padding:0 13px;border:0;border-radius:9px;background:transparent;color:var(--text-muted);font-size:10px;font-weight:620;white-space:nowrap;cursor:pointer}.tabs button.active{background:var(--brand-soft);color:var(--brand)}.message{display:flex;align-items:center;gap:6px;margin:12px 0 0;padding:10px 12px;border-radius:9px;font-size:10px}.message.error{background:#fff0f1;color:var(--danger)}.message.success{background:var(--success-soft);color:var(--success);cursor:pointer}.panel{margin-top:14px;padding:20px}.panel-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:16px}.panel-heading h2,.comments h2{margin:0;color:var(--text-strong);font-size:15px}.panel-heading p{margin:5px 0 0;color:var(--text-muted);font-size:9px}.empty{display:grid;min-height:180px;place-items:center;color:var(--text-faint);font-size:10px}.task-list{margin-top:14px}.task-row{display:grid;grid-template-columns:34px minmax(0,1fr) auto 36px;align-items:center;gap:10px;min-height:74px;padding:10px 4px;border-bottom:1px solid var(--border)}.task-row:last-child{border:0}.task-state{display:inline-flex;align-items:center;justify-content:center;width:31px;height:31px;border-radius:9px;background:var(--bg-hover);color:var(--text-muted)}.task-state.succeeded{background:var(--success-soft);color:var(--success)}.task-state.failed{background:#fff0f1;color:var(--danger)}.task-row strong,.task-row small,.task-row em{display:block}.task-row strong{color:var(--text);font-size:10px}.task-row small{margin-top:3px;color:var(--text-faint);font-size:8px}.task-row i{display:block;height:3px;margin-top:7px;overflow:hidden;border-radius:3px;background:var(--bg-hover)}.task-row i b{display:block;height:100%;background:var(--brand)}.task-row em{margin-top:5px;color:var(--text-muted);font-size:8px;font-style:normal}.status{padding:4px 7px;border-radius:999px;background:var(--bg-hover);font-size:8px}.status.succeeded{background:var(--success-soft);color:var(--success)}.status.failed{background:#fff0f1;color:var(--danger)}.inline-form{display:grid;gap:8px;margin-top:16px;padding:12px;border:1px solid var(--border);border-radius:11px;background:var(--bg-subtle)}.connection-form{grid-template-columns:1fr 1.2fr 1.4fr 1.2fr auto}.member-form{grid-template-columns:1fr 1fr 1.3fr .8fr auto}input,select,textarea{width:100%;border:1px solid var(--border);border-radius:8px;background:var(--bg-elevated);color:var(--text);font-size:10px;outline:0}input,select{height:38px;padding:0 9px}textarea{padding:9px;resize:vertical}.card-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin-top:15px}.connection-card{padding:15px;border:1px solid var(--border);border-radius:12px;background:var(--bg-subtle)}.connection-card>div{display:flex;align-items:center;gap:9px}.provider-icon{display:inline-flex;align-items:center;justify-content:center;width:33px;height:33px;border-radius:9px;background:var(--brand-soft);color:var(--brand)}.connection-card strong,.connection-card small{display:block}.connection-card strong{color:var(--text);font-size:10px}.connection-card small{margin-top:3px;color:var(--text-faint);font-size:8px}.connection-card b{margin-left:auto;color:var(--warning);font-size:8px}.connection-card b.connected{color:var(--success)}.connection-card p{margin:13px 0 8px;color:var(--text-muted);font-size:9px}.connection-card footer{display:flex;gap:6px;margin-top:13px}.connection-card .secondary-button{min-height:33px;padding:0 10px;font-size:9px}.danger{color:var(--danger)}.readonly-hint{color:var(--text-muted);font-size:10px}.member-list{margin-top:14px}.member-row{display:grid;grid-template-columns:34px minmax(0,1fr) 120px 100px 36px;align-items:center;gap:10px;min-height:62px;border-bottom:1px solid var(--border)}.avatar{display:inline-flex;align-items:center;justify-content:center;width:31px;height:31px;border-radius:9px;background:var(--brand-soft);color:var(--brand);font-size:10px;font-weight:700}.member-row strong,.member-row small{display:block}.member-row strong{color:var(--text);font-size:10px}.member-row small{margin-top:3px;color:var(--text-faint);font-size:8px}.pilot-layout{margin-top:14px}.metric-grid{display:grid;grid-template-columns:repeat(5,1fr);gap:9px}.metric-grid article{padding:15px}.metric-grid small,.metric-grid strong{display:block}.metric-grid small{color:var(--text-muted);font-size:8px}.metric-grid strong{margin-top:7px;color:var(--text-strong);font-size:17px}.feedback-form{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:16px}.feedback-form label{display:flex;flex-direction:column;gap:6px;color:var(--text-muted);font-size:9px}.feedback-form .wide{grid-column:1/-1}.feedback-form button{justify-self:start}.comments p{padding:9px 0;margin:0;border-bottom:1px solid var(--border);color:var(--text-muted);font-size:9px;line-height:1.6}.spin{animation:spin 1.3s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}@media(max-width:1000px){.connection-form,.member-form{grid-template-columns:1fr 1fr}.metric-grid{grid-template-columns:repeat(2,1fr)}}@media(max-width:700px){.card-grid,.feedback-form{grid-template-columns:1fr}.feedback-form .wide{grid-column:1}.member-row{grid-template-columns:34px minmax(0,1fr)}.member-row select,.member-row button{grid-column:2}.task-row{grid-template-columns:34px minmax(0,1fr) auto}.task-row>button{grid-column:3}.panel-heading{flex-direction:column}.metric-grid{grid-template-columns:1fr 1fr}}@media(max-width:480px){.connection-form,.member-form,.metric-grid{grid-template-columns:1fr}}
</style>
