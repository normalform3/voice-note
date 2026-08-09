<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api, hashFile, key, stageStatusText, stageText, statusText, timecode, uploadErrorMessage, type AgentCapabilities, type AgentRun, type AgentRunDetail, type AgentScopeType, type AgentSkill, type AnalysisRun, type AnalysisRunDetail, type KnowledgeDocument, type KnowledgeIndexBuild, type KnowledgeRun, type KnowledgeRunDetail, type OrganizedDocumentDetail, type PipelineStage, type Segment, type Speaker, type SpeakerCorrectionResult, type Task, type WorkspaceSnapshot } from './api'

type WorkspaceView = 'library' | 'document'
type DetailTab = 'transcript' | 'summary' | 'organized'
type Coverage = { scopeDocumentCount: number; overviewedDocumentIds: string[]; searchedDocumentIds: string[]; citedDocumentIds: string[]; omittedDocumentIds: string[]; limitations: string[] }
type Finding = { title?: string; content?: string; evidence?: { sourceRef?: string; chunkId?: string; segmentId?: string }[] }

const token = ref(localStorage.getItem('voicenote_token') || '')
const loginMode = ref<'login' | 'register'>('login')
const account = ref('')
const password = ref('')
const authError = ref('')
const tasks = ref<Task[]>([])
const documents = ref<KnowledgeDocument[]>([])
const runs = ref<KnowledgeRun[]>([])
const agentRuns = ref<AgentRun[]>([])
const agent = ref<AgentRunDetail | null>(null)
const agentSkills = ref<AgentSkill[]>([])
const agentCapabilities = ref<AgentCapabilities | null>(null)
const selectedSkillId = ref('')
const selectedTaskIds = ref<string[]>([])
const libraryScope = ref<'selected' | 'all'>('all')
const analysisRuns = ref<AnalysisRun[]>([])
const selected = ref<Task | null>(null)
const segments = ref<Segment[]>([])
const speakers = ref<Speaker[]>([])
const organized = ref<OrganizedDocumentDetail | null>(null)
const audioUrl = ref('')
const audioLoading = ref(false)
const audioLoadError = ref('')
const workspaceLoading = ref(false)
const workspaceLoadError = ref('')
const documentLoading = ref(false)
const documentLoadError = ref('')
const file = ref<File | null>(null)
const uploading = ref(false)
const progress = ref('')
const importStartedAt = ref<number | null>(null)
const clockNow = ref(Date.now())
const retryingStage = ref<PipelineStage | null>(null)
const stageRetryError = ref('')
const resubmittingTask = ref(false)
const taskActionError = ref('')
const savingMetadata = ref(false)
const metadataSaved = ref(false)
const metadataForm = ref({ occurredAt: '', sceneType: 'OTHER' as Task['sceneType'], subject: '', tags: '' })
const editingTaskId = ref<string | null>(null)
const renameValue = ref('')
const renamingTaskId = ref<string | null>(null)
const renameError = ref('')
const question = ref('请总结近期会议中的关键结论、风险和下一步行动。')
const knowledge = ref<KnowledgeRunDetail | null>(null)
const analysis = ref<AnalysisRunDetail | null>(null)
const asking = ref(false)
const audio = ref<HTMLAudioElement | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const speakerDiarization = ref(true)
const speakerCount = ref<number | null>(null)
const savingSpeakerId = ref<string | null>(null)
const speakerEditMode = ref(false)
const selectedSegmentIds = ref<string[]>([])
const speakerCorrectionTarget = ref('')
const savingSpeakerCorrection = ref(false)
const speakerCorrectionMessage = ref('')
const speakerCorrectionError = ref('')
let lastSelectedSegmentIndex: number | null = null
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
let workspaceRequest: Promise<void> | null = null
let documentRequestVersion = 0

const isDocumentView = computed(() => workspaceView.value === 'document')
const agentScopeType = computed<AgentScopeType>(() => isDocumentView.value ? 'CURRENT_DOCUMENT' : libraryScope.value === 'selected' ? 'SELECTED_DOCUMENTS' : 'ALL_DOCUMENTS')
const selectedDocument = computed(() => documents.value.find(document => document.transcriptionTaskId === selected.value?.id))
const knowledgeBuild = computed<KnowledgeIndexBuild | undefined>(() => selectedDocument.value?.currentBuild || selected.value?.knowledgeDocument?.currentBuild)
const organizedTopics = computed(() => organized.value?.blocks.filter(block => block.type === 'TOPIC') || [])
const selectedTitle = computed(() => selected.value ? taskTitle(selected.value) : '从资料库选择一份听记')
const canAnalyzeCurrent = computed(() => Boolean(selected.value?.transcriptReady))
const canCancelTask = computed(() => Boolean(selected.value && !['SUCCEEDED', 'CANCELLED'].includes(selected.value.status)))
const canResubmitTask = computed(() => selected.value?.status === 'CANCELLED')
const canSummarize = computed(() => selected.value?.organizedDocument?.status === 'READY')
const canCreateFormalDocument = computed(() => selected.value?.status === 'WAITING_FOR_FORMAL_DOCUMENT' && (!selected.value?.organizedDocument || selected.value.organizedDocument.status === 'STALE'))
const canCreateKnowledgeBuild = computed(() => selected.value?.status === 'WAITING_FOR_KNOWLEDGE_BUILD' && selected.value?.organizedDocument?.status === 'READY')
const activeRun = computed(() => agent.value?.run)
const activeEvidence = computed(() => agent.value?.evidence || [])
const activeRunUsage = computed(() => activeRun.value
  ? `模型 ${activeRun.value.modelCallsUsed}/${activeRun.value.maxModelCalls} · 工具 ${activeRun.value.toolCallsUsed}/${activeRun.value.maxToolCalls}` : '')
const parsedAnswer = computed(() => parseResultDocument(activeRun.value?.resultDocument))
const visibleHistory = computed(() => agentRuns.value.filter(run => run.scopeType === agentScopeType.value))
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
  return analysisRuns.value.find(run => run.status !== 'STALE' && run.transcriptionTaskId === task.id && run.analysisMode === 'summary' && run.organizedDocumentId === document.id)
})
const speakerCorrectionBlocked = computed(() => Boolean(selected.value && selected.value.status === 'RUNNING'
  && ['DOCUMENT_ORGANIZATION', 'KNOWLEDGE_PREPARE', 'KNOWLEDGE_INDEX'].includes(selected.value.currentStage || '')))
const parsedSummary = computed(() => parseResultDocument(summaryDetail.value?.run.resultDocument))
const summaryLoading = computed(() => summaryLoadingTaskId.value === selected.value?.id)
const agentTitle = computed(() => agentScopeType.value === 'CURRENT_DOCUMENT' ? '当前文档问答' : '自主知识问答')
const agentDescription = computed(() => agentScopeType.value === 'CURRENT_DOCUMENT'
  ? '只基于这份音频的听记内容回答。'
  : libraryScope.value === 'selected' ? `在勾选的 ${selectedTaskIds.value.length} 份资料中检索、比较并核实证据。` : '在全部已收录资料中自主选择工具、检索并核实证据。')
const agentPlaceholder = computed(() => agentScopeType.value === 'CURRENT_DOCUMENT'
  ? '例如：这场会议的结论和待办是什么？'
  : '例如：近期会议有哪些未决事项？')
const agentSuggestions = computed(() => agentScopeType.value === 'CURRENT_DOCUMENT'
  ? ['提炼这份录音的重点内容', '有哪些明确的下一步行动？', '不同发言人的主要观点是什么？']
  : ['总结近期会议中的关键结论', '跨会议有哪些重复出现的风险？', '找出所有需要跟进的行动项'])
const importElapsedMs = computed(() => importStartedAt.value == null ? 0 : Math.max(0, clockNow.value - importStartedAt.value))

function parseResultDocument(raw?: string) {
  if (!raw) return null
  try { return JSON.parse(raw) as { answer?: string; findings?: Finding[]; coverage?: Coverage } }
  catch { return { answer: raw, findings: [] } }
}
function taskTitle(task: Task) {
  return task.subject || documents.value.find(document => document.transcriptionTaskId === task.id)?.title
    || task.knowledgeDocument?.title
    || `录音 ${task.id.slice(0, 8)}`
}
function normalizeTaskTags(tags: unknown): string[] {
  if (Array.isArray(tags)) return tags.filter((tag): tag is string => typeof tag === 'string')
  if (typeof tags !== 'string' || !tags.trim()) return []
  try {
    const parsed = JSON.parse(tags)
    return Array.isArray(parsed) ? parsed.filter((tag): tag is string => typeof tag === 'string') : []
  } catch { return [] }
}
function formatFileSize(size: number) {
  if (size < 1024 * 1024) return `${Math.max(1, Math.round(size / 1024))} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}
function formatCreatedAt(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '创建时间未知'
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false }).format(date)
}
function formatAudioDuration(durationMs?: number) {
  if (durationMs == null) return '时长待生成'
  const totalSeconds = Math.max(0, Math.round(durationMs / 1000))
  const hours = Math.floor(totalSeconds / 3600)
  const minutes = Math.floor((totalSeconds % 3600) / 60)
  const seconds = totalSeconds % 60
  return hours ? `${hours}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}` : `${minutes}:${String(seconds).padStart(2, '0')}`
}
function beginRename(task: Task) {
  editingTaskId.value = task.id
  renameValue.value = taskTitle(task)
  renameError.value = ''
  void nextTick(() => {
    const input = document.getElementById(`record-name-${task.id}`) as HTMLInputElement | null
    input?.focus()
    input?.select()
  })
}
function cancelRename() {
  editingTaskId.value = null
  renameValue.value = ''
  renameError.value = ''
}
async function saveTaskName(task: Task) {
  const subject = renameValue.value.trim()
  if (!subject) { renameError.value = '音频名称不能为空'; return }
  if (subject === taskTitle(task)) { cancelRename(); return }
  renamingTaskId.value = task.id
  renameError.value = ''
  try {
    const { data } = await api.patch<Task>(`/transcription-tasks/${task.id}/metadata`, {
      occurredAt: task.occurredAt,
      sceneType: task.sceneType,
      subject,
      tags: normalizeTaskTags(task.tags)
    })
    upsertTask(data)
    cancelRename()
  } catch (error: any) {
    renameError.value = error.response?.data?.message || '名称保存失败，请稍后重试。'
  } finally {
    renamingTaskId.value = null
  }
}
function showLibrary() {
  if (agent.value?.run.scopeType === 'CURRENT_DOCUMENT') agent.value = null
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
async function loadWorkspace() {
  if (workspaceRequest) return workspaceRequest
  workspaceLoading.value = true
  workspaceRequest = Promise.all([loadTasks(), loadDocuments(), loadRuns(), loadAnalysisRuns(), loadAgentRuns(), loadAgentSkills(), loadAgentCapabilities()])
    .then(() => { workspaceLoadError.value = '' })
    .catch((error) => {
      workspaceLoadError.value = error.response?.data?.message || '后端服务暂时不可用，正在等待恢复连接。'
      throw error
    })
    .finally(() => {
      workspaceLoading.value = false
      workspaceRequest = null
    })
  return workspaceRequest
}
async function retryWorkspace() {
  try { await loadWorkspace() } catch { /* Keep the visible connection error until the next retry succeeds. */ }
}
async function loadTasks() {
  const { data } = await api.get<Task[]>('/transcription-tasks')
  tasks.value = data
}
async function loadDocuments() { const { data } = await api.get<KnowledgeDocument[]>('/knowledge-documents'); documents.value = data }
async function loadRuns() { const { data } = await api.get<KnowledgeRun[]>('/knowledge-runs'); runs.value = data }
async function loadAnalysisRuns() { const { data } = await api.get<AnalysisRun[]>('/analysis-runs'); analysisRuns.value = data }
async function loadAgentRuns() { const { data } = await api.get<AgentRun[]>('/agent-runs'); agentRuns.value = data }
async function loadAgentSkills() { const { data } = await api.get<AgentSkill[]>('/agent-runs/skills'); agentSkills.value = data }
async function loadAgentCapabilities() { const { data } = await api.get<AgentCapabilities>('/agent-runs/capabilities'); agentCapabilities.value = data }
async function choose(task: Task) {
  agent.value = null
  selected.value = task
  workspaceView.value = 'document'
  detailTab.value = 'transcript'
  mobileAgentOpen.value = false
  segments.value = []
  speakers.value = []
  speakerEditMode.value = false
  selectedSegmentIds.value = []
  speakerCorrectionTarget.value = ''
  speakerCorrectionMessage.value = ''
  speakerCorrectionError.value = ''
  lastSelectedSegmentIndex = null
  organized.value = null
  syncMetadataForm(task)
  if (audioUrl.value) URL.revokeObjectURL(audioUrl.value)
  audioUrl.value = ''
  audioLoading.value = false
  audioLoadError.value = ''
  await loadDocumentDetails(task)
}
async function loadDocumentDetails(task: Task) {
  const requestVersion = ++documentRequestVersion
  documentLoading.value = true
  documentLoadError.value = ''
  try {
    const [transcript, speakerResponse, organizedResponse] = await Promise.all([
      api.get<Segment[]>(`/transcription-tasks/${task.id}/segments`),
      api.get<Speaker[]>(`/transcription-tasks/${task.id}/speakers`),
      task.organizedDocument?.status === 'READY' ? api.get<OrganizedDocumentDetail>(`/organized-documents/${task.organizedDocument.id}`) : Promise.resolve(null)
    ])
    if (selected.value?.id !== task.id || requestVersion !== documentRequestVersion) return
    segments.value = transcript.data
    speakers.value = speakerResponse.data
    organized.value = organizedResponse?.data || null
  } catch (error: any) {
    if (selected.value?.id === task.id && requestVersion === documentRequestVersion) {
      documentLoadError.value = error.response?.data?.message || '文档读取失败。后端恢复连接后将自动重试，也可以立即重试。'
    }
  } finally {
    if (requestVersion === documentRequestVersion) documentLoading.value = false
  }
}
async function retrySelectedDocument() {
  if (selected.value) await loadDocumentDetails(selected.value)
}
function syncMetadataForm(task: Task) {
  const date = new Date(task.occurredAt)
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 16)
  metadataForm.value = { occurredAt: local, sceneType: task.sceneType || 'OTHER', subject: task.subject || '', tags: normalizeTaskTags(task.tags).join('，') }
  metadataSaved.value = false
}
async function saveMetadata() {
  if (!selected.value || !metadataForm.value.occurredAt) return
  savingMetadata.value = true; metadataSaved.value = false; taskActionError.value = ''
  try {
    const tags = metadataForm.value.tags.split(/[,，]/).map(value => value.trim()).filter(Boolean)
    const { data } = await api.patch<Task>(`/transcription-tasks/${selected.value.id}/metadata`, {
      occurredAt: new Date(metadataForm.value.occurredAt).toISOString(), sceneType: metadataForm.value.sceneType,
      subject: metadataForm.value.subject.trim() || null, tags
    })
    upsertTask(data); metadataSaved.value = true
  } catch (error: any) { taskActionError.value = error.response?.data?.message || '元数据保存失败。' }
  finally { savingMetadata.value = false }
}
function isScopeSelectable(task: Task) { return documents.value.some(document => document.transcriptionTaskId === task.id && document.status === 'READY') }
function toggleTaskSelection(taskId: string) {
  selectedTaskIds.value = selectedTaskIds.value.includes(taskId) ? selectedTaskIds.value.filter(id => id !== taskId) : [...selectedTaskIds.value, taskId]
  if (selectedTaskIds.value.length) libraryScope.value = 'selected'
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
function speakerName(speakerId?: string) {
  const speaker = speakers.value.find(value => value.speakerId === speakerId)
  return speaker?.displayName || speakerId || '未知说话人'
}
function enterSpeakerEditMode() {
  if (speakerCorrectionBlocked.value) return
  speakerEditMode.value = true
  speakerCorrectionMessage.value = ''
  speakerCorrectionError.value = ''
}
function finishSpeakerEditMode() {
  speakerEditMode.value = false
  clearSegmentSelection()
}
function handleSegmentClick(segment: Segment, event: MouseEvent) {
  if (!speakerEditMode.value) { void seekToSegment(segment); return }
  toggleSegmentSelection(segment, event.shiftKey)
}
function handleSegmentKeydown(segment: Segment, event: KeyboardEvent) {
  if (event.key !== 'Enter' && event.key !== ' ') return
  event.preventDefault()
  if (!speakerEditMode.value) { void seekToSegment(segment); return }
  toggleSegmentSelection(segment, event.shiftKey)
}
function toggleSegmentSelection(segment: Segment, shiftKey = false) {
  if (savingSpeakerCorrection.value || speakerCorrectionBlocked.value) return
  const next = new Set(selectedSegmentIds.value)
  const shouldSelect = !next.has(segment.id)
  if (shiftKey && lastSelectedSegmentIndex != null) {
    const start = Math.min(lastSelectedSegmentIndex, segment.index)
    const end = Math.max(lastSelectedSegmentIndex, segment.index)
    for (const item of segments.value.filter(value => value.index >= start && value.index <= end)) {
      if (shouldSelect) next.add(item.id); else next.delete(item.id)
    }
  } else if (shouldSelect) next.add(segment.id)
  else next.delete(segment.id)
  selectedSegmentIds.value = [...next]
  lastSelectedSegmentIndex = segment.index
  speakerCorrectionMessage.value = ''
  speakerCorrectionError.value = ''
}
function clearSegmentSelection() {
  selectedSegmentIds.value = []
  speakerCorrectionTarget.value = ''
  lastSelectedSegmentIndex = null
}
async function applySelectedSpeakerCorrection() {
  if (!speakerCorrectionTarget.value || !selectedSegmentIds.value.length) return
  await applySpeakerCorrection(selectedSegmentIds.value, speakerCorrectionTarget.value === '__ASR__' ? null : speakerCorrectionTarget.value)
}
async function resetSelectedSpeakerCorrections() {
  if (!selectedSegmentIds.value.length) return
  await applySpeakerCorrection(selectedSegmentIds.value, null)
}
async function applySpeakerCorrection(segmentIds: string[], speakerId: string | null): Promise<boolean> {
  const task = selected.value
  if (!task || savingSpeakerCorrection.value || speakerCorrectionBlocked.value) return false
  const invalidatesDerived = task.organizedDocument?.status === 'READY' || task.knowledgeDocument?.status === 'READY'
  if (invalidatesDerived && !window.confirm('修改说话人后，现有正式文档、摘要和知识索引需要重新生成。继续吗？')) return false
  savingSpeakerCorrection.value = true
  speakerCorrectionMessage.value = ''
  speakerCorrectionError.value = ''
  try {
    const { data } = await api.patch<SpeakerCorrectionResult>(`/transcription-tasks/${task.id}/segments/speakers`, {
      segmentIds, speakerId, expectedRevision: task.speakerCorrectionRevision || 0
    })
    upsertTask(data.task)
    organized.value = null
    const summaries = { ...summaryByTaskId.value }; delete summaries[task.id]; summaryByTaskId.value = summaries
    await Promise.all([refreshTranscript(task.id), loadDocuments(), loadAnalysisRuns()])
    clearSegmentSelection()
    speakerCorrectionMessage.value = data.changedSegmentCount
      ? `已修正 ${data.changedSegmentCount} 句。${data.task.organizedDocument?.status === 'STALE' ? '请重新生成正式文档和知识库。' : ''}`
      : '所选句子的说话人没有变化。'
    return true
  } catch (error: any) {
    const code = error.response?.data?.code
    speakerCorrectionError.value = error.response?.data?.message || '说话人纠错保存失败。'
    if (code === 'SPEAKER_REVISION_CONFLICT') {
      const { data } = await api.get<Task>(`/transcription-tasks/${task.id}`)
      upsertTask(data)
      await refreshTranscript(task.id)
    }
    return false
  } finally { savingSpeakerCorrection.value = false }
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
function evidenceLabel(citation: { sourceRef?: string; chunkId?: string; segmentId?: string }) {
  const evidence = activeEvidence.value.find(item => citation.sourceRef ? item.sourceRef === citation.sourceRef : item.chunkId === citation.chunkId && item.segmentId === citation.segmentId)
  if (!evidence) return '原文证据 ↗'
  if (evidence.sourceKind === 'EXTERNAL') return `${evidence.externalLabel || '外部来源'} ↗`
  if (evidence.sourceKind === 'DOCUMENT_METADATA') return `${evidence.topic || '文档元数据'} · ${evidence.text || '范围信息'}`
  return `${evidence.topic || '原文'} · ${evidence.speaker || evidence.speakerId || '说话人'} · ${timecode(evidence.startMs || 0)} ↗`
}
function stepLabel(type: string, toolName?: string) {
  if (type === 'ROUTE') return '选择任务 Skill'
  if (type === 'MODEL') return 'Agent 决策'
  if (type === 'FINALIZE') return '校验证据并提交答案'
  return ({ document_list: '筛选文档范围', document_overview: '读取文档概览', knowledge_search: '混合检索与重排', transcript_context: '读取相邻原文' } as Record<string, string>)[toolName || ''] || toolName || '调用只读工具'
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
  if (!question.value.trim() || !agentCapabilities.value?.enabled) return
  asking.value = true
  try {
    if (agentScopeType.value === 'CURRENT_DOCUMENT' && !selected.value?.transcriptReady) return
    if (agentScopeType.value === 'SELECTED_DOCUMENTS' && !selectedTaskIds.value.length) return
    const transcriptionTaskIds = agentScopeType.value === 'CURRENT_DOCUMENT' ? [selected.value!.id]
      : agentScopeType.value === 'SELECTED_DOCUMENTS' ? selectedTaskIds.value : []
    const { data } = await api.post<AgentRun>('/agent-runs', {
      question: question.value, scope: { type: agentScopeType.value, transcriptionTaskIds },
      skillId: selectedSkillId.value || null, timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone
    }, { headers: { 'Idempotency-Key': key() } })
    agent.value = { run: data, documentIds: transcriptionTaskIds, steps: [], evidence: [] }
    upsertAgent(data)
  } catch (error: any) {
    agent.value = { run: { id: '', question: question.value, status: 'FAILED', scopeType: agentScopeType.value, skillId: selectedSkillId.value || 'auto', skillVersion: '', scopeDocumentCount: 0,
      modelCallsUsed: 0, maxModelCalls: 0, agentTurnsUsed: 0, maxAgentTurns: 0, toolCallsUsed: 0, maxToolCalls: 0,
      failureMessage: error.response?.data?.message || '无法创建 Agent 任务', createdAt: new Date().toISOString() }, documentIds: [], steps: [], evidence: [] }
  } finally { asking.value = false }
}
async function loadAgentDetail(runId: string) {
  const { data } = await api.get<AgentRunDetail>(`/agent-runs/${runId}`)
  agent.value = data; upsertAgent(data.run)
  return data
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
async function openHistory(run: AgentRun) {
  if (run.scopeType !== 'CURRENT_DOCUMENT') { showLibrary(); await loadAgentDetail(run.id); return }
  const detail = await loadAgentDetail(run.id)
  const task = tasks.value.find(value => value.id === detail.documentIds[0])
  if (task) await choose(task)
  agent.value = detail
}
async function seekToTime(startMs: number, play = false) {
  await ensureAudio(startMs, play)
}
async function seekToSegment(segment: Segment) {
  await seekToTime(segment.startMs, true)
}
async function openEvidence(citation: { sourceRef?: string; chunkId?: string; segmentId?: string }) {
  const evidence = activeEvidence.value.find(item => citation.sourceRef ? item.sourceRef === citation.sourceRef : item.chunkId === citation.chunkId && item.segmentId === citation.segmentId)
  if (evidence?.sourceKind === 'EXTERNAL' && evidence.externalUrl) { window.open(evidence.externalUrl, '_blank', 'noopener,noreferrer'); return }
  if (!evidence?.segmentId) return
  const detail = agent.value
  await openEvidenceForTask({ segmentId: evidence.segmentId }, evidence.transcriptionTaskId)
  agent.value = detail
}
async function openSummaryEvidence(citation?: { segmentId?: string }) {
  if (!citation?.segmentId) return
  await openEvidenceForTask({ segmentId: citation.segmentId }, summaryDetail.value?.run.transcriptionTaskId)
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
    if (document?.status === 'STALE') {
      organized.value = null
      const summaries = { ...summaryByTaskId.value }; delete summaries[task.id]; summaryByTaskId.value = summaries
    }
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
function upsertAgent(run: AgentRun) { agentRuns.value = [run, ...agentRuns.value.filter(item => item.id !== run.id)] }
function applySnapshot(snapshot: WorkspaceSnapshot) {
  tasks.value = snapshot.tasks
  documents.value = snapshot.documents
  runs.value = snapshot.knowledgeRuns
  analysisRuns.value = snapshot.analyses
  selectedTaskIds.value = selectedTaskIds.value.filter(id => snapshot.tasks.some(task => task.id === id))
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
  if (name === 'knowledge-run-settled' && payload.run) {
    if (agent.value?.run.id === payload.run.id) void loadAgentDetail(payload.run.id).catch(() => loadKnowledgeDetail(payload.run.id))
    else void loadAgentRuns()
    return
  }
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
    if (workspaceLoadError.value || documentLoadError.value) void recoverAfterReconnect()
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
async function recoverAfterReconnect() {
  try {
    if (workspaceLoadError.value) await loadWorkspace()
    if (documentLoadError.value && selected.value) {
      const current = tasks.value.find(task => task.id === selected.value?.id) || selected.value
      selected.value = current
      await loadDocumentDetails(current)
    }
  } catch { /* The progress stream will reconnect and try again if the backend remains unavailable. */ }
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
  agentRuns.value = []
  agent.value = null
  agentCapabilities.value = null
  analysisRuns.value = []
  knowledge.value = null
  analysis.value = null
  organized.value = null
  workspaceLoadError.value = ''
  documentLoadError.value = ''
  documentRequestVersion++
  summaryByTaskId.value = {}
  workspaceView.value = 'library'
}
watch(speakerDiarization, enabled => { if (!enabled) speakerCount.value = null })
onMounted(() => {
  clockTimer = window.setInterval(() => { clockNow.value = Date.now() }, 1000)
  if (token.value) { void loadWorkspace().catch(() => {}); void connectProgressEvents() }
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

        <div v-if="workspaceLoadError" class="connection-notice" role="alert">
          <span><b>后端连接已中断</b>{{ workspaceLoadError }}</span>
          <button type="button" :disabled="workspaceLoading" @click="retryWorkspace">{{ workspaceLoading ? '正在重连…' : '立即重试' }}</button>
        </div>

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

        <div class="records-head"><div><p class="eyebrow">RECENT RECORDINGS</p><h3>最近音频</h3></div><span>创建时间 · 音频时长 · 状态</span></div>
        <div class="record-list">
          <article v-for="task in tasks" :key="task.id" class="record-row" :class="{ active: selected?.id === task.id }">
            <label class="record-select" :class="{ unavailable: !isScopeSelectable(task) }" :title="isScopeSelectable(task) ? '加入多文档问答范围' : '建立知识索引后可勾选'" @click.stop>
              <input type="checkbox" :checked="selectedTaskIds.includes(task.id)" :disabled="!isScopeSelectable(task)" @change="toggleTaskSelection(task.id)">
            </label>
            <button class="record-row-open" type="button" :aria-label="`打开${taskTitle(task)}`" @click="choose(task)"></button>
            <div class="record-main">
              <span class="record-wave" aria-hidden="true"><i></i><i></i><i></i><i></i><i></i></span>
              <span class="record-title">
                <form v-if="editingTaskId === task.id" class="record-rename" @submit.prevent="saveTaskName(task)" @click.stop>
                  <label class="visually-hidden" :for="`record-name-${task.id}`">音频名称</label>
                  <input :id="`record-name-${task.id}`" v-model="renameValue" maxlength="512" autocomplete="off" :aria-invalid="Boolean(renameError)" @keydown.esc.prevent="cancelRename">
                  <button type="submit" :disabled="renamingTaskId === task.id || !renameValue.trim()" aria-label="保存音频名称">{{ renamingTaskId === task.id ? '…' : '✓' }}</button>
                  <button type="button" :disabled="renamingTaskId === task.id" aria-label="取消修改" @click="cancelRename">×</button>
                  <small v-if="renameError" class="record-rename-error" role="alert">{{ renameError }}</small>
                </form>
                <template v-else>
                  <span class="record-name-line">
                    <span class="record-name"><b>{{ taskTitle(task) }}</b></span>
                    <button class="record-edit" type="button" :aria-label="`修改${taskTitle(task)}的名称`" title="修改名称" @click="beginRename(task)">修改</button>
                  </span>
                  <small><time :datetime="task.createdAt">{{ formatCreatedAt(task.createdAt) }}</time><span> · </span><span>{{ formatAudioDuration(task.durationMs) }}</span></small>
                </template>
              </span>
              <span class="record-progress"><b>{{ task.progressPercent || 0 }}%</b><small>{{ statusText(task.status) }}</small></span>
              <span class="record-arrow" aria-hidden="true">→</span>
            </div>
            <button v-if="task.knowledgeDocument?.status === 'FAILED'" class="record-retry" type="button" @click="retryDocument(task.knowledgeDocument)">重试收录</button>
          </article>
          <div v-if="!tasks.length" class="library-empty"><span aria-hidden="true">⌁</span><b>这里还没有声音。</b><p>导入第一段音频，开始建立你的个人听记资料库。</p></div>
        </div>
      </section>

      <section v-else class="document-page page-reveal">
        <nav class="breadcrumb" aria-label="当前位置"><button type="button" @click="showLibrary">音频资料库</button><span>/</span><b>{{ selectedTitle }}</b></nav>
        <header class="document-head">
          <div><p class="eyebrow">DOCUMENT LISTENING</p><h2>{{ selectedTitle }}</h2></div>
          <div v-if="selected" class="document-actions"><span class="state-pill">{{ selected.progressPercent || 0 }}% · {{ stageText(selected.currentStage) }}</span><button v-if="canCreateFormalDocument" class="stage-retry" :disabled="startingFormalDocument" @click="createFormalDocument">{{ startingFormalDocument ? '正在开始…' : selected.organizedDocument?.status === 'STALE' ? '重新生成正式文档' : '生成正式文档' }}</button><button v-if="canCreateKnowledgeBuild" class="stage-retry" :disabled="startingKnowledgeBuild" @click="createKnowledgeBuild">{{ startingKnowledgeBuild ? '正在开始…' : '建立知识库' }}</button><button v-else-if="selectedDocument && selected.organizedDocument?.status === 'READY'" class="text-action" @click="rebuildKnowledge(true)">重建知识库</button><button v-if="canCancelTask" class="text-action" @click="cancelTask">取消任务</button><button v-if="canResubmitTask" class="stage-retry resubmit-task" :disabled="resubmittingTask" @click="resubmitTask">{{ resubmittingTask ? '正在重新提交…' : '重新提交转写' }}</button><button class="text-action danger" @click="deleteTask">删除录音</button><p v-if="taskActionError" class="task-action-error" role="alert">{{ taskActionError }}</p></div>
        </header>

        <details v-if="selected" class="metadata-editor">
          <summary><span>业务元数据</span><b>{{ selected.sceneType === 'INTERVIEW' ? '面试' : selected.sceneType === 'MEETING' ? '会议' : '其他' }} · {{ selected.subject || '未填写主题' }}</b></summary>
          <div class="metadata-grid">
            <label>发生时间<input v-model="metadataForm.occurredAt" type="datetime-local" required></label>
            <label>场景<select v-model="metadataForm.sceneType"><option value="INTERVIEW">面试</option><option value="MEETING">会议</option><option value="OTHER">其他</option></select></label>
            <label class="metadata-wide">主题<input v-model="metadataForm.subject" maxlength="512" placeholder="例如：后端工程师二面"></label>
            <label class="metadata-wide">标签<input v-model="metadataForm.tags" placeholder="使用逗号分隔，例如：Java，候选人 A"></label>
            <div class="metadata-actions"><span v-if="metadataSaved">已保存</span><button type="button" :disabled="savingMetadata" @click="saveMetadata">{{ savingMetadata ? '保存中…' : '保存元数据' }}</button></div>
          </div>
        </details>

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
            <div v-if="segments.length" class="speaker-correction-guide" :class="{ editing: speakerEditMode }"><span><b>{{ speakerEditMode ? '正在修改说话人' : '局部说话人校对' }}</b><small>{{ speakerEditMode ? '点击整条句子即可选择，按住 Shift 可连续选择。' : '发现少量身份标错时，可进入修改模式人工校对。' }}</small></span><div class="speaker-correction-guide-actions"><em>修订 {{ selected?.speakerCorrectionRevision || 0 }}</em><button v-if="!speakerEditMode" type="button" :disabled="speakerCorrectionBlocked" @click="enterSpeakerEditMode">修改说话人</button><button v-else type="button" class="finish" :disabled="savingSpeakerCorrection" @click="finishSpeakerEditMode">完成</button></div></div>
            <p v-if="selected?.organizedDocument?.status === 'STALE'" class="speaker-correction-notice">说话人标注已经修改。旧正式文档、摘要和知识索引已停用，请重新生成正式文档。</p>
            <div v-if="speakerEditMode" class="speaker-correction-toolbar" role="region" aria-label="批量修改所选句子的说话人"><b>{{ selectedSegmentIds.length ? `已选 ${selectedSegmentIds.length} 句` : '点击下方句子进行选择' }}</b><select v-model="speakerCorrectionTarget" :disabled="!selectedSegmentIds.length || savingSpeakerCorrection || speakerCorrectionBlocked"><option value="">选择正确的说话人</option><option v-for="speaker in speakers" :key="speaker.speakerId" :value="speaker.speakerId">{{ speaker.displayName || speaker.speakerId }}</option></select><button type="button" :disabled="!speakerCorrectionTarget || !selectedSegmentIds.length || savingSpeakerCorrection || speakerCorrectionBlocked" @click="applySelectedSpeakerCorrection">{{ savingSpeakerCorrection ? '保存中…' : '应用修改' }}</button><button type="button" class="speaker-reset" :disabled="!selectedSegmentIds.length || savingSpeakerCorrection || speakerCorrectionBlocked" @click="resetSelectedSpeakerCorrections">重置所选</button><button type="button" class="text-action" :disabled="!selectedSegmentIds.length || savingSpeakerCorrection" @click="clearSegmentSelection">清除选择</button></div>
            <p v-if="speakerCorrectionBlocked" class="speaker-correction-notice">正式文档或知识索引正在处理，完成后才能修改说话人。</p>
            <p v-if="speakerCorrectionMessage" class="speaker-correction-feedback success" role="status">{{ speakerCorrectionMessage }}</p>
            <p v-if="speakerCorrectionError" class="speaker-correction-feedback error" role="alert">{{ speakerCorrectionError }}</p>
            <article v-for="segment in segments" :key="segment.id" :id="`segment-${segment.id}`" class="segment" :class="{ editing: speakerEditMode, selected: selectedSegmentIds.includes(segment.id), corrected: segment.speakerCorrected }" :role="speakerEditMode ? 'option' : 'button'" :aria-selected="speakerEditMode ? selectedSegmentIds.includes(segment.id) : undefined" tabindex="0" @click="handleSegmentClick(segment, $event)" @keydown="handleSegmentKeydown(segment, $event)">
              <div class="segment-marker"><span v-if="speakerEditMode" class="selection-indicator" aria-hidden="true">{{ selectedSegmentIds.includes(segment.id) ? '✓' : '' }}</span><time>{{ timecode(segment.startMs) }}</time></div>
              <div class="segment-copy"><b :style="{ color: speakerColor(segment.speakerId) }">{{ segment.speaker || '说话人' }}</b><small v-if="segment.speakerCorrected" class="corrected-badge">已人工修正 · 原标注 {{ speakerName(segment.asrSpeakerId) }}</small><p>{{ segment.text }}</p></div>
              <button class="segment-preview" type="button" :aria-label="`试听 ${timecode(segment.startMs)} 的原声`" @click.stop="seekToSegment(segment)" @keydown.stop><span>{{ speakerEditMode ? '试听' : '播放' }}</span><i aria-hidden="true">↗</i></button>
            </article>
            <div v-if="documentLoading && !segments.length" class="content-empty"><span aria-hidden="true">…</span><b>正在读取原始文档…</b><p>正在加载说话人和时间戳信息。</p></div>
            <div v-else-if="documentLoadError && !segments.length" class="content-empty"><span aria-hidden="true">!</span><b>原始文档读取失败。</b><p>{{ documentLoadError }}</p><button class="stage-retry" type="button" @click="retrySelectedDocument">立即重试</button></div>
            <div v-else-if="!segments.length" class="content-empty"><span aria-hidden="true">…</span><b>原始文档尚未准备好。</b><p>完成后会显示带说话人和时间戳的完整 ASR 转写。</p></div>
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
            <div v-else-if="documentLoading" class="content-empty"><span aria-hidden="true">◎</span><b>正在读取正式文档…</b><p>正在加载整理后的主题和内容。</p></div>
            <div v-else-if="documentLoadError" class="content-empty"><span aria-hidden="true">!</span><b>正式文档读取失败。</b><p>{{ documentLoadError }}</p><button class="stage-retry" type="button" @click="retrySelectedDocument">立即重试</button></div>
            <div v-else class="content-empty"><span aria-hidden="true">◎</span><b>正式文档尚未准备好。</b><p>请在原始文档完成后手动生成清洗、整理后的正式文档。</p></div>
          </section>
        </template>
      </section>
    </section>

    <aside class="agent-rail" :class="{ 'is-open': mobileAgentOpen }">
      <header class="agent-head"><div><p class="eyebrow">AI KNOWLEDGE</p><h3>{{ agentTitle }}</h3></div><button class="agent-close" type="button" @click="mobileAgentOpen = false" aria-label="关闭 AI 问答">×</button></header>
      <p class="agent-description">{{ agentDescription }}</p>
      <div class="agent-suggestions"><button v-for="suggestion in agentSuggestions" :key="suggestion" type="button" @click="useSuggestion(suggestion)">{{ suggestion }} <span>↗</span></button></div>
      <div v-if="!isDocumentView" class="scope-switch" aria-label="问答范围"><button :class="{ active: libraryScope === 'all' }" @click="libraryScope = 'all'">全部资料</button><button :class="{ active: libraryScope === 'selected' }" @click="libraryScope = 'selected'">已勾选 · {{ selectedTaskIds.length }}</button></div>
      <label class="skill-select">任务方式<select v-model="selectedSkillId"><option value="">自动选择 Skill</option><option v-for="skill in agentSkills" :key="skill.id" :value="skill.id">{{ skill.displayName }}</option></select></label>
      <div class="ask-box"><textarea v-model="question" rows="4" :disabled="agentCapabilities?.enabled === false" :placeholder="agentPlaceholder"></textarea><div><span>{{ agentScopeType === 'CURRENT_DOCUMENT' ? '当前音频' : agentScopeType === 'SELECTED_DOCUMENTS' ? `${selectedTaskIds.length} 份已勾选` : '全部已收录资料' }}</span><button class="send-button" :disabled="agentCapabilities?.enabled !== true || asking || !question.trim() || (agentScopeType === 'CURRENT_DOCUMENT' && !canAnalyzeCurrent) || (agentScopeType === 'SELECTED_DOCUMENTS' && !selectedTaskIds.length)" @click="askAgent">{{ asking ? '处理中' : '发送' }} <b>↑</b></button></div></div>
      <p v-if="agentCapabilities?.enabled === false" class="agent-note">自主 Agent 正在灰度中，请在部署环境启用 VOICENOTE_AGENT_ENABLED。</p>
      <p v-else-if="agentScopeType === 'CURRENT_DOCUMENT' && !canAnalyzeCurrent" class="agent-note">当前音频仍在转写，完成后即可提问。</p>
      <p v-else-if="agentScopeType === 'SELECTED_DOCUMENTS' && !selectedTaskIds.length" class="agent-note">请先在资料库勾选 1–50 份已收录文档。</p>

      <section v-if="activeRun" class="result-card">
        <div class="result-head"><span>{{ activeRun.skillId === 'auto' ? '自主 Agent' : agentSkills.find(item => item.id === activeRun?.skillId)?.displayName || activeRun.skillId }}</span><small>{{ statusText(activeRun.status) }} · {{ activeRunUsage }}</small></div>
        <template v-if="parsedAnswer"><p class="answer">{{ parsedAnswer.answer }}</p><article v-for="(finding, index) in parsedAnswer.findings" :key="index" class="finding"><b>{{ finding.title || `发现 ${index + 1}` }}</b><p>{{ finding.content }}</p><button v-for="citation in finding.evidence" :key="citation.sourceRef || `${citation.chunkId || 'local'}-${citation.segmentId}`" class="citation" @click="openEvidence(citation)">{{ evidenceLabel(citation) }}</button></article>
          <div v-if="parsedAnswer.coverage" class="coverage-strip"><span>范围 {{ parsedAnswer.coverage.scopeDocumentCount }}</span><span>概览 {{ parsedAnswer.coverage.overviewedDocumentIds.length }}</span><span>深入 {{ parsedAnswer.coverage.searchedDocumentIds.length }}</span><span>引用 {{ parsedAnswer.coverage.citedDocumentIds.length }}</span><p v-if="parsedAnswer.coverage.limitations.length">限制：{{ parsedAnswer.coverage.limitations.join('；') }}</p></div>
        </template>
        <p v-else-if="activeRun.failureMessage" class="error">{{ activeRun.failureMessage }}</p>
        <p v-else class="waiting">Agent 正在规划检索、读取原文并校验证据…</p>
        <details v-if="agent?.steps.length" class="agent-trace"><summary>运行轨迹 <b>{{ agent.steps.length }} 步</b></summary><ol><li v-for="step in agent.steps" :key="step.index" :class="step.status.toLowerCase()"><i></i><span><b>{{ stepLabel(step.type, step.toolName) }}</b><small>{{ step.summary || step.errorMessage || statusText(step.status) }}</small></span><time>{{ step.durationMs == null ? '—' : `${step.durationMs}ms` }}</time></li></ol></details>
      </section>

      <section v-if="visibleHistory.length" class="run-history"><p class="eyebrow">RECENT QUESTIONS</p><button v-for="run in visibleHistory.slice(0, 4)" :key="run.id" @click="openHistory(run)"><span>{{ run.question }}</span><small>{{ statusText(run.status) }}</small></button></section>
    </aside>
  </main>
</template>
