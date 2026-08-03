<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api, hashFile, key, stageText, statusText, timecode, uploadErrorMessage, type AnalysisEvidence, type AnalysisRun, type AnalysisRunDetail, type KnowledgeDocument, type KnowledgeEvidence, type KnowledgeRun, type KnowledgeRunDetail, type PipelineStage, type Segment, type Task, type WorkspaceSnapshot } from './api'

type AgentScope = 'CURRENT_DOCUMENT' | 'CROSS_DOCUMENT'
type Finding = { title?: string; content?: string; evidence?: { chunkId?: string; segmentId: string }[] }

const token = ref(localStorage.getItem('voicenote_token') || '')
const loginMode = ref<'login' | 'register'>('login')
const account = ref('')
const password = ref('')
const authError = ref('')
const tasks = ref<Task[]>([])
const documents = ref<KnowledgeDocument[]>([])
const runs = ref<KnowledgeRun[]>([])
const analysisRuns = ref<AnalysisRun[]>([])
const selected = ref<Task | null>(null)
const segments = ref<Segment[]>([])
const audioUrl = ref('')
const file = ref<File | null>(null)
const uploading = ref(false)
const progress = ref('')
const question = ref('请总结近期会议中的关键结论、风险和下一步行动。')
const scope = ref<AgentScope>('CROSS_DOCUMENT')
const scopeTouched = ref(false)
const knowledge = ref<KnowledgeRunDetail | null>(null)
const analysis = ref<AnalysisRunDetail | null>(null)
const asking = ref(false)
const audio = ref<HTMLAudioElement | null>(null)
let streamController: AbortController | null = null
let reconnectTimer: number | null = null
let streamClosed = false
let reconnectDelay = 1000

const selectedDocument = computed(() => documents.value.find(document => document.transcriptionTaskId === selected.value?.id))
const selectedTitle = computed(() => selectedDocument.value?.title || (selected.value ? `录音 ${selected.value.id.slice(0, 8)}` : '从资料库选择一份听记'))
const canAnalyzeCurrent = computed(() => Boolean(selected.value?.transcriptReady))
const activeRun = computed(() => scope.value === 'CURRENT_DOCUMENT' ? analysis.value?.run : knowledge.value?.run)
const activeEvidence = computed(() => scope.value === 'CURRENT_DOCUMENT' ? analysis.value?.evidence || [] : knowledge.value?.evidence || [])
const activeRunUsage = computed(() => scope.value === 'CURRENT_DOCUMENT'
  ? `${analysis.value?.run.callsUsed || 0}/${analysis.value?.run.maxCalls || 0}`
  : `${knowledge.value?.run.toolCallsUsed || 0}/${knowledge.value?.run.maxToolCalls || 0}`)
const parsedAnswer = computed(() => {
  const raw = activeRun.value?.resultDocument
  if (!raw) return null
  try { return JSON.parse(raw) as { answer?: string; findings?: Finding[] } }
  catch { return { answer: raw, findings: [] } }
})
const visibleHistory = computed(() => scope.value === 'CURRENT_DOCUMENT'
  ? analysisRuns.value.filter(run => run.transcriptionTaskId === selected.value?.id)
  : runs.value)

async function authenticate() {
  authError.value = ''
  try {
    const { data } = await api.post(`/auth/${loginMode.value}`, { account: account.value, password: password.value })
    token.value = data.accessToken
    localStorage.setItem('voicenote_token', token.value)
    await loadWorkspace(); connectProgressEvents()
  } catch (error: any) { authError.value = error.response?.data?.message || '无法完成登录' }
}
async function loadWorkspace() { await Promise.all([loadTasks(), loadDocuments(), loadRuns(), loadAnalysisRuns()]) }
async function loadTasks() {
  const { data } = await api.get<Task[]>('/transcription-tasks')
  tasks.value = data
  if (!selected.value && data[0]) await choose(data[0])
}
async function loadDocuments() { const { data } = await api.get<KnowledgeDocument[]>('/knowledge-documents'); documents.value = data }
async function loadRuns() { const { data } = await api.get<KnowledgeRun[]>('/knowledge-runs'); runs.value = data }
async function loadAnalysisRuns() { const { data } = await api.get<AnalysisRun[]>('/analysis-runs'); analysisRuns.value = data }
async function choose(task: Task) {
  selected.value = task
  if (!scopeTouched.value) scope.value = task.transcriptReady ? 'CURRENT_DOCUMENT' : 'CROSS_DOCUMENT'
  const [transcript, audioResponse] = await Promise.all([
    api.get<Segment[]>(`/transcription-tasks/${task.id}/segments`),
    api.get(`/audio/${task.id}/content`, { responseType: 'blob' })
  ])
  segments.value = transcript.data
  if (audioUrl.value) URL.revokeObjectURL(audioUrl.value)
  audioUrl.value = URL.createObjectURL(audioResponse.data)
}
async function selectDocument(document: KnowledgeDocument) {
  const task = tasks.value.find(item => item.id === document.transcriptionTaskId)
  if (task) await choose(task)
}
function selectScope(value: AgentScope) { if (value === 'CURRENT_DOCUMENT' && !canAnalyzeCurrent.value) return; scopeTouched.value = true; scope.value = value }
function chooseFile(event: Event) { file.value = (event.target as HTMLInputElement).files?.[0] || null }
async function upload() {
  if (!file.value) return
  uploading.value = true
  let phase: 'intent' | 'content' | 'task' = 'intent'
  try {
    progress.value = '正在核验音频指纹…'
    const sha256 = await hashFile(file.value)
    progress.value = '正在创建上传意图…'
    const intent = await api.post('/uploads/intents', { sha256, contentLength: file.value.size, contentType: file.value.type || 'application/octet-stream', originalFilename: file.value.name }, { headers: { 'Idempotency-Key': key() } })
    if (!intent.data.contentReady) {
      phase = 'content'
      await api.put(`/uploads/intents/${intent.data.audioBlobId}/content`, file.value, {
        headers: { 'Content-Type': 'application/octet-stream' }, maxBodyLength: Infinity,
        onUploadProgress: event => { progress.value = `正在存入私有音频库… ${Math.round((event.loaded / (event.total || file.value!.size)) * 100)}%` }
      })
    }
    phase = 'task'; progress.value = '正在创建异步处理任务…'
    const { data: task } = await api.post<Task>('/transcription-tasks', { audioBlobId: intent.data.audioBlobId }, { headers: { 'Idempotency-Key': key() } })
    upsertTask(task); await choose(task)
    progress.value = '已进入异步处理流程；后续阶段会自动更新。'
  } catch (error: unknown) { progress.value = uploadErrorMessage(error, phase) }
  finally { uploading.value = false }
}
async function askAgent() {
  if (!question.value.trim()) return
  asking.value = true
  try {
    if (scope.value === 'CURRENT_DOCUMENT') {
      if (!selected.value?.transcriptReady) return
      const { data } = await api.post<AnalysisRun>('/analysis-runs', { transcriptionTaskId: selected.value.id, mode: 'custom', goal: question.value }, { headers: { 'Idempotency-Key': key() } })
      analysis.value = { run: data, evidence: [] }; upsertAnalysis(data)
    } else {
      const { data } = await api.post<KnowledgeRun>('/knowledge-runs', { question: question.value }, { headers: { 'Idempotency-Key': key() } })
      knowledge.value = { run: data, evidence: [] }; upsertKnowledge(data)
    }
  } catch (error: any) {
    if (scope.value === 'CURRENT_DOCUMENT') analysis.value = { run: { id: '', transcriptionTaskId: selected.value?.id || '', status: 'FAILED', callsUsed: 0, maxCalls: 0, failureMessage: error.response?.data?.message || '无法创建文档分析' }, evidence: [] }
    else knowledge.value = { run: { id: '', status: 'FAILED', toolCallsUsed: 0, maxToolCalls: 4, failureMessage: error.response?.data?.message || '无法创建知识任务' }, evidence: [] }
  } finally { asking.value = false }
}
async function loadKnowledgeDetail(runId: string) { const { data } = await api.get<KnowledgeRunDetail>(`/knowledge-runs/${runId}`); knowledge.value = data; upsertKnowledge(data.run) }
async function loadAnalysisDetail(runId: string) { const { data } = await api.get<AnalysisRunDetail>(`/analysis-runs/${runId}`); analysis.value = data; upsertAnalysis(data.run) }
async function openHistory(run: KnowledgeRun | AnalysisRun) {
  if ('transcriptionTaskId' in run) { selectScope('CURRENT_DOCUMENT'); await loadAnalysisDetail(run.id) }
  else { selectScope('CROSS_DOCUMENT'); await loadKnowledgeDetail(run.id) }
}
async function openEvidence(citation: { chunkId?: string; segmentId: string }) {
  let taskId: string | undefined
  if (scope.value === 'CURRENT_DOCUMENT') taskId = analysis.value?.run.transcriptionTaskId
  else {
    const evidence = (activeEvidence.value as KnowledgeEvidence[]).find(item => item.chunkId === citation.chunkId && item.segmentId === citation.segmentId)
    taskId = evidence?.transcriptionTaskId
  }
  const task = tasks.value.find(item => item.id === taskId)
  if (task && task.id !== selected.value?.id) await choose(task)
  const segment = segments.value.find(item => item.id === citation.segmentId)
  if (segment && audio.value) {
    audio.value.currentTime = segment.startMs / 1000
    await audio.value.play().catch(() => undefined)
    document.getElementById(`segment-${segment.id}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }
}
async function retryDocument(document: KnowledgeDocument) { await api.post(`/knowledge-documents/${document.id}/retry`); await loadDocuments() }
async function retryStage(stage: PipelineStage) {
  if (!selected.value) return
  const { data } = await api.post<Task>(`/transcription-tasks/${selected.value.id}/stages/${stage}/retry`, undefined, { headers: { 'Idempotency-Key': key() } })
  upsertTask(data)
}
function upsertTask(task: Task) {
  tasks.value = [task, ...tasks.value.filter(item => item.id !== task.id)]
  if (selected.value?.id === task.id) selected.value = task
  if (task.knowledgeDocument) {
    const document: KnowledgeDocument = { ...task.knowledgeDocument, transcriptionTaskId: task.id, updatedAt: new Date().toISOString() }
    documents.value = [document, ...documents.value.filter(item => item.id !== document.id)]
  }
}
function upsertKnowledge(run: KnowledgeRun) { runs.value = [run, ...runs.value.filter(item => item.id !== run.id)] }
function upsertAnalysis(run: AnalysisRun) { analysisRuns.value = [run, ...analysisRuns.value.filter(item => item.id !== run.id)] }
function applySnapshot(snapshot: WorkspaceSnapshot) {
  tasks.value = snapshot.tasks; documents.value = snapshot.documents; runs.value = snapshot.knowledgeRuns; analysisRuns.value = snapshot.analyses
  if (selected.value) { const update = tasks.value.find(task => task.id === selected.value?.id); if (update) selected.value = update }
  else if (tasks.value[0]) void choose(tasks.value[0])
}
function handleProgressEvent(name: string, payload: any) {
  if (name === 'snapshot') { applySnapshot(payload as WorkspaceSnapshot); return }
  if (name === 'task-stage-settled' && payload.task) { upsertTask(payload.task as Task); return }
  if (name === 'knowledge-run-settled' && payload.run) { void loadKnowledgeDetail(payload.run.id); return }
  if (name === 'analysis-run-settled' && payload.run) { void loadAnalysisDetail(payload.run.id) }
}
function parseSseFrame(frame: string) {
  let eventName = 'message'; const data: string[] = []
  for (const line of frame.split(/\r?\n/)) { if (line.startsWith('event:')) eventName = line.slice(6).trim(); if (line.startsWith('data:')) data.push(line.slice(5).trim()) }
  if (data.length) { try { handleProgressEvent(eventName, JSON.parse(data.join('\n'))) } catch { /* ignore malformed reconnect payload */ } }
}
async function connectProgressEvents() {
  if (!token.value || streamController) return
  streamClosed = false; streamController = new AbortController()
  try {
    const response = await fetch('/api/progress-events', { headers: { Authorization: `Bearer ${token.value}`, Accept: 'text/event-stream' }, signal: streamController.signal })
    if (!response.ok || !response.body) throw new Error('Progress stream is unavailable')
    reconnectDelay = 1000
    const reader = response.body.getReader(); const decoder = new TextDecoder(); let buffer = ''
    while (true) {
      const { done, value } = await reader.read(); if (done) break
      buffer += decoder.decode(value, { stream: true })
      const frames = buffer.split(/\r?\n\r?\n/); buffer = frames.pop() || ''
      frames.forEach(parseSseFrame)
    }
  } catch (error) { if ((error as DOMException).name !== 'AbortError') scheduleReconnect() }
  finally { streamController = null; if (!streamClosed && token.value && !reconnectTimer) scheduleReconnect() }
}
function scheduleReconnect() {
  if (streamClosed || reconnectTimer || !token.value) return
  const delay = reconnectDelay; reconnectDelay = Math.min(reconnectDelay * 2, 15000)
  reconnectTimer = window.setTimeout(() => { reconnectTimer = null; void connectProgressEvents() }, delay)
}
function stopProgressEvents() {
  streamClosed = true; streamController?.abort(); streamController = null
  if (reconnectTimer) window.clearTimeout(reconnectTimer); reconnectTimer = null
}
function formatDuration(milliseconds?: number) {
  if (milliseconds == null) return '等待中'
  if (milliseconds < 1000) return `${milliseconds}ms`
  return `${Math.round(milliseconds / 1000)} 秒`
}
function logout() {
  stopProgressEvents(); token.value = ''; localStorage.removeItem('voicenote_token'); selected.value = null; documents.value = []; runs.value = []; analysisRuns.value = []; knowledge.value = null; analysis.value = null
}
onMounted(() => { if (token.value) { void loadWorkspace(); void connectProgressEvents() } })
onBeforeUnmount(stopProgressEvents)
</script>

<template>
  <main v-if="!token" class="auth-shell">
    <section class="auth-panel">
      <p class="folio">VOICENOTE · 听记档案 01</p>
      <h1>让每一段声音<br>都能<span>被找回。</span></h1>
      <p class="lede">面向会议、面试与访谈的 AI 听记和私人知识库。录音转写、跨文档追问与原声回跳，在一个清醒的工作台里完成。</p>
      <div class="ink-rule"></div>
      <form @submit.prevent="authenticate">
        <label>账号 <input v-model="account" type="text" autocomplete="username" required placeholder="区分大小写，不含空格"></label>
        <label>密码 <input v-model="password" type="password" autocomplete="current-password" required placeholder="不设格式限制"></label>
        <p v-if="authError" class="error">{{ authError }}</p>
        <button class="primary">{{ loginMode === 'login' ? '进入听记工作台' : '创建 voicenote 账号' }} <span>↗</span></button>
      </form>
      <button class="quiet" @click="loginMode = loginMode === 'login' ? 'register' : 'login'">{{ loginMode === 'login' ? '第一次使用？创建账号' : '已有账号？返回登录' }}</button>
    </section>
    <aside class="auth-art" aria-hidden="true">
      <div class="sound-sheet sheet-one"></div><div class="sound-sheet sheet-two"></div>
      <p>LISTEN<br><i>TWICE.</i></p><small>01:24 / 42:16</small>
    </aside>
  </main>

  <main v-else class="app-shell">
    <header class="topbar">
      <a class="brand">voice<span>note</span></a>
      <p>音频听记与知识库</p>
      <div class="topbar-meta"><span>{{ documents.filter(item => item.status === 'READY').length }} 份已收录</span><button class="quiet logout" @click="logout">退出</button></div>
    </header>

    <aside class="library-rail">
      <section class="upload-card">
        <div><p class="folio">收录新音频</p><b>从声音开始</b></div>
        <input type="file" accept="audio/*" @change="chooseFile">
        <button class="primary compact" :disabled="!file || uploading" @click="upload">{{ uploading ? '正在处理' : '上传并转写' }} <span>→</span></button>
        <small v-if="progress">{{ progress }}</small>
      </section>
      <div class="rail-head"><p class="folio">知识文档</p><span>{{ documents.length }}</span></div>
      <div class="document-list">
        <article v-for="document in documents" :key="document.id" class="document-row" :class="{ active: selected?.id === document.transcriptionTaskId }" @click="selectDocument(document)">
          <div class="document-status" :class="document.status.toLowerCase()"></div>
          <div><b>{{ document.title }}</b><small>{{ statusText(document.status) }}</small></div>
          <button v-if="document.status === 'FAILED'" class="retry" @click.stop="retryDocument(document)">重试</button>
        </article>
        <p v-if="!documents.length" class="empty-rail">成功转写的录音会自动沉淀成可检索文档。</p>
      </div>
    </aside>

    <section class="workspace">
      <div class="workspace-head">
        <div><p class="folio">原始听记 {{ selectedDocument ? `· ${statusText(selectedDocument.status)}` : '' }}</p><h2>{{ selectedTitle }}</h2></div>
        <span v-if="selected" class="state-pill">{{ selected.progressPercent || 0 }}% · {{ stageText(selected.currentStage) }}</span>
      </div>
      <audio v-if="selected && audioUrl" ref="audio" :src="audioUrl" controls></audio>
      <div v-if="!selected" class="empty-page"><span>⌁</span><b>选择一份声音档案</b><p>上传音频后，voicenote 会将可回跳的转写沉淀到你的知识库。</p></div>
      <template v-else>
        <section v-if="selected.stages?.length" class="pipeline-card" aria-label="处理进度">
          <div class="pipeline-head"><div><p class="folio">处理流程</p><b>{{ stageText(selected.currentStage) }}</b></div><span>{{ selected.progressPercent || 0 }}%</span></div>
          <ol class="pipeline-stages">
            <li v-for="stage in selected.stages" :key="`${stage.stage}-${stage.attemptNumber}`" :class="stage.status.toLowerCase()">
              <i></i><div><b>{{ stageText(stage.stage) }}</b><small>{{ statusText(stage.status) }} · 等待 {{ formatDuration(stage.totalWaitDurationMs) }}</small><small v-if="stage.nextRetryAt">将在 {{ new Date(stage.nextRetryAt).toLocaleTimeString() }} 自动重试</small><small v-else-if="stage.errorMessage" class="error">{{ stage.errorMessage }}</small></div>
              <button v-if="(stage.stage === 'ASR_SUBMIT' || stage.stage === 'KNOWLEDGE_INDEX') && selected.retryableStages?.includes(stage.stage)" class="retry" @click="retryStage(stage.stage)">从此阶段重试</button>
            </li>
          </ol>
        </section>
        <div class="transcript">
        <article v-for="segment in segments" :key="segment.id" :id="`segment-${segment.id}`" class="segment">
          <time>{{ timecode(segment.startMs) }}</time><div><b>{{ segment.speaker || '说话人' }}</b><p>{{ segment.text }}</p></div>
        </article>
        <p v-if="!segments.length" class="empty-page small">{{ selected.currentStage === 'KNOWLEDGE_INDEX' ? '听记已保存，正在建立可跨文档检索的知识索引。' : '转写完成后，带说话人和时间戳的听记会出现在这里。' }}</p>
        </div>
      </template>
    </section>

    <aside class="agent-rail">
      <p class="folio">询问你的资料库</p>
      <h3>把问题交给<br><em>听记 Agent</em></h3>
      <div class="scope-switch" role="group" aria-label="分析范围">
        <button :class="{ active: scope === 'CURRENT_DOCUMENT' }" :disabled="!canAnalyzeCurrent" @click="selectScope('CURRENT_DOCUMENT')">当前文档</button>
        <button :class="{ active: scope === 'CROSS_DOCUMENT' }" @click="selectScope('CROSS_DOCUMENT')">跨文档</button>
      </div>
      <textarea v-model="question" rows="5" placeholder="例如：本周会议有哪些未决事项？"></textarea>
      <button class="primary ask" :disabled="asking || !question.trim() || (scope === 'CURRENT_DOCUMENT' && !canAnalyzeCurrent)" @click="askAgent">{{ asking ? '正在创建任务' : scope === 'CURRENT_DOCUMENT' ? '开始当前文档分析' : '开始跨文档分析' }} <span>→</span></button>
      <p class="agent-note">{{ scope === 'CURRENT_DOCUMENT' ? '当前文档分析会使用已保存的听记快照；无需等待知识索引完成。' : '跨文档分析只检索已收录听记；结论会附上可回到原声的证据。' }}</p>

      <section v-if="activeRun" class="result-card">
        <div class="result-head"><span>{{ scope === 'CURRENT_DOCUMENT' ? '文档分析' : '知识任务' }}</span><small>{{ statusText(activeRun.status) }} · {{ activeRunUsage }}</small></div>
        <template v-if="parsedAnswer">
          <p class="answer">{{ parsedAnswer.answer }}</p>
          <article v-for="(finding, index) in parsedAnswer.findings" :key="index" class="finding">
            <b>{{ finding.title || `发现 ${index + 1}` }}</b><p>{{ finding.content }}</p>
            <button v-for="citation in finding.evidence" :key="`${citation.chunkId || 'local'}-${citation.segmentId}`" class="citation" @click="openEvidence(citation)">原文证据 ↗</button>
          </article>
        </template>
        <p v-else-if="activeRun.failureMessage" class="error">{{ activeRun.failureMessage }}</p>
        <p v-else class="waiting">{{ scope === 'CURRENT_DOCUMENT' ? '正在阅读当前听记并整理证据…' : '正在检索、读取原文并整理证据…' }}</p>
      </section>

      <section v-if="visibleHistory.length" class="run-history"><p class="folio">最近任务</p><button v-for="run in visibleHistory.slice(0, 4)" :key="run.id" @click="openHistory(run)"><span>{{ statusText(run.status) }}</span><small>{{ run.id.slice(0, 8) }}</small></button></section>
    </aside>
  </main>
</template>
