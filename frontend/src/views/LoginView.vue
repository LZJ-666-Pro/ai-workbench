<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { login } from '../api/auth'

const router = useRouter()
const route = useRoute()

const username = ref('')
const password = ref('')
const loading = ref(false)
const errorMsg = ref('')

/** 演示账号：点击快速填充（密码统一 123456） */
const demoAccounts = [
  { username: 'zhangsan', name: '张三', desc: '零售客户 · 管理员' },
  { username: 'staff001', name: '小陈', desc: '内部员工（只读）' },
  { username: 'corp001', name: '星辰科技', desc: '对公客户' },
]

async function submit() {
  if (!username.value.trim() || !password.value || loading.value) return
  loading.value = true
  errorMsg.value = ''
  try {
    await login(username.value.trim(), password.value)
    // 支持登录前被拦截的原始目标，如 /admin/dashboard
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    router.push(redirect)
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '登录失败，请稍后再试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="brand-row">
        <span class="brand-logo">🧠</span>
        <span class="brand-name">智汇工作台</span>
      </div>
      <h1 class="login-title">登录</h1>
      <p class="login-sub">登录后进入你的专属 AI 工作台</p>

      <form class="login-form" @submit.prevent="submit">
        <label class="field">
          <span class="field-label">用户名</span>
          <input
            v-model="username"
            class="field-input"
            type="text"
            placeholder="如 zhangsan"
            autocomplete="username"
          >
        </label>
        <label class="field">
          <span class="field-label">密码</span>
          <input
            v-model="password"
            class="field-input"
            type="password"
            placeholder="请输入密码"
            autocomplete="current-password"
          >
        </label>

        <div v-if="errorMsg" class="error-tip">{{ errorMsg }}</div>

        <button class="submit-btn" type="submit" :disabled="loading">
          {{ loading ? '登录中…' : '登 录' }}
        </button>
      </form>

      <div class="demo-area">
        <div class="demo-caption">演示账号（密码 123456，点击填充）</div>
        <div class="demo-list">
          <button
            v-for="a in demoAccounts"
            :key="a.username"
            type="button"
            class="demo-chip"
            @click="username = a.username; password = '123456'"
          >
            <span class="demo-name">{{ a.name }}</span>
            <span class="demo-desc">{{ a.desc }}</span>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: calc(100vh - 48px);
  display: flex;
  align-items: center;
  justify-content: center;
  background:
    radial-gradient(1000px 500px at 80% -10%, #dbe9f8 0%, transparent 60%),
    radial-gradient(800px 400px at 0% 110%, #e8f1fa 0%, transparent 55%),
    #f3f7fc;
}

.login-card {
  width: 400px;
  background: #fff;
  border: 1px solid #e5e8ec;
  border-radius: 10px;
  box-shadow: 0 8px 30px rgba(18, 38, 63, 0.08);
  padding: 36px 36px 28px;
}

.brand-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 26px;
}
.brand-logo { font-size: 22px; }
.brand-name {
  font-size: 15px;
  font-weight: 700;
  color: #12263f;
  letter-spacing: 0.5px;
}

.login-title {
  margin: 0 0 4px;
  font-size: 22px;
  color: #12263f;
}
.login-sub {
  margin: 0 0 22px;
  font-size: 13px;
  color: #8a97a8;
}

.login-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.field-label {
  font-size: 12.5px;
  color: #5a6b80;
  font-weight: 600;
}
.field-input {
  height: 40px;
  border: 1px solid #d5dde7;
  border-radius: 6px;
  padding: 0 12px;
  font-size: 14px;
  color: #12263f;
  outline: none;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.field-input:focus {
  border-color: #0b4f9e;
  box-shadow: 0 0 0 3px rgba(11, 79, 158, 0.12);
}

.error-tip {
  font-size: 12.5px;
  color: #c0392b;
  background: #fdeeec;
  border: 1px solid #f5c6c0;
  border-radius: 4px;
  padding: 7px 10px;
}

.submit-btn {
  height: 42px;
  margin-top: 4px;
  border: none;
  border-radius: 6px;
  background: #0b4f9e;
  color: #fff;
  font-size: 14.5px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s;
}
.submit-btn:hover { background: #09417f; }
.submit-btn:disabled { opacity: 0.6; cursor: not-allowed; }

.demo-area {
  margin-top: 22px;
  border-top: 1px dashed #e0e6ee;
  padding-top: 14px;
}
.demo-caption {
  font-size: 12px;
  color: #98a2b0;
  margin-bottom: 8px;
}
.demo-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.demo-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  border: 1px solid #e5e8ec;
  background: #fafcfe;
  border-radius: 6px;
  padding: 7px 10px;
  cursor: pointer;
  font-size: 12.5px;
  transition: border-color 0.15s, background 0.15s;
  text-align: left;
}
.demo-chip:hover {
  border-color: #9fbcd9;
  background: #f2f7fc;
}
.demo-name {
  font-weight: 700;
  color: #12263f;
  min-width: 56px;
}
.demo-desc { color: #8a97a8; }
</style>
