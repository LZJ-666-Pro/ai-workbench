<script setup lang="ts">
</script>

<template>
  <section>
    <h1>三个 Agent 应用，一套底座</h1>
    <p class="desc">
      platform-core 提供 LLM 接入、SSE 流式、会话记忆与工具框架；
      三个应用只是不同的 AgentSpec 声明。
    </p>
    <div class="cards">
      <RouterLink to="/bank" class="card">
        <div class="icon">🏦</div>
        <h3>银行助手「小银」</h3>
        <p>交易型 Agent：工具调用 + 流式对话。<br>Phase 1 加入转账确认卡片、幂等与审计。</p>
        <span class="badge ok">Phase 0 可演示</span>
      </RouterLink>
      <RouterLink to="/knowledge" class="card">
        <div class="icon">📚</div>
        <h3>个人知识库</h3>
        <p>检索型 Agent：多源路由 + RAG + 引用溯源。<br>Phase 2 接入真实数据源与混合检索。</p>
        <span class="badge">Phase 2 占位</span>
      </RouterLink>
      <RouterLink to="/interview" class="card">
        <div class="icon">🎤</div>
        <h3>面试模拟器</h3>
        <p>流程型 Agent：结构化评分与评估报告。<br>Phase 3 加入简历 RAG。</p>
        <span class="badge mid">可对话</span>
      </RouterLink>
    </div>
  </section>
</template>

<style scoped>
h1 {
  font-size: 22px;
  margin-bottom: 4px;
}

.desc {
  color: var(--text-dim);
  margin-top: 0;
}

.cards {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  margin-top: 24px;
}

.card {
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 20px;
  text-decoration: none;
  color: var(--text);
  transition: box-shadow 0.15s, transform 0.15s;
}

.card:hover {
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08);
  transform: translateY(-2px);
}

.icon {
  font-size: 28px;
}

.card h3 {
  margin: 10px 0 6px;
}

.card p {
  color: var(--text-dim);
  line-height: 1.7;
  min-height: 58px;
}

.badge {
  display: inline-block;
  margin-top: 6px;
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 12px;
  background: var(--bg);
  border: 1px solid var(--border);
  color: var(--text-dim);
}

.badge.ok {
  background: #e8f7ee;
  border-color: #b7e2c6;
  color: #1a7f37;
}

.badge.mid {
  background: var(--accent-weak);
  border-color: #c9d4ff;
  color: var(--accent);
}

@media (max-width: 720px) {
  .cards {
    grid-template-columns: 1fr;
  }
}
</style>
