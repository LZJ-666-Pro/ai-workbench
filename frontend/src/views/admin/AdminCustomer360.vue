<template>
  <div>
    <div class="page-head">
      <h2>客户管理 · 360 视图</h2>
      <span class="page-sub">按户名聚合名下账户，点开卡片查看存款/交易/订单/AI 操作全景</span>
    </div>

    <el-row v-if="customers.length" :gutter="14">
      <el-col v-for="c in customers" :key="c.owner" :xs="24" :sm="12" :md="8" :lg="6">
        <div class="customer-card" @click="openProfile(c.owner)">
          <div class="card-top">
            <span class="avatar">{{ c.owner.substring(0, 1) }}</span>
            <div class="card-title">
              <div class="name">{{ c.owner }}</div>
              <el-tag size="small" :type="tagType(c.type)" effect="light">{{ typeText(c.type) }}</el-tag>
            </div>
          </div>
          <div class="balance">¥ {{ fmt(c.totalBalance) }}</div>
          <div class="meta">{{ c.accountCount }} 个账户 · 点击查看 360 视图</div>
        </div>
      </el-col>
    </el-row>
    <el-empty v-else-if="loaded" description="暂无客户数据" />

    <!-- 360 抽屉 -->
    <el-drawer v-model="drawer" :title="`${profileOwner} · 客户 360 视图`" size="62%">
      <template v-if="profile">
        <el-descriptions :column="3" border size="small" class="block">
          <el-descriptions-item label="客户类型">
            <el-tag size="small" :type="tagType(profile.type)">{{ typeText(profile.type) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="存款总额">¥ {{ fmt(profile.totalBalance) }}</el-descriptions-item>
          <el-descriptions-item label="名下账户">{{ profile.accounts.length }} 个</el-descriptions-item>
        </el-descriptions>

        <el-row :gutter="12" class="block">
          <el-col :span="6"><div class="stat"><div class="stat-label">累计入账</div><div class="stat-val in">+{{ fmt(profile.txnIncome) }}</div></div></el-col>
          <el-col :span="6"><div class="stat"><div class="stat-label">累计支出</div><div class="stat-val out">-{{ fmt(profile.txnExpense) }}</div></div></el-col>
          <el-col :span="6"><div class="stat"><div class="stat-label">AI 转账订单</div><div class="stat-val">{{ profile.orderCount }} 单 <span class="stat-sub">成功 {{ profile.orderExecuted }}</span></div></div></el-col>
          <el-col :span="6"><div class="stat"><div class="stat-label">AI 操作留痕</div><div class="stat-val">{{ profile.auditCount }} 条</div></div></el-col>
        </el-row>

        <el-tabs type="border-card">
          <el-tab-pane label="名下账户">
            <el-table :data="profile.accounts" size="small" stripe>
              <el-table-column prop="accountNo" label="账号" min-width="120" />
              <el-table-column label="类型" width="90">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.accountNo.startsWith('8') ? 'warning' : 'primary'">
                    {{ row.accountNo.startsWith('8') ? '对公' : '个人' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="余额" align="right" min-width="120">
                <template #default="{ row }">¥ {{ fmt(row.balance) }}</template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`最近流水 (${profile.recentTxns.length})`">
            <el-table :data="profile.recentTxns" size="small" stripe>
              <el-table-column prop="createdAt" label="时间" width="160" />
              <el-table-column prop="accountNo" label="账号" width="110" />
              <el-table-column prop="description" label="摘要" min-width="200" show-overflow-tooltip />
              <el-table-column label="金额" align="right" width="120">
                <template #default="{ row }">
                  <span :class="row.amount >= 0 ? 'amt-in' : 'amt-out'">{{ fmtSigned(row.amount) }}</span>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`转账订单 (${profile.recentOrders.length})`">
            <el-table :data="profile.recentOrders" size="small" stripe>
              <el-table-column prop="createdAt" label="时间" width="160" />
              <el-table-column prop="fromAccount" label="付款账号" width="110" />
              <el-table-column prop="toAccount" label="收款账号" width="110" />
              <el-table-column label="金额" align="right" width="110">
                <template #default="{ row }">¥ {{ fmt(row.amount) }}</template>
              </el-table-column>
              <el-table-column label="状态" width="90">
                <template #default="{ row }">
                  <el-tag size="small" :type="statusType(row.status)">{{ row.status }}</el-tag>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`AI 操作轨迹 (${profile.recentAudits.length})`">
            <el-timeline style="padding-left: 4px">
              <el-timeline-item v-for="a in profile.recentAudits" :key="a.id" :timestamp="a.createdAt" :type="auditDot(a.result)">
                <b>{{ a.toolName }}</b>
                <span :class="`audit-${a.result.toLowerCase()}`" class="audit-tag">{{ a.result }}</span>
                <div class="audit-detail">{{ a.detail }}</div>
              </el-timeline-item>
            </el-timeline>
          </el-tab-pane>
        </el-tabs>
      </template>
      <el-skeleton v-else :rows="6" animated />
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { adminApi, type CustomerView, type CustomerProfile } from '../../api/admin'

const customers = ref<CustomerView[]>([])
const loaded = ref(false)
const drawer = ref(false)
const profile = ref<CustomerProfile | null>(null)
const profileOwner = ref('')

function fmt(n: number): string {
  return Number(n ?? 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function fmtSigned(n: number): string {
  return (n >= 0 ? '+' : '') + fmt(n)
}
function typeText(t: string): string {
  return t === 'corporate' ? '对公客户' : t === 'mixed' ? '综合客户' : '个人客户'
}
function tagType(t: string): 'warning' | 'primary' | 'success' {
  return t === 'corporate' ? 'warning' : t === 'mixed' ? 'success' : 'primary'
}
function statusType(s: string): 'warning' | 'success' | 'info' | 'danger' {
  return s === 'PENDING' ? 'warning' : s === 'EXECUTED' ? 'success' : s === 'CANCELLED' ? 'info' : 'danger'
}
function auditDot(r: string): 'success' | 'danger' | 'info' {
  return r === 'SUCCESS' ? 'success' : r === 'DENY' ? 'danger' : 'info'
}

async function openProfile(owner: string) {
  profileOwner.value = owner
  profile.value = null
  drawer.value = true
  profile.value = await adminApi.customerProfile(owner)
}

onMounted(async () => {
  try {
    customers.value = await adminApi.customers()
  } finally {
    loaded.value = true
  }
})
</script>

<style scoped>
.page-head { margin-bottom: 14px; }
.page-head h2 { margin: 0 0 4px; font-size: 18px; color: #12263f; }
.page-sub { font-size: 12px; color: #8a97a8; }
.customer-card {
  background: #fff; border: 1px solid #e5e8ec; border-radius: 8px;
  padding: 14px; margin-bottom: 14px; cursor: pointer;
  transition: box-shadow .2s, transform .2s;
}
.customer-card:hover { box-shadow: 0 4px 16px rgba(11,79,158,.12); transform: translateY(-2px); }
.card-top { display: flex; gap: 10px; align-items: center; margin-bottom: 10px; }
.avatar {
  width: 38px; height: 38px; border-radius: 8px; background: #0b4f9e; color: #fff;
  display: flex; align-items: center; justify-content: center; font-size: 16px; font-weight: 700;
}
.card-title .name { font-weight: 700; color: #12263f; margin-bottom: 2px; }
.balance { font-size: 20px; font-weight: 700; color: #0b4f9e; font-family: 'JetBrains Mono', Consolas, monospace; }
.meta { font-size: 12px; color: #8a97a8; margin-top: 4px; }
.block { margin-bottom: 14px; }
.stat { background: #f7f9fc; border: 1px solid #e5e8ec; border-radius: 8px; padding: 10px 12px; }
.stat-label { font-size: 12px; color: #8a97a8; margin-bottom: 4px; }
.stat-val { font-size: 16px; font-weight: 700; color: #12263f; font-family: 'JetBrains Mono', Consolas, monospace; }
.stat-sub { font-size: 11px; color: #8a97a8; font-weight: 400; }
.stat-val.in { color: #0a8f3c; }
.stat-val.out { color: #d93026; }
.amt-in { color: #0a8f3c; font-family: 'JetBrains Mono', Consolas, monospace; }
.amt-out { color: #d93026; font-family: 'JetBrains Mono', Consolas, monospace; }
.audit-tag { font-size: 10px; padding: 1px 6px; border-radius: 8px; margin-left: 6px; }
.audit-success { background: #e6f4ea; color: #0a8f3c; }
.audit-deny { background: #fdecea; color: #d93026; }
.audit-fail { background: #fdecea; color: #d93026; }
.audit-allow, .audit-pending, .audit-cancelled { background: #eef1f5; color: #5f6b7c; }
.audit-detail { font-size: 12px; color: #5f6b7c; margin-top: 2px; }
</style>
