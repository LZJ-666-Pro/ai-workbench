<script setup lang="ts">
import { auth, roleLabel } from '../api/auth'

/** 工作台/应用中心共用的顶部上下文条：左侧租户与角色，右侧平台能力指标（数值上/标签下，竖线分隔） */
defineProps<{
  hero: { models: number; sseP95Ms: number | null; memorySegments: number; tools: number }
}>()
</script>

<template>
  <div class="context-bar">
    <div class="bar-inner">
      <div class="ctx-left">
        <span class="ctx-org">智汇银行（总行）</span>
        <span class="ctx-sep">·</span>
        <span class="ctx-role">{{ roleLabel(auth.user?.platformRole) }}</span>
        <span class="env-tag">生产环境</span>
      </div>
      <div class="ctx-caps">
        <div class="cap"><b>{{ hero.models }}</b><span>LLM 模型</span></div>
        <div class="cap"><b>{{ hero.sseP95Ms == null ? '—' : hero.sseP95Ms + 'ms' }}</b><span>SSE P95</span></div>
        <div class="cap"><b>{{ hero.memorySegments.toLocaleString() }}</b><span>记忆段</span></div>
        <div class="cap"><b>{{ hero.tools }}</b><span>业务工具</span></div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.context-bar {
  background: #fff;
  border-bottom: 1px solid #e4e7ec;
  padding: 10px 0;
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
.ctx-left {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #1f2d3d;
}
.ctx-org { font-weight: 600; }
.ctx-sep { color: #c8ced8; }
.ctx-role { color: #5a6b80; }
.env-tag {
  font-size: 11.5px;
  color: #0a8f3c;
  background: #e8f7ee;
  border: 1px solid #b7e2c6;
  border-radius: 4px;
  padding: 2px 8px;
  font-weight: 600;
}

/* 能力组：数值粗体在上、标签小字在下，组间竖线分隔（无底色框） */
.ctx-caps {
  display: inline-flex;
  align-items: stretch;
  flex-wrap: wrap;
}
.cap {
  display: inline-flex;
  flex-direction: column;
  justify-content: center;
  padding: 2px 18px;
  border-left: 1px solid #e8ecf1;
  line-height: 1.3;
  white-space: nowrap;
}
.cap:first-child { border-left: 0; padding-left: 0; }
.cap:last-child { padding-right: 0; }
.cap b {
  color: #1f2d3d;
  font-size: 14px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.cap span {
  color: #98a2b0;
  font-size: 11.5px;
  margin-top: 1px;
}
</style>
