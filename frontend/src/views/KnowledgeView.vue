<script setup lang="ts">
import ChatWindow from '../components/ChatWindow.vue'
</script>

<template>
  <ChatWindow
    base-path="/knowledge"
    agent="knowledge"
    title="📚 个人知识库（Phase 2 占位）"
    welcome="我是知识库助手，目前还无法检索你的个人知识库（Phase 2 接入多源检索后可用），暂时只能基于通用知识回答。"
    placeholder="Phase 2 后：在我的笔记里搜 LangGraph 路由"
  />
</template>
