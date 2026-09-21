<script setup lang="ts">
import ChatWindow from '../components/ChatWindow.vue'
</script>

<template>
  <ChatWindow
    base-path="/interview"
    agent="interview"
    title="🎤 模拟面试（Java 后端）"
    welcome="你好，我是今天的面试官。请先简单做个自我介绍，我们开始吧。（面试结束请输入：结束面试）"
    placeholder="回答上一个问题，或输入：开始面试"
  />
</template>
