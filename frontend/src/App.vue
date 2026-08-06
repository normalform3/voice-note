<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api, hashFile, key, stageStatusText, stageText, statusText, timecode, uploadErrorMessage, type AnalysisEvidence, type AnalysisRun, type AnalysisRunDetail, type KnowledgeDocument, type KnowledgeEvidence, type KnowledgeIndexBuild, type KnowledgeRun, type KnowledgeRunDetail, type OrganizedDocumentDetail, type PipelineStage, type Segment, type Speaker, type Task, type WorkspaceSnapshot } from './api'

type AgentScope = 'CURRENT_DOCUMENT' | 'CROSS_DOCUMENT'
type WorkspaceView = 'library' | 'document'
type DetailTab = 'transcript' | 'summary' | 'organized'
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
const speakers = ref<Speaker[]>([])
const organized = ref<OrganizedDocumentDetail | null>(null)
const audioUrl = ref('')
const audioLoading = ref(false)
const audioLoadError = ref('')
const file = ref<File | null>(null)
const uploading = ref(false)
const progress = ref('')
const importStartedAt = ref<number | null>(null)
const clockNow = ref(Date.now())
const retryingStage = ref<PipelineStage | null>(null)
const stageRetryError = ref('')
const resubmittingTask = ref(false)
const taskActionError = ref('')
const question = ref('请总结近期会议中的关键结论、风险和下一步行动。')
const knowledge = ref<KnowledgeRunDetail | null>(null)
const analysis = ref<AnalysisRunDetail | null>(null)
const asking = ref(false)
const audio = ref<HTMLAudioElement | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const speakerDiarization = ref(true)
const speakerCount = ref<number | null>(null)
const savingSpeakerId = ref<string | null>(null)
const startingFormalDocument = ref(false)
const startingKnowledgeBuild = ref(false)
const workspaceView = ref<WorkspaceView>('library')
const detailTab = ref<DetailTab>('transcript')
const mobileAgentOpen = ref(false)
const summaryByTaskId = ref<Record<string, AnalysisRunDetail>>({})
const summaryLoadingTaskId = ref<string | null>(null)
let streamController: AbortController | null = null
let reconnectTimer: number | null = null
let streamClosed = false
let reconnectDelay = 1000
let clockTimer: number | null = null

const isDocumentView = computed(() => workspaceView.value === 'document')
const scope = computed<AgentScope>(() => isDocumentView.value ? 'CURRENT_DOCUMENT' : 'CROSS_DOCUMENT')
const selectedDocument = computed(() => documents.value.find(document => document.transcriptionTaskId === selected.value?.id))
const knowledgeBuild = computed<KnowledgeIndexBuild | undefined>(() => selectedDocument.value?.currentBuild || selected.value?.knowledgeDocument?.currentBuild)
const organizedTopics = computed(() => organized.value?.blocks.filter(block => block.type === 'TOPIC') || [])
const selectedTitle = computed(() => selected.value ? taskTitle(selected.value) : '从资料库选择一份听记')
const canAnalyzeCurrent = computed(() => Boolean(selected.value?.transcriptReady))
const canCancelTask = computed(() => Boolean(selected.value && !['SUCCEEDED', 'CANCELLED'].includes(selected.value.status)))
const canResubmitTask = computed(() => selected.value?.status === 'CANCELLED')
const canSummarize = computed(() => selected.value?.organizedDocument?.status === 'READY')
const canCreateFormalDocument = computed(() => selected.value?.status === 'WAITING_FOR_FORMAL_DOCUMENT' && !selected.value?.organizedDocument)
const canCreateKnowledgeBuild = computed(() => selected.value?.status === 'WAITING_FOR_KNOWLEDGE_BUILD' && selected.value?.organizedDocument?.status === 'READY')
const activeRun = computed(() => scope.value === 'CURRENT_DOCUMENT' ? analysis.value?.run : knowledge.value?.run)
const activeEvidence = computed(() => scope.value === 'CURRENT_DOCUMENT' ? analysis.value?.evidence || [] : knowledge.value?.evidence || [])
const activeRunUsage = computed(() => scope.value === 'CURRENT_DOCUMENT'
  ? `${analysis.value?.run.callsUsed || 0}/${analysis.value?.run.maxCalls || 0}`
  : `${knowledge.value?.run.toolCallsUsed || 0}/${knowledge.value?.run.maxToolCalls || 0}`)
const parsedAnswer = computed(() => parseResultDocument(activeRun.value?.resultDocument))
const visibleHistory = computed(() => scope.value === 'CURRENT_DOCUMENT'
  ? analysisRuns.value.filter(run => run.transcriptionTaskId === selected.value?.id)
  : runs.value)
const summaryDetail = computed(() => {
  const task = selected.value
  const detail = task ? summaryByTaskId.value[task.id] : undefined
  if (!detail || !task?.organizedDocument || detail.run.organizedDocumentId !== task.organizedDocument.id) return undefined
  return detail
})
const existingSummaryRun = computed(() => {
  const task = selected.value
  const document = task?.organizedDocument
  if (!task || !document) return undefined
  return analysisRuns.value.find(run => run.transcriptionTaskId === task.id && run.analysisMode === 'summary' && run.organizedDocumentId === document.id)
})
const parsedSummary = computed(() => parseResultDocument(summaryDetail.value?.run.resultDocument))
const summaryLoading = computed(() => summaryLoadingTaskId.value === selected.value?.id)
const agentTitle = computed(() => scope.value === 'CURRENT_DOCUMENT' ? '当前文档问答' : '跨文档问答')
const agentDescription = computed(() => scope.value === 'CURRENT_DOCUMENT'
  ? '只基于这份音频的听记内容回答。'
  : '检索你已收录的全部音频资料，串联结论与证据。')
const agentPlaceholder = computed(() => scope.value === 'CURRENT_DOCUMENT'
  ? '例如：这场会议的结论和待办是什么？'
  : '例如：近期会议有哪些未决事项？')
const agentSuggestions = computed(() => scope.value === 'CURRENT_DOCUMENT'
  ? ['提炼这份录音的重点内容', '有哪些明确的下一步行动？', '不同发言人的主要观点是什么？']
  : ['总结近期会议中的关键结论', '跨会议有哪些重复出现的风险？', '找出所有需要跟进的行动项'])
const importElapsedMs = computed(() => importStartedAt.value == null ? 0 : Math.max(0, clockNow.value - importStartedAt.value))

function parseResultDocument(raw?: string) {
  if (!raw) return null
  try { return JSON.parse(raw) as { answer?: string; findings?: Finding[] } }
  catch { return { answer: raw, findings: [] } }
}
function taskTitle(task: Task) {
  return documents.value.find(document => document.transcriptionTaskId === task.id)?.title
    || task.knowledgeDocument?.title
    || `录音 ${task.id.slice(0, 8)}`
}
function formatFileSize(size: number) {
  if (size < 1024 * 1024) return `${Math.max(1, Math.round(size / 1024))} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}
function showLibrary() {
  workspaceView.value = 'library'
  mobileAgentOpen.value = false
}
function toggleAgent() { mobileAgentOpen.value = !mobileAgentOpen.value }
function triggerFilePicker() { fileInput.value?.click() }
function useSuggestion(suggestion: string) { question.value = suggestion }
function speakerColor(speakerId?: string) {
  const palette = ['#637d72', '#9a6c77', '#92733d', '#647d98', '#7b6f99', '#8b745d']
  const source = speakerId || 'speaker'
  return palette[Array.from(source).reduce((total, character) => total + character.charCodeAt(0), 0) % palette.length]
}

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
}
async function loadDocuments() { const { data } = await api.get<KnowledgeDocument[]>('/knowledge-documents'); documents.value = data }
async function loadRuns() { const { data } = await api.get<KnowledgeRun[]>('/knowledge-runs'); runs.value = data }
async function loadAnalysisRuns() { const { data } = await api.get<AnalysisRun[]>('/analysis-runs'); analysisRuns.value = data }
async function choose(task: Task) {
  selected.value = task
  workspaceView.value = 'document'
  detailTab.value = 'transcript'
  mobileAgentOpen.value = false
  segments.value = []
  speakers.value = []
  organized.value = null
  if (audioUrl.value) URL.revokeObjectURL(audioUrl.value)
  audioUrl.value = ''
  audioLoading.value = false
  audioLoadError.value = ''
  const [transcript, speakerResponse, organizedResponse] = await Promise.all([
    api.get<Segment[]>(`/transcription-tasks/${task.id}/segments`),
    api.get<Speaker[]>(`/transcription-tasks/${task.id}/speakers`),
    task.organizedDocument ? api.get<OrganizedDocumentDetail>(`/organized-documents/${task.organizedDocument.id}`) : Promise.resolve(null)
  ])
  if (selected.value?.id !== task.id) return
  segments.value = transcript.data
  speakers.value = speakerResponse.data
  organized.value = organizedResponse?.data || null
}
async function refreshTranscript(taskId: string) {
  const [transcript, speakerResponse] = await Promise.all([
    api.get<Segment[]>(`/transcription-tasks/${taskId}/segments`),
    api.get<Speaker[]>(`/transcription-tasks/${taskId}/speakers`)
  ])
  if (selected.value?.id !== taskId) return
  segments.value = transcript.data
  speakers.value = speakerResponse.data
}
async function refreshOrganizedDocument(taskId: string, documentId: string) {
  const { data } = await api.get<OrganizedDocumentDetail>(`/organized-documents/${documentId}`)
  if (selected.value?.id === taskId) organized.value = data
}
function topicChildren(topicId: string) { return organized.value?.blocks.filter(block => block.parentBlockId === topicId) || [] }
async function saveSpeakerName(speaker: Speaker) {
  if (!selected.value) return
  savingSpeakerId.value = speaker.speakerId
  try {
    const { data } = await api.put<Speaker>(`/transcription-tasks/${selected.value.id}/speakers/${speaker.speakerId}`, { displayName: speaker.displayName || null })
    speakers.value = speakers.value.map(value => value.speakerId === data.speakerId ? data : value)
    const { data: transcript } = await api.get<Segment[]>(`/transcription-tasks/${selected.value.id}/segments`)
    segments.value = transcript
  } finally { savingSpeakerId.value = null }
}
function evidenceLabel(citation: { chunkId?: string; segmentId: string }) {
  const evidence = (activeEvidence.value as KnowledgeEvidence[]).find(item => item.chunkId === citation.chunkId && item.segmentId === citation.segmentId)
  if (!evidence) return '原文证据 ↗'
  return `${evidence.topic || '原文'} · ${evidence.speaker || evidence.speakerId || '说话人'} · ${timecode(evidence.startMs || 0)} ↗`
}
async function ensureAudio(startMs = 0, play = true) {
  const task = selected.value
  if (!task) return
  const taskId = task.id
  audioLoadError.value = ''
  if (!audioUrl.value) {
    audioLoading.value = true
    try {
      const { data } = await api.get(`/audio/${taskId}/content`, { responseType: 'blob' })
      if (selected.value?.id !== taskId) return
      audioUrl.value = URL.createObjectURL(data)
      await nextTick()
    } catch (error: any) {
      if (selected.value?.id === taskId) audioLoadError.value = error.response?.data?.message || '原始音频加载失败，请稍后重试。'
      return
    } finally {
      if (selected.value?.id === taskId) audioLoading.value = false
    }
  }
  if (selected.value?.id !== taskId || !audio.value) return
  audio.value.currentTime = startMs / 1000
  if (play) await audio.value.play().catch(() => undefined)
}
function chooseFile(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0] || null
  progress.value = ''
}
async function upload() {
  if (!file.value) return
  uploading.value = true
  importStartedAt.value = Date.now()
  const startedAt = importStartedAt.value
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
    const { data: task } = await api.post<Task>(`/uploads/intents/${intent.data.audioBlobId}/complete`, {
      asrConfig: { diarizationEnabled: speakerDiarization.value, speakerCount: speakerCount.value || null },
      clientImportStartedAt: new Date(startedAt).toISOString()
    }, { headers: { 'Idempotency-Key': key() } })
    file.value = null
    if (fileInput.value) fileInput.value.value = ''
    upsertTask(task)
    await choose(task)
    progress.value = `导入完成，用时 ${formatDuration(Date.now() - startedAt)}；后续阶段会自动更新。`
  } catch (error: unknown) { progress.value = uploadErrorMessage(error, phase) }
  finally { uploading.value = false; importStartedAt.value = null }
}
async function createFormalDocument() {
  if (!selected.value) return
  startingFormalDocument.value = true
  taskActionError.value = ''
  try {
    const { data } = await api.post<Task>(`/transcription-tasks/${selected.value.id}/formal-document`, undefined, { headers: { 'Idempotency-Key': key() } })
    upsertTask(data)
  } catch (error: any) { taskActionError.value = error.response?.data?.message || '无法开始生成正式文档。' }
  finally { startingFormalDocument.value = false }
}
async function createKnowledgeBuild() {
  if (!selected.value) return
  startingKnowledgeBuild.value = true
  taskActionError.value = ''
  try {
    const { data } = await api.post<Task>(`/transcription-tasks/${selected.value.id}/knowledge-build`, undefined, { headers: { 'Idempotency-Key': key() } })
    upsertTask(data)
  } catch (error: any) { taskActionError.value = error.response?.data?.message || '无法开始建立知识库。' }
  finally { startingKnowledgeBuild.value = false }
}
async function askAgent() {
  if (!question.value.trim()) return
  asking.value = true
  try {
    if (scope.value === 'CURRENT_DOCUMENT') {
      if (!selected.value?.transcriptReady) return
      const { data } = await api.post<AnalysisRun>('/analysis-runs', { transcriptionTaskId: selected.value.id, mode: 'custom', goal: question.value }, { headers: { 'Idempotency-Key': key() } })
      analysis.value = { run: data, evidence: [] }
      upsertAnalysis(data)
    } else {
      const { data } = await api.post<KnowledgeRun>('/knowledge-runs', { question: question.value }, { headers: { 'Idempotency-Key': key() } })
      knowledge.value = { run: data, evidence: [] }
      upsertKnowledge(data)
    }
  } catch (error: any) {
    if (scope.value === 'CURRENT_DOCUMENT') analysis.value = { run: { id: '', transcriptionTaskId: selected.value?.id || '', status: 'FAILED', callsUsed: 0, maxCalls: 0, failureMessage: error.response?.data?.message || '无法创建文档分析' }, evidence: [] }
    else knowledge.value = { run: { id: '', status: 'FAILED', toolCallsUsed: 0, maxToolCalls: 4, failureMessage: error.response?.data?.message || '无法创建知识任务' }, evidence: [] }
  } finally { asking.value = false }
}
async function loadKnowledgeDetail(runId: string) {
  const { data } = await api.get<KnowledgeRunDetail>(`/knowledge-runs/${runId}`)
  knowledge.value = data
  upsertKnowledge(data.run)
}
async function loadAnalysisDetail(runId: string) {
  const { data } = await api.get<AnalysisRunDetail>(`/analysis-runs/${runId}`)
  analysis.value = data
  upsertAnalysis(data.run)
}
async function loadSummaryDetail(taskId: string, runId: string) {
  const { data } = await api.get<AnalysisRunDetail>(`/analysis-runs/${runId}`)
  summaryByTaskId.value = { ...summaryByTaskId.value, [taskId]: data }
  upsertAnalysis(data.run)
}
async function selectDetailTab(tab: DetailTab) {
  detailTab.value = tab
  if (tab === 'summary') await loadExistingSummary()
}
async function loadExistingSummary() {
  const task = selected.value
  const existing = existingSummaryRun.value
  if (!task || !existing || summaryByTaskId.value[task.id]?.run.id === existing.id) return
  summaryLoadingTaskId.value = task.id
  try { await loadSummaryDetail(task.id, existing.id) }
  finally { if (summaryLoadingTaskId.value === task.id) summaryLoadingTaskId.value = null }
}
async function createSummary() {
  const task = selected.value
  const document = task?.organizedDocument
  if (!task || !document || document.status !== 'READY') return
  const existing = summaryDetail.value
  if (existing && existing.run.status !== 'FAILED') return
  summaryLoadingTaskId.value = task.id
  try {
    const { data } = await api.post<AnalysisRun>(`/organized-documents/${document.id}/summary`, undefined, { headers: { 'Idempotency-Key': key() } })
    await loadSummaryDetail(task.id, data.id)
  } catch (error: any) {
    summaryByTaskId.value = {
      ...summaryByTaskId.value,
      [task.id]: { run: { id: '', transcriptionTaskId: task.id, organizedDocumentId: document.id, analysisMode: 'summary', status: 'FAILED', callsUsed: 0, maxCalls: 0, failureMessage: error.response?.data?.message || '无法创建 AI 摘要' }, evidence: [] }
    }
  } finally {
    if (summaryLoadingTaskId.value === task.id) summaryLoadingTaskId.value = null
  }
}
async function openHistory(run: KnowledgeRun | AnalysisRun) {
  if ('transcriptionTaskId' in run) {
    const task = tasks.value.find(item => item.id === run.transcriptionTaskId)
    if (task && (task.id !== selected.value?.id || !isDocumentView.value)) await choose(task)
    else workspaceView.value = 'document'
    await loadAnalysisDetail(run.id)
  } else {
    showLibrary()
    await loadKnowledgeDetail(run.id)
  }
}
async function seekToTime(startMs: number, play = false) {
  await ensureAudio(startMs, play)
}
async function seekToSegment(segment: Segment) {
  await seekToTime(segment.startMs, true)
}
async function openEvidence(citation: { chunkId?: string; segmentId: string }) {
  let taskId: string | undefined
  if (scope.value === 'CURRENT_DOCUMENT') taskId = analysis.value?.run.transcriptionTaskId
  else {
    const evidence = (activeEvidence.value as KnowledgeEvidence[]).find(item => item.chunkId === citation.chunkId && item.segmentId === citation.segmentId)
    taskId = evidence?.transcriptionTaskId
  }
  await openEvidenceForTask(citation, taskId)
}
async function openSummaryEvidence(citation?: { segmentId: string }) {
  if (!citation) return
  await openEvidenceForTask(citation, summaryDetail.value?.run.transcriptionTaskId)
}
async function openEvidenceForTask(citation: { segmentId: string }, taskId?: string) {
  const task = tasks.value.find(item => item.id === taskId)
  if (task && (task.id !== selected.value?.id || !isDocumentView.value)) await choose(task)
  const activeTask = task || selected.value
  if (activeTask && !segments.value.some(item => item.id === citation.segmentId)) await refreshTranscript(activeTask.id)
  await nextTick()
  const segment = segments.value.find(item => item.id === citation.segmentId)
  if (segment) {
    await seekToSegment(segment)
    document.getElementById(`segment-${segment.id}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }
}
async function retryDocument(document: Pick<KnowledgeDocument, 'id'>) {
  await api.post(`/knowledge-documents/${document.id}/retry`)
  await loadDocuments()
}
async function rebuildKnowledge(force = false) {
  if (!selectedDocument.value) return
  taskActionError.value = ''
  try {
    const { data } = await api.post<KnowledgeIndexBuild>(`/knowledge-documents/${selectedDocument.value.id}/rebuild`, undefined, { params: { force }, headers: { 'Idempotency-Key': key() } })
    documents.value = documents.value.map(document => document.id === selectedDocument.value?.id ? { ...document, currentBuild: data } : document)
  } catch (error: any) { taskActionError.value = error.response?.data?.message || '知识库重建没有启动，请稍后再试。' }
}
async function retryStage(stage: PipelineStage) {
  if (!selected.value) return
  retryingStage.value = stage
  stageRetryError.value = ''
  try {
    const { data } = await api.post<Task>(`/transcription-tasks/${selected.value.id}/stages/${stage}/retry`, undefined, { headers: { 'Idempotency-Key': key() } })
    upsertTask(data)
  } catch (error: any) {
    stageRetryError.value = error.response?.data?.message || '重新提交失败，请稍后再试。'
  } finally {
    retryingStage.value = null
  }
}
async function cancelTask() {
  if (!selected.value || !canCancelTask.value) return
  const { data } = await api.post<Task>(`/transcription-tasks/${selected.value.id}/cancel`, undefined, { headers: { 'Idempotency-Key': key() } })
  upsertTask(data)
}
async function resubmitTask() {
  if (!selected.value || !canResubmitTask.value) return
  resubmittingTask.value = true
  taskActionError.value = ''
  try {
    const { data } = await api.post<Task>(`/transcription-tasks/${selected.value.id}/retry`, undefined, { headers: { 'Idempotency-Key': key() } })
    upsertTask(data)
  } catch (error: any) {
    taskActionError.value = error.response?.data?.message || '重新提交失败，请稍后再试。'
  } finally {
    resubmittingTask.value = false
  }
}
async function deleteTask() {
  const task = selected.value
  if (!task || !window.confirm('删除后将移除原始录音、转写、整理文档和知识库切片，且无法恢复。确定删除吗？')) return
  try {
    await api.delete(`/transcription-tasks/${task.id}`, { headers: { 'Idempotency-Key': key() } })
    if (audioUrl.value) URL.revokeObjectURL(audioUrl.value)
    selected.value = null
    segments.value = []
    speakers.value = []
    organized.value = null
    analysis.value = null
    knowledge.value = null
    audioUrl.value = ''
    showLibrary()
    await loadWorkspace()
  } catch (error: any) {
    window.alert(error.response?.data?.message || '删除失败，请稍后重试。')
  }
}
function upsertTask(task: Task) {
  const previous = selected.value?.id === task.id ? selected.value : null
  tasks.value = [task, ...tasks.value.filter(item => item.id !== task.id)]
  if (selected.value?.id === task.id) selected.value = task
  if (previous && selected.value?.id === task.id) {
    if (task.transcriptReady && (!previous.transcriptReady || !segments.value.length)) void refreshTranscript(task.id)
    const document = task.organizedDocument
    if (document?.status === 'READY' && (previous.organizedDocument?.status !== 'READY' || organized.value?.document.id !== document.id || organized.value?.document.status !== 'READY')) {
      void refreshOrganizedDocument(task.id, document.id)
    }
  }
  if (task.knowledgeDocument) {
    const document: KnowledgeDocument = { ...task.knowledgeDocument, transcriptionTaskId: task.id, updatedAt: new Date().toISOString() }
    documents.value = [document, ...documents.value.filter(item => item.id !== document.id)]
  }
}
function upsertKnowledge(run: KnowledgeRun) { runs.value = [run, ...runs.value.filter(item => item.id !== run.id)] }
function upsertAnalysis(run: AnalysisRun) { analysisRuns.value = [run, ...analysisRuns.value.filter(item => item.id !== run.id)] }
function applySnapshot(snapshot: WorkspaceSnapshot) {
  tasks.value = snapshot.tasks
  documents.value = snapshot.documents
  runs.value = snapshot.knowledgeRuns
  analysisRuns.value = snapshot.analyses
  if (selected.value) {
    const update = tasks.value.find(task => task.id === selected.value?.id)
    if (update) upsertTask(update)
    else {
      selected.value = null
      segments.value = []
      speakers.value = []
      organized.value = null
      audioUrl.value = ''
      showLibrary()
    }
  }
}
function handleProgressEvent(name: string, payload: any) {
  if (name === 'snapshot') { applySnapshot(payload as WorkspaceSnapshot); return }
  if (name === 'task-stage-settled' && payload.task) { upsertTask(payload.task as Task); return }
  if (name === 'knowledge-index-progress') { void loadDocuments(); return }
  if (name === 'knowledge-run-settled' && payload.run) { void loadKnowledgeDetail(payload.run.id); return }
  if (name === 'analysis-run-settled' && payload.run) {
    const run = payload.run as AnalysisRun
    upsertAnalysis(run)
    const summaryTaskId = run.analysisMode === 'summary'
      ? run.transcriptionTaskId
      : Object.entries(summaryByTaskId.value).find(([, detail]) => detail.run.id === run.id)?.[0]
    if (summaryTaskId) void loadSummaryDetail(summaryTaskId, run.id)
    else void loadAnalysisDetail(payload.run.id)
  }
}
function parseSseFrame(frame: string) {
  let eventName = 'message'; const data: string[] = []
  for (const line of frame.split(/\r?\n/)) { if (line.startsWith('event:')) eventName = line.slice(6).trim(); if (line.startsWith('data:')) data.push(line.slice(5).trim()) }
  if (data.length) { try { handleProgressEvent(eventName, JSON.parse(data.join('\n'))) } catch { /* Ignore a malformed reconnect payload. */ } }
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
function stageWaitDuration(stage: { status: string; queuedAt: string; totalWaitDurationMs: number }) {
  if (stage.status !== 'QUEUED') return stage.totalWaitDurationMs
  return stage.totalWaitDurationMs + Math.max(0, clockNow.value - new Date(stage.queuedAt).getTime())
}
function stageProcessingDuration(stage: { status: string; startedAt?: string; completedAt?: string }) {
  if (!stage.startedAt) return 0
  const endedAt = stage.status === 'RUNNING' ? clockNow.value : stage.completedAt ? new Date(stage.completedAt).getTime() : new Date(stage.startedAt).getTime()
  return Math.max(0, endedAt - new Date(stage.startedAt).getTime())
}
function stageDurationText(stage: { stage: PipelineStage; status: string; queuedAt: string; startedAt?: string; completedAt?: string; totalWaitDurationMs: number }) {
  const queueDuration = formatDuration(stageWaitDuration(stage))
  if (stage.stage === 'UPLOAD_COMPLETED') return `导入耗时 ${queueDuration}`
  if (stage.status === 'QUEUED') return `排队等待 ${queueDuration}`
  const processingDuration = formatDuration(stageProcessingDuration(stage))
  return stage.status === 'RUNNING' ? `排队 ${queueDuration} · 已处理 ${processingDuration}` : `排队 ${queueDuration} · 处理 ${processingDuration}`
}
function canRetryStage(stage: PipelineStage) {
  return (stage === 'ASR_SUBMIT' || stage === 'ASR_POLL' || stage === 'DOCUMENT_ORGANIZATION' || stage === 'KNOWLEDGE_INDEX')
    && Boolean(selected.value?.retryableStages?.includes(stage))
}
function knowledgeStageText(stage: KnowledgeIndexBuild['stages'][number]['stage']) {
  return ({ INGEST: '知识库入库', CHUNK: '按主题切块', INDEX: '构建检索索引' } as const)[stage]
}
function retryStageLabel(stage: PipelineStage) {
  if (stage === 'ASR_SUBMIT') return '重新提交转写'
  if (stage === 'ASR_POLL') return '重新查询结果'
  return '从此阶段重试'
}
function logout() {
  stopProgressEvents()
  if (audioUrl.value) URL.revokeObjectURL(audioUrl.value)
  token.value = ''
  localStorage.removeItem('voicenote_token')
  selected.value = null
  documents.value = []
  runs.value = []
  analysisRuns.value = []
  knowledge.value = null
  analysis.value = null
  organized.value = null
  summaryByTaskId.value = {}
  workspaceView.value = 'library'
}
watch(speakerDiarization, enabled => { if (!enabled) speakerCount.value = null })
onMounted(() => {
  clockTimer = window.setInterval(() => { clockNow.value = Date.now() }, 1000)
  if (token.value) { void loadWorkspace(); void connectProgressEvents() }
})
onBeforeUnmount(() => {
  stopProgressEvents()
  if (clockTimer) window.clearInterval(clockTimer)
})
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
      <button class="brand" type="button" @click="showLibrary" aria-label="返回音频资料库">voice<span>note</span></button>
      <p>音频听记与私人知识库</p>
      <div class="topbar-meta">
        <span>{{ documents.filter(item => item.status === 'READY').length }} 份已收录</span>
        <button class="quiet logout" @click="logout">退出</button>
      </div>
      <button class="mobile-agent-toggle" type="button" :aria-expanded="mobileAgentOpen" @click="toggleAgent">{{ mobileAgentOpen ? '关闭问答' : 'AI 问答' }}</button>
    </header>

    <section class="content-pane">
      <section v-if="workspaceView === 'library'" class="library-page page-reveal">
        <header class="page-intro">
          <div>
            <p class="eyebrow">YOUR AUDIO LIBRARY</p>
            <h2>声音资料库</h2>
            <p>把会议、访谈和灵感片段收在一起，随时从声音里找回答案。</p>
          </div>
          <span class="collection-count">{{ tasks.length }} <small>RECORDINGS</small></span>
        </header>

        <section class="import-panel" aria-label="导入音频">
          <div class="import-mark" aria-hidden="true">↥</div>
          <div class="import-copy"><b>导入新的音频</b><span>支持常见音频格式，上传后自动转写与归档。</span></div>
          <input ref="fileInput" class="visually-hidden" type="file" accept="audio/*" aria-label="选择音频文件" @change="chooseFile">
          <button class="import-button" type="button" @click="triggerFilePicker">导入音频 <span>+</span></button>
        </section>

        <section v-if="file || progress" class="upload-queue" aria-live="polite">
          <div v-if="file" class="picked-file"><span class="file-glyph" aria-hidden="true">♫</span><div><b>{{ file.name }}</b><small>{{ formatFileSize(file.size) }}</small></div></div>
          <label v-if="file" class="diarization-option"><input v-model="speakerDiarization" type="checkbox"> 识别说话人 <small>仅单声道</small></label>
          <label v-if="file && speakerDiarization" class="speaker-count-option">说话人数（可选）<input v-model.number="speakerCount" type="number" min="2" max="100" placeholder="自动判断"></label>
          <p v-if="progress">{{ progress }}<b v-if="uploading" class="upload-timer"> · 导入用时 {{ formatDuration(importElapsedMs) }}</b></p>
          <button v-if="file" class="primary upload-start" :disabled="uploading" @click="upload">{{ uploading ? '正在导入' : '上传并转写' }} <span>→</span></button>
        </section>

        <div class="records-head"><div><p class="eyebrow">RECENT RECORDINGS</p><h3>最近音频</h3></div><span>状态 · 处理进度</span></div>
        <div class="record-list">
          <article v-for="task in tasks" :key="task.id" class="record-row" :class="{ active: selected?.id === task.id }">
            <button class="record-main" type="button" @click="choose(task)">
              <span class="record-wave" aria-hidden="true"><i></i><i></i><i></i><i></i><i></i></span>
              <span class="record-title"><b>{{ taskTitle(task) }}</b><small>{{ statusText(task.status) }}<template v-if="task.currentStage"> · {{ stageText(task.currentStage) }}</template></small></span>
              <span class="record-progress"><b>{{ task.progressPercent || 0 }}%</b><small>{{ task.transcriptReady ? '可阅读' : '处理中' }}</small></span>
              <span class="record-arrow" aria-hidden="true">→</span>
            </button>
            <button v-if="task.knowledgeDocument?.status === 'FAILED'" class="record-retry" type="button" @click="retryDocument(task.knowledgeDocument)">重试收录</button>
          </article>
          <div v-if="!tasks.length" class="library-empty"><span aria-hidden="true">⌁</span><b>这里还没有声音。</b><p>导入第一段音频，开始建立你的个人听记资料库。</p></div>
        </div>
      </section>

      <section v-else class="document-page page-reveal">
        <nav class="breadcrumb" aria-label="当前位置"><button type="button" @click="showLibrary">音频资料库</button><span>/</span><b>{{ selectedTitle }}</b></nav>
        <header class="document-head">
          <div><p class="eyebrow">DOCUMENT LISTENING</p><h2>{{ selectedTitle }}</h2></div>
          <div v-if="selected" class="document-actions"><span class="state-pill">{{ selected.progressPercent || 0 }}% · {{ stageText(selected.currentStage) }}</span><button v-if="canCreateFormalDocument" class="stage-retry" :disabled="startingFormalDocument" @click="createFormalDocument">{{ startingFormalDocument ? '正在开始…' : '生成正式文档' }}</button><button v-if="canCreateKnowledgeBuild" class="stage-retry" :disabled="startingKnowledgeBuild" @click="createKnowledgeBuild">{{ startingKnowledgeBuild ? '正在开始…' : '建立知识库' }}</button><button v-else-if="selectedDocument && selected.organizedDocument?.status === 'READY'" class="text-action" @click="rebuildKnowledge(true)">重建知识库</button><button v-if="canCancelTask" class="text-action" @click="cancelTask">取消任务</button><button v-if="canResubmitTask" class="stage-retry resubmit-task" :disabled="resubmittingTask" @click="resubmitTask">{{ resubmittingTask ? '正在重新提交…' : '重新提交转写' }}</button><button class="text-action danger" @click="deleteTask">删除录音</button><p v-if="taskActionError" class="task-action-error" role="alert">{{ taskActionError }}</p></div>
        </header>

        <section v-if="selected" class="player-surface" aria-label="音频播放">
          <p v-if="selected.failureMessage" class="task-failure" role="alert"><b>{{ stageText(selected.failedStage || selected.currentStage) }}失败</b><span>{{ selected.failureMessage }}</span></p>
          <div class="player-caption"><span class="live-dot"></span><b>原始音频</b><small>{{ selectedDocument ? statusText(selectedDocument.status) : statusText(selected.status) }}</small></div>
          <audio v-if="audioUrl" ref="audio" :src="audioUrl" controls preload="none"></audio>
          <button v-else class="audio-load" type="button" :disabled="audioLoading" @click="ensureAudio()">{{ audioLoading ? '正在加载原音…' : '播放原音' }}</button>
          <p v-if="audioLoadError" class="task-action-error" role="alert">{{ audioLoadError }}</p>
        </section>

        <template v-if="selected">
          <details v-if="selected.stages?.length" class="processing-disclosure" :open="selected.status !== 'SUCCEEDED' && selected.status !== 'CANCELLED'">
            <summary><span>处理进度</span><b>{{ stageText(selected.currentStage) }} · {{ selected.progressPercent || 0 }}%</b></summary>
            <ol class="pipeline-stages">
              <li v-for="stage in selected.stages" :key="`${stage.stage}-${stage.attemptNumber}`" :class="stage.status.toLowerCase()">
                <i></i><div><b>{{ stageText(stage.stage) }}</b><small><span class="stage-status">{{ stageStatusText(stage) }}</span> · {{ stageDurationText(stage) }}</small><small v-if="stage.modelId">模型：{{ stage.modelId }}</small><small v-if="stage.stage === 'KNOWLEDGE_INDEX'">向量库：Qdrant</small><small v-if="stage.nextRetryAt">将在 {{ new Date(stage.nextRetryAt).toLocaleTimeString() }} 自动重试</small><small v-else-if="stage.errorMessage" class="error">{{ stage.errorMessage }}</small></div>
                <button v-if="canRetryStage(stage.stage)" class="stage-retry" :disabled="retryingStage === stage.stage" @click="retryStage(stage.stage)">{{ retryingStage === stage.stage ? '正在重新提交…' : retryStageLabel(stage.stage) }}</button>
              </li>
            </ol>
            <section v-if="knowledgeBuild" class="knowledge-build-progress" aria-label="知识库构建进度">
              <header><span>知识库构建</span><b>{{ knowledgeBuild.progressPercent }}% · {{ statusText(knowledgeBuild.status) }}</b></header>
              <ol class="pipeline-stages">
                <li v-for="stage in knowledgeBuild.stages" :key="stage.stage" :class="stage.status.toLowerCase()"><i></i><div><b>{{ knowledgeStageText(stage.stage) }}</b><small><span class="stage-status">{{ statusText(stage.status) }}</span> · {{ stage.progressPercent }}%</small><small v-if="stage.stage === 'INDEX'">已索引 {{ stage.completedCount }} / {{ stage.totalCount || knowledgeBuild.chunkCount }} 个 Chunk</small><small v-else-if="stage.stage === 'INGEST'">已入库 {{ stage.completedCount }} / {{ stage.totalCount || knowledgeBuild.topicCount }} 个 Topic</small><small v-else-if="stage.stage === 'CHUNK'">已生成 {{ stage.completedCount }} / {{ stage.totalCount || knowledgeBuild.chunkCount }} 个 Chunk</small><small v-if="stage.errorMessage" class="error">{{ stage.errorMessage }}</small></div></li>
              </ol>
            </section>
            <p v-if="stageRetryError" class="retry-feedback" role="alert">{{ stageRetryError }}</p>
          </details>

          <nav class="detail-tabs" role="tablist" aria-label="文档内容">
            <button type="button" role="tab" :aria-selected="detailTab === 'transcript'" :class="{ active: detailTab === 'transcript' }" @click="selectDetailTab('transcript')"><span class="tab-icon">原</span><span><b>原始文档</b><small>完整 ASR 转写</small></span></button>
            <button type="button" role="tab" :aria-selected="detailTab === 'organized'" :class="{ active: detailTab === 'organized' }" @click="selectDetailTab('organized')"><span class="tab-icon">正</span><span><b>正式文档</b><small>清洗与 AI 整理</small></span></button>
            <button type="button" role="tab" :aria-selected="detailTab === 'summary'" :class="{ active: detailTab === 'summary' }" @click="selectDetailTab('summary')"><span class="tab-icon">AI</span><span><b>AI 摘要</b><small>重点与结论</small></span></button>
          </nav>

          <section v-if="detailTab === 'transcript'" class="detail-content transcript" role="tabpanel">
            <div v-if="speakers.length" class="speaker-roster"><span>说话人名称</span><label v-for="speaker in speakers" :key="speaker.speakerId"><b :style="{ color: speakerColor(speaker.speakerId) }">{{ speaker.speakerId }}</b><input v-model="speaker.displayName" maxlength="128" placeholder="填写名称（可选）" @keyup.enter="saveSpeakerName(speaker)" @blur="saveSpeakerName(speaker)"><button type="button" class="speaker-save" :disabled="savingSpeakerId === speaker.speakerId" @click="saveSpeakerName(speaker)">{{ savingSpeakerId === speaker.speakerId ? '保存中' : '保存' }}</button></label></div>
            <button v-for="segment in segments" :key="segment.id" :id="`segment-${segment.id}`" class="segment" type="button" @click="seekToSegment(segment)"><time>{{ timecode(segment.startMs) }}</time><span><b :style="{ color: speakerColor(segment.speakerId) }">{{ segment.speaker || '说话人' }}</b><p>{{ segment.text }}</p></span><i aria-hidden="true">↗</i></button>
            <div v-if="!segments.length" class="content-empty"><span aria-hidden="true">…</span><b>原始文档尚未准备好。</b><p>完成后会显示带说话人和时间戳的完整 ASR 转写。</p></div>
          </section>

          <section v-else-if="detailTab === 'summary'" class="detail-content summary-content" role="tabpanel">
            <div v-if="!canSummarize" class="content-empty"><span aria-hidden="true">AI</span><b>整理文档完成后即可生成摘要。</b><p>摘要会基于整理后的内容提炼核心结论，并保留原文证据。</p></div>
            <template v-else-if="summaryDetail">
              <div class="summary-label"><span>AI SUMMARY</span><small>{{ statusText(summaryDetail.run.status) }} · {{ summaryDetail.run.callsUsed }}/{{ summaryDetail.run.maxCalls }}<template v-if="summaryDetail.run.modelId"> · {{ summaryDetail.run.modelId }}</template></small></div>
              <template v-if="parsedSummary"><p class="summary-lede">{{ parsedSummary.answer }}</p><article v-for="(finding, index) in parsedSummary.findings" :key="index" class="summary-finding"><b>{{ finding.title || `要点 ${index + 1}` }}</b><p>{{ finding.content }}</p><button v-if="finding.evidence?.length" class="citation" @click="openSummaryEvidence(finding.evidence?.[0])">定位原文音频<span v-if="finding.evidence.length > 1">（{{ finding.evidence.length }} 处）</span> ↗</button></article></template>
              <p v-else-if="summaryDetail.run.failureMessage" class="error">{{ summaryDetail.run.failureMessage }}</p>
              <p v-else class="waiting">AI 正在阅读整理文档并提炼重点…</p>
            </template>
            <div v-else class="content-empty"><span aria-hidden="true">✦</span><b>{{ summaryLoading ? '正在读取已有 AI 摘要…' : 'AI 摘要尚未生成。' }}</b><p>摘要是可选操作，会基于正式文档额外调用模型生成重点与结论。</p><button class="stage-retry" type="button" :disabled="summaryLoading" @click="createSummary">{{ summaryLoading ? '正在开始…' : '生成 AI 摘要' }}</button></div>
          </section>

          <section v-else class="detail-content organized-content" role="tabpanel">
            <template v-if="organized?.document.status === 'READY'"><div v-if="organized.document.summary" class="organized-summary"><b>文档摘要</b><p>{{ organized.document.summary }}</p></div><article v-for="topic in organizedTopics" :key="topic.id" class="organized-block"><button class="topic-link" @click="seekToTime(topic.startMs, true)">{{ topic.topic || '整理片段' }} <span>{{ timecode(topic.startMs) }}</span></button><p v-if="topic.summary" class="topic-summary">{{ topic.summary }}</p><article v-for="item in topicChildren(topic.id)" :key="item.id" class="organized-unit"><small>{{ item.type === 'QA_PAIR' ? '问答' : '对话' }}</small><p>{{ item.text }}</p></article></article></template>
            <div v-else class="content-empty"><span aria-hidden="true">◎</span><b>正式文档尚未准备好。</b><p>请在原始文档完成后手动生成清洗、整理后的正式文档。</p></div>
          </section>
        </template>
      </section>
    </section>

    <aside class="agent-rail" :class="{ 'is-open': mobileAgentOpen }">
      <header class="agent-head"><div><p class="eyebrow">AI KNOWLEDGE</p><h3>{{ agentTitle }}</h3></div><button class="agent-close" type="button" @click="mobileAgentOpen = false" aria-label="关闭 AI 问答">×</button></header>
      <p class="agent-description">{{ agentDescription }}</p>
      <div class="agent-suggestions"><button v-for="suggestion in agentSuggestions" :key="suggestion" type="button" @click="useSuggestion(suggestion)">{{ suggestion }} <span>↗</span></button></div>
      <div class="ask-box"><textarea v-model="question" rows="4" :placeholder="agentPlaceholder"></textarea><div><span>{{ scope === 'CURRENT_DOCUMENT' ? '当前音频' : '全部资料库' }}</span><button class="send-button" :disabled="asking || !question.trim() || (scope === 'CURRENT_DOCUMENT' && !canAnalyzeCurrent)" @click="askAgent">{{ asking ? '处理中' : '发送' }} <b>↑</b></button></div></div>
      <p v-if="scope === 'CURRENT_DOCUMENT' && !canAnalyzeCurrent" class="agent-note">当前音频仍在转写，完成后即可提问。</p>

      <section v-if="activeRun" class="result-card">
        <div class="result-head"><span>{{ scope === 'CURRENT_DOCUMENT' ? '文档分析' : '知识任务' }}</span><small>{{ statusText(activeRun.status) }} · {{ activeRunUsage }}</small></div>
        <template v-if="parsedAnswer"><p class="answer">{{ parsedAnswer.answer }}</p><article v-for="(finding, index) in parsedAnswer.findings" :key="index" class="finding"><b>{{ finding.title || `发现 ${index + 1}` }}</b><p>{{ finding.content }}</p><button v-for="citation in finding.evidence" :key="`${citation.chunkId || 'local'}-${citation.segmentId}`" class="citation" @click="openEvidence(citation)">{{ evidenceLabel(citation) }}</button></article></template>
        <p v-else-if="activeRun.failureMessage" class="error">{{ activeRun.failureMessage }}</p>
        <p v-else class="waiting">{{ scope === 'CURRENT_DOCUMENT' ? '正在阅读当前听记并整理证据…' : '正在检索、读取原文并整理证据…' }}</p>
      </section>

      <section v-if="visibleHistory.length" class="run-history"><p class="eyebrow">RECENT QUESTIONS</p><button v-for="run in visibleHistory.slice(0, 4)" :key="run.id" @click="openHistory(run)"><span>{{ statusText(run.status) }}</span><small>{{ run.id.slice(0, 8) }}</small></button></section>
    </aside>
  </main>
</template>
