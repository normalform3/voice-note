<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch, type ComponentPublicInstance } from 'vue'
import AgentResultBlocks from './AgentResult.vue'
import { statusText, type AgentConversationTurn, type AgentProgressEvent, type AgentResult, type AgentRunDetail, type AgentStep, type AgentStepDetail, type ResultCitation } from './api'

type AgentThreadTurn = AgentConversationTurn & { auxiliary?: boolean }

const props = defineProps<{
  conversationKey: string
  turns: AgentThreadTurn[]
  details: Record<string, AgentRunDetail>
  loadingRunIds: Record<string, boolean>
  liveProgressByRunId: Record<string, AgentProgressEvent[]>
  activeStepRunId: string | null
  activeStepId: string | null
  activeStepDetail: AgentStepDetail | null
  stepDetailLoading: boolean
  replayingCheckpointId: string | null
  traceActionRunId: string | null
  traceActionError: string
}>()

const emit = defineEmits<{
  ensureDetail: [runId: string]
  evidence: [payload: { runId: string; citation: ResultCitation }]
  toggleStep: [payload: { runId: string; step: AgentStep }]
  replay: [payload: { runId: string; checkpointId: string }]
  lineage: [runId: string]
}>()

const threadElement = ref<HTMLElement | null>(null)
const nearLatest = ref(true)
const showLatestButton = ref(false)
const turnElements = new Map<string, HTMLElement>()
let detailObserver: IntersectionObserver | null = null

const resultByTurnId = computed<Record<string, AgentResult | null>>(() => Object.fromEntries(props.turns.map(turn => {
  const raw = turn.runId ? props.details[turn.runId]?.run.resultDocument || turn.resultDocument : turn.resultDocument
  if (!raw) return [turn.id, null]
  try { return [turn.id, JSON.parse(raw) as AgentResult] }
  catch { return [turn.id, { answer: raw, findings: [] }] }
})))

const threadSignature = computed(() => props.turns.map(turn => [
  turn.id,
  statusFor(turn),
  rawResultFor(turn)?.length || 0,
  latestProgressFor(turn)?.sequence || 0,
  detailFor(turn)?.steps.map(step => `${step.id}-${step.status}`).join(',') || ''
].join(':')).join('|'))
const detailSignature = computed(() => Object.keys(props.details).sort().join('|'))

function rawResultFor(turn: AgentThreadTurn) {
  return turn.runId ? props.details[turn.runId]?.run.resultDocument || turn.resultDocument : turn.resultDocument
}
function detailFor(turn: AgentThreadTurn) { return turn.runId ? props.details[turn.runId] : undefined }
function resultFor(turn: AgentThreadTurn) { return resultByTurnId.value[turn.id] }
function statusFor(turn: AgentThreadTurn) { return (turn.runId ? detailFor(turn)?.run.status : undefined) || turn.runStatus || '' }
function failureFor(turn: AgentThreadTurn) { return (turn.runId ? detailFor(turn)?.run.failureMessage : undefined) || turn.failureMessage || '' }
function isBusy(turn: AgentThreadTurn) { return ['PENDING', 'QUEUED', 'RUNNING'].includes(statusFor(turn)) }
function isTerminal(turn: AgentThreadTurn) { return Boolean(statusFor(turn) && !isBusy(turn)) }
function latestProgressFor(turn: AgentThreadTurn) {
  if (!turn.runId) return undefined
  const events = props.liveProgressByRunId[turn.runId]
  return events?.[events.length - 1]
}
function skillNameFor(turn: AgentThreadTurn) {
  const run = detailFor(turn)?.run
  if (!run) return 'Agent'
  if (run.skillId === 'auto' || run.skillVersion === 'pending') return isBusy(turn) ? '正在匹配 Skill' : 'Agent'
  return run.skillDisplayName || run.skillId
}
function usageFor(turn: AgentThreadTurn) {
  const run = detailFor(turn)?.run
  return run ? `模型 ${run.modelCallsUsed}/${run.maxModelCalls} · 工具 ${run.toolCallsUsed}/${run.maxToolCalls}` : ''
}
function initialReplayCheckpoint(turn: AgentThreadTurn) { return detailFor(turn)?.checkpoints.find(checkpoint => !checkpoint.stepId && checkpoint.replayable) }
function isStepExpanded(runId: string, stepId: string) { return props.activeStepRunId === runId && props.activeStepId === stepId }
function traceErrorFor(runId: string) { return props.traceActionRunId === runId ? props.traceActionError : '' }
function formatTurnTime(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false }).format(date)
}
function formatDuration(milliseconds?: number) {
  if (milliseconds == null) return '—'
  if (milliseconds < 1000) return `${milliseconds}ms`
  return `${Math.round(milliseconds / 1000)} 秒`
}
function stepLabel(type: string, toolName?: string) {
  if (type === 'ROUTE') return '选择任务 Skill'
  if (type === 'MODEL') return 'Agent 决策'
  if (type === 'FINALIZE') return '校验证据并提交答案'
  if (type === 'RECOVERY') return '从 Checkpoint 恢复'
  return ({ document_list: '筛选文档范围', document_overview: '读取文档概览', knowledge_search: '混合检索与重排', transcript_context: '读取相邻原文' } as Record<string, string>)[toolName || ''] || toolName || '调用只读工具'
}
function readableTraceValue(value: unknown) {
  if (value == null) return '无'
  return typeof value === 'string' ? value : JSON.stringify(value, null, 2)
}
function handleEvidence(turn: AgentThreadTurn, citation: ResultCitation) {
  if (!turn.runId) return
  emit('evidence', { runId: turn.runId, citation })
}
function handleTraceToggle(turn: AgentThreadTurn, event: Event) {
  if ((event.currentTarget as HTMLDetailsElement).open && turn.runId) emit('ensureDetail', turn.runId)
}
function setTurnElement(runId: string | undefined, value: Element | ComponentPublicInstance | null) {
  if (!runId) return
  const previous = turnElements.get(runId)
  if (previous) detailObserver?.unobserve(previous)
  const element = value instanceof HTMLElement ? value : null
  if (!element) { turnElements.delete(runId); return }
  turnElements.set(runId, element)
  detailObserver?.observe(element)
}
function updateScrollPosition() {
  const element = threadElement.value
  if (!element) return
  nearLatest.value = element.scrollHeight - element.scrollTop - element.clientHeight < 120
  showLatestButton.value = !nearLatest.value
}
function scrollToLatest(behavior: ScrollBehavior = 'smooth') {
  const element = threadElement.value
  if (!element) return
  element.scrollTo({ top: element.scrollHeight, behavior })
  nearLatest.value = true
  showLatestButton.value = false
}

watch(() => props.conversationKey, async () => {
  nearLatest.value = true
  await nextTick()
  scrollToLatest('auto')
})
watch(threadSignature, async (_, previous) => {
  const shouldFollow = nearLatest.value || !previous
  await nextTick()
  if (shouldFollow) scrollToLatest(previous ? 'smooth' : 'auto')
  else showLatestButton.value = true
})
watch(detailSignature, async () => {
  if (!nearLatest.value) return
  await nextTick()
  scrollToLatest('auto')
})

onMounted(() => {
  if ('IntersectionObserver' in window) {
    detailObserver = new IntersectionObserver(entries => entries.forEach(entry => {
      if (!entry.isIntersecting) return
      const runId = (entry.target as HTMLElement).dataset.runId
      if (runId && !props.details[runId] && !props.loadingRunIds[runId]) emit('ensureDetail', runId)
      detailObserver?.unobserve(entry.target)
    }), { root: threadElement.value, rootMargin: '180px 0px' })
    turnElements.forEach(element => detailObserver?.observe(element))
  } else {
    const latestRunId = [...props.turns].reverse().find(turn => turn.runId)?.runId
    if (latestRunId) emit('ensureDetail', latestRunId)
  }
  nextTick(() => scrollToLatest('auto'))
})
onBeforeUnmount(() => detailObserver?.disconnect())

defineExpose({ scrollToLatest })
</script>

<template>
  <section ref="threadElement" class="agent-thread" aria-label="Agent 对话消息" @scroll.passive="updateScrollPosition">
    <div v-if="!turns.length" class="agent-thread-empty"><slot name="empty" /></div>

    <ol v-else class="agent-message-list">
      <li v-for="turn in turns" :key="turn.id" class="agent-turn" :class="{ auxiliary: turn.auxiliary }">
        <article class="user-message">
          <header><span>{{ turn.auxiliary ? 'Run 回放' : `你 · 第 ${turn.turnIndex + 1} 轮` }}</span><time>{{ formatTurnTime(turn.createdAt) }}</time></header>
          <p>{{ turn.userMessage }}</p>
        </article>

        <article :ref="element => setTurnElement(turn.runId, element)" class="assistant-message" :data-run-id="turn.runId">
          <header class="assistant-message-head">
            <span><i aria-hidden="true"></i><b>{{ skillNameFor(turn) }}</b></span>
            <small>{{ statusFor(turn) ? statusText(statusFor(turn)) : '等待回答' }}<template v-if="usageFor(turn)"> · {{ usageFor(turn) }}</template></small>
          </header>

          <div v-if="detailFor(turn)?.run.failureCode" class="trace-failure">
            <b>{{ detailFor(turn)?.run.failureCode }}</b><span>{{ detailFor(turn)?.run.failureStage || '未知阶段' }}</span><p>{{ failureFor(turn) }}</p>
          </div>
          <template v-if="resultFor(turn)">
            <AgentResultBlocks :result="resultFor(turn)!" :evidence="detailFor(turn)?.evidence || []" @evidence="handleEvidence(turn, $event)" />
            <div v-if="resultFor(turn)?.coverage" class="coverage-strip">
              <span>范围 {{ resultFor(turn)?.coverage?.scopeDocumentCount }}</span><span>概览 {{ resultFor(turn)?.coverage?.overviewedDocumentIds.length }}</span><span>深入 {{ resultFor(turn)?.coverage?.searchedDocumentIds.length }}</span><span>引用 {{ resultFor(turn)?.coverage?.citedDocumentIds.length }}</span>
              <p v-if="resultFor(turn)?.coverage?.limitations.length">限制：{{ resultFor(turn)?.coverage?.limitations.join('；') }}</p>
            </div>
          </template>
          <p v-else-if="failureFor(turn)" class="turn-error">{{ failureFor(turn) }}</p>
          <div v-else-if="isBusy(turn)" class="turn-waiting">
            <span aria-hidden="true"><i></i><i></i><i></i></span>
            <p>{{ latestProgressFor(turn)?.message || 'Agent 正在规划检索、读取原文并校验证据…' }}</p>
            <small v-if="latestProgressFor(turn)">LIVE · {{ latestProgressFor(turn)?.phase }}</small>
          </div>
          <p v-else class="turn-empty-answer">这轮暂时没有可显示的回答。</p>

          <details v-if="turn.runId" class="agent-trace" @toggle="handleTraceToggle(turn, $event)">
            <summary><span>依据与运行轨迹</span><b><i v-if="isBusy(turn)" aria-hidden="true"></i>{{ detailFor(turn)?.steps.length || 0 }} 步</b></summary>
            <p v-if="loadingRunIds[turn.runId] && !detailFor(turn)" class="trace-loading">正在读取这轮的证据与运行记录…</p>
            <template v-else-if="detailFor(turn)">
              <nav v-if="detailFor(turn)?.run.parentRunId || detailFor(turn)?.childRunIds.length" class="run-lineage" aria-label="Run 演进关系">
                <span>LINEAGE</span>
                <button v-if="detailFor(turn)?.run.rootRunId && detailFor(turn)?.run.rootRunId !== turn.runId && detailFor(turn)?.run.rootRunId !== detailFor(turn)?.run.parentRunId" type="button" @click="emit('lineage', detailFor(turn)!.run.rootRunId!)">根 Run ↖</button>
                <button v-if="detailFor(turn)?.run.parentRunId" type="button" @click="emit('lineage', detailFor(turn)!.run.parentRunId!)">父 Run ↖</button>
                <button v-for="(childRunId, index) in detailFor(turn)?.childRunIds || []" :key="childRunId" type="button" @click="emit('lineage', childRunId)">子 Run {{ index + 1 }} ↗</button>
              </nav>

              <div v-if="initialReplayCheckpoint(turn) && isTerminal(turn)" class="initial-replay">
                <span>初始状态 · Checkpoint #{{ initialReplayCheckpoint(turn)?.sequence }}</span>
                <button type="button" :disabled="Boolean(replayingCheckpointId)" @click="emit('replay', { runId: turn.runId!, checkpointId: initialReplayCheckpoint(turn)!.id })">从此重新执行</button>
              </div>

              <ol v-if="detailFor(turn)?.steps.length" class="trace-steps">
                <li v-for="step in detailFor(turn)?.steps || []" :key="step.id" :class="[step.status.toLowerCase(), { expanded: isStepExpanded(turn.runId!, step.id) }]">
                  <i></i>
                  <button class="trace-step-open" type="button" :aria-expanded="isStepExpanded(turn.runId!, step.id)" @click="emit('toggleStep', { runId: turn.runId!, step })">
                    <span><b>{{ stepLabel(step.type, step.toolName) }}</b><small>{{ step.summary || step.errorMessage || statusText(step.status) }}</small></span>
                    <span class="trace-metrics"><time>{{ formatDuration(step.durationMs) }}</time><em v-if="step.totalTokens != null">{{ step.totalTokens }} tok</em><em v-if="detailFor(turn)?.run.recoveryCount">E{{ step.executionEpoch }}</em></span>
                  </button>
                  <button v-if="isTerminal(turn) && step.replayable && step.checkpointId" class="trace-replay" type="button" :disabled="Boolean(replayingCheckpointId)" @click.stop="emit('replay', { runId: turn.runId!, checkpointId: step.checkpointId })">{{ replayingCheckpointId === step.checkpointId ? '创建中…' : '从此重新执行' }}</button>
                  <div v-if="isStepExpanded(turn.runId!, step.id)" class="trace-detail">
                    <p v-if="stepDetailLoading">正在加载该步的可观察输入与输出…</p>
                    <template v-else-if="activeStepDetail">
                      <div class="trace-detail-meta"><span>状态 {{ statusText(activeStepDetail.status) }}</span><span v-if="activeStepDetail.finishReason">Finish {{ activeStepDetail.finishReason }}</span><span v-if="activeStepDetail.totalTokens != null">Token {{ activeStepDetail.inputTokens || 0 }} + {{ activeStepDetail.outputTokens || 0 }} = {{ activeStepDetail.totalTokens }}</span></div>
                      <section><b>可观察输入</b><pre>{{ readableTraceValue(activeStepDetail.input) }}</pre></section>
                      <section><b>可观察输出</b><pre>{{ readableTraceValue(activeStepDetail.output) }}</pre></section>
                      <section v-if="activeStepDetail.errorCode || activeStepDetail.errorMessage" class="trace-detail-error"><b>错误定位</b><p>{{ activeStepDetail.errorCode }}<template v-if="activeStepDetail.errorMessage"> · {{ activeStepDetail.errorMessage }}</template></p></section>
                    </template>
                  </div>
                </li>
              </ol>
              <p v-else class="trace-loading">本轮没有可显示的运行步骤。</p>
              <p v-if="traceErrorFor(turn.runId)" class="trace-action-error">{{ traceErrorFor(turn.runId) }}</p>
            </template>
          </details>
        </article>
      </li>
    </ol>

    <button v-if="showLatestButton" class="thread-latest" type="button" @click="scrollToLatest()">回到最新 <span>↓</span></button>
  </section>
</template>

<style scoped>
.agent-thread { position: relative; min-height: 0; overflow-y: auto; overscroll-behavior: contain; scrollbar-gutter: stable; }
.agent-thread-empty { min-height: 100%; }
.agent-message-list { display: grid; gap: 26px; margin: 0; padding: 24px clamp(18px, 3vw, 30px) 34px; list-style: none; }
.agent-turn { display: grid; gap: 11px; min-width: 0; animation: turn-reveal .28s both ease-out; }
.user-message { justify-self: end; width: min(82%, 430px); border: 1px solid #d9ddec; border-radius: 16px 16px 4px 16px; padding: 11px 13px 12px; color: #354158; background: linear-gradient(145deg, #f8f9fe, #eef1fb); box-shadow: 0 6px 18px rgba(63, 74, 116, .05); }
.user-message header { display: flex; align-items: center; justify-content: space-between; gap: 10px; color: #6672a3; font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .05em; }
.user-message time { color: #a0a5b5; font-size: 7px; }
.user-message p { margin: 6px 0 0; font-size: 13px; line-height: 1.75; white-space: pre-wrap; overflow-wrap: anywhere; }
.assistant-message { position: relative; min-width: 0; border: 1px solid #e0e0da; border-radius: 4px 18px 18px; padding: 16px 18px 17px; background: rgba(255, 254, 250, .94); box-shadow: 0 12px 30px rgba(39, 49, 61, .045); }
.assistant-message::before { position: absolute; top: 16px; left: -1px; width: 3px; height: 28px; border-radius: 0 3px 3px 0; background: #7a84b8; content: ''; }
.assistant-message-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-left: 3px; }
.assistant-message-head > span { display: inline-flex; min-width: 0; align-items: center; gap: 7px; color: #4f5d95; }
.assistant-message-head i { width: 7px; height: 7px; flex: 0 0 auto; border-radius: 50%; background: #7a84b8; box-shadow: 0 0 0 4px #eef0f8; }
.assistant-message-head b { overflow: hidden; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.assistant-message-head small { flex: 0 0 auto; color: #999da4; font-family: 'DM Mono', monospace; font-size: 8px; text-align: right; }
.turn-waiting { display: flex; min-width: 0; align-items: center; gap: 11px; margin-top: 17px; color: #737b84; font-size: 11px; }
.turn-waiting > span { display: flex; gap: 3px; }.turn-waiting i { width: 4px; height: 4px; border-radius: 50%; background: #7a84b8; animation: thinking 1s infinite ease-in-out; }.turn-waiting i:nth-child(2) { animation-delay: .14s; }.turn-waiting i:nth-child(3) { animation-delay: .28s; }.turn-waiting p { margin: 0; }
.turn-waiting p { min-width: 0; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.turn-waiting small { flex: 0 0 auto; color: #8b93b8; font-family: 'DM Mono', monospace; font-size: 7px; letter-spacing: .04em; }
.turn-error, .turn-empty-answer, .trace-loading { margin: 14px 0 0; color: #8b6460; font-size: 11px; line-height: 1.65; }.turn-empty-answer, .trace-loading { color: #999da4; }
.agent-trace { margin-top: 16px; border-top: 1px solid #e4e3dc; padding-top: 11px; }
.agent-trace summary { display: flex; align-items: center; justify-content: space-between; color: #79818a; cursor: pointer; font-size: 10px; list-style: none; }.agent-trace summary::-webkit-details-marker { display: none; }.agent-trace summary::before { width: 16px; height: 1px; margin-right: 7px; background: #cfd2dc; content: ''; }.agent-trace summary span { margin-right: auto; }.agent-trace summary b { display: inline-flex; align-items: center; gap: 5px; color: #59669f; font-family: 'DM Mono', monospace; font-size: 8px; }.agent-trace summary b i { width: 5px; height: 5px; border-radius: 50%; background: #7884ba; box-shadow: 0 0 0 3px rgba(120, 132, 186, .12); animation: live-pulse 1.4s infinite ease-out; }
.trace-steps { display: grid; gap: 3px; margin: 10px 0 0; padding: 0; list-style: none; }
.trace-steps > li { display: grid; grid-template-columns: 7px minmax(0, 1fr) auto; align-items: start; gap: 7px; border-radius: 7px; padding: 7px 5px; background: #fafaf7; }.trace-steps > li.expanded { box-shadow: inset 0 0 0 1px #dfe2dc; background: #fff; }.trace-steps > li > i { width: 6px; height: 6px; margin-top: 8px; border-radius: 50%; background: #aeb2b6; }.trace-steps > li.running > i { background: #7884ba; animation: live-pulse 1.4s infinite ease-out; }.trace-steps > li.succeeded > i { background: #5b8b78; }.trace-steps > li.failed > i { background: #a84d54; }.trace-steps > li.interrupted > i { background: #9c6f39; }
.thread-latest { position: sticky; z-index: 3; bottom: 12px; display: flex; width: max-content; align-items: center; gap: 7px; margin: -48px auto 12px; border: 1px solid #cfd4e6; border-radius: 999px; padding: 7px 11px; color: #4f5d95; background: rgba(255, 254, 250, .96); box-shadow: 0 10px 28px rgba(39, 49, 61, .12); font-size: 9px; font-weight: 700; backdrop-filter: blur(8px); }
.thread-latest span { font-family: 'DM Mono', monospace; }
.auxiliary .user-message { border-style: dashed; background: #f7f7f3; }
:deep(.agent-result-block) { padding-top: 16px; }:deep(.agent-result-block:first-child) { margin-top: 12px; }:deep(.block-copy .evidence-copy), :deep(.answer) { font-size: 15px; line-height: 1.9; }:deep(.finding-copy .evidence-copy), :deep(.item-copy .evidence-copy) { font-size: 13px; line-height: 1.8; }
@keyframes turn-reveal { from { opacity: 0; transform: translateY(6px); } to { opacity: 1; transform: translateY(0); } }
@keyframes thinking { 0%, 60%, 100% { opacity: .35; transform: translateY(0); } 30% { opacity: 1; transform: translateY(-2px); } }
@keyframes live-pulse { 0% { box-shadow: 0 0 0 0 rgba(120, 132, 186, .35); } 75%, 100% { box-shadow: 0 0 0 5px rgba(120, 132, 186, 0); } }
@media (max-width: 430px) { .agent-message-list { padding-right: 13px; padding-left: 13px; }.user-message { width: 90%; }.assistant-message { padding-right: 14px; padding-left: 14px; }.assistant-message-head { align-items: start; }.assistant-message-head small { max-width: 46%; } }
@media (prefers-reduced-motion: reduce) { .agent-turn, .turn-waiting i, .agent-trace summary b i, .trace-steps > li.running > i { animation: none; }.thread-latest { scroll-behavior: auto; } }
</style>
