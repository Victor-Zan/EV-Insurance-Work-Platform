<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { ArrowUp, ArrowDown, ChatLineSquare, EditPen, Fold, Expand } from '@element-plus/icons-vue'
import { ElDropdown, ElDropdownMenu, ElDropdownItem } from 'element-plus'
import { assistantApi, type AssistantModel } from './assistant-api'
import { readHistory, writeHistory, type AssistantHistory } from './history'
import { statusLabels } from '@/features/workorders/work-orders'

const props = defineProps<{ userId: number }>()
const history = ref<AssistantHistory>({ activeId: null, drafts: [] })
const storageError = ref(''), focused = ref(false), sidebarOpen = ref(false)
const input = ref<HTMLTextAreaElement | null>(null)
const active = computed(() => history.value.drafts.find(draft => draft.id === history.value.activeId))
const draftText = computed(() => active.value?.text ?? '')
const drafts = computed(() => [...history.value.drafts].sort((a, b) => b.updatedAt - a.updatedAt))
const examples = ['分析最近五单的经营情况', '帮我写一句礼貌的催办提醒', '怎么提交网点报价', '今天有多少工单']
const enabled = ref<boolean | null>(null), error = ref('')
const models = ref<AssistantModel[]>([]), preferredModel = ref(''), defaultModel = ref('')
const selectedModelId = computed(() => active.value?.model || preferredModel.value || defaultModel.value)
const selectedModel = computed(() => models.value.find(model => model.id === selectedModelId.value))
const canSend = computed(() => enabled.value && (!models.value.length || selectedModel.value?.enabled))
const pendingTurn = ref<{ id: string; message: string; model?: string } | null>(null)
const busy = computed(() => pendingTurn.value !== null)
const visiblePending = computed(() => pendingTurn.value?.id === active.value?.id ? pendingTurn.value : null)
const messages = ref<HTMLElement | null>(null)
const turns = computed(() => active.value?.turns ?? [])
let request: AbortController | undefined
const composing = ref(false)
const animatedText = ref(''), reducedMotion = ref(false), pageHidden = ref(false)
const idle = computed(() => !focused.value && !draftText.value && !pageHidden.value && !turns.value.length)
let timer: ReturnType<typeof setTimeout> | undefined
let phrase = 0, deleting = false
let motionQuery: MediaQueryList | undefined

function save() {
  try { writeHistory(localStorage, props.userId, history.value); storageError.value = '' }
  catch { storageError.value = '本地保存失败，刷新后可能丢失会话。' }
}
function updateDraft(text: string) {
  if (active.value) { active.value.text = text; active.value.updatedAt = Date.now() }
  else if (text) {
    const id = crypto.randomUUID()
    history.value.drafts.unshift({ id, text, updatedAt: Date.now(), model: selectedModelId.value || undefined })
    history.value.activeId = id
  }
  save()
}
function newConversation() {
  history.value.activeId = null
  sidebarOpen.value = false
  error.value = ''
  save()
  void nextTick(() => input.value?.focus())
}
function selectConversation(id: string) {
  history.value.activeId = id
  sidebarOpen.value = false
  error.value = ''
  save()
  void scrollToLatest()
}
async function scrollToLatest() {
  await nextTick()
  if (messages.value) messages.value.scrollTop = messages.value.scrollHeight
}
async function loadStatus() {
  try {
    const status = await assistantApi.status()
    models.value = status.models ?? []
    defaultModel.value = status.defaultModel ?? ''
    enabled.value = status.enabled
  } catch {
    enabled.value = false
    error.value = '无法读取模型配置，请点击“重新检查”。'
  }
}
function selectModel(id: string) {
  if (busy.value) return
  if (id === '__refresh') { void loadStatus(); return }
  if (!models.value.some(model => model.id === id && model.enabled)) return
  preferredModel.value = id
  if (active.value) active.value.model = id
  error.value = ''
  save()
}
function stopWaiting() {
  request?.abort()
  request = undefined
  pendingTurn.value = null
  error.value = '已停止等待，问题已保留。供应商可能仍在处理原请求。'
}
function onComposerKey(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.shiftKey || event.ctrlKey || event.metaKey || event.altKey ||
    composing.value || event.isComposing || event.keyCode === 229) return
  event.preventDefault()
  if (!event.repeat) void send()
}
async function send() {
  const draft = active.value
  const message = draft?.text.trim()
  if (!draft || !message || !canSend.value || busy.value) return
  const modelId = selectedModelId.value || undefined
  const previous = draft.turns?.at(-1)
  const replacingFailure = previous?.reply.errorCode && previous.message === message
  const pending = new AbortController()
  request = pending
  pendingTurn.value = { id: draft.id, message }
  void scrollToLatest()
  error.value = ''
  try {
    const context = (draft.turns ?? []).filter(turn => (turn.reply.ai || turn.reply.contextEligible) &&
        turn.reply.blocked === false && !turn.reply.errorCode)
      .slice(-4).map(turn => ({
        message: turn.message,
        answer: [turn.reply.answer, turn.reply.reference].filter(Boolean).join('\n\n').slice(0, 8000),
      }))
    while (context.length > 1 && context.reduce((size, turn) => size + turn.message.length + turn.answer.length, 0) > 16000) context.shift()
    const reply = await assistantApi.chat(message, pending.signal, model => {
      if (!pending.signal.aborted && request === pending && pendingTurn.value?.id === draft.id) {
        pendingTurn.value.model = model
        if (active.value?.id === draft.id) void scrollToLatest()
      }
    }, context, modelId)
    if (pending.signal.aborted) return
    if (reply.blocked) throw new Error(reply.answer)
    const previousTurns = draft.turns ?? []
    draft.turns = [...(replacingFailure ? previousTurns.slice(0, -1) : previousTurns).slice(-19), { message, reply }]
    draft.text = reply.errorCode ? message : ''
    draft.updatedAt = Date.now()
    save()
  } catch (failure) {
    if (!pending.signal.aborted && active.value?.id === draft.id) {
      error.value = failure instanceof Error ? failure.message : '发送失败，请稍后重试。'
    }
  } finally {
    if (request === pending) {
      request = undefined
      pendingTurn.value = null
    }
    if (active.value?.id === draft.id) void scrollToLatest()
  }
}
function retry() {
  if (busy.value) return
  const failed = turns.value.at(-1)
  if (!failed?.reply.errorCode) return
  updateDraft(failed.message)
  void send()
}
function useExample(text: string) {
  updateDraft(text)
  void nextTick(() => input.value?.focus())
}
function stopAnimation() {
  if (timer !== undefined) clearTimeout(timer)
  timer = undefined
}
function typeNext() {
  const target = examples[phrase]!
  let delay = deleting ? 42 : 105
  if (!deleting && animatedText.value === target) { deleting = true; delay = 2100 }
  else if (deleting && !animatedText.value) { deleting = false; phrase = (phrase + 1) % examples.length; delay = 450 }
  else animatedText.value = deleting ? animatedText.value.slice(0, -1) : target.slice(0, animatedText.value.length + 1)
  timer = setTimeout(typeNext, delay)
}
function syncAnimation() {
  stopAnimation()
  if (reducedMotion.value) { animatedText.value = examples[0]!; return }
  if (idle.value) timer = setTimeout(typeNext, 450)
}
function syncMotion() {
  reducedMotion.value = motionQuery?.matches ?? false
  animatedText.value = ''; phrase = 0; deleting = false
  syncAnimation()
}
function syncVisibility() { pageHidden.value = document.hidden }
const stopWatching = watch([idle, reducedMotion], syncAnimation, { flush: 'sync' })
onMounted(() => {
  try { history.value = readHistory(localStorage, props.userId) }
  catch { storageError.value = '本地会话记录暂时无法读取。' }
  motionQuery = window.matchMedia('(prefers-reduced-motion: reduce)')
  motionQuery.addEventListener('change', syncMotion)
  document.addEventListener('visibilitychange', syncVisibility)
  syncVisibility(); syncMotion()
  void loadStatus()
  void scrollToLatest()
})
onUnmounted(() => {
  request?.abort()
  stopWatching(); stopAnimation()
  motionQuery?.removeEventListener('change', syncMotion)
  document.removeEventListener('visibilitychange', syncVisibility)
})
</script>

<template>
  <section class="assistant" aria-labelledby="assistant-title">
    <aside id="assistant-history" class="assistant-sidebar" :class="{ 'is-open': sidebarOpen }" aria-label="会话记录">
      <div class="sidebar-heading"><span>会话记录</span><span class="local-label">本机</span></div>
      <button class="new-conversation" type="button" @click="newConversation"><EditPen aria-hidden="true" />新对话</button>
      <div class="conversation-list">
        <p class="list-label">最近会话</p>
        <div v-if="!drafts.length" class="history-empty"><ChatLineSquare aria-hidden="true" /><p>还没有会话记录</p><span>输入的问题会保存为草稿</span></div>
        <button v-for="draft in drafts" :key="draft.id" type="button" class="conversation" :class="{ selected: draft.id === history.activeId }" :aria-pressed="draft.id === history.activeId" @click="selectConversation(draft.id)">
          <span>{{ draft.turns?.[0]?.message || draft.text.trim() || '新对话' }}</span><small v-if="!draft.turns?.length">草稿</small>
        </button>
      </div>
      <p class="history-note">仅保存在当前浏览器</p>
    </aside>
    <div class="assistant-main">
      <header class="assistant-header">
        <div class="assistant-heading"><button class="history-toggle" type="button" :aria-expanded="sidebarOpen" aria-controls="assistant-history" :aria-label="sidebarOpen ? '收起会话记录' : '展开会话记录'" @click="sidebarOpen = !sidebarOpen"><Fold v-if="sidebarOpen" aria-hidden="true" /><Expand v-else aria-hidden="true" /></button><h2 id="assistant-title">平台助手</h2><span class="preview-label">只读试用</span></div>
        <div class="model-controls">
          <el-dropdown trigger="click" :disabled="busy" @command="selectModel">
            <button type="button" class="model-picker" :disabled="busy" aria-label="切换模型">
              <span>{{ selectedModel?.name || (enabled === null ? '读取模型…' : '选择模型') }}</span><ArrowDown aria-hidden="true" />
            </button>
            <template #dropdown>
              <el-dropdown-menu class="assistant-model-menu">
                <el-dropdown-item v-for="model in models" :key="model.id" :command="model.id" :disabled="!model.enabled">
                  <span class="model-option"><span>{{ model.name }}</span><small>{{ model.provider }} · {{ model.enabled ? model.id === selectedModelId ? '当前选择' : '已配置' : '未配置密钥' }}</small></span>
                </el-dropdown-item>
                <el-dropdown-item divided command="__refresh">刷新模型列表</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <button v-if="enabled === false" type="button" class="retry-button" @click="loadStatus">重新检查</button>
        </div>
      </header>
      <div class="assistant-body">
        <div v-if="!turns.length && !visiblePending" class="assistant-welcome">
          <p class="welcome-eyebrow">从一个问题开始</p>
          <h3>有什么可以帮你？</h3>
          <p class="welcome-description">可以自由提问，也可以查工单、分析经营情况，或了解平台操作</p>
          <div class="typing-line" aria-hidden="true"><span>{{ animatedText || '\u00a0' }}</span><span class="typing-caret" :class="{ paused: !idle || reducedMotion }"></span></div>
          <div class="suggestions" aria-label="示例问题">
            <button v-for="example in examples" :key="example" type="button" :disabled="busy" @click="useExample(example)">{{ example }}</button>
          </div>
        </div>
        <div v-else ref="messages" class="assistant-messages" role="log" aria-label="对话消息">
          <div v-for="(turn, index) in turns" :key="index" class="assistant-turn">
            <p class="question">{{ turn.message }}</p>
            <div class="answer">
              <span class="answer-label">{{ turn.reply.model || '平台助手' }}{{ turn.reply.errorCode ? ' · 调用失败' : '' }}</span>
              <p>{{ turn.reply.answer }}</p>
              <button v-if="turn.reply.errorCode && index === turns.length - 1" type="button" class="retry-button" :disabled="busy || !canSend" @click="retry">使用当前模型重试</button>
              <div v-if="turn.reply.reference" class="answer-facts"><strong>{{ turn.reply.source }}</strong><p>{{ turn.reply.reference }}</p></div>
              <div v-if="turn.reply.facts" class="answer-facts">
                <strong>平台数据 · 匹配 {{ turn.reply.facts.total }} 张</strong>
                <div v-if="turn.reply.facts.totalCny !== undefined">已确认最终核损合计 {{ turn.reply.facts.totalCny }} 元</div>
                <div v-for="row in turn.reply.facts.counts" :key="row.status">{{ statusLabels[row.status] }} · {{ row.count }} 张</div>
                <div v-for="row in turn.reply.facts.orders" :key="row.businessNo">{{ row.businessNo }} · {{ statusLabels[row.status] }} · {{ new Date(row.updatedAt).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' }) }}</div>
                <div v-if="turn.reply.facts.orders.length && turn.reply.facts.orders.length < turn.reply.facts.total">显示最近 {{ turn.reply.facts.orders.length }} 张</div>
              </div>
            </div>
          </div>
          <div v-if="visiblePending" class="assistant-turn">
            <p class="question">{{ visiblePending.message }}</p>
            <div class="assistant-pending" role="status">
              <span class="assistant-dots" aria-hidden="true"><i></i><i></i><i></i></span>
              <span>{{ visiblePending.model ? `正在调用 ${visiblePending.model} 生成回答…` : '正在检查敏感信息…' }}</span>
            </div>
          </div>
        </div>
        <form class="composer" @submit.prevent="send">
          <label class="sr-only" for="assistant-input">向平台助手提问</label>
          <textarea id="assistant-input" ref="input" :value="visiblePending ? '' : draftText" :readonly="busy" rows="2" maxlength="4000" placeholder="输入问题，或接着上一条追问…" aria-describedby="assistant-send-note" @keydown="onComposerKey" @compositionstart="composing = true" @compositionend="composing = false" @input="updateDraft(($event.target as HTMLTextAreaElement).value)" @focus="focused = true" @blur="focused = false" />
          <div class="composer-footer"><span>Enter 发送 · Shift + Enter 换行</span>
            <button v-if="busy" type="button" class="stop-button" aria-label="停止等待" @click="stopWaiting"><span aria-hidden="true"></span></button>
            <button v-else type="submit" class="send-button" :disabled="!canSend || !draftText.trim()" aria-label="发送"><ArrowUp aria-hidden="true" /></button>
          </div>
        </form>
        <p id="assistant-send-note" class="send-note">通过敏感信息检查后，本条提问与最近最多四轮有效问答将发给{{ selectedModel?.provider || '所选供应商' }}。平台数据只读。</p>
        <p v-if="error" role="alert" class="storage-error">{{ error }} <button v-if="!busy && draftText.trim() && canSend" type="button" class="retry-button" @click="send">重试发送</button></p>
        <p v-if="storageError" role="alert" class="storage-error">{{ storageError }}</p>
      </div>
    </div>
  </section>
</template>

<style scoped>
.model-controls{display:flex;align-items:center;gap:8px;min-width:0}.model-picker{display:flex;align-items:center;gap:7px;max-width:300px;padding:7px 9px;border:0;border-radius:8px;background:transparent;color:var(--color-text);font-size:12px!important}.model-picker:hover{background:var(--color-accent-soft)}.model-picker>span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.model-picker svg{width:13px;height:13px}.model-picker:disabled{opacity:.6;cursor:wait}.model-option{display:flex;flex-direction:column;gap:3px;padding:6px 0;line-height:1.5}.model-option small{font-size:11px;color:var(--color-text-secondary)}.retry-button{padding:5px 9px;border:1px solid var(--color-border);border-radius:7px;background:transparent;color:var(--color-text-secondary);font-size:11px!important}.retry-button:disabled{opacity:.5;cursor:not-allowed}.stop-button{display:grid;place-items:center;width:30px;height:30px;border:0;border-radius:50%;background:var(--color-accent);color:#fff}.stop-button>span{width:10px;height:10px;border-radius:2px;background:currentColor}
@media(max-width:760px){.assistant-header{flex-wrap:wrap}.model-picker{max-width:230px}.model-controls{margin-left:auto}}
.assistant{display:grid;grid-template-columns:216px minmax(0,1fr);min-height:480px;margin:24px 0;overflow:hidden;border:1px solid var(--color-border);border-radius:12px;background:var(--color-surface);color:var(--color-text);box-shadow:var(--shadow-surface)}
.assistant button,.assistant textarea{font:inherit}.assistant button{cursor:pointer}.assistant button:focus-visible{outline:2px solid var(--color-accent);outline-offset:3px}.assistant svg{width:18px;height:18px;flex:none}.assistant-sidebar{display:flex;flex-direction:column;min-width:0;padding:22px 14px 16px;border-right:1px solid var(--color-border);background:#f5f4f0}.sidebar-heading{display:flex;align-items:center;justify-content:space-between;padding:0 8px 20px;font-size:13px;font-weight:600}.local-label{font-size:10px;font-weight:400;color:var(--color-text-secondary)}.new-conversation{display:flex;gap:10px;align-items:center;min-height:40px;padding:10px 12px;border:1px solid var(--color-border);border-radius:7px;background:#fff;color:var(--color-text);font-size:13px!important}.new-conversation:hover{background:var(--color-accent-soft)}.conversation-list{flex:1;max-height:320px;overflow-y:auto;padding:20px 0}.list-label{margin:0 8px 12px;font-size:11px;color:var(--color-text-secondary)}.history-empty{padding:22px 5px;color:var(--color-text-secondary);text-align:center}.history-empty svg{width:24px;height:24px;opacity:.55}.history-empty p{margin:10px 0 5px;font-size:12px}.history-empty span{font-size:10px}.conversation{display:flex;align-items:center;gap:8px;width:100%;padding:11px 10px;border:0;border-radius:6px;background:transparent;color:var(--color-text);text-align:left}.conversation>span{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:12px}.conversation small{flex:none;margin-left:auto;font-size:10px;color:var(--color-text-secondary)}.conversation:hover,.conversation.selected{background:#e8e6e0}.history-note{margin:0;padding:0 8px;font-size:10px;color:var(--color-text-secondary)}.assistant-main{min-width:0;display:flex;flex-direction:column}.assistant-header{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:20px 26px}.assistant-heading{display:flex;align-items:center;gap:10px}.assistant-heading h2{margin:0;font-size:15px;font-weight:600}.preview-label{border:1px solid var(--color-border);border-radius:4px;padding:2px 5px;font-size:10px;color:var(--color-text-secondary)}.connection-status{font-size:11px;color:var(--color-text-secondary)}.history-toggle{display:none;align-items:center;justify-content:center;padding:5px;border:0;background:transparent;color:var(--color-text)}.assistant-body{display:flex;flex:1;flex-direction:column;align-items:center;padding:20px 36px 17px}.assistant-welcome{display:flex;flex:1;flex-direction:column;align-items:center;justify-content:center;width:100%;text-align:center;padding:4px 0 30px}.welcome-eyebrow{margin:0 0 12px;color:var(--color-text-secondary);font-size:11px;letter-spacing:.12em}.assistant-welcome h3{margin:0;font-size:clamp(24px,2.4vw,32px);font-weight:500;letter-spacing:-.04em;line-height:1.4}.welcome-description{margin:12px 0 0;color:var(--color-text-secondary);font-size:12px}.typing-line{display:flex;align-items:center;justify-content:center;min-height:26px;max-width:100%;margin-top:22px;font-size:13px;color:var(--color-text-secondary)}.typing-line>span:first-child{white-space:pre-wrap;overflow-wrap:anywhere}.typing-caret{flex:none;width:2px;height:16px;margin-left:3px;background:var(--color-accent);animation:caret-blink 1s steps(1) infinite}.typing-caret.paused{animation:none;opacity:.35}.suggestions{display:flex;flex-wrap:wrap;justify-content:center;gap:8px;margin-top:17px}.suggestions button{padding:6px 12px;border:1px solid var(--color-border);border-radius:18px;background:transparent;color:var(--color-text-secondary);font-size:11px}.suggestions button:hover{background:var(--color-accent-soft);color:var(--color-text)}.composer{box-sizing:border-box;width:100%;max-width:760px;padding:15px 16px 10px;border:1px solid var(--color-border);border-radius:16px;background:#fff;box-shadow:0 4px 20px rgba(65,55,38,.04)}.composer:focus-within{border-color:var(--color-border-strong)}.composer textarea{display:block;box-sizing:border-box;width:100%;height:56px;max-height:160px;resize:vertical;padding:0;border:0;outline:none;background:transparent;color:var(--color-text);font-size:13px;line-height:1.7}.composer textarea::placeholder{color:var(--color-text-secondary)}.composer-footer{display:flex;align-items:center;justify-content:space-between;gap:12px}.composer-footer>span{font-size:10px;color:var(--color-text-secondary)}.send-button{display:grid;place-items:center;flex:none;width:32px;height:32px;padding:0;border:0;border-radius:50%;background:#eae8e3;color:#88857e;cursor:not-allowed!important}.send-note{margin:10px 0 0;font-size:10px;color:var(--color-text-secondary)}.storage-error{margin:8px 0 0;font-size:12px;color:var(--color-danger)}.sr-only{position:absolute;width:1px;height:1px;padding:0;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}
@keyframes caret-blink{50%{opacity:0}}
.assistant-messages{box-sizing:border-box;width:100%;max-width:760px;height:325px;overflow-y:auto;padding:0 6px 15px 0;margin-bottom:14px}
.assistant-turn{display:flex;flex-direction:column;gap:12px;margin:0 0 20px;font-size:13px;line-height:1.65;overflow-wrap:anywhere}
.question{align-self:flex-end;max-width:88%;margin:0;padding:9px 13px;border-radius:12px;background:var(--color-accent-soft);white-space:pre-wrap}
.answer{padding:0 12px}.answer p{margin:4px 0 10px;white-space:pre-wrap}
.answer-label,.answer-facts{font-size:11px;color:var(--color-text-secondary)}
.answer-facts{padding-left:10px;border-left:2px solid var(--color-border)}
.assistant-pending{display:flex;align-items:center;gap:9px;padding:7px 12px;color:var(--color-text-secondary);font-size:12px}
.assistant-dots{display:inline-flex;align-items:center;gap:4px;color:var(--color-accent);flex:none}
.assistant-dots i{width:4px;height:4px;border-radius:50%;background:currentColor;animation:assistant-dot 1.15s ease-in-out infinite}
.assistant-dots i:nth-child(2){animation-delay:.15s}.assistant-dots i:nth-child(3){animation-delay:.3s}
@keyframes assistant-dot{0%,70%,100%{opacity:.35;transform:translateY(0)}35%{opacity:1;transform:translateY(-3px)}}
.send-button:enabled{background:var(--color-accent);color:#fff;cursor:pointer!important}
@media(max-width:1100px){.assistant{grid-template-columns:180px minmax(0,1fr)}.assistant-body{padding-right:22px;padding-left:22px}}
@media(max-width:760px){.assistant{position:relative;grid-template-columns:minmax(0,1fr);min-height:460px}.assistant-sidebar{display:none;position:absolute;inset:57px auto 0 0;width:210px;z-index:3;box-sizing:border-box;box-shadow:8px 0 24px rgba(65,55,38,.08)}.assistant-sidebar.is-open{display:flex}.assistant-header{padding:16px}.history-toggle{display:flex}.assistant-body{padding:16px}.assistant-welcome{padding-bottom:25px}.connection-status{font-size:10px}.composer{padding:12px}.suggestions{gap:6px}.suggestions button{padding:6px 10px}}
@media(prefers-reduced-motion:reduce){.typing-caret,.assistant-dots i{animation:none}}
</style>
