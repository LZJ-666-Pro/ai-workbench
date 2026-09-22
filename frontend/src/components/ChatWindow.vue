<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { getMemoryId, newMemoryId, respondTransferConfirm, streamChat } from '../api/chat'
import type { ConfirmRequestData } from '../api/chat'

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

interface CardMsg extends ConfirmRequestData {
  status: 'pending' | 'processing' | 'confirmed' | 'cancelled'
}

interface Msg {
  role: 'user' | 'assistant'
  kind: 'text' | 'card'
  content: string
  card?: CardMsg
}

const messages = ref<Msg[]>([])
const input = ref('')
const streaming = ref(false)
const listEl = ref<HTMLElement | null>(null)
let memoryId = ''
let controller: AbortController | null = null

onMounted(() => {
  memoryId = getMemoryId(props.agent)
  messages.value.push({ role: 'assistant', kind: 'text', content: props.welcome })
})

onBeforeUnmount(() => controller?.abort())

async function send() {
  const message = input.value.trim()
  if (!message || streaming.value) return
  input.value = ''
  messages.value.push({ role: 'user', kind: 'text', content: message })

  // 从响应式数组里取代理对象，流式追加才会触发视图更新
  messages.value.push({ role: 'assistant', kind: 'text', content: '' })
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
      onConfirmRequest: (card) => {
        // 接口数据不含 status，入列时初始化为待确认
        messages.value.push({
          role: 'assistant',
          kind: 'card',
          content: '',
          card: { ...card, status: 'pending' },
        })
        scrollBottom()
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

/** 确认卡片操作：调后端确认接口，结果以普通消息回显 */
async function respondCard(card: CardMsg, action: 'confirm' | 'cancel') {
  if (card.status !== 'pending') return
  card.status = 'processing'
  try {
    const res = await respondTransferConfirm(props.basePath, memoryId, card.confirmId, action)
    card.status = action === 'confirm' ? 'confirmed' : 'cancelled'
    messages.value.push({ role: 'assistant', kind: 'text', content: res.message })
  } catch (e) {
    card.status = 'pending'
    messages.value.push({
      role: 'assistant',
      kind: 'text',
      content: `[确认失败] ${e instanceof Error ? e.message : String(e)}`,
    })
  }
  scrollBottom()
}

/** 新建会话：换 memoryId 并清空界面 */
function reset() {
  controller?.abort()
  memoryId = newMemoryId(props.agent)
  messages.value = [{ role: 'assistant', kind: 'text', content: props.welcome }]
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
        :class="[m.role, { card: m.kind === 'card' }]"
      >
        <template v-if="m.kind === 'card' && m.card">
          <div class="card-title">💸 待确认转账</div>
          <div class="card-row">收款：{{ m.card.toOwner }}（{{ m.card.toAccount }}）</div>
          <div class="card-row">金额：<b>¥{{ m.card.amount.toFixed(2) }}</b></div>
          <div v-if="m.card.reason" class="card-row">附言：{{ m.card.reason }}</div>
          <div class="card-row">付款：{{ m.card.fromAccount }}</div>
          <div v-if="m.card.status === 'pending'" class="card-actions">
            <button class="primary" @click="respondCard(m.card, 'confirm')">确认转账</button>
            <button class="ghost" @click="respondCard(m.card, 'cancel')">取消</button>
          </div>
          <div v-else-if="m.card.status === 'processing'" class="card-status">处理中…</div>
          <div v-else-if="m.card.status === 'confirmed'" class="card-status ok">✅ 已确认</div>
          <div v-else class="card-status">🚫 已取消</div>
        </template>
        <template v-else>
          {{ m.content }}<template
            v-if="streaming && m.role === 'assistant' && i === messages.length - 1"
          ><span v-if="!m.content" class="thinking">思考中…</span><span
              v-else
              class="caret"
            >▍</span></template>
        </template>
      </div>
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

/* 确认卡片：比普通消息更醒目 */
.msg.card {
  min-width: 260px;
  border: 1px solid var(--accent);
  background: var(--bg);
}

.card-title {
  font-weight: 600;
  margin-bottom: 6px;
}

.card-row {
  font-size: 14px;
}

.card-actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}

.card-actions .primary {
  background: var(--accent);
  color: #fff;
}

.card-status {
  margin-top: 10px;
  font-size: 14px;
}

.card-status.ok {
  color: var(--accent);
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
