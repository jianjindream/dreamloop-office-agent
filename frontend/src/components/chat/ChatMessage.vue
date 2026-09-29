<script setup lang="ts">
import { Check, Copy, FilePlus2, RefreshCw, Sparkles, Square, ThumbsDown, ThumbsUp, UserRound } from 'lucide-vue-next'
import { ref } from 'vue'
import type { ChatMessage } from '@/stores/chat'
import ExecutionTimeline from './ExecutionTimeline.vue'
import MarkdownContent from './MarkdownContent.vue'
import SourceList from './SourceList.vue'

const props = defineProps<{ message: ChatMessage }>()
const emit = defineEmits<{ retry: [messageId: string]; toDocument: [messageId: string] }>()
const copied = ref(false)

const copyMessage = async () => {
  await navigator.clipboard.writeText(props.message.content)
  copied.value = true
  window.setTimeout(() => (copied.value = false), 1200)
}
</script>

<template>
  <article class="message" :class="[message.role, message.status]">
    <div class="message-avatar">
      <UserRound v-if="message.role === 'user'" :size="15" />
      <Sparkles v-else :size="16" />
    </div>
    <div class="message-body">
      <div class="message-heading"><strong>{{ message.role === 'user' ? '你' : 'DreamLoop' }}</strong><span>{{ new Date(message.createdAt).toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit'}) }}</span></div>
      <div v-if="message.role === 'user'" class="user-bubble">{{ message.content }}</div>
      <div v-else class="assistant-content">
        <ExecutionTimeline :events="message.execution" :mode="message.mode" :streaming="message.status === 'streaming'" />
        <div v-if="message.extractedInfo" class="memory-note"><Check :size="13" />{{ message.extractedInfo }}</div>
        <div v-if="message.status === 'streaming' && !message.content" class="thinking-line"><i></i><i></i><i></i><span>正在组织答案</span></div>
        <div v-else-if="message.status === 'error'" class="error-box"><strong>生成失败</strong><span>{{ message.error || '服务暂时不可用，请稍后重试。' }}</span><button type="button" @click="emit('retry', message.id)"><RefreshCw :size="13" />重新生成</button></div>
        <MarkdownContent v-else-if="message.content" :content="message.content" :streaming="message.status === 'streaming'" />
        <div v-if="message.status === 'stopped'" class="stopped-note"><Square :size="10" />已停止生成</div>
        <SourceList :sources="message.sources" />
        <div v-if="message.status === 'complete'" class="message-actions">
          <button type="button" :aria-label="copied ? '已复制' : '复制回答'" @click="copyMessage"><Check v-if="copied" :size="14" /><Copy v-else :size="14" /><span>{{ copied ? '已复制' : '复制' }}</span></button>
          <button type="button" aria-label="将回答转为文档" @click="emit('toDocument', message.id)"><FilePlus2 :size="14" /><span>转为文档</span></button>
          <button type="button" aria-label="重新生成" @click="emit('retry', message.id)"><RefreshCw :size="14" /></button>
          <span></span><button type="button" aria-label="有帮助"><ThumbsUp :size="14" /></button><button type="button" aria-label="没有帮助"><ThumbsDown :size="14" /></button>
        </div>
      </div>
    </div>
  </article>
</template>

<style scoped>
.message { display: flex; width: min(820px,100%); gap: 11px; margin: 0 auto; padding: 20px 24px; }.message-avatar { display: inline-flex; align-items: center; justify-content: center; width: 30px; height: 30px; flex: 0 0 auto; border: 1px solid var(--border); border-radius: 10px; background: var(--bg-elevated); color: var(--text-muted); box-shadow: var(--shadow-sm); }.assistant .message-avatar { border-color: color-mix(in srgb,var(--brand) 20%,var(--border)); background: var(--brand-soft); color: var(--brand); }.message-body { min-width: 0; flex: 1; }.message-heading { display: flex; align-items: center; gap: 7px; min-height: 24px; }.message-heading strong { color: var(--text-strong); font-size: 10px; font-weight: 650; }.message-heading span { color: var(--text-faint); font-size: 8px; }.user-bubble { display: inline-block; max-width: min(650px,100%); margin-top: 5px; padding: 10px 13px; border-radius: 4px 13px 13px 13px; background: var(--bg-elevated); color: var(--text); font-size: 12px; line-height: 1.7; box-shadow: var(--shadow-sm); white-space: pre-wrap; }.assistant-content { margin-top: 7px; }.memory-note { display: flex; align-items: center; gap: 6px; margin-bottom: 10px; padding: 7px 9px; border-radius: 8px; background: var(--success-soft); color: var(--success); font-size: 8px; }
.thinking-line { display: flex; align-items: center; gap: 4px; height: 30px; color: var(--text-faint); font-size: 9px; }.thinking-line i { width: 5px; height: 5px; border-radius: 50%; background: var(--brand); animation: bounce 1.2s infinite; }.thinking-line i:nth-child(2){animation-delay:.15s}.thinking-line i:nth-child(3){animation-delay:.3s}.thinking-line span{margin-left:5px}.error-box { display: flex; align-items: flex-start; padding: 11px; flex-direction: column; border: 1px solid color-mix(in srgb,var(--danger) 24%,var(--border)); border-radius: 10px; background: color-mix(in srgb,var(--danger) 5%,var(--bg-elevated)); }.error-box strong { color: var(--danger); font-size: 10px; }.error-box span { margin-top: 4px; color: var(--text-muted); font-size: 9px; }.error-box button { display: inline-flex; align-items: center; gap: 5px; margin-top: 9px; padding: 5px 8px; border: 1px solid var(--border); border-radius: 7px; background: var(--bg-elevated); color: var(--text); font-size: 8px; cursor: pointer; }.stopped-note { display: flex; align-items: center; gap: 5px; margin-top: 9px; color: var(--text-faint); font-size: 8px; }
.message-actions { display: flex; align-items: center; gap: 3px; margin-top: 10px; opacity: 0; transition: 150ms; }.message:hover .message-actions { opacity: 1; }.message-actions button { display: inline-flex; align-items: center; justify-content: center; min-width: 27px; height: 27px; gap: 5px; padding: 0 6px; border: 0; border-radius: 7px; background: transparent; color: var(--text-faint); font-size: 8px; cursor: pointer; }.message-actions button:hover { background: var(--bg-hover); color: var(--text); }.message-actions > span { flex: 1; }
@keyframes bounce{0%,60%,100%{transform:translateY(0);opacity:.35}30%{transform:translateY(-4px);opacity:1}}
@media(max-width:700px){.message{padding:16px 15px}.message-actions{opacity:1}.message-avatar{width:28px;height:28px}}
</style>
