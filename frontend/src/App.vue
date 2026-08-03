<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, hashFile, key, statusText, timecode, type KnowledgeDocument, type KnowledgeEvidence, type KnowledgeRun, type KnowledgeRunDetail, type Segment, type Task } from './api'

const token = ref(localStorage.getItem('voicenote_token') || '')
const loginMode = ref<'login' | 'register'>('login')
const account = ref('')
const password = ref('')
const authError = ref('')
const tasks = ref<Task[]>([])
const documents = ref<KnowledgeDocument[]>([])
const runs = ref<KnowledgeRun[]>([])
const selected = ref<Task | null>(null)
const segments = ref<Segment[]>([])
const audioUrl = ref('')
const file = ref<File | null>(null)
const uploading = ref(false)
const progress = ref('')
const question = ref('请总结近期会议中的关键结论、风险和下一步行动。')
const knowledge = ref<KnowledgeRunDetail | null>(null)
const asking = ref(false)
const audio = ref<HTMLAudioElement | null>(null)

const selectedDocument = computed(() => documents.value.find(document => document.transcriptionTaskId === selected.value?.id))
const selectedTitle = computed(() => selectedDocument.value?.title || (selected.value ? `录音 ${selected.value.id.slice(0, 8)}` : '从资料库选择一份听记'))
const parsedAnswer = computed(() => {
  const raw = knowledge.value?.run.resultDocument
  if (!raw) return null
  try { return JSON.parse(raw) as { answer?: string; findings?: { title?: string; content?: string; evidence?: { chunkId: string; segmentId: string }[] }[] } }
  catch { return { answer: raw, findings: [] } }
})

async function authenticate() {
  authError.value = ''
  try {
    const { data } = await api.post(`/auth/${loginMode.value}`, { account: account.value, password: password.value })
    token.value = data.accessToken
    localStorage.setItem('voicenote_token', token.value)
    await loadWorkspace()
  } catch (error: any) { authError.value = error.response?.data?.message || '无法完成登录' }
}
async function loadWorkspace() { await Promise.all([loadTasks(), loadDocuments(), loadRuns()]) }
async function loadTasks() {
  const { data } = await api.get<Task[]>('/transcription-tasks')
  tasks.value = data
  if (!selected.value && data[0]) await choose(data[0])
}
async function loadDocuments() { const { data } = await api.get<KnowledgeDocument[]>('/knowledge-documents'); documents.value = data }
async function loadRuns() { const { data } = await api.get<KnowledgeRun[]>('/knowledge-runs'); runs.value = data }
async function choose(task: Task) {
  selected.value = task
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
function chooseFile(event: Event) { file.value = (event.target as HTMLInputElement).files?.[0] || null }
async function upload() {
  if (!file.value) return
  uploading.value = true
  try {
    progress.value = '正在核验音频指纹…'
    const sha256 = await hashFile(file.value)
    progress.value = '正在创建上传意图…'
    const intent = await api.post('/uploads/intents', { sha256, contentLength: file.value.size, contentType: file.value.type || 'application/octet-stream', originalFilename: file.value.name }, { headers: { 'Idempotency-Key': key() } })
    if (!intent.data.contentReady) {
      progress.value = '正在存入私有音频库…'
      await api.put(`/uploads/intents/${intent.data.audioBlobId}/content`, file.value, { headers: { 'Content-Type': 'application/octet-stream' }, maxBodyLength: Infinity })
    }
    progress.value = '正在创建说话人转写…'
    const task = await api.post('/transcription-tasks', { audioBlobId: intent.data.audioBlobId }, { headers: { 'Idempotency-Key': key() } })
    await loadTasks(); await choose(task.data); await loadDocuments()
    progress.value = '已进入转写队列，完成后会自动收录至知识库'
  } catch (error: any) { progress.value = error.response?.data?.message || '上传失败' }
  finally { uploading.value = false }
}
async function askKnowledge() {
  if (!question.value.trim()) return
  asking.value = true
  try {
    const { data } = await api.post<KnowledgeRun>('/knowledge-runs', { question: question.value }, { headers: { 'Idempotency-Key': key() } })
    knowledge.value = { run: data, evidence: [] }
    await loadRuns()
    window.setTimeout(refreshKnowledge, 1600)
  } catch (error: any) {
    knowledge.value = { run: { id: '', status: 'FAILED', toolCallsUsed: 0, maxToolCalls: 4, failureMessage: error.response?.data?.message || '无法创建知识任务' }, evidence: [] }
  } finally { asking.value = false }
}
async function refreshKnowledge() {
  if (!knowledge.value?.run.id) return
  try {
    const { data } = await api.get<KnowledgeRunDetail>(`/knowledge-runs/${knowledge.value.run.id}`)
    knowledge.value = data
    await loadRuns()
    if (['PENDING', 'QUEUED', 'RUNNING'].includes(data.run.status)) window.setTimeout(refreshKnowledge, 2200)
  } catch { /* retain the last known task result */ }
}
async function openEvidence(evidence: KnowledgeEvidence) {
  const task = tasks.value.find(item => item.id === evidence.transcriptionTaskId)
  if (task && task.id !== selected.value?.id) await choose(task)
  const segment = segments.value.find(item => item.id === evidence.segmentId)
  if (segment && audio.value) {
    audio.value.currentTime = segment.startMs / 1000
    await audio.value.play().catch(() => undefined)
    document.getElementById(`segment-${segment.id}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }
}
async function retryDocument(document: KnowledgeDocument) {
  await api.post(`/knowledge-documents/${document.id}/retry`)
  await loadDocuments()
}
function logout() { token.value = ''; localStorage.removeItem('voicenote_token'); selected.value = null; documents.value = []; runs.value = []; knowledge.value = null }
onMounted(() => { if (token.value) loadWorkspace() })
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
        <span v-if="selected" class="state-pill">{{ statusText(selected.status) }}</span>
      </div>
      <audio v-if="selected && audioUrl" ref="audio" :src="audioUrl" controls></audio>
      <div v-if="!selected" class="empty-page"><span>⌁</span><b>选择一份声音档案</b><p>上传音频后，voicenote 会将可回跳的转写沉淀到你的知识库。</p></div>
      <div v-else class="transcript">
        <article v-for="segment in segments" :key="segment.id" :id="`segment-${segment.id}`" class="segment">
          <time>{{ timecode(segment.startMs) }}</time><div><b>{{ segment.speaker || '说话人' }}</b><p>{{ segment.text }}</p></div>
        </article>
        <p v-if="!segments.length" class="empty-page small">转写完成后，带说话人和时间戳的听记会出现在这里。</p>
      </div>
    </section>

    <aside class="agent-rail">
      <p class="folio">询问你的资料库</p>
      <h3>把问题交给<br><em>听记 Agent</em></h3>
      <textarea v-model="question" rows="5" placeholder="例如：本周会议有哪些未决事项？"></textarea>
      <button class="primary ask" :disabled="asking || !question.trim()" @click="askKnowledge">{{ asking ? '正在创建任务' : '开始跨文档分析' }} <span>→</span></button>
      <p class="agent-note">Agent 只检索你的已收录听记；结论会附上可回到原声的证据。</p>

      <section v-if="knowledge" class="result-card">
        <div class="result-head"><span>知识任务</span><small>{{ statusText(knowledge.run.status) }} · {{ knowledge.run.toolCallsUsed }}/{{ knowledge.run.maxToolCalls }}</small></div>
        <template v-if="parsedAnswer">
          <p class="answer">{{ parsedAnswer.answer }}</p>
          <article v-for="(finding, index) in parsedAnswer.findings" :key="index" class="finding">
            <b>{{ finding.title || `发现 ${index + 1}` }}</b><p>{{ finding.content }}</p>
            <button v-for="citation in finding.evidence" :key="citation.chunkId + citation.segmentId" class="citation" @click="openEvidence(knowledge.evidence.find(item => item.chunkId === citation.chunkId && item.segmentId === citation.segmentId) || { resultPath: '', documentId: '', chunkId: citation.chunkId, segmentId: citation.segmentId })">原文证据 ↗</button>
          </article>
        </template>
        <p v-else-if="knowledge.run.failureMessage" class="error">{{ knowledge.run.failureMessage }}</p>
        <p v-else class="waiting">正在检索、读取原文并整理证据…</p>
      </section>

      <section v-if="runs.length" class="run-history"><p class="folio">最近任务</p><button v-for="run in runs.slice(0, 4)" :key="run.id" @click="knowledge = { run, evidence: [] }; refreshKnowledge()"><span>{{ statusText(run.status) }}</span><small>{{ run.id.slice(0, 8) }}</small></button></section>
    </aside>
  </main>
</template>
