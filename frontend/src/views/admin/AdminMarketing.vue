<template>
  <div>
    <div class="page-head">
      <h2>营销管理</h2>
      <span class="page-sub">对公客户商机与营销活动经营看板</span>
    </div>

    <div class="stat-grid">
      <div class="stat-card"><div class="stat-label">本月新增商机</div><div class="stat-value">12</div><div class="stat-sub">较上月 +4</div></div>
      <div class="stat-card"><div class="stat-label">跟进中商机</div><div class="stat-value">8</div><div class="stat-sub">重点跟进 3 个</div></div>
      <div class="stat-card"><div class="stat-label">本月成交</div><div class="stat-value">3</div><div class="stat-sub">转化率 25%</div></div>
      <div class="stat-card"><div class="stat-label">商机预计金额</div><div class="stat-value">¥ 1,860,000</div><div class="stat-sub sub-green">本月新增 ¥ 620,000</div></div>
    </div>

    <el-card shadow="never" class="block">
      <template #header><b>商机列表</b></template>
      <el-table :data="opportunities" size="small" stripe>
        <el-table-column prop="company" label="企业客户" min-width="130" />
        <el-table-column prop="product" label="意向产品" min-width="140" />
        <el-table-column label="阶段" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="stageType(row.stage)">{{ row.stage }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="amount" label="预计金额（元）" align="right" width="150">
          <template #default="{ row }"><span class="mono">{{ row.amount }}</span></template>
        </el-table-column>
        <el-table-column prop="owner" label="客户经理" width="100" />
        <el-table-column prop="lastFollow" label="最近跟进" width="120" />
        <el-table-column prop="next" label="下一步计划" min-width="160" show-overflow-tooltip />
      </el-table>
    </el-card>

    <el-card shadow="never">
      <template #header><b>营销活动</b></template>
      <el-table :data="campaigns" size="small" stripe>
        <el-table-column prop="name" label="活动名称" min-width="200" />
        <el-table-column prop="type" label="类型" width="110" />
        <el-table-column prop="reach" label="触达客户数" align="right" width="120" />
        <el-table-column prop="converted" label="转化" align="right" width="90" />
        <el-table-column prop="period" label="活动周期" width="200" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === '进行中' ? 'success' : 'info'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
type TagType = 'success' | 'primary' | 'info' | 'danger' | 'warning'

const opportunities: {
  company: string; product: string; stage: string; amount: string
  owner: string; lastFollow: string; next: string
}[] = [
  { company: '星辰科技', product: '对公定期存款', stage: '方案报价', amount: '2,000,000.00', owner: '陈铭', lastFollow: '09-22', next: '下周提交定价方案' },
  { company: '远航贸易', product: '代发工资', stage: '需求确认', amount: '360,000.00', owner: '林芳', lastFollow: '09-21', next: 'HR 对接取数模板' },
  { company: '嘉禾制造', product: '流动资金贷款', stage: '尽调中', amount: '5,000,000.00', owner: '陈铭', lastFollow: '09-20', next: '收集近三年财报' },
  { company: '恒通物流', product: '收单聚合支付', stage: '意向沟通', amount: '180,000.00', owner: '赵倩', lastFollow: '09-19', next: '演示收单后台' },
  { company: '云启数据', product: '对公理财', stage: '已成交', amount: '1,000,000.00', owner: '林芳', lastFollow: '09-18', next: '季度回访' },
  { company: '嘉禾制造', product: '票据贴现', stage: '方案报价', amount: '800,000.00', owner: '赵倩', lastFollow: '09-17', next: '等待总行额度审批' },
]

const campaigns: {
  name: string; type: string; reach: number; converted: number; period: string; status: string
}[] = [
  { name: '对公结算费率优惠季', type: '费率营销', reach: 156, converted: 18, period: '2026-09-01 ~ 2026-09-30', status: '进行中' },
  { name: '代发工资企业回流专项', type: '名单营销', reach: 42, converted: 6, period: '2026-09-08 ~ 2026-10-08', status: '进行中' },
  { name: '供应链金融产品推介会', type: '活动营销', reach: 30, converted: 4, period: '2026-08-15 ~ 2026-08-31', status: '已结束' },
]

function stageType(s: string): TagType {
  return s === '已成交' ? 'success' : s === '尽调中' ? 'warning' : s === '方案报价' ? 'primary' : 'info'
}
</script>

<style scoped>
.page-head { margin-bottom: 14px; }
.page-head h2 { margin: 0 0 4px; font-size: 18px; color: #12263f; }
.page-sub { font-size: 12px; color: #8a97a8; }
.block { margin-bottom: 14px; }
.mono { font-family: 'JetBrains Mono', Consolas, monospace; }
.stat-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; margin-bottom: 14px; }
.stat-card { background: #fff; border: 1px solid #e5e8ec; border-radius: 4px; padding: 14px 16px; box-shadow: 0 1px 3px rgba(16, 42, 83, 0.06); }
.stat-label { font-size: 12.5px; color: #7a8798; margin-bottom: 6px; }
.stat-value { font-size: 22px; font-weight: 700; color: #0b4f9e; font-variant-numeric: tabular-nums; }
.stat-sub { font-size: 12px; color: #98a2b0; margin-top: 4px; }
.sub-green { color: #0a8f3c; }
</style>
