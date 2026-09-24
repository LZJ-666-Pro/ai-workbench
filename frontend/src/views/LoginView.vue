<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  View, Hide, Key, Postcard, Connection,
  User, Lock, ChatDotRound, Document, Operation,
} from '@element-plus/icons-vue'
import { login } from '../api/auth'

const router = useRouter()
const route = useRoute()

/** 登录身份选项卡：企业平台先选身份再登录，体现多角色边界 */
const TABS = [
  { key: 'personal', label: '个人用户', placeholder: '手机号 / 用户名' },
  { key: 'corporate', label: '企业用户', placeholder: '企业账号 / 操作员编号' },
  { key: 'staff', label: '内部员工', placeholder: '工号 / 邮箱' },
] as const
type TabKey = (typeof TABS)[number]['key']

const activeTab = ref<TabKey>('personal')
const activePlaceholder = computed(() => TABS.find(t => t.key === activeTab.value)!.placeholder)
const TAB_LABEL: Record<TabKey, string> = { personal: '个人用户', corporate: '企业用户', staff: '内部员工' }

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

/** 未开通能力的占位交互（U盾/证书/SSO/忘记密码/开通账号） */
function notAvailable(feature: string) {
  ElMessage.info(`${feature}：演示版暂未开通，请使用账号密码登录`)
}
</script>

<template>
  <div class="login-page">
    <!-- 背景装饰：星点连线网格（科技感） -->
    <svg class="mesh mesh-tl" width="360" height="300" viewBox="0 0 360 300" fill="none" aria-hidden="true">
      <circle cx="40" cy="60" r="3" fill="rgba(255,255,255,.5)" />
      <circle cx="150" cy="30" r="2" fill="rgba(255,255,255,.35)" />
      <circle cx="240" cy="90" r="2.5" fill="rgba(255,255,255,.4)" />
      <circle cx="90" cy="160" r="2" fill="rgba(255,255,255,.3)" />
      <circle cx="200" cy="200" r="3" fill="rgba(255,255,255,.35)" />
      <path d="M40 60 L150 30 L240 90 L200 200 L90 160 Z" stroke="rgba(255,255,255,.14)" fill="none" />
      <path d="M40 60 L90 160 M150 30 L90 160 M240 90 L200 200" stroke="rgba(255,255,255,.1)" fill="none" />
    </svg>
    <svg class="mesh mesh-br" width="420" height="340" viewBox="0 0 420 340" fill="none" aria-hidden="true">
      <circle cx="380" cy="60" r="3" fill="rgba(255,255,255,.45)" />
      <circle cx="280" cy="30" r="2" fill="rgba(255,255,255,.3)" />
      <circle cx="180" cy="110" r="2.5" fill="rgba(255,255,255,.35)" />
      <circle cx="330" cy="180" r="2" fill="rgba(255,255,255,.3)" />
      <circle cx="120" cy="250" r="3" fill="rgba(255,255,255,.3)" />
      <path d="M380 60 L280 30 L180 110 L330 180 L120 250" stroke="rgba(255,255,255,.12)" fill="none" />
      <path d="M380 60 L330 180 M280 30 L180 110" stroke="rgba(255,255,255,.09)" fill="none" />
    </svg>

    <span class="env-tag">生产环境</span>
    <button type="button" class="apply-hint" @click="notAvailable('账号开通')">还没有账号？联系管理员开通</button>

    <!-- 品牌区：直接落在页面上，不在卡内 -->
    <header class="hero">
      <h1 class="hero-title">AI 工作台</h1>
      <p class="hero-sub">一个工作台，承载企业所有的 AI 能力</p>
      <div class="hero-pills">
        <span class="pill"><el-icon><ChatDotRound /></el-icon>智能对话<i class="dot g"></i></span>
        <span class="sep">·</span>
        <span class="pill"><el-icon><Document /></el-icon>知识检索<i class="dot y"></i></span>
        <span class="sep">·</span>
        <span class="pill"><el-icon><Operation /></el-icon>流程自动化<i class="dot b"></i></span>
      </div>
    </header>

    <!-- 登录卡：只装表单 -->
    <div class="login-card">
      <h2 class="card-title">欢迎回来</h2>
      <p class="card-sub">登录工作台，进入你的专属空间</p>

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
          <span class="input-wrap">
            <el-icon class="input-icon"><User /></el-icon>
            <input
              v-model="username"
              class="field-input"
              type="text"
              :placeholder="activePlaceholder"
              autocomplete="username"
            >
          </span>
        </label>

        <label class="field">
          <span class="input-wrap">
            <el-icon class="input-icon"><Lock /></el-icon>
            <input
              v-model="password"
              class="field-input"
              :type="showPassword ? 'text' : 'password'"
              placeholder="密码"
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

      <!-- 其他登录方式：企业平台标志位 -->
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

      <!-- 测试账号：跟随身份选项卡变化（演示便捷入口） -->
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

      <div class="card-sec">🛡 安全登录 · 隐私保护 · 审计追踪</div>
    </div>

    <footer class="page-foot">
      <span>🔒 本系统为内部业务系统，所有操作将被记录并纳入审计</span>
      <span class="foot-copy">© 2026 智汇工作台 v1.0.0 · 京ICP备20260088号</span>
    </footer>
  </div>
</template>

<style scoped>
/* 骨架：深蓝科技感全域背景；品牌大标题与能力胶囊在页面上，白卡只装表单 */
.login-page {
  height: 100%;
  box-sizing: border-box;
  position: relative;
  overflow: auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 18px;
  padding: 26px 20px;
  background:
    radial-gradient(900px 500px at 50% -10%, rgba(88, 112, 212, 0.35) 0%, transparent 60%),
    radial-gradient(700px 460px at 100% 100%, rgba(64, 78, 168, 0.4) 0%, transparent 55%),
    linear-gradient(168deg, #141c46 0%, #1b2458 48%, #232e6e 100%);
}
.mesh {
  position: absolute;
  pointer-events: none;
  opacity: 0.8;
}
.mesh-tl { left: 0; top: 0; }
.mesh-br { right: 0; bottom: 0; }

.env-tag {
  position: absolute;
  top: 18px;
  left: 22px;
  z-index: 2;
  font-size: 12px;
  color: #7ee2a8;
  background: rgba(46, 160, 90, 0.18);
  border: 1px solid rgba(126, 226, 168, 0.4);
  border-radius: 4px;
  padding: 2px 8px;
  font-weight: 600;
}
.apply-hint {
  position: absolute;
  top: 16px;
  right: 24px;
  z-index: 2;
  border: none;
  background: transparent;
  padding: 0;
  font-size: 12.5px;
  color: rgba(255, 255, 255, 0.85);
  cursor: pointer;
}
.apply-hint:hover { color: #fff; text-decoration: underline; }

/* ============ 品牌区（页面上） ============ */
.hero {
  position: relative;
  z-index: 1;
  text-align: center;
}
.hero-title {
  margin: 0 0 10px;
  font-size: 46px;
  font-weight: 800;
  letter-spacing: 4px;
  color: #fff;
  text-shadow: 0 4px 24px rgba(0, 0, 0, 0.35);
}
.hero-sub {
  margin: 0 0 18px;
  font-size: 15px;
  color: rgba(255, 255, 255, 0.85);
  letter-spacing: 1px;
}
.hero-pills {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
}
.pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: rgba(255, 255, 255, 0.95);
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 999px;
  padding: 7px 14px;
  backdrop-filter: blur(4px);
  white-space: nowrap;
}
.pill .el-icon { font-size: 14px; }
.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  margin-left: 2px;
}
.dot.g { background: #34d399; box-shadow: 0 0 6px rgba(52, 211, 153, 0.8); }
.dot.y { background: #fbbf24; box-shadow: 0 0 6px rgba(251, 191, 36, 0.8); }
.dot.b { background: #60a5fa; box-shadow: 0 0 6px rgba(96, 165, 250, 0.8); }
.sep { color: rgba(255, 255, 255, 0.4); }

/* ============ 登录白卡 ============ */
.login-card {
  position: relative;
  z-index: 1;
  width: min(560px, 100%);
  box-sizing: border-box;
  background: #fff;
  border-radius: 16px;
  padding: 26px 32px 18px;
  color: #12263f;
  box-shadow: 0 24px 64px rgba(4, 10, 40, 0.45);
}
.card-title {
  margin: 0 0 4px;
  font-size: 23px;
  font-weight: 700;
}
.card-sub {
  margin: 0 0 16px;
  font-size: 13px;
  color: #8a97a8;
}

/* 身份选项卡：分段控件，浅灰容器 + 激活蓝块 */
.tab-bar {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 4px;
  background: #eef0f4;
  border-radius: 10px;
  padding: 4px;
  margin-bottom: 16px;
}
.tab-item {
  padding: 9px 0;
  font-size: 13.5px;
  background: transparent;
  color: #5a6b80;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s, color 0.15s, box-shadow 0.15s;
}
.tab-item:hover { background: rgba(255, 255, 255, 0.9); }
.tab-item.active {
  background: #1265e0;
  color: #fff;
  font-weight: 600;
  box-shadow: 0 2px 6px rgba(18, 101, 224, 0.35);
}

/* 表单：浅灰填充式输入框 */
.login-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.input-wrap {
  position: relative;
  display: block;
}
.field-input {
  height: 44px;
  width: 100%;
  box-sizing: border-box;
  border: 1px solid transparent;
  border-radius: 10px;
  padding: 0 42px 0 40px;
  font-size: 14px;
  color: #12263f;
  background: #f2f4f8;
  outline: none;
  transition: border-color 0.15s, background 0.15s, box-shadow 0.15s;
}
.field-input::placeholder { color: #9aa6b5; }
.field-input:focus {
  background: #fff;
  border-color: #1265e0;
  box-shadow: 0 0 0 3px rgba(18, 101, 224, 0.12);
}
/* 浏览器自动填充会覆盖填充底色：回填浅灰底、正常字色 */
.field-input:-webkit-autofill,
.field-input:-webkit-autofill:hover,
.field-input:-webkit-autofill:focus {
  -webkit-box-shadow: 0 0 0 1000px #f2f4f8 inset;
  -webkit-text-fill-color: #12263f;
  caret-color: #12263f;
  transition: background-color 9999s ease-in-out 0s;
}
.input-icon {
  position: absolute;
  left: 14px;
  top: 50%;
  transform: translateY(-50%);
  font-size: 15px;
  color: #7c8aa0;
  pointer-events: none;
}
.input-wrap:focus-within .input-icon { color: #1265e0; }
.eye-btn {
  position: absolute;
  right: 6px;
  top: 50%;
  transform: translateY(-50%);
  width: 32px;
  height: 32px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  color: #7c8aa0;
  cursor: pointer;
  border-radius: 8px;
}
.eye-btn:hover { color: #1265e0; }

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
  accent-color: #1265e0;
  cursor: pointer;
}
.link-btn {
  border: none;
  background: transparent;
  color: #1265e0;
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
  border-radius: 8px;
  padding: 7px 10px;
}

/* 登录按钮：品牌蓝渐变 */
.submit-btn {
  height: 46px;
  border: none;
  border-radius: 10px;
  background: linear-gradient(90deg, #2f7bff 0%, #1265e0 100%);
  color: #fff;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 6px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  box-shadow: 0 6px 16px rgba(24, 96, 210, 0.35);
  transition: filter 0.15s, box-shadow 0.15s;
}
.submit-btn:hover {
  filter: brightness(1.06);
  box-shadow: 0 8px 20px rgba(24, 96, 210, 0.42);
}
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
  margin: 16px 0 10px;
  color: #98a2b0;
  font-size: 12px;
}
.alt-divider::before,
.alt-divider::after {
  content: '';
  flex: 1;
  height: 1px;
  background: #e8ecf1;
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
  gap: 6px;
  height: 38px;
  border: 1px solid transparent;
  border-radius: 10px;
  background: #f2f4f8;
  color: #33475e;
  font-size: 13px;
  cursor: pointer;
  transition: background 0.15s, color 0.15s, border-color 0.15s;
}
.alt-btn:hover {
  background: #fff;
  border-color: #1265e0;
  color: #1265e0;
}

/* 测试账号：跟随选项卡，单卡填充 + 选中高亮 */
.demo-area {
  margin-top: 14px;
  border-top: 1px dashed #e0e6ee;
  padding-top: 10px;
}
.demo-caption {
  font-size: 11.5px;
  color: #98a2b0;
  margin-bottom: 7px;
}
.demo-chip {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  border: 1px solid #e5e8ec;
  background: #fafcfe;
  border-radius: 8px;
  padding: 7px 12px;
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
  border-color: #1265e0;
  background: #eef4fd;
}
.demo-name {
  font-weight: 700;
  color: #12263f;
  min-width: 56px;
}
.demo-desc { color: #8a97a8; }

/* 卡内安全标语 */
.card-sec {
  margin-top: 13px;
  text-align: center;
  font-size: 12px;
  color: #8a97a8;
}

/* 页脚：安全提示 + 版权 */
.page-foot {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 5px;
  text-align: center;
  font-size: 12.5px;
  color: rgba(255, 255, 255, 0.78);
}
.foot-copy {
  font-size: 11.5px;
  color: rgba(255, 255, 255, 0.45);
}

/* 窄屏：能力胶囊换行、卡片贴边 */
@media (max-width: 640px) {
  .hero-title { font-size: 34px; }
  .hero-pills { flex-wrap: wrap; }
  .sep { display: none; }
  .login-card { padding: 22px 20px 14px; }
}
</style>
