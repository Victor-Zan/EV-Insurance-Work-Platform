<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref } from 'vue'
import { ElMessage } from 'element-plus'
import Icon from './Icon.vue'
const emit = defineEmits<{ back: [] }>()
const tab = ref('task'), amount = ref(''), checked = ref(false), error = ref(''), expanded = ref(''), confirmation = ref(false), saved = ref(false)
const body = ref<HTMLElement>(), amountField = ref<{ focus: () => void }>()
const steps = ['建单', '派单', '报价', '审核', '核损', '维修', '收车', '资金', '完成']
const materials = ['物损照片', '接车照片', '网点报价原件', '最终核损单']
const lines = [
  ['前挡泥板', '1', '128.00'], ['前大灯总成', '1', '268.00'], ['前轮毂', '1', '386.00'], ['前减震总成', '1', '296.00'], ['拆装工时', '1', '180.00'], ['辅材', '1', '120.60'],
]
const history = [
  { time: '10-18 09:42', title: '最终核损单已上传', desc: '示例客服 · 核损单 v1' },
  { time: '10-18 09:36', title: '已记录保险公司确认', desc: '采用金额 1,378.60 元' },
  { time: '10-17 16:18', title: '正式报价已生成', desc: '正式报价 v1 · 1,378.60 元' },
  { time: '10-17 15:48', title: '网点提交原始报价', desc: '示例维修网点一 · 网点报价 v1' },
]
let revealObserver: IntersectionObserver | undefined
function observeBelow() {
  revealObserver?.disconnect()
  if (matchMedia('(prefers-reduced-motion: reduce)').matches || document.querySelector('.quiet-motion')) return
  revealObserver = new IntersectionObserver(entries => entries.forEach(e => { if (e.isIntersecting) { e.target.classList.remove('will-reveal'); e.target.classList.add('revealed'); revealObserver?.unobserve(e.target) } }), { root: body.value, threshold: 0.05 })
  const boundary = body.value?.getBoundingClientRect().bottom || 0
  body.value?.querySelectorAll('[data-case-reveal]').forEach(node => { if (node.getBoundingClientRect().top >= boundary) { node.classList.add('will-reveal'); revealObserver?.observe(node) } })
}
function changeTab(value: string) { tab.value = value; nextTick(() => { body.value?.scrollTo({ top: 0 }); observeBelow() }) }
function validate() {
  saved.value = false
  if (!/^\d+(\.\d{1,2})?$/.test(amount.value.trim())) error.value = '请填写确认金额，最多保留 2 位小数。'
  else {
    const [whole, fraction = ''] = amount.value.trim().split('.')
    const cents = BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'))
    error.value = cents !== 137860n ? '确认金额须与核损单采用金额 1,378.60 元一致。' : !checked.value ? '请勾选已核对两份单据和最终金额。' : ''
  }
  if (error.value) { nextTick(() => { amountField.value?.focus(); document.getElementById('amount-check')?.scrollIntoView({ block: 'center' }) }); return }
  confirmation.value = true
}
function confirmDemo() { confirmation.value = false; saved.value = true; ElMessage.success('确认反馈演示完成。未提交业务数据，案件状态保持不变。') }
function jumpToCheck() { document.getElementById('amount-check')?.scrollIntoView({ block: 'start', behavior: matchMedia('(prefers-reduced-motion: reduce)').matches || document.querySelector('.quiet-motion') ? 'instant' : 'smooth' }); nextTick(() => amountField.value?.focus()) }
nextTick(observeBelow)
onBeforeUnmount(() => revealObserver?.disconnect())
</script>

<template>
  <div class="case-page">
    <header class="case-heading glass"><div class="case-heading-top"><button class="icon-button case-back" aria-label="返回工作台" @click="emit('back')"><Icon name="back"/></button><div class="case-title-group"><div class="case-title-line"><h1 class="mono">EVR-20261018-000001</h1><span class="status warning"><span/>待维修授权</span></div><p>示例品牌 A · 城市款<span class="tiny-divider">/</span>示例车主甲<span class="tiny-divider">/</span>138****0001<span class="tiny-divider">/</span>示例保险公司</p></div><button class="text-button case-more" @click="ElMessage.info('关键字段修改与取消操作沿用原流程，本轮不演示业务变更。')">更多操作<Icon name="more" :size="18"/></button></div>
      <ol class="workflow-steps" aria-label="案件流程"><li v-for="(step, index) in steps" :key="step" :class="{ done: index < 4, current: index === 4 }" :aria-current="index === 4 ? 'step' : undefined"><span class="step-mark"><Icon v-if="index < 4" name="check" :size="13"/><span v-else>{{ String(index + 1).padStart(2, '0') }}</span></span><span>{{ step }}</span></li></ol>
    </header>
    <nav class="case-tabs" aria-label="案件内容"><button v-for="item in [{ key: 'task', name: '当前任务' }, { key: 'overview', name: '案件资料' }, { key: 'history', name: '记录' }]" :key="item.key" :class="{ selected: tab === item.key }" :aria-current="tab === item.key ? 'page' : undefined" @click="changeTab(item.key)">{{ item.name }}</button></nav>
    <div class="case-columns"><div class="case-workspace"><article ref="body" class="case-body surface" aria-label="案件正文">
      <template v-if="tab === 'task'"><header class="body-heading"><div><p class="overline">核损 · 最终确认</p><h2>核对单据与最终金额</h2></div><span class="version-label">报价 v1 / 核损 v1</span></header>
        <div class="document-comparison"><section v-for="name in ['正式报价单', '最终核损单']" :key="name" class="document-column"><header><h3>{{ name }}</h3><button class="icon-button" :aria-label="`放大${name}`" @click="expanded = name"><Icon name="expand" :size="16"/></button></header><button class="document-sheet" :aria-label="`查看${name}原件示例`" @click="expanded = name"><div class="document-microhead">e保 / {{ name === '正式报价单' ? '保险协作台' : '示例保险公司' }}</div><h4>{{ name }}</h4><div class="paper-rule"/><dl class="document-facts"><div><dt>报案号</dt><dd>示例报案 001</dd></div><div><dt>车辆</dt><dd>示例品牌 A · 城市款</dd></div><div><dt>维修网点</dt><dd>示例维修网点一</dd></div></dl><table><thead><tr><th>项目</th><th>数量</th><th>金额 / 元</th></tr></thead><tbody><tr v-for="line in lines" :key="line[0]"><td>{{ line[0] }}</td><td>{{ line[1] }}</td><td class="mono">{{ line[2] }}</td></tr></tbody></table><div class="paper-total"><span>{{ name === '正式报价单' ? '报价合计' : '核损金额' }}</span><strong class="mono">¥ 1,378.60</strong></div><footer><span>2026-10-18</span><span>设计示例 · 非真实单据</span></footer></button><div class="document-foot"><span><Icon name="file" :size="14"/>{{ name }}.pdf</span><span>1 / 1</span></div></section></div>
        <section id="amount-check" class="amount-check" data-case-reveal><div><span class="field-label">核损单采用金额</span><div class="check-total"><span>¥</span><strong class="mono">1,378.60</strong></div></div><label><span class="field-label">确认授权金额（元）<span class="required">*</span></span><el-input ref="amountField" v-model="amount" inputmode="decimal" maxlength="16" placeholder="请核对后输入" aria-label="确认授权金额（元）" :class="{ 'invalid-field': error }" @input="error = ''; saved = false"><template #prefix>¥</template></el-input></label></section><label class="check-line"><input v-model="checked" type="checkbox" @change="error = ''; saved = false"><span>已核对正式报价单、核损单和最终金额</span></label><p class="small-note">授权金额以最终核损单为准。</p><p v-if="error" class="inline-error" role="alert">{{ error }}</p><p v-if="saved" class="inline-success" role="status">确认反馈已演示，业务状态未改变。</p>
        <section class="gate-section" data-case-reveal><header class="section-heading"><h3>授权条件</h3><span class="muted">3 / 4 已满足</span></header><div class="gate-grid"><span v-for="name in ['核损单已上传', '最终核损金额已录入', '保险公司确认已记录']" :key="name"><Icon name="check" :size="16"/>{{ name }}</span><span class="gate-wait"><Icon name="clock" :size="16"/>待客服确认</span></div></section>
        <details class="version-records" data-case-reveal><summary>版本记录<Icon name="down" :size="16"/></summary><div v-for="item in history" :key="item.time" class="history-row"><time class="mono">{{ item.time }}</time><span><strong>{{ item.title }}</strong><small>{{ item.desc }}</small></span></div></details>
      </template>
      <template v-else-if="tab === 'overview'"><header class="body-heading"><div><p class="overline">案件信息</p><h2>案件资料</h2></div><span class="version-label">只读示例</span></header><dl class="case-information"><div v-for="item in [['报案号','示例报案 001'],['保险公司','示例保险公司'],['车主','示例车主甲'],['手机','138****0001'],['车辆','示例品牌 A · 城市款'],['车牌','示例牌照'],['维修网点','示例维修网点一'],['事故时间','2026-10-17 10:20']]" :key="item[0]"><dt>{{ item[0] }}</dt><dd>{{ item[1] }}</dd></div></dl><h3 class="subheading">材料记录</h3><button v-for="material in materials" :key="material" class="attachment-row" @click="material.endsWith('照片') ? ElMessage.info('本轮仅展示材料入口，未附照片示例。') : expanded = material"><Icon name="file"/><span>{{ material }}</span><span class="muted">查看示例</span><Icon name="arrow" :size="16"/></button></template>
      <template v-else><header class="body-heading"><div><p class="overline">操作留痕</p><h2>处理记录</h2></div><span class="version-label">4 条</span></header><div v-for="item in history" :key="item.time" class="timeline-row"><span class="timeline-dot"/><div><time class="mono">{{ item.time }}</time><h3>{{ item.title }}</h3><p>{{ item.desc }}</p></div></div></template>
    </article><footer v-if="tab === 'task'" class="case-action-bar glass"><span><span class="action-dot"/>下一步：网点开始维修</span><el-button type="primary" @click="validate">客服确认<Icon name="arrow" :size="16"/></el-button></footer></div>
      <aside class="case-control glass" aria-label="当前任务面板"><p class="overline">当前任务</p><h2>维修授权</h2><span class="actor"><span/>由你办理</span><div class="control-divider"/><h3>材料状态 <span>4 / 4</span></h3><ul class="material-list"><li v-for="item in materials" :key="item"><span>{{ item }}</span><span class="material-ready"><Icon name="check" :size="15"/>已上传</span></li></ul><div class="control-note"><Icon name="clock" :size="17"/><span>待客服核对并确认</span></div><button class="text-button control-link" @click="changeTab('task'); nextTick(jumpToCheck)">前往金额核对<Icon name="arrow" :size="16"/></button></aside>
    </div>
    <el-dialog :model-value="Boolean(expanded)" :title="expanded" width="680px" class="document-dialog" @close="expanded = ''"><div class="large-document"><p class="overline">设计示例 · 非真实单据</p><h2>{{ expanded }}</h2><p>示例报案 001 · 示例维修网点一</p><table><thead><tr><th>项目</th><th>数量</th><th>金额（元）</th></tr></thead><tbody><tr v-for="line in lines" :key="line[0]"><td>{{ line[0] }}</td><td>{{ line[1] }}</td><td class="mono">{{ line[2] }}</td></tr></tbody></table><div class="paper-total"><span>合计</span><strong class="mono">¥ 1,378.60</strong></div><p class="small-note">此预览用于检查原件阅读与关闭后的位置恢复。</p></div></el-dialog>
    <el-dialog v-model="confirmation" title="确认授权金额" width="440px"><div class="confirm-amount mono">¥ 1,378.60</div><p class="dialog-copy">该金额与核损单采用金额一致。本次只演示确认反馈，不提交案件数据。</p><template #footer><el-button @click="confirmation = false">返回核对</el-button><el-button type="primary" @click="confirmDemo">演示确认</el-button></template></el-dialog>
  </div>
</template>
