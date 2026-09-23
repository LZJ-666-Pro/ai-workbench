<template>
  <div>
    <div class="page-head">
      <h2>审批中心</h2>
      <span class="page-sub">AI 发起的转账需人工二次确认（HITL），这里集中监控审批队列与交易旅程</span>
    </div>

    <el-row :gutter="12" class="block">
      <el-col :span="6"><div class="stat pending"><div class="stat-label">待审批</div><div class="stat-val">{{ board?.pending ?? '–' }}</div></div></el-col>
      <el-col :span="6"><div class="stat"><div class="stat-label">今日已执行</div><div class="stat-val ok">{{ board?.executedToday ?? '–' }}</div></div></el-col>
      <el-col :span="6"><div class="stat"><div class="stat-label">已取消</div><div class="stat-val">{{ board?.cancelled ?? '–' }}</div></div></el-col>
      <el-col :span="6"><div class="stat"><div class="stat-label">已拒绝/过期</div><div class="stat-val bad">{{ board?.rejected ?? '–' }}</div></div></el-col>
    </el-row>

    <el-card shadow="never" class="block">
      <template #header><b>审批流规则</b>（代码层强制，不依赖模型自觉）</template>
      <el-descriptions :column="2" size="small">
        <el-descriptions-item label="审批模式">AI 创建确认单 → 人工点击确认/取消（两段式 HITL）</el-descriptions-item>
        <el-descriptions-item label="确认单有效期">10 分钟，超时自动失效（REJECTED）</el-descriptions-item>
        <el-descriptions-item label="幂等控制">状态机 CAS：重复确认只执行一次</el-descriptions-item>
        <el-descriptions-item label="强制拦截">内部员工身份发起转账直接拒绝（无资金权限）</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card shadow="never">
      <template #header><b>待审批队列</b>（PENDING 确认单）</template>
      <el-table v-if="board?.pendingOrders?.length" :data="board.pendingOrders" size="small" stripe>
        <el-table-column prop="createdAt" label="创建时间" width="160" />
        <el-table-column prop="confirmId" label="确认码" width="290" show-overflow-tooltip>
          <template #default="{ row }"><span class="mono">{{ row.confirmId }}</span></template>
        </el-table-column>
        <el-table-column prop="fromAccount" label="付款账号" width="110" />
        <el-table-column prop="toAccount" label="收款账号" width="110" />
        <el-table-column label="金额" align="right" width="110">
          <template #default="{ row }">¥ {{ fmt(row.amount) }}</template>
        </el-table-column>
        <el-table-column prop="reason" label="附言" min-width="120" show-overflow-tooltip />
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="openJourney(row.id)">交易旅程</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else-if="board" description="暂无待审批确认单，所有转账均已处理" :image-size="80" />
    </el-card>

    <el-card shadow="never" class="block">
      <template #header><b>全部订单追溯</b></template>
      <el-table :data="orders" size="small" stripe>
        <el-table-column prop="createdAt" label="时间" width="160" />
        <el-table-column prop="fromAccount" label="付款账号" width="110" />
        <el-table-column prop="toAccount" label="收款账号" width="110" />
        <el-table-column label="金额" align="right" width="110">
          <template #default="{ row }">¥ {{ fmt(row.amount) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="openJourney(row.id)">交易旅程</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 交易旅程抽屉 -->
    <el-drawer v-model="journeyDrawer" title="交易旅程（Transaction Journey）" size="45%">
      <template v-if="journey">
        <el-descriptions :column="2" border size="small" class="block">
          <el-descriptions-item label="订单号">#{{ journey.order.id }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag size="small" :type="statusType(journey.order.status)">{{ journey.order.status }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="付款 → 收款">
            {{ journey.order.fromAccount }} → {{ journey.order.toAccount }}
          </el-descriptions-item>
          <el-descriptions-item label="金额">¥ {{ fmt(journey.order.amount) }}</el-descriptions-item>
        </el-descriptions>
        <el-timeline style="padding-left: 4px">
          <el-timeline-item
            v-for="(node, i) in journey.timeline"
            :key="i"
            :timestamp="node.time"
            :type="node.result === 'SUCCESS' ? 'success' : node.result === 'DENY' ? 'danger' : node.result === 'PENDING' ? 'warning' : 'info'"
          >
            <b>{{ node.type }}</b>
            <span class="audit-tag" :class="`audit-${node.result.toLowerCase()}`">{{ node.result }}</span>
            <div class="node-text">{{ node.text }}</div>
          </el-timeline-item>
        </el-timeline>
      </template>
      <el-skeleton v-else :rows="8" animated />
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { adminApi, type ApprovalBoard, type OrderView, type OrderJourney } from '../../api/admin'

const board = ref<ApprovalBoard | null>(null)
const orders = ref<OrderView[]>([])
const journeyDrawer = ref(false)
const journey = ref<OrderJourney | null>(null)

function fmt(n: number): string {
  return Number(n ?? 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function statusType(s: string): 'warning' | 'success' | 'info' | 'danger' {
  return s === 'PENDING' ? 'warning' : s === 'EXECUTED' ? 'success' : s === 'CANCELLED' ? 'info' : 'danger'
}

async function openJourney(id: number) {
  journey.value = null
  journeyDrawer.value = true
  journey.value = await adminApi.orderJourney(id)
}

onMounted(async () => {
  board.value = await adminApi.approvals()
  const all = await adminApi.transferOrders('', 1, 20)
  orders.value = all.list
})
</script>

<style scoped>
.page-head { margin-bottom: 14px; }
.page-head h2 { margin: 0 0 4px; font-size: 18px; color: #12263f; }
.page-sub { font-size: 12px; color: #8a97a8; }
.block { margin-bottom: 14px; }
.stat { background: #fff; border: 1px solid #e5e8ec; border-radius: 8px; padding: 12px 14px; }
.stat.pending { border-color: #f0c36d; background: #fffbf2; }
.stat-label { font-size: 12px; color: #8a97a8; margin-bottom: 4px; }
.stat-val { font-size: 22px; font-weight: 700; color: #12263f; }
.stat-val.ok { color: #0a8f3c; }
.stat-val.bad { color: #d93026; }
.mono { font-family: 'JetBrains Mono', Consolas, monospace; font-size: 11px; }
.audit-tag { font-size: 10px; padding: 1px 6px; border-radius: 8px; margin-left: 6px; }
.audit-success { background: #e6f4ea; color: #0a8f3c; }
.audit-deny { background: #fdecea; color: #d93026; }
.audit-pending { background: #fff8e6; color: #b26a00; }
.audit-allow, .audit-cancelled, .audit-idempotent_skip, .audit-already_done { background: #eef1f5; color: #5f6b7c; }
.node-text { font-size: 12px; color: #5f6b7c; margin-top: 2px; }
</style>
