<script setup lang="ts">
import { computed, ref } from 'vue'
import { BrainCircuit, Check, ChevronDown, CircleAlert, LoaderCircle, Wrench } from 'lucide-vue-next'
import type { ExecutionEvent } from '@/stores/chat'

const props = defineProps<{ events: ExecutionEvent[]; mode?: string; streaming?: boolean }>()
const expanded = ref(false)
const summary = computed(() => {
  const completed = props.events.filter((event) => event.status === 'success').length
  if (props.streaming) return props.events.at(-1)?.title ?? '正在理解你的需求'
  return completed ? `已完成 ${completed} 个执行步骤` : '已完成分析'
})
</script>

<template>
  <div v-if="events.length" class="execution-panel" :class="{ open: expanded }">
    <button class="execution-summary" type="button" @click="expanded = !expanded">
      <span class="summary-icon"><BrainCircuit :size="15" /></span>
      <span><strong>{{ summary }}</strong><small v-if="mode">{{ mode.toUpperCase() }} 模式</small></span>
      <LoaderCircle v-if="streaming" class="spinning" :size="14" />
      <ChevronDown v-else :size="15" />
    </button>
    <div v-if="expanded" class="timeline">
      <div v-for="event in events" :key="event.id" class="timeline-row">
        <span class="timeline-status" :class="event.status">
          <LoaderCircle v-if="event.status === 'running'" class="spinning" :size="12" />
          <CircleAlert v-else-if="event.status === 'error' || event.status === 'cancelled'" :size="12" />
          <Wrench v-else-if="event.type === 'tool'" :size="12" />
          <Check v-else :size="12" />
        </span>
        <div><strong>{{ event.title }}</strong><p v-if="event.detail">{{ event.detail }}</p></div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.execution-panel { margin-bottom: 13px; overflow: hidden; border: 1px solid var(--border); border-radius: 12px; background: var(--bg-subtle); }
.execution-summary { display: flex; align-items: center; width: 100%; min-height: 42px; gap: 9px; padding: 7px 10px; border: 0; background: transparent; text-align: left; cursor: pointer; }
.summary-icon { display: inline-flex; align-items: center; justify-content: center; width: 26px; height: 26px; flex: 0 0 auto; border-radius: 8px; background: var(--brand-soft); color: var(--brand); }
.execution-summary > span:nth-child(2) { display: flex; min-width: 0; flex: 1; flex-direction: column; }.execution-summary strong { overflow: hidden; color: var(--text); font-size: 9px; font-weight: 620; text-overflow: ellipsis; white-space: nowrap; }.execution-summary small { margin-top: 2px; color: var(--text-faint); font-size: 7px; }.execution-summary > svg { color: var(--text-faint); transition: 150ms; }.open .execution-summary > svg:not(.spinning) { transform: rotate(180deg); }
.timeline { padding: 3px 12px 12px 23px; border-top: 1px solid var(--border); }
.timeline-row { position: relative; display: flex; gap: 9px; padding-top: 12px; }.timeline-row:not(:last-child)::after { position: absolute; top: 30px; bottom: -9px; left: 9px; width: 1px; background: var(--border); content: ''; }
.timeline-status { position: relative; z-index: 1; display: inline-flex; align-items: center; justify-content: center; width: 19px; height: 19px; flex: 0 0 auto; border: 1px solid var(--border); border-radius: 50%; background: var(--bg-elevated); color: var(--text-faint); }.timeline-status.success { border-color: color-mix(in srgb,var(--success) 30%,var(--border)); background: var(--success-soft); color: var(--success); }.timeline-status.running { color: var(--brand); }.timeline-status.error,.timeline-status.cancelled { color: var(--danger); }.timeline-status.skipped { color: var(--text-muted); }
.timeline-row > div { min-width: 0; padding-top: 2px; }.timeline-row strong { color: var(--text); font-size: 9px; font-weight: 610; }.timeline-row p { display: -webkit-box; margin: 4px 0 0; overflow: hidden; color: var(--text-faint); font-size: 8px; line-height: 1.5; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.spinning { animation: spin 1s linear infinite; }@keyframes spin{to{transform:rotate(360deg)}}
</style>
