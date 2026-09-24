<script setup lang="ts">
import ChatWindow from '../components/ChatWindow.vue'
import { auth } from '../api/auth'
</script>

<template>
  <ChatWindow
    base-path="/bank"
    agent="bank"
    theme="theme-bank"
    enable-identity
    :identity="auth.user?.identityId"
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
    :business-functions="[
      { icon: '🏦', label: '账户总览', prompt: '帮我看看我所有账户的余额情况' },
      { icon: '📋', label: '交易明细', prompt: '看看我最近的交易明细' },
      { icon: '💸', label: '转账汇款', prompt: '我想办理转账汇款，请告诉我需要提供哪些信息' },
      { icon: '⏳', label: '待审批', prompt: '查一下有没有待我确认的转账订单' },
      { icon: '🧾', label: '电子回单', prompt: '帮我生成最近一笔交易的电子回单' },
      { icon: '📦', label: '批量付款', disabled: true },
      { icon: '💰', label: '投资理财', disabled: true },
      { icon: '🌏', label: '国际业务', disabled: true },
      { icon: '🏛', label: '融资管理', disabled: true },
    ]"
  />
</template>
