<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { View, Hide, Key, Postcard, Connection } from '@element-plus/icons-vue'
import { login } from '../api/auth'

const router = useRouter()
const route = useRoute()

/** 登录身份选项卡：企业平台先选身份再登录，体现多角色边界 */
const TABS = [
  { key: 'personal', label: '个人客户', placeholder: '手机号 / 用户名' },
  { key: 'corporate', label: '企业客户', placeholder: '企业账号 / 操作员编号' },
  { key: 'staff', label: '内部员工', placeholder: '工号 / 邮箱' },
] as const
type TabKey = (typeof TABS)[number]['key']

const activeTab = ref<TabKey>('personal')
const activePlaceholder = computed(() => TABS.find(t => t.key === activeTab.value)!.placeholder)
const TAB_LABEL: Record<TabKey, string> = { personal: '个人客户', corporate: '企业客户', staff: '内部员工' }

/** 测试账号：归属到身份选项卡，点击填充（密码 Demo@2026） */
const DEMO_PASSWORD = 'Demo@2026'
const testAccounts: { tab: TabKey; username: string; name: string; desc: string }[] = [
  { tab: 'personal', username: 'zhangsan', name: '张三', desc: '零售客户 · 管理员' },
  { tab: 'corporate', username: 'corp001', name: '星辰科技', desc: '对公客户' },
  { tab: 'staff', username: 'staff001', name: '小陈', desc: '内部员工 · 只读' },
]
/** 账号 → 归属身份（演示账号固定映射；未登记账号直接交后端校验） */
const ACCOUNT_TAB: Record<string, TabKey> =
  Object.fromEntries(testAccounts.map(a => [a.username, a.tab]))

const username = ref('')
const password = ref('')
const showPassword = ref(false)
const rememberMe = ref(true)
const loading = ref(false)
const errorMsg = ref('')

/** 当前身份标签对应的测试账号（点击填充并高亮） */
const tabAccount = computed(() => testAccounts.find(a => a.tab === activeTab.value)!)

function fillAccount() {
  username.value = tabAccount.value.username
  password.value = DEMO_PASSWORD
  errorMsg.value = ''
}

async function submit() {
  if (!username.value.trim() || !password.value || loading.value) return
  // 身份边界：演示账号必须在其归属标签下登录，防止"以员工标签登客户账号"的混淆
  const ownerTab = ACCOUNT_TAB[username.value.trim()]
  if (ownerTab && ownerTab !== activeTab.value) {
    errorMsg.value = `该账号属于「${TAB_LABEL[ownerTab]}」身份，请切换到对应标签后登录`
    return
  }
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

/** 未开通能力的占位交互（U盾/证书/SSO/忘记密码） */
function notAvailable(feature: string) {
  ElMessage.info(`${feature}：演示版暂未开通，请使用账号密码登录`)
}
</script>

<template>
  <div class="login-page">
    <!-- 页眉：平台全称 + 环境标识 -->
    <header class="page-header">
      <div class="header-brand">
        <span class="brand-logo">🧠</span>
        <span class="brand-name">智汇银行 · 智汇工作台</span>
      </div>
      <span class="env-tag">生产环境</span>
    </header>

    <!-- 登录卡片 -->
    <main class="login-main">
      <div class="login-card">
        <h1 class="login-title">欢迎回来</h1>
        <p class="login-sub">登录智汇工作台，进入你的专属工作台</p>

        <!-- 登录身份选项卡 -->
        <div class="tab-bar" role="tablist">
          <button
            v-for="t in TABS"
            :key="t.key"
            type="button"
            class="tab-item"
            :class="{ active: activeTab === t.key }"
            role="tab"
            :aria-selected="activeTab === t.key"
            @click="activeTab = t.key; errorMsg = ''"
          >
            {{ t.label }}
          </button>
        </div>

        <form class="login-form" @submit.prevent="submit">
          <label class="field">
            <span class="field-label">{{ TAB_LABEL[activeTab] }}账号</span>
            <input
              v-model="username"
              class="field-input"
              type="text"
              :placeholder="activePlaceholder"
              autocomplete="username"
            >
          </label>

          <label class="field">
            <span class="field-label">密码</span>
            <span class="password-wrap">
              <input
                v-model="password"
                class="field-input"
                :type="showPassword ? 'text' : 'password'"
                placeholder="请输入密码"
                autocomplete="current-password"
              >
              <button
                type="button"
                class="eye-btn"
                :title="showPassword ? '隐藏密码' : '显示密码'"
                @click="showPassword = !showPassword"
              >
                <el-icon><component :is="showPassword ? Hide : View" /></el-icon>
              </button>
            </span>
          </label>

          <div class="form-row">
            <label class="remember">
              <input v-model="rememberMe" type="checkbox" class="checkbox">
              记住我
            </label>
            <button type="button" class="link-btn" @click="notAvailable('忘记密码')">忘记密码?</button>
          </div>

          <div v-if="errorMsg" class="error-tip">{{ errorMsg }}</div>

          <button class="submit-btn" type="submit" :disabled="loading">
            <span v-if="loading" class="spinner" aria-hidden="true"></span>
            {{ loading ? '登录中…' : '登 录' }}
          </button>
        </form>

        <!-- 其他登录方式：银行场景标志位 -->
        <div class="alt-divider"><span>其他登录方式</span></div>
        <div class="alt-list">
          <button type="button" class="alt-btn" @click="notAvailable('U盾登录')">
            <el-icon><Key /></el-icon>U盾登录
          </button>
          <button type="button" class="alt-btn" @click="notAvailable('数字证书登录')">
            <el-icon><Postcard /></el-icon>数字证书
          </button>
          <button type="button" class="alt-btn" @click="notAvailable('SSO 单点登录')">
            <el-icon><Connection /></el-icon>SSO 单点登录
          </button>
        </div>

        <!-- 测试账号：跟随身份选项卡变化 -->
        <div class="demo-area">
          <div class="demo-caption">测试账号（密码 {{ DEMO_PASSWORD }}，点击填充）</div>
          <button
            type="button"
            class="demo-chip"
            :class="{ filled: username === tabAccount.username }"
            @click="fillAccount"
          >
            <span class="demo-name">{{ tabAccount.name }}</span>
            <span class="demo-desc">{{ tabAccount.desc }}</span>
          </button>
        </div>
      </div>
    </main>

    <!-- 页脚：企业级标配 -->
    <footer class="page-footer">
      © 2026 智汇银行 · 智汇工作台 v1.0.0 · 京ICP备20260088号
    </footer>
  </div>
</template>

<style scoped>
/* 页面骨架：页眉在上、卡片居中、页脚贴底；蓝灰渐变加一点品牌感 */
.login-page {
  min-height: calc(100vh - 48px);
  display: flex;
  flex-direction: column;
  background:
    radial-gradient(900px 420px at 85% -10%, #dde9f7 0%, transparent 60%),
    radial-gradient(700px 380px at 0% 110%, #e6eef8 0%, transparent 55%),
    linear-gradient(180deg, #f7f9fc 0%, #eef2f7 100%);
}

/* 页眉 */
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 28px 0;
}
.header-brand {
  display: flex;
  align-items: center;
  gap: 9px;
}
.brand-logo { font-size: 26px; }
.brand-name {
  font-size: 17px;
  font-weight: 700;
  color: #12263f;
  letter-spacing: 0.5px;
}
.env-tag {
  font-size: 12px;
  color: #0a8f3c;
  background: #e8f7ee;
  border: 1px solid #b7e2c6;
  border-radius: 4px;
  padding: 2px 8px;
  font-weight: 600;
}

/* 登录卡片 */
.login-main {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 28px 16px;
}
.login-card {
  width: 440px;
  background: #fff;
  border: 1px solid #e5e8ec;
  border-radius: 6px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.06);
  padding: 30px 34px 24px;
}

.login-title {
  margin: 0 0 4px;
  font-size: 21px;
  color: #12263f;
}
.login-sub {
  margin: 0 0 18px;
  font-size: 13px;
  color: #8a97a8;
}

/* 身份选项卡：分段式，激活项深蓝 */
.tab-bar {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  overflow: hidden;
  margin-bottom: 18px;
}
.tab-item {
  padding: 9px 0;
  font-size: 13px;
  background: #fff;
  color: #5a6b80;
  border: none;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}
.tab-item + .tab-item {
  border-left: 1px solid #e5e8ec;
}
.tab-item:hover { background: #f2f7fc; }
.tab-item.active {
  background: #0b4f9e;
  color: #fff;
  font-weight: 600;
}

/* 表单 */
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
/* 输入框：白底 + 浅灰描边 + 4px 圆角，聚焦深蓝 + 浅蓝外发光 */
.field-input {
  height: 40px;
  width: 100%;
  box-sizing: border-box;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  padding: 0 12px;
  font-size: 14px;
  color: #12263f;
  background: #fff;
  outline: none;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.field-input::placeholder { color: #b3bec9; }
.field-input:focus {
  border-color: #0b4f9e;
  box-shadow: 0 0 0 3px rgba(11, 79, 158, 0.12);
}

/* 密码框 + 眼睛切换 */
.password-wrap {
  position: relative;
  display: block;
}
.password-wrap .field-input {
  padding-right: 40px;
}
.eye-btn {
  position: absolute;
  right: 4px;
  top: 50%;
  transform: translateY(-50%);
  width: 32px;
  height: 32px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  color: #98a2b0;
  cursor: pointer;
  border-radius: 4px;
}
.eye-btn:hover { color: #0b4f9e; }

/* 记住我 / 忘记密码 */
.form-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: -2px;
}
.remember {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  color: #5a6b80;
  cursor: pointer;
  user-select: none;
}
.checkbox {
  width: 14px;
  height: 14px;
  accent-color: #0b4f9e;
  cursor: pointer;
}
.link-btn {
  border: none;
  background: transparent;
  color: #0b4f9e;
  font-size: 12.5px;
  cursor: pointer;
  padding: 0;
}
.link-btn:hover { text-decoration: underline; }

.error-tip {
  font-size: 12.5px;
  color: #c0392b;
  background: #fdeeec;
  border: 1px solid #f5c6c0;
  border-radius: 4px;
  padding: 7px 10px;
}

/* 登录按钮 + loading 态 */
.submit-btn {
  height: 42px;
  margin-top: 4px;
  border: none;
  border-radius: 4px;
  background: #0b4f9e;
  color: #fff;
  font-size: 14.5px;
  font-weight: 600;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  transition: background 0.15s;
}
.submit-btn:hover { background: #09417f; }
.submit-btn:disabled { opacity: 0.65; cursor: not-allowed; }
.spinner {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255, 255, 255, 0.35);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}

/* 其他登录方式 */
.alt-divider {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 20px 0 12px;
  color: #98a2b0;
  font-size: 12px;
}
.alt-divider::before,
.alt-divider::after {
  content: '';
  flex: 1;
  height: 1px;
  background: #e5e8ec;
}
.alt-list {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}
.alt-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  height: 34px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  background: #fff;
  color: #4a5a6d;
  font-size: 12.5px;
  cursor: pointer;
  transition: border-color 0.15s, color 0.15s, background 0.15s;
}
.alt-btn:hover {
  border-color: #0b4f9e;
  color: #0b4f9e;
  background: #f7fafd;
}

/* 测试账号：跟随选项卡，单卡填充 + 选中高亮 */
.demo-area {
  margin-top: 18px;
  border-top: 1px dashed #e0e6ee;
  padding-top: 12px;
}
.demo-caption {
  font-size: 12px;
  color: #98a2b0;
  margin-bottom: 8px;
}
.demo-chip {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  border: 1px solid #e5e8ec;
  background: #fafcfe;
  border-radius: 4px;
  padding: 8px 12px;
  cursor: pointer;
  font-size: 12.5px;
  text-align: left;
  transition: border-color 0.15s, background 0.15s;
}
.demo-chip:hover {
  border-color: #9fbcd9;
  background: #f2f7fc;
}
.demo-chip.filled {
  border-color: #0b4f9e;
  background: #eef4fb;
}
.demo-name {
  font-weight: 700;
  color: #12263f;
  min-width: 56px;
}
.demo-desc { color: #8a97a8; }

/* 页脚 */
.page-footer {
  text-align: center;
  font-size: 12px;
  color: #98a2b0;
  padding: 14px 0 18px;
}
</style>
