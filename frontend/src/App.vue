<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, hashFile, key, timecode, type Analysis, type Segment, type Task } from './api'

const token = ref(localStorage.getItem('echotrace_token') || '')
const loginMode = ref<'login' | 'register'>('login')
const email = ref('')
const password = ref('')
const authError = ref('')
const tasks = ref<Task[]>([])
const selected = ref<Task | null>(null)
const segments = ref<Segment[]>([])
const analysis = ref<Analysis | null>(null)
const audioUrl = ref('')
const file = ref<File | null>(null)
const uploading = ref(false)
const progress = ref('')
const goal = ref('总结这段语音的主题、关键结论与待办事项。')
const mode = ref('meeting')
const audio = ref<HTMLAudioElement | null>(null)
const selectedTitle = computed(() => selected.value ? `任务 #${selected.value.id.slice(0, 8)}` : '选择一个转写任务')

async function authenticate() {
  authError.value = ''
  try {
    const { data } = await api.post(`/auth/${loginMode.value}`, { email: email.value, password: password.value })
    token.value = data.accessToken
    localStorage.setItem('echotrace_token', token.value)
    await loadTasks()
  } catch (error: any) { authError.value = error.response?.data?.message || '无法完成登录' }
}
async function loadTasks() {
  const { data } = await api.get<Task[]>('/transcription-tasks')
  tasks.value = data
  if (!selected.value && data[0]) await choose(data[0])
}
async function choose(task: Task) {
  selected.value = task; analysis.value = null
  const { data } = await api.get<Segment[]>(`/transcription-tasks/${task.id}/segments`)
  segments.value = data
  const audioResponse = await api.get(`/audio/${task.id}/content`, { responseType: 'blob' })
  if (audioUrl.value) URL.revokeObjectURL(audioUrl.value)
  audioUrl.value = URL.createObjectURL(audioResponse.data)
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
      progress.value = '正在写入私有音频仓库…'
      await api.put(`/uploads/intents/${intent.data.audioBlobId}/content`, file.value, { headers: { 'Content-Type': 'application/octet-stream' }, maxBodyLength: Infinity })
    }
    progress.value = '正在创建转写任务…'
    const task = await api.post('/transcription-tasks', { audioBlobId: intent.data.audioBlobId }, { headers: { 'Idempotency-Key': key() } })
    progress.value = '任务已进入队列'
    await loadTasks(); await choose(task.data)
  } catch (error: any) { progress.value = error.response?.data?.message || '上传失败' }
  finally { uploading.value = false }
}
async function createAnalysis() {
  if (!selected.value) return
  const { data } = await api.post('/analysis-runs', { transcriptionTaskId: selected.value.id, mode: mode.value, goal: goal.value }, { headers: { 'Idempotency-Key': key() } })
  analysis.value = { run: data, evidence: [] }
  setTimeout(loadAnalysis, 3500)
}
async function loadAnalysis() {
  if (!analysis.value) return
  const { data } = await api.get<Analysis>(`/analysis-runs/${analysis.value.run.id}`)
  analysis.value = data
}
function jump(segmentId: string) {
  const segment = segments.value.find(value => value.id === segmentId)
  if (segment && audio.value) { audio.value.currentTime = segment.startMs / 1000; audio.value.play() }
}
function logout() { token.value = ''; localStorage.removeItem('echotrace_token') }
onMounted(() => { if (token.value) loadTasks() })
</script>

<template>
  <main v-if="!token" class="auth-shell">
    <section class="auth-panel">
      <p class="eyebrow">ECHO TRACE / 01</p>
      <h1>把每一段<br><em>声音</em>还给事实。</h1>
      <p class="lede">面向会议、访谈和课程的音频证据工作台。转写、分析和原声定位在同一个界面完成。</p>
      <div class="rule"></div>
      <form @submit.prevent="authenticate">
        <label>邮箱 <input v-model="email" type="email" autocomplete="email" required></label>
        <label>密码 <input v-model="password" type="password" minlength="8" autocomplete="current-password" required></label>
        <p v-if="authError" class="error">{{ authError }}</p>
        <button class="primary">{{ loginMode === 'login' ? '进入工作台' : '创建工作台' }} <span>↗</span></button>
      </form>
      <button class="quiet" @click="loginMode = loginMode === 'login' ? 'register' : 'login'">{{ loginMode === 'login' ? '还没有账号？创建一个' : '已有账号？登录' }}</button>
    </section>
    <aside class="auth-art"><div class="orbit orbit-a"></div><div class="orbit orbit-b"></div><div class="pulse-word">EVIDENCE<br>BEFORE<br>OPINION</div></aside>
  </main>

  <main v-else class="app-shell">
    <header class="topbar"><a class="brand">ECHO<span>TRACE</span></a><p>离线音频证据工作台</p><button class="quiet logout" @click="logout">退出</button></header>
    <aside class="sidebar">
      <p class="eyebrow">音频档案</p>
      <button v-for="task in tasks" :key="task.id" class="task-row" :class="{active:selected?.id===task.id}" @click="choose(task)">
        <span class="status-dot" :class="task.status.toLowerCase()"></span><span>录音 {{ task.id.slice(0, 5) }}</span><small>{{ task.status }}</small>
      </button>
      <section class="upload-card">
        <p>导入完整音频</p><input type="file" accept="audio/*" @change="chooseFile">
        <button class="primary compact" :disabled="!file || uploading" @click="upload">{{ uploading ? '正在处理' : '上传并转写' }} <span>→</span></button>
        <small>{{ progress }}</small>
      </section>
    </aside>
    <section class="workspace">
      <div class="workspace-head"><p class="eyebrow">转写档案</p><h2>{{ selectedTitle }}</h2><span v-if="selected" class="state-pill">{{ selected.status }}</span></div>
      <audio v-if="selected && audioUrl" ref="audio" :src="audioUrl" controls></audio>
      <div v-if="!selected" class="empty"><b>上传一段音频</b><p>系统会以内容哈希识别重复文件，然后创建可恢复的异步转写任务。</p></div>
      <div v-else class="transcript">
        <article v-for="segment in segments" :key="segment.id" class="segment" :id="`segment-${segment.id}`">
          <time>{{ timecode(segment.startMs) }}</time><div><b>{{ segment.speaker || '原声' }}</b><p>{{ segment.text }}</p></div>
        </article>
        <p v-if="!segments.length" class="empty">转写完成后，时间戳文本会出现在这里。</p>
      </div>
    </section>
    <aside class="analysis-rail">
      <p class="eyebrow">分析命令</p>
      <select v-model="mode"><option value="meeting">会议</option><option value="interview">访谈 / 面试</option><option value="course">课程</option><option value="podcast">播客</option><option value="custom">自定义</option></select>
      <textarea v-model="goal" rows="5"></textarea>
      <button class="primary" :disabled="!selected || selected.status !== 'SUCCEEDED'" @click="createAnalysis">开始审查分析 <span>→</span></button>
      <section v-if="analysis" class="result-card">
        <div class="result-head"><span>ANALYSIS</span><small>{{ analysis.run.status }} · {{ analysis.run.callsUsed }}/{{ analysis.run.maxCalls }}</small></div>
        <pre>{{ analysis.run.resultDocument || '分析正在排队，稍后刷新…' }}</pre>
        <button v-for="cite in analysis.evidence" :key="cite.segmentId + cite.resultPath" class="citation" @click="jump(cite.segmentId)">证据 · {{ cite.segmentId.slice(0, 8) }} ↗</button>
      </section>
    </aside>
  </main>
</template>
