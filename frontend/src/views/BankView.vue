<script setup lang="ts">
import ChatWindow from '../components/ChatWindow.vue'
</script>

<template>
  <ChatWindow
    base-path="/bank"
    agent="bank"
    title="银行助手「小银」"
    bot-name="小银"
    bot-tagline="你的智能银行管家：查余额、看交易、办转账，直接说就行"
    welcome="你好，我是银行助手小银。可以试试：查一下张三的余额，或看看 62220002 的最近交易。"
    placeholder="给小银发消息，例如：查一下张三的余额"
    :suggestions="[
      { icon: '💰', label: '查张三的余额' },
      { icon: '📋', label: '看最近的交易' },
      { icon: '💸', label: '给李四转 200' },
      { icon: '🧾', label: '查王五的明细' },
    ]"
  />
</template>
