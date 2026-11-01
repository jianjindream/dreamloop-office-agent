<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { AlertTriangle, Calculator, CheckCircle2, Download, FileText, LoaderCircle, Plus, Save } from 'lucide-vue-next'
import { agentApi } from '@/api/agent'
import MarkdownContent from '@/components/chat/MarkdownContent.vue'
import type { DocumentSummary, ReviewDiff, WeeklyReportResult, WeeklyReportTemplate } from '@/types/api'

const today = new Date()
const end = today.toISOString().slice(0, 10)
const startDate = new Date(today); startDate.setDate(today.getDate() - 4)
const projectName = ref('')
const periodStart = ref(startDate.toISOString().slice(0, 10))
const periodEnd = ref(end)
const templates = ref<WeeklyReportTemplate[]>([])
const templateId = ref('builtin-weekly-report')
const documents = ref<DocumentSummary[]>([])
const selectedIds = ref<string[]>([])
const userFacts = ref('')
const loading = ref(true)
const generating = ref(false)
const saving = ref(false)
const error = ref('')
const toast = ref('')
const result = ref<WeeklyReportResult | null>(null)
const generatedContent = ref('')
const editedContent = ref('')
const diff = ref<ReviewDiff | null>(null)
const showTemplateForm = ref(false)
const templateName = ref('')
const templateDescription = ref('')
const templateContent = ref('# {{项目名称}}项目周报\n\n汇报周期：{{开始日期}} 至 {{结束日期}}\n\n## 本周摘要\n{{本周摘要}}\n\n## 本周完成\n{{本周完成}}\n\n## 进行中事项\n{{进行中事项}}\n\n## 风险与阻塞\n{{风险与阻塞}}\n\n## 下周计划\n{{下周计划}}\n\n## 待确认信息\n{{待确认信息}}\n\n## 资料来源与版本\n{{资料来源与版本}}')

const eligibleDocuments = computed(() => documents.value.filter((doc) => doc.docType !== 'weekly_report_template' && doc.status !== 'deleted'))
const issueCount = computed(() => (result.value?.review.conflicts.length ?? 0) + (result.value?.review.missing.length ?? 0))

const load = async () => {
  loading.value = true
  try {
    ;[documents.value, templates.value] = await Promise.all([agentApi.documents(), agentApi.weeklyReportTemplates()])
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '加载失败' }
  finally { loading.value = false }
}

const generate = async () => {
  error.value = ''
  if (!projectName.value.trim() || !selectedIds.value.length) { error.value = '请填写项目名称并至少选择一份资料'; return }
  generating.value = true
  try {
    result.value = await agentApi.generateWeeklyReport({
      project_name: projectName.value.trim(), period_start: periodStart.value, period_end: periodEnd.value,
      template_id: templateId.value, document_ids: selectedIds.value, user_facts: userFacts.value, save_draft: true,
    })
    generatedContent.value = result.value.contentMd
    editedContent.value = result.value.contentMd
    diff.value = null
    toast.value = '周报草稿已保存，可继续审阅和修改。'
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '生成失败' }
  finally { generating.value = false }
}

const reviewChanges = async () => {
  if (!result.value) return
  diff.value = await agentApi.compareWeeklyReport(generatedContent.value, editedContent.value)
}

const save = async () => {
  if (!result.value) return
  saving.value = true
  try {
    const saved = await agentApi.updateDocument(result.value.documentId, {
      title: `${projectName.value}项目周报 ${periodEnd.value}`, content_md: editedContent.value,
      doc_type: 'weekly_report', base_version: result.value.version, draft: false,
      metadata: { project_name: projectName.value, period_start: periodStart.value, period_end: periodEnd.value },
    })
    if (saved.version) { result.value.version = saved.version.version; result.value.versionId = String(saved.version.id) }
    generatedContent.value = editedContent.value
    diff.value = null
    toast.value = '修改已保存为新的正式版本。'
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '保存失败' }
  finally { saving.value = false }
}

const exportFile = async (format: 'docx' | 'pdf') => {
  if (!result.value) return
  try {
    const blob = await agentApi.exportDocument(result.value.documentId, format, result.value.versionId)
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = `${projectName.value}项目周报.${format}`; anchor.click()
    URL.revokeObjectURL(url)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '导出失败' }
}

const createTemplate = async () => {
  try {
    const created = await agentApi.createWeeklyReportTemplate({ name: templateName.value, description: templateDescription.value, content_md: templateContent.value })
    templates.value.push(created); templateId.value = created.id; showTemplateForm.value = false; toast.value = '公司模板已保存。'
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '模板保存失败' }
}

onMounted(() => { void load() })
</script>

<template>
  <div class="page-shell report-page">
    <header class="page-heading"><div><h1>项目周报</h1><p>从指定资料提取事实，核对数字和冲突，再导出可交付的 Word 或 PDF。</p></div><span class="status-chip"><CheckCircle2 :size="15" /> 来源可追溯</span></header>
    <div v-if="loading" class="surface-card loading"><LoaderCircle class="spin" :size="20" /> 正在读取资料和模板…</div>
    <template v-else>
      <section class="surface-card setup-card">
        <div class="section-title"><div><small>步骤 1</small><h2>设置周报范围</h2></div><button class="secondary-button" type="button" @click="showTemplateForm = !showTemplateForm"><Plus :size="15" /> 公司模板</button></div>
        <div class="form-grid">
          <label>项目名称<input v-model="projectName" placeholder="例如：星桥客户门户" /></label>
          <label>开始日期<input v-model="periodStart" type="date" /></label>
          <label>结束日期<input v-model="periodEnd" type="date" /></label>
          <label>输出模板<select v-model="templateId"><option v-for="template in templates" :key="template.id" :value="template.id">{{ template.name }}{{ template.builtIn ? '（内置）' : '' }}</option></select></label>
        </div>
        <div v-if="showTemplateForm" class="template-form"><input v-model="templateName" placeholder="模板名称" /><input v-model="templateDescription" placeholder="用途说明（可选）" /><textarea v-model="templateContent" rows="8" aria-label="模板正文"></textarea><button class="primary-button" type="button" @click="createTemplate">保存模板</button></div>
        <div class="source-picker"><strong>参与处理的资料</strong><span>已选 {{ selectedIds.length }} / 20</span><div class="source-list"><label v-for="doc in eligibleDocuments" :key="String(doc.id)" class="source-item"><input v-model="selectedIds" type="checkbox" :value="String(doc.id)" :disabled="selectedIds.length >= 20 && !selectedIds.includes(String(doc.id))" /><FileText :size="16" /><span><b>{{ doc.title }}</b><small>v{{ doc.latestVersion }} · {{ doc.docType || '文档' }}</small></span></label><p v-if="!eligibleDocuments.length">请先到知识库上传项目资料。</p></div></div>
        <label class="facts-field">用户补充事实（单独标注来源）<textarea v-model="userFacts" rows="3" placeholder="可选。不要在这里填写尚未确认的推测。"></textarea></label>
        <p v-if="error" class="error-message" role="alert">{{ error }}</p>
        <button class="primary-button generate-button" type="button" :disabled="generating" @click="generate"><LoaderCircle v-if="generating" class="spin" :size="16" />{{ generating ? '正在核对资料…' : '生成并保存草稿' }}</button>
      </section>

      <template v-if="result">
        <section class="review-grid">
          <article class="surface-card review-card"><span class="review-icon warning"><AlertTriangle :size="18" /></span><div><small>待人工确认</small><strong>{{ issueCount }}</strong></div></article>
          <article class="surface-card review-card"><span class="review-icon"><Calculator :size="18" /></span><div><small>确定性计算</small><strong>{{ result.review.calculations.length }}</strong></div></article>
          <article class="surface-card review-card"><span class="review-icon success"><FileText :size="18" /></span><div><small>引用资料</small><strong>{{ result.sources.length }}</strong></div></article>
        </section>
        <section v-if="issueCount || result.review.calculations.length" class="surface-card audit-card"><div class="section-title"><div><small>步骤 2</small><h2>核对计算与资料问题</h2></div></div><div class="audit-list"><div v-for="item in [...result.review.conflicts, ...result.review.missing]" :key="`${item.type}-${item.field}`" class="audit-row" :class="item.type"><b>{{ item.type === 'conflict' ? '资料冲突' : '信息缺失' }}</b><span>{{ item.message }}</span><small>{{ item.sourceIds.join('、') || '系统检查' }} {{ Object.keys(item.values).length ? JSON.stringify(item.values) : '' }}</small></div><div v-for="calc in result.review.calculations" :key="`${calc.metric}-${calc.sourceId}`" class="audit-row calculation"><b>{{ calc.metric }}</b><span>{{ calc.inputs.join(' / ') }} · {{ calc.formula }} = {{ calc.result === null ? '不可用' : calc.unit === 'ratio' ? `${(calc.result * 100).toFixed(1)}%` : `${calc.result} ${calc.unit === 'count' ? '个' : calc.unit}` }}</span><small>{{ calc.sourceId }}</small></div></div></section>
        <section class="surface-card editor-card"><div class="section-title"><div><small>步骤 3</small><h2>审阅和定稿</h2></div><div class="actions"><button class="secondary-button" type="button" @click="reviewChanges">查看差异</button><button class="secondary-button" type="button" :disabled="saving" @click="save"><Save :size="15" /> 保存正式版本</button><button class="secondary-button" type="button" @click="exportFile('docx')"><Download :size="15" /> Word</button><button class="secondary-button" type="button" @click="exportFile('pdf')"><Download :size="15" /> PDF</button></div></div><div class="editor-grid"><textarea v-model="editedContent" aria-label="周报正文"></textarea><div class="preview"><MarkdownContent :content="editedContent" /></div></div><div v-if="diff" class="diff-summary"><strong>本次修改</strong><span>新增 {{ diff.added }} 行 · 删除 {{ diff.removed }} 行 · 未变 {{ diff.unchanged }} 行</span><div class="diff-lines"><p v-for="(change, index) in diff.changes.filter((item) => item.type !== 'unchanged')" :key="index" :class="change.type">{{ change.type === 'added' ? '+' : '-' }} {{ change.text || '空行' }}</p></div></div></section>
      </template>
    </template>
    <Transition name="toast"><div v-if="toast" class="toast-message" @click="toast = ''">{{ toast }}</div></Transition>
  </div>
</template>

<style scoped>
.report-page{max-width:1260px}.status-chip{display:inline-flex;align-items:center;gap:6px;padding:8px 11px;border-radius:999px;background:var(--success-soft);color:var(--success);font-size:11px;font-weight:650}.loading{display:flex;align-items:center;justify-content:center;gap:8px;min-height:240px;color:var(--text-muted)}.setup-card,.audit-card,.editor-card{padding:22px}.section-title{display:flex;align-items:center;justify-content:space-between;gap:16px}.section-title small{color:var(--brand);font-size:9px;font-weight:700}.section-title h2{margin:4px 0 0;color:var(--text-strong);font-size:16px}.form-grid{display:grid;grid-template-columns:2fr 1fr 1fr 1.4fr;gap:12px;margin-top:20px}label{display:flex;flex-direction:column;gap:7px;color:var(--text-muted);font-size:10px;font-weight:600}input,select,textarea{width:100%;border:1px solid var(--border);border-radius:9px;background:var(--bg-subtle);color:var(--text);outline:0}input,select{height:39px;padding:0 10px}textarea{padding:10px;line-height:1.65;resize:vertical}input:focus,select:focus,textarea:focus{border-color:var(--brand);box-shadow:0 0 0 3px var(--brand-soft)}.template-form{display:grid;grid-template-columns:1fr 1fr auto;gap:10px;margin-top:14px;padding:14px;border:1px solid var(--border);border-radius:12px;background:var(--bg-subtle)}.template-form textarea{grid-column:1/-1}.template-form .primary-button{grid-column:3}.source-picker{margin-top:20px}.source-picker>strong{color:var(--text-strong);font-size:11px}.source-picker>span{margin-left:8px;color:var(--text-faint);font-size:9px}.source-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;margin-top:10px}.source-item{display:grid;grid-template-columns:18px 20px minmax(0,1fr);align-items:center;gap:8px;padding:10px;border:1px solid var(--border);border-radius:10px;background:var(--bg-subtle);cursor:pointer}.source-item input{width:14px;height:14px}.source-item b,.source-item small{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.source-item b{color:var(--text);font-size:10px}.source-item small{margin-top:3px;color:var(--text-faint);font-size:8px}.facts-field{margin-top:16px}.generate-button{margin-top:16px}.error-message{color:var(--danger);font-size:10px}.review-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:12px;margin-top:18px}.review-card{display:flex;align-items:center;gap:12px;padding:16px}.review-icon{display:inline-flex;align-items:center;justify-content:center;width:38px;height:38px;border-radius:11px;background:var(--brand-soft);color:var(--brand)}.review-icon.warning{background:var(--warning-soft);color:var(--warning)}.review-icon.success{background:var(--success-soft);color:var(--success)}.review-card small,.review-card strong{display:block}.review-card small{color:var(--text-muted);font-size:9px}.review-card strong{margin-top:3px;color:var(--text-strong);font-size:18px}.audit-card,.editor-card{margin-top:18px}.audit-list{display:grid;gap:8px;margin-top:16px}.audit-row{display:grid;grid-template-columns:90px minmax(0,1fr) auto;gap:12px;align-items:center;padding:11px 12px;border:1px solid var(--border);border-radius:9px;font-size:10px}.audit-row b{color:var(--warning)}.audit-row.conflict b{color:var(--danger)}.audit-row.calculation b{color:var(--brand)}.audit-row small{color:var(--text-faint)}.actions{display:flex;flex-wrap:wrap;gap:7px}.editor-grid{display:grid;grid-template-columns:1fr 1fr;gap:14px;margin-top:18px}.editor-grid>textarea,.preview{min-height:560px}.editor-grid>textarea{resize:none;font-family:"SFMono-Regular",Consolas,monospace;font-size:11px}.preview{overflow:auto;padding:18px;border:1px solid var(--border);border-radius:10px;background:var(--bg-subtle)}.diff-summary{margin-top:14px;padding:13px;border:1px solid var(--border);border-radius:10px}.diff-summary>strong{color:var(--text-strong);font-size:11px}.diff-summary>span{margin-left:8px;color:var(--text-muted);font-size:9px}.diff-lines{max-height:180px;margin-top:9px;overflow:auto;font-family:monospace;font-size:9px}.diff-lines p{margin:2px 0;padding:3px 6px}.diff-lines .added{background:var(--success-soft);color:var(--success)}.diff-lines .removed{background:#fff0f1;color:var(--danger)}.toast-message{position:fixed;z-index:90;right:24px;bottom:24px;padding:12px 15px;border-radius:10px;background:var(--text-strong);color:var(--bg-elevated);box-shadow:var(--shadow-md);font-size:10px}.spin{animation:spin 1.3s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}@media(max-width:960px){.form-grid{grid-template-columns:1fr 1fr}.editor-grid{grid-template-columns:1fr}.source-list{grid-template-columns:1fr}}@media(max-width:650px){.form-grid,.review-grid{grid-template-columns:1fr}.section-title{align-items:flex-start;flex-direction:column}.template-form{grid-template-columns:1fr}.template-form .primary-button{grid-column:1}.audit-row{grid-template-columns:1fr}.actions{width:100%}}
</style>
