<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import AgentResultBlocks from './AgentResult.vue'
import { statusText, type AgentAnswerBlockEvent, type AgentEvidence, type AgentProgressEvent, type AgentResult, type ResultCitation } from './api'
import { useSpeechRecognition, type SpeechRecognitionFailure } from './useSpeechRecognition'
import { splitSpeechText, useStreamingPcmAudio } from './useStreamingPcmAudio'

type VoicePhase = 'idle' | 'listening' | 'submitting' | 'running' | 'streaming' | 'speaking' | 'interrupted-listening' | 'queued-turn' | 'paused' | 'unsupported' | 'error'
type SpeechItem = { text: string; kind: 'progress' | 'answer' | 'retry' }

const props = defineProps<{
  busy: boolean
  runStatus?: string
  runQuestion?: string
  runFailure?: string
  result: AgentResult | null
  evidence: AgentEvidence[]
  scopeLabel: string
  skillLabel: string
  memoryEnabled: boolean
  liveProgress: AgentProgressEvent[]
  liveBlocks: AgentAnswerBlockEvent[]
  ttsEnabled: boolean
  submitMessage: (message: string) => Promise<void>
}>()
const emit = defineEmits<{ close: []; evidence: [citation: ResultCitation] }>()

const phase = ref<VoicePhase>('idle')
const sessionActive = ref(false)
const lastTranscript = ref('')
const queuedTranscript = ref('')
const notice = ref('')
const errorMessage = ref('')
const ttsDegraded = ref(false)
const primaryControl = ref<HTMLButtonElement | null>(null)
const handledUtterances = new Set<string>()
const audio = useStreamingPcmAudio()
let restartTimer: number | null = null
let previousBodyOverflow = ''
let interruptCapture = false
let speechQueue: SpeechItem[] = []
let speechDraining = false
let speechGeneration = 0
let speechSuppressedForRun = false
let progressSpeechCount = 0
let answerSpeechCharacters = 0
let lastProgressSpokenAt = 0
let lastReadableText = ''

const pendingStatuses = new Set(['PENDING', 'QUEUED', 'RUNNING'])
const failedStatuses = new Set(['FAILED', 'BUDGET_EXHAUSTED', 'TIMED_OUT'])
const isSpeaking = computed(() => audio.playing.value || phase.value === 'speaking')
const liveResult = computed<AgentResult | null>(() => props.liveBlocks.length
  ? { resultSchemaVersion: 3, blocks: props.liveBlocks.map(value => value.block) }
  : null)
const visibleProgress = computed(() => props.liveProgress.slice(-8))

const recognition = useSpeechRecognition({
  onStart: () => {
    if (!sessionActive.value) return
    phase.value = interruptCapture ? 'interrupted-listening' : 'listening'
    notice.value = ''
    errorMessage.value = ''
  },
  onFinal: (transcript, utteranceId) => { void submitTranscript(transcript, utteranceId) },
  onNoSpeech: () => recoverListening('没有听到清晰语音，正在继续聆听。'),
  onEndWithoutResult: () => recoverListening('这一轮没有形成可提交的文字，正在继续聆听。'),
  onError: failure => failRecognition(failure)
})

if (!recognition.supported) phase.value = 'unsupported'

const statusTitle = computed(() => {
  if (phase.value === 'unsupported') return '当前浏览器不支持语音模式'
  if (phase.value === 'listening') return '我在听'
  if (phase.value === 'interrupted-listening') return '请说出下一轮问题'
  if (phase.value === 'queued-turn') return '下一轮已记录'
  if (phase.value === 'submitting') return '收到，正在开始处理'
  if (phase.value === 'speaking') return '正在朗读本轮反馈'
  if (phase.value === 'streaming') return '答案正在形成'
  if (phase.value === 'running') return 'Agent 正在工作'
  if (phase.value === 'paused') return '语音会话已暂停'
  if (phase.value === 'error') return '语音会话需要处理'
  return sessionActive.value ? '准备继续聆听' : '开始语音会话'
})
const statusDetail = computed(() => {
  if (phase.value === 'unsupported') return '请改用桌面版 Chrome 或 Edge；原有文字问答仍可正常使用。'
  if (phase.value === 'listening') return '自然说出你的问题，停顿后会自动提交给当前 Agent。'
  if (phase.value === 'interrupted-listening') return '当前任务仍在执行；停顿后，这句话会暂存为下一轮。'
  if (phase.value === 'queued-turn') return '当前任务终止后会立即提交，不会取消正在进行的工作。'
  if (phase.value === 'submitting') return '语音已经识别完成，正在创建 Agent 任务。'
  if (phase.value === 'speaking') return '你可以打断朗读并直接说出下一轮问题。'
  if (phase.value === 'streaming') return '已验证的完整区块会即时出现，最终结果仍会再做一次完整校验。'
  if (phase.value === 'running') return '正在检索、读取并核对证据；安全进度会持续更新。'
  if (phase.value === 'paused') return '点击继续后会重新启用麦克风，不会取消正在执行的任务。'
  if (phase.value === 'error') return errorMessage.value || props.runFailure || '请处理错误后手动恢复语音会话。'
  return '首次启动会请求麦克风权限，之后每轮由停顿自动分隔。'
})
const liveTranscript = computed(() => recognition.interimTranscript.value
  || (phase.value === 'listening' || phase.value === 'interrupted-listening' ? '正在等待你说话…'
    : queuedTranscript.value || lastTranscript.value || '开始后，实时识别文字会显示在这里。'))
const displayQuestion = computed(() => lastTranscript.value || props.runQuestion || '')
const actionLabel = computed(() => sessionActive.value ? '暂停连续会话' : phase.value === 'paused' || phase.value === 'error' ? '继续语音会话' : '开始语音会话')
const runStatusLabel = computed(() => props.runStatus ? statusText(props.runStatus) : '等待提问')

function clearRestartTimer() {
  if (restartTimer == null) return
  window.clearTimeout(restartTimer); restartTimer = null
}

function startListening(allowBusy = false) {
  if (!sessionActive.value || (!allowBusy && props.busy) || !recognition.supported) return
  clearRestartTimer(); notice.value = ''; errorMessage.value = ''
  phase.value = interruptCapture ? 'interrupted-listening' : 'listening'
  if (!recognition.start()) {
    sessionActive.value = false
    phase.value = recognition.supported ? 'error' : 'unsupported'
  }
}

function scheduleListening(delay = 650, allowBusy = false) {
  if (!sessionActive.value || (!allowBusy && props.busy) || !recognition.supported) return
  clearRestartTimer(); phase.value = 'idle'
  restartTimer = window.setTimeout(() => { restartTimer = null; startListening(allowBusy) }, delay)
}

function startSession() {
  if (!recognition.supported) { phase.value = 'unsupported'; return }
  sessionActive.value = true; notice.value = ''; errorMessage.value = ''
  if (props.ttsEnabled) void audio.prepare().catch(() => { /* A later playback attempt will show the non-blocking fallback. */ })
  if (queuedTranscript.value && !props.busy) { void submitQueuedTurn(); return }
  if (props.busy || (props.runStatus && pendingStatuses.has(props.runStatus))) { phase.value = props.liveBlocks.length ? 'streaming' : 'running'; return }
  startListening()
}

function pauseSession() {
  sessionActive.value = false; interruptCapture = false
  clearRestartTimer(); recognition.stop(); audio.stop(); speechQueue = []
  notice.value = '麦克风和朗读已停止；正在执行的 Agent 任务不会被取消。'
  errorMessage.value = ''; phase.value = 'paused'
}

function toggleSession() { if (sessionActive.value) pauseSession(); else startSession() }

function recoverListening(message: string) {
  if (!sessionActive.value || (props.busy && !interruptCapture)) return
  notice.value = message; scheduleListening(500, interruptCapture)
}

function failRecognition(failure: SpeechRecognitionFailure) {
  clearRestartTimer(); recognition.stop(); audio.stop(); speechQueue = []
  sessionActive.value = false; errorMessage.value = failure.message
  phase.value = failure.code === 'unsupported' ? 'unsupported' : 'error'
}

function resetSpeechTurn() {
  speechGeneration++; audio.stop(); speechQueue = []; speechDraining = false; speechSuppressedForRun = false; progressSpeechCount = 0
  answerSpeechCharacters = 0; lastProgressSpokenAt = 0; lastReadableText = ''; ttsDegraded.value = false
}

async function submitTranscript(transcript: string, utteranceId: string) {
  if (!sessionActive.value || handledUtterances.has(utteranceId)) return
  handledUtterances.add(utteranceId); clearRestartTimer(); recognition.stop()
  if (interruptCapture && props.busy) {
    queuedTranscript.value = transcript; interruptCapture = false
    phase.value = 'queued-turn'; notice.value = '下一轮已记录；当前任务结束后会自动提交。'
    return
  }
  lastTranscript.value = transcript; queuedTranscript.value = ''; notice.value = ''; errorMessage.value = ''
  phase.value = 'submitting'; resetSpeechTurn()
  try {
    await props.submitMessage(transcript)
    if (sessionActive.value) phase.value = 'running'
  } catch (error) {
    sessionActive.value = false; phase.value = 'error'
    errorMessage.value = error instanceof Error ? error.message : '无法提交本轮语音，请稍后重试。'
  }
}

async function submitQueuedTurn() {
  const transcript = queuedTranscript.value.trim()
  if (!transcript || props.busy || !sessionActive.value) return
  queuedTranscript.value = ''; lastTranscript.value = transcript; phase.value = 'submitting'; resetSpeechTurn()
  try {
    await props.submitMessage(transcript)
    if (sessionActive.value) phase.value = 'running'
  } catch (error) {
    sessionActive.value = false; phase.value = 'error'
    errorMessage.value = error instanceof Error ? error.message : '无法提交已暂存的下一轮问题。'
  }
}

function enqueueSpeech(text: string, kind: SpeechItem['kind']) {
  if (!props.ttsEnabled || ttsDegraded.value || !sessionActive.value || speechSuppressedForRun) return
  let allowed = text.replace(/\s+/g, ' ').trim()
  if (!allowed) return
  if (kind === 'progress') {
    if (progressSpeechCount >= 3) return
    progressSpeechCount++
  } else if (kind === 'answer') {
    const remaining = 600 - answerSpeechCharacters
    if (remaining <= 0) return
    allowed = allowed.slice(0, remaining); answerSpeechCharacters += allowed.length
  }
  lastReadableText = allowed
  speechQueue.push(...splitSpeechText(allowed).map(value => ({ text: value, kind })))
  void drainSpeech()
}

async function drainSpeech() {
  if (speechDraining || !props.ttsEnabled || ttsDegraded.value || !sessionActive.value) return
  speechDraining = true
  const generation = speechGeneration
  try {
    while (speechQueue.length && sessionActive.value && !ttsDegraded.value && generation === speechGeneration) {
      const item = speechQueue.shift()!
      if (item.kind === 'progress') {
        const wait = Math.max(0, 1500 - (Date.now() - lastProgressSpokenAt))
        if (wait) await new Promise(resolve => window.setTimeout(resolve, wait))
        if (generation !== speechGeneration) return
        lastProgressSpokenAt = Date.now()
      }
      phase.value = 'speaking'
      try { await audio.speak(item.text) }
      catch (error) {
        if ((error as DOMException).name === 'AbortError') return
        ttsDegraded.value = true; speechQueue = []
        notice.value = '朗读暂时不可用，本轮已降级为文字反馈。'
        if (props.busy) phase.value = props.liveBlocks.length ? 'streaming' : 'running'
        break
      }
    }
  } finally {
    if (generation === speechGeneration) {
      speechDraining = false
      if (sessionActive.value && !props.busy && !speechQueue.length) settleAfterRun()
      else if (sessionActive.value && props.busy && phase.value === 'speaking') phase.value = props.liveBlocks.length ? 'streaming' : 'running'
    }
  }
}

function interruptAndListen() {
  if (!props.ttsEnabled || !isSpeaking.value || !sessionActive.value) return
  audio.stop(); speechQueue = []; speechSuppressedForRun = true; interruptCapture = true
  notice.value = '朗读已停止，请说出下一轮问题。'
  startListening(true)
}

function retrySpeech() {
  if (!props.ttsEnabled || !lastReadableText) return
  ttsDegraded.value = false; enqueueSpeech(lastReadableText, 'retry')
}

function finalSpokenText(result: AgentResult | null) {
  if (!result) return ''
  if (result.answer) return result.answer
  const summary = result.blocks?.find(value => value.type === 'SUMMARY')
  const texts = summary?.statements?.map(value => value.text) || (summary?.content ? [summary.content] : [])
  return texts.join('。').slice(0, 600)
}

function settleAfterRun() {
  if (!sessionActive.value || props.busy) return
  if (queuedTranscript.value) { void submitQueuedTurn(); return }
  if (props.runStatus === 'SUCCEEDED') {
    notice.value = '本轮已经完成，正在恢复监听。'; scheduleListening(750); return
  }
  if (props.runStatus && failedStatuses.has(props.runStatus)) {
    sessionActive.value = false; phase.value = 'error'
    errorMessage.value = props.runFailure || 'Agent 未能完成本轮任务，请手动恢复后重试。'
  }
}

function close() {
  queuedTranscript.value = ''; pauseSession(); emit('close')
}
function handleKeydown(event: KeyboardEvent) { if (event.key === 'Escape') close() }

watch(() => props.liveProgress, (events, previous = []) => {
  if (!sessionActive.value) return
  const seen = new Set(previous.map(value => value.sequence))
  for (const event of events) if (!seen.has(event.sequence) && event.speakable) enqueueSpeech(event.message, 'progress')
  if (props.busy && !isSpeaking.value && events.length && !interruptCapture && !queuedTranscript.value) phase.value = props.liveBlocks.length ? 'streaming' : 'running'
})

watch(() => props.liveBlocks, (events, previous = []) => {
  if (!sessionActive.value) return
  const seen = new Set(previous.map(value => value.sequence))
  const incoming = events.filter(value => !seen.has(value.sequence))
  if (!incoming.length) return
  speechQueue = speechQueue.filter(value => value.kind !== 'progress')
  if (interruptCapture || queuedTranscript.value) return
  phase.value = isSpeaking.value ? 'speaking' : 'streaming'
  incoming.forEach(value => enqueueSpeech(value.spokenText, 'answer'))
})

watch(() => props.busy, (busy, wasBusy) => {
  if (!sessionActive.value) return
  if (busy) {
    clearRestartTimer()
    if (!interruptCapture) recognition.stop()
    if (phase.value !== 'submitting' && phase.value !== 'interrupted-listening' && phase.value !== 'queued-turn' && !isSpeaking.value) {
      phase.value = props.liveBlocks.length ? 'streaming' : 'running'
    }
    return
  }
  if (!wasBusy) return
  if (queuedTranscript.value) { void submitQueuedTurn(); return }
  if (props.runStatus && failedStatuses.has(props.runStatus)) {
    speechGeneration++; audio.stop(); speechQueue = []; speechDraining = false
    settleAfterRun(); return
  }
  if (props.runStatus === 'SUCCEEDED' && props.ttsEnabled && !ttsDegraded.value && answerSpeechCharacters === 0) {
    enqueueSpeech(finalSpokenText(props.result), 'answer')
  }
  if (!speechDraining && !speechQueue.length && !isSpeaking.value) settleAfterRun()
})

watch(() => props.ttsEnabled, enabled => { if (!enabled) { audio.stop(); speechQueue = []; ttsDegraded.value = false } })

onMounted(() => {
  previousBodyOverflow = document.body.style.overflow; document.body.style.overflow = 'hidden'
  window.addEventListener('keydown', handleKeydown)
  void nextTick(() => primaryControl.value?.focus())
})
onBeforeUnmount(() => {
  clearRestartTimer(); recognition.stop(); audio.dispose(); speechQueue = []
  document.body.style.overflow = previousBodyOverflow
  window.removeEventListener('keydown', handleKeydown)
})
</script>

<template>
  <Teleport to="body">
    <div class="voice-overlay">
      <section class="voice-dialog" role="dialog" aria-modal="true" aria-labelledby="voice-title">
        <header class="voice-header">
          <div><p class="voice-folio">VOICENOTE · LIVE SESSION</p><b>语音 Agent</b></div>
          <div class="voice-context" aria-label="当前会话设置">
            <span>{{ scopeLabel }}</span><span>{{ skillLabel }}</span><span>{{ memoryEnabled ? '长期记忆开启' : '长期记忆关闭' }}</span>
            <span v-if="ttsEnabled">摘要朗读开启</span>
          </div>
          <button class="voice-close" type="button" aria-label="退出语音模式" @click="close">退出 <i>×</i></button>
        </header>

        <div class="voice-body">
          <section class="voice-stage" :class="`phase-${phase}`">
            <p class="voice-state-label"><i aria-hidden="true"></i>{{ phase === 'listening' || phase === 'interrupted-listening' ? 'MICROPHONE ACTIVE' : phase === 'speaking' ? 'VOICE PLAYBACK' : ['running', 'streaming', 'submitting', 'queued-turn'].includes(phase) ? 'AGENT RUNNING' : 'VOICE SESSION' }}</p>
            <div class="voice-orbit" :class="{ active: phase === 'listening' || phase === 'interrupted-listening', working: ['running', 'streaming', 'submitting', 'speaking', 'queued-turn'].includes(phase) }">
              <span class="orbit orbit-one" aria-hidden="true"></span><span class="orbit orbit-two" aria-hidden="true"></span>
              <button ref="primaryControl" type="button" :disabled="phase === 'unsupported'" :aria-label="actionLabel" @click="toggleSession">
                <svg v-if="!sessionActive" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 15.25a3.5 3.5 0 0 0 3.5-3.5V6.5a3.5 3.5 0 1 0-7 0v5.25a3.5 3.5 0 0 0 3.5 3.5Z"/><path d="M5.75 11.25v.5a6.25 6.25 0 0 0 12.5 0v-.5M12 18v3M9.25 21h5.5"/></svg>
                <span v-else class="pause-mark" aria-hidden="true"><i></i><i></i></span>
              </button>
            </div>
            <div class="voice-copy" aria-live="polite">
              <h1 id="voice-title">{{ statusTitle }}</h1>
              <p>{{ statusDetail }}</p>
            </div>

            <article class="live-transcript" aria-live="polite">
              <header><span>LIVE TRANSCRIPT</span><small>{{ recognition.listening.value ? '实时识别中' : '单轮自动分隔' }}</small></header>
              <p :class="{ placeholder: !recognition.interimTranscript.value && !lastTranscript }">{{ liveTranscript }}</p>
              <em v-if="notice">{{ notice }}</em>
            </article>

            <button v-if="ttsEnabled && isSpeaking" class="voice-interrupt" type="button" @click="interruptAndListen">打断并说话 <span>↗</span></button>
            <button v-if="ttsEnabled && ttsDegraded && lastReadableText" class="voice-retry" type="button" @click="retrySpeech">重试朗读</button>
            <button v-if="phase !== 'unsupported'" class="voice-session-control" type="button" @click="toggleSession">
              {{ actionLabel }} <span>{{ sessionActive ? 'Ⅱ' : '↗' }}</span>
            </button>
          </section>

          <aside class="voice-result">
            <header><div><span>AGENT OUTPUT</span><h2>本轮反馈</h2></div><small>{{ runStatusLabel }}</small></header>
            <article v-if="displayQuestion" class="voice-question"><span>你刚才说</span><p>{{ displayQuestion }}</p></article>
            <article v-if="queuedTranscript" class="voice-queued"><span>NEXT TURN</span><p>{{ queuedTranscript }}</p></article>
            <template v-if="busy">
              <ol v-if="visibleProgress.length" class="voice-progress" aria-label="Agent 执行进度" aria-live="polite">
                <li v-for="(event, index) in visibleProgress" :key="event.sequence" :class="{ active: index === visibleProgress.length - 1 }"><i></i><span><b>{{ event.message }}</b><small>{{ event.phase.replace(/_/g, ' ') }}</small></span></li>
              </ol>
              <div v-else class="voice-working" aria-live="polite"><i></i><span><b>请求已接收，正在启动 Agent</b><small>无需等待下一次定时轮询。</small></span></div>
              <div v-if="liveResult" class="voice-result-content voice-stream-result">
                <p class="voice-stream-label"><i></i>已验证区块 · 最终结果生成中</p>
                <AgentResultBlocks :result="liveResult" :evidence="evidence" @evidence="emit('evidence', $event)" />
              </div>
            </template>
            <div v-else-if="result" class="voice-result-content"><AgentResultBlocks :result="result" :evidence="evidence" @evidence="emit('evidence', $event)" /></div>
            <div v-else-if="runFailure || phase === 'error'" class="voice-result-error"><b>本轮未完成</b><p>{{ errorMessage || runFailure }}</p></div>
            <div v-else class="voice-result-empty"><span>声</span><p>完成一次语音提问后，Agent 的结构化结果和证据会显示在这里。</p></div>
          </aside>
        </div>

        <footer class="voice-footer"><span><i></i>麦克风只在语音模式中启用，不保存原始音频{{ ttsEnabled ? '；朗读音频只在内存中播放' : '' }}。</span><small>语音识别可能由浏览器服务商处理 · ESC 退出</small></footer>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.voice-overlay { position: fixed; z-index: 100; inset: 0; display: grid; padding: clamp(16px, 3vw, 42px); place-items: center; background: rgba(24, 30, 39, .78); backdrop-filter: blur(18px); }
.voice-dialog { display: grid; width: min(1180px, 100%); height: min(790px, calc(100vh - clamp(32px, 6vw, 84px))); grid-template-rows: auto minmax(0, 1fr) auto; overflow: hidden; border: 1px solid rgba(255, 255, 255, .2); border-radius: 24px; color: #27313d; background: #f7f6f1; box-shadow: 0 38px 100px rgba(8, 13, 22, .42); animation: voice-enter .32s both cubic-bezier(.2, .8, .2, 1); }
.voice-header { display: grid; min-height: 76px; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 24px; padding: 14px 20px 14px 26px; border-bottom: 1px solid #dfded7; background: rgba(255, 254, 250, .88); }
.voice-header > div:first-child { display: grid; gap: 2px; }.voice-header b { font-family: 'Noto Serif SC', serif; font-size: 20px; letter-spacing: -.05em; }.voice-folio { margin: 0; color: #747eb8; font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .12em; }
.voice-context { display: flex; min-width: 0; flex-wrap: wrap; justify-content: center; gap: 6px; }.voice-context span { overflow: hidden; max-width: 220px; border: 1px solid #dcddd8; border-radius: 999px; padding: 5px 9px; color: #69727c; background: #fbfaf6; font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.voice-close { display: inline-flex; align-items: center; gap: 8px; border: 0; padding: 5px; color: #717982; background: transparent; font-size: 10px; }.voice-close i { display: grid; width: 28px; height: 28px; place-items: center; border: 1px solid #d9dad4; border-radius: 9px; color: #394450; background: #fffefa; font-size: 18px; font-style: normal; line-height: 1; }.voice-close:hover i { border-color: #bfc4d8; color: #59669f; }
.voice-body { display: grid; min-height: 0; grid-template-columns: minmax(0, .9fr) minmax(380px, 1.1fr); }
.voice-stage { position: relative; display: flex; min-height: 0; align-items: center; flex-direction: column; justify-content: center; overflow: hidden; padding: 42px clamp(28px, 5vw, 68px); color: #eef0f2; background: #26303b; isolation: isolate; }.voice-stage::before { position: absolute; z-index: -1; inset: -25%; background: radial-gradient(circle at 50% 42%, rgba(116, 126, 184, .24), transparent 34%), repeating-radial-gradient(circle at 50% 45%, transparent 0 54px, rgba(255,255,255,.025) 55px 56px); content: ''; }.voice-stage::after { position: absolute; z-index: -1; right: -100px; bottom: -130px; width: 330px; height: 330px; border: 1px solid rgba(255,255,255,.08); border-radius: 50%; content: ''; }
.voice-state-label { display: inline-flex; align-items: center; gap: 8px; margin: 0 0 28px; color: #aeb7c2; font-family: 'DM Mono', monospace; font-size: 9px; letter-spacing: .12em; }.voice-state-label i { width: 7px; height: 7px; border-radius: 50%; background: #87919c; box-shadow: 0 0 0 4px rgba(135,145,156,.11); }.phase-listening .voice-state-label i, .phase-interrupted-listening .voice-state-label i { background: #82bbaa; box-shadow: 0 0 0 4px rgba(130,187,170,.13); }.phase-speaking .voice-state-label i { background: #c7a86f; box-shadow: 0 0 0 4px rgba(199,168,111,.13); }.phase-error .voice-state-label i { background: #cf7c7c; }
.voice-orbit { position: relative; display: grid; width: 150px; height: 150px; place-items: center; }.voice-orbit .orbit { position: absolute; inset: 8px; border: 1px solid rgba(221, 226, 234, .16); border-radius: 50%; }.voice-orbit .orbit-two { inset: -9px; border-style: dashed; opacity: .55; }.voice-orbit.active .orbit-one { border-color: rgba(130,187,170,.55); animation: voice-breathe 1.8s infinite ease-in-out; }.voice-orbit.active .orbit-two { border-color: rgba(130,187,170,.35); animation: voice-spin 11s infinite linear; }.voice-orbit.working .orbit-one { border-color: rgba(150,161,214,.48); animation: voice-breathe 2.4s infinite ease-in-out; }.voice-orbit button { position: relative; z-index: 1; display: grid; width: 96px; height: 96px; place-items: center; border: 1px solid rgba(255,255,255,.17); border-radius: 50%; color: #26303b; background: #f5f3eb; box-shadow: 0 16px 40px rgba(5,10,18,.28), inset 0 0 0 7px rgba(255,255,255,.45); transition: transform .2s ease, box-shadow .2s ease; }.voice-orbit button:hover:not(:disabled) { transform: scale(1.035); box-shadow: 0 20px 46px rgba(5,10,18,.34), inset 0 0 0 7px rgba(255,255,255,.55); }.voice-orbit button:disabled { opacity: .45; }.voice-orbit svg { width: 31px; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.7; }.pause-mark { display: flex; gap: 7px; }.pause-mark i { width: 6px; height: 27px; border-radius: 4px; background: #26303b; }
.voice-copy { margin-top: 25px; text-align: center; }.voice-copy h1 { margin: 0; color: #fffefa; font-family: 'Noto Serif SC', serif; font-size: clamp(28px, 3.3vw, 43px); line-height: 1.2; letter-spacing: -.07em; }.voice-copy p { max-width: 450px; margin: 11px auto 0; color: #abb4be; font-size: 11px; line-height: 1.75; }
.live-transcript { width: min(100%, 500px); min-height: 104px; margin-top: 30px; border: 1px solid rgba(255,255,255,.11); border-radius: 15px; padding: 13px 15px; background: rgba(10,16,23,.22); box-shadow: inset 0 1px rgba(255,255,255,.04); }.live-transcript header { display: flex; align-items: center; justify-content: space-between; gap: 12px; color: #8e99a5; font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .09em; }.live-transcript small { color: #7d8792; font-size: 8px; letter-spacing: 0; }.live-transcript p { margin: 12px 0 0; color: #f1f0ea; font-family: 'Noto Serif SC', serif; font-size: 16px; font-weight: 600; line-height: 1.7; }.live-transcript p.placeholder { color: #8e98a3; font-family: 'Noto Sans SC', sans-serif; font-size: 12px; font-weight: 400; }.live-transcript em { display: block; margin-top: 8px; color: #c7a86f; font-size: 9px; font-style: normal; }
.voice-session-control, .voice-interrupt, .voice-retry { display: inline-flex; align-items: center; gap: 14px; margin-top: 12px; border: 1px solid rgba(255,255,255,.14); border-radius: 9px; padding: 8px 11px; color: #d9dde1; background: rgba(255,255,255,.04); font-size: 9px; }.voice-session-control span, .voice-interrupt span { color: #9ca8b5; font-family: 'DM Mono', monospace; }.voice-session-control:hover, .voice-retry:hover { border-color: rgba(255,255,255,.28); background: rgba(255,255,255,.07); }.voice-interrupt { border-color: rgba(199,168,111,.42); color: #f2dfba; background: rgba(199,168,111,.1); }.voice-interrupt:hover { background: rgba(199,168,111,.17); }
.voice-result { min-width: 0; overflow-y: auto; padding: clamp(28px, 4vw, 52px); background: #fffefa; }.voice-result > header { display: flex; align-items: start; justify-content: space-between; gap: 16px; border-bottom: 1px solid #e4e3dc; padding-bottom: 18px; }.voice-result > header span { color: #747eb8; font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .11em; }.voice-result > header h2 { margin: 3px 0 0; font-family: 'Noto Serif SC', serif; font-size: 30px; letter-spacing: -.06em; }.voice-result > header small { border-radius: 999px; padding: 5px 8px; color: #69727c; background: #f1f1ec; font-family: 'DM Mono', monospace; font-size: 8px; }
.voice-question { margin-top: 22px; border-left: 2px solid #747eb8; padding: 2px 0 2px 13px; }.voice-question span { color: #a1a5aa; font-size: 9px; }.voice-question p { margin: 5px 0 0; color: #414c58; font-family: 'Noto Serif SC', serif; font-size: 15px; font-weight: 600; line-height: 1.7; }
.voice-queued { margin-top: 14px; border: 1px solid #d9dfd7; border-radius: 11px; padding: 10px 12px; background: #f6faf4; }.voice-queued span { color: #6f967d; font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .1em; }.voice-queued p { margin: 5px 0 0; color: #48584d; font-size: 11px; line-height: 1.65; }
.voice-working { display: flex; align-items: center; gap: 14px; margin-top: 28px; border: 1px solid #e1e3ed; border-radius: 13px; padding: 16px; background: #f7f8fc; }.voice-working > i { width: 24px; height: 24px; flex: 0 0 auto; border: 2px solid #d8dbea; border-top-color: #59669f; border-radius: 50%; animation: voice-spin 1s infinite linear; }.voice-working span { display: grid; gap: 3px; }.voice-working b { color: #414c58; font-size: 12px; }.voice-working small { color: #858c95; font-size: 10px; line-height: 1.6; }
.voice-progress { display: grid; gap: 0; margin: 22px 0 0; padding: 0; list-style: none; }.voice-progress li { position: relative; display: flex; gap: 11px; min-height: 39px; color: #8d949b; }.voice-progress li::before { position: absolute; left: 4px; top: 14px; bottom: -2px; width: 1px; background: #e0e2e6; content: ''; }.voice-progress li:last-child::before { display: none; }.voice-progress li > i { position: relative; z-index: 1; width: 9px; height: 9px; flex: 0 0 auto; margin-top: 3px; border: 2px solid #fffefa; border-radius: 50%; background: #b9bec3; box-shadow: 0 0 0 1px #d9dcdf; }.voice-progress li.active > i { background: #747eb8; box-shadow: 0 0 0 4px #eef0fa; }.voice-progress li span { display: grid; gap: 2px; }.voice-progress li b { color: #66707a; font-size: 10px; font-weight: 500; }.voice-progress li.active b { color: #3e4854; font-weight: 650; }.voice-progress li small { color: #b0b4b8; font-family: 'DM Mono', monospace; font-size: 7px; letter-spacing: .07em; }
.voice-result-content { margin-top: 8px; }.voice-stream-result { margin-top: 16px; border-top: 1px solid #dedfe6; padding-top: 8px; }.voice-stream-label { display: flex; align-items: center; gap: 7px; margin: 4px 0 0; color: #747eb8; font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .06em; }.voice-stream-label i { width: 6px; height: 6px; border-radius: 50%; background: #747eb8; animation: voice-breathe 1.5s infinite; }.voice-result-error { margin-top: 24px; border-left: 3px solid #bd6969; padding: 10px 12px; color: #7a4747; background: #fff7f5; }.voice-result-error b { font-size: 12px; }.voice-result-error p { margin: 5px 0 0; font-size: 11px; line-height: 1.65; }.voice-result-empty { display: grid; min-height: 320px; place-items: center; align-content: center; gap: 12px; color: #9a9ea3; text-align: center; }.voice-result-empty span { display: grid; width: 58px; height: 58px; place-items: center; border: 1px solid #d9dce9; border-radius: 50%; color: #747eb8; font-family: 'Noto Serif SC', serif; font-size: 24px; }.voice-result-empty p { max-width: 320px; margin: 0; font-size: 11px; line-height: 1.8; }
.voice-footer { display: flex; min-height: 48px; align-items: center; justify-content: space-between; gap: 16px; padding: 10px 24px; border-top: 1px solid #dfded7; color: #7e858d; background: #f3f2ec; font-size: 9px; }.voice-footer span { display: inline-flex; align-items: center; gap: 7px; }.voice-footer span i { width: 6px; height: 6px; border-radius: 50%; background: #68a695; }.voice-footer small { color: #a0a4a8; font-size: 8px; }
@keyframes voice-enter { from { opacity: 0; transform: translateY(12px) scale(.985); } to { opacity: 1; transform: translateY(0) scale(1); } }
@keyframes voice-spin { to { transform: rotate(360deg); } }
@keyframes voice-breathe { 0%, 100% { opacity: .45; transform: scale(.94); } 50% { opacity: 1; transform: scale(1.08); } }
@media (max-width: 840px) { .voice-overlay { padding: 0; }.voice-dialog { width: 100%; height: 100vh; border: 0; border-radius: 0; }.voice-header { grid-template-columns: auto 1fr auto; gap: 10px; padding: 12px 15px; }.voice-context { justify-content: start; }.voice-context span:nth-child(n+2) { display: none; }.voice-body { grid-template-columns: 1fr; overflow-y: auto; }.voice-stage { min-height: 600px; }.voice-result { overflow: visible; }.voice-footer { flex-direction: column; align-items: start; gap: 2px; } }
@media (prefers-reduced-motion: reduce) { .voice-dialog, .voice-orbit .orbit, .voice-working > i { animation: none !important; } }
</style>
