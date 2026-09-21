<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { getMemoryId, newMemoryId, streamChat } from '../api/chat'

const props = withDefaults(defineProps<{
  /** 后端代理前缀：/bank | /knowledge | /interview */
  basePath: string
  /** Agent 标识（与后端 AgentSpec.name 一致） */
  agent: string
  title: string
  welcome: string
  placeholder?: string
}>(), {
  placeholder: '输入消息，回车发送…',
})

interface Msg {
  role: 'user' | 'assistant'
  content: string
}

const messages = ref<Msg[]>([])
const input = ref('')
const streaming = ref(false)
const listEl = ref<HTMLElement | null>(null)
let memoryId = ''
let controller: AbortController | null = null

onMounted(() => {
  memoryId = getMemoryId(props.agent)
  messages.value.push({ role: 'assistant', content: props.welcome })
})

onBeforeUnmount(() => controller?.abort())

async function send() {
  const message = input.value.trim()
  if (!message || streaming.value) return
  input.value = ''
  messages.value.push({ role: 'user', content: message })

  // 从响应式数组里取代理对象，流式追加才会触发视图更新
  messages.value.push({ role: 'assistant', content: '' })
  const reply = messages.value[messages.value.length - 1]

  streaming.value = true
  scrollBottom()
  controller = new AbortController()
  try {
    await streamChat({
      basePath: props.basePath,
      agent: props.agent,
      memoryId,
      message,
      signal: controller.signal,
      onDelta: (text) => {
        reply.content += text
        scrollBottom()
      },
      onError: (msg) => {
        reply.content += `\n[错误] ${msg}`
      },
    })
  } catch (e) {
    reply.content += `\n[连接失败] ${e instanceof Error ? e.message : String(e)}`
  } finally {
    streaming.value = false
    controller = null
    scrollBottom()
  }
}

/** 新建会话：换 memoryId 并清空界面 */
function reset() {
  controller?.abort()
  memoryId = newMemoryId(props.agent)
  messages.value = [{ role: 'assistant', content: props.welcome }]
}

function scrollBottom() {
  nextTick(() => listEl.value?.scrollTo({ top: listEl.value.scrollHeight }))
}
</script>

<template>
  <section class="chat">
    <div class="chat-head">
      <h2>{{ title }}</h2>
      <button class="ghost" :disabled="streaming" @click="reset">新建会话</button>
    </div>
    <div ref="listEl" class="chat-body">
      <div
        v-for="(m, i) in messages"
        :key="i"
        class="msg"
        :class="m.role"
      >{{ m.content }}<template
          v-if="streaming && m.role === 'assistant' && i === messages.length - 1"
        ><span v-if="!m.content" class="thinking">思考中…</span><span
            v-else
            class="caret"
          >▍</span></template></div>
    </div>
    <div class="chat-input">
      <input
        v-model="input"
        :placeholder="placeholder"
        :disabled="streaming"
        @keydown.enter="send"
      >
      <button :disabled="streaming" @click="send">
        {{ streaming ? '回复中…' : '发送' }}
      </button>
    </div>
  </section>
</template>

<style scoped>
.chat {
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  overflow: hidden;
}

.chat-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border);
}

.chat-head h2 {
  margin: 0;
  font-size: 16px;
}

.chat-body {
  height: 62vh;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.msg {
  max-width: 85%;
  padding: 10px 14px;
  border-radius: 10px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
}

.msg.user {
  align-self: flex-end;
  background: var(--accent);
  color: #fff;
}

.msg.assistant {
  align-self: flex-start;
  background: var(--bg);
  border: 1px solid var(--border);
}

.caret {
  animation: blink 0.9s steps(1) infinite;
  color: var(--accent);
}

/* GLM 是推理模型：思考阶段没有 delta，先给占位避免看起来像卡死 */
.thinking {
  color: var(--accent);
  animation: pulse 1.2s ease-in-out infinite;
}

@keyframes pulse {
  50% {
    opacity: 0.35;
  }
}

@keyframes blink {
  50% {
    opacity: 0;
  }
}

.chat-input {
  display: flex;
  gap: 8px;
  padding: 12px 16px;
  border-top: 1px solid var(--border);
}

.chat-input input {
  flex: 1;
}
</style>
