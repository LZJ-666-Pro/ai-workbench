<script setup lang="ts">
import ChatWindow from '../components/ChatWindow.vue'
</script>

<template>
  <ChatWindow
    base-path="/bank"
    agent="bank"
    title="🏦 银行助手「小银」"
    welcome="你好，我是银行助手小银。可以试试：查一下张三的余额，或看看 62220002 的最近交易。"
    placeholder="例如：查一下张三的余额"
  />
</template>
