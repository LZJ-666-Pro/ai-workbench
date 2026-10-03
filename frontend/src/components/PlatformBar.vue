<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { auth } from '../api/auth'

/** 工作台/应用中心共用的底部平台状态栏：运行状态点 + 平台指标 + 快捷链接 */
const props = defineProps<{
  platform: {
    apps: number
    eventsToday: number
    successRate: number
    sessionsToday: number
    updatedAt: string
  }
  runningCount: number
}>()

const router = useRouter()
const healthy = computed(() => props.platform.successRate >= 99)
</script>

<template>
  <div class="platform-bar">
    <div class="bar-inner">
      <div class="pb-metrics">
        <span class="pb-health" :class="healthy ? 'ok' : 'warn'">
          <i class="dot" />平台运行状态: <b>{{ healthy ? '正常' : '降级' }}</b>
        </span>
        <span class="pb-item">总应用数: <b>{{ platform.apps }}</b></span>
        <span class="pb-item">运行中应用: <b>{{ runningCount }}</b></span>
        <span class="pb-item">今日总会话: <b>{{ platform.sessionsToday.toLocaleString() }}</b></span>
        <span class="pb-item">今日工具调用: <b>{{ platform.eventsToday.toLocaleString() }}</b></span>
        <el-tooltip content="按今日平台事件的成功率计算" placement="top">
          <span class="pb-item rate">平均成功率: <b>{{ platform.successRate }}%</b></span>
        </el-tooltip>
      </div>
      <div class="pb-links">
        <span class="pb-updated">数据更新于 {{ platform.updatedAt }}</span>
        <el-button size="small" class="outline-btn" @click="router.push('/developers')">开发者文档</el-button>
        <el-button v-if="auth.user?.platformRole === 'ADMIN'" size="small" class="outline-btn" @click="router.push('/logs')">查看日志</el-button>
        <el-button v-if="auth.user?.platformRole === 'ADMIN'" size="small" class="outline-btn" @click="router.push('/admin/dashboard')">平台监控</el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.platform-bar {
  background: #fff;
  border-top: 1px solid #e4e7ec;
  padding: 12px 0;
}
.bar-inner {
  max-width: 1600px;
  margin: 0 auto;
  padding: 0 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.pb-metrics {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px 26px;
  color: #7a8798;
  font-size: 12.5px;
}
.pb-item b {
  color: #12263f;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  margin-left: 4px;
}
/* 运行状态点（绿色正常/橙色降级）与绿色成功率 */
.pb-health {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
}
.pb-health .dot { width: 7px; height: 7px; border-radius: 50%; }
.pb-health.ok { color: #0a8f3c; }
.pb-health.ok .dot { background: #0a8f3c; box-shadow: 0 0 0 3px rgba(10, 143, 60, 0.15); }
.pb-health.warn { color: #d97706; }
.pb-health.warn .dot { background: #d97706; box-shadow: 0 0 0 3px rgba(217, 119, 6, 0.15); }
.pb-item.rate b { color: #0a8f3c; }
.pb-links {
  display: flex;
  align-items: center;
  gap: 8px;
}
.pb-updated {
  font-size: 11.5px;
  color: #98a2b0;
  margin-right: 4px;
  font-variant-numeric: tabular-nums;
}
.outline-btn {
  background: #fff;
  border-color: #b9cee3;
  color: #0b4f9e;
}
.outline-btn:hover {
  background: #f2f7fc;
  border-color: #0b4f9e;
  color: #0b4f9e;
}
</style>
