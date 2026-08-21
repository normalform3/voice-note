<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import AgentResultBlocks from './AgentResult.vue'
import ProfilePage from './ProfilePage.vue'
import SkillManager from './SkillManager.vue'
import ToolsCenter from './ToolsCenter.vue'
import VoiceConversationOverlay from './VoiceConversationOverlay.vue'
import { isSpeechRecognitionSupported } from './useSpeechRecognition'
import { api, hashFile, isSessionExpiredError, key, SESSION_EXPIRED_EVENT, stageStatusText, stageText, statusText, timecode, uploadErrorMessage, type AgentAnswerBlockEvent, type AgentCapabilities, type AgentConversation, type AgentConversationDetail, type AgentProgressEvent, type AgentResult as AgentResultDocument, type AgentRun, type AgentRunDetail, type AgentScopeType, type AgentSkill, type AgentStep, type AgentStepDetail, type AiSpeakerCorrectionApplyResult, type AiSpeakerCorrectionDetail, type AiSpeakerCorrectionSuggestion, type AnalysisRun, type AnalysisRunDetail, type KnowledgeDocument, type KnowledgeIndexBuild, type KnowledgeRun, type KnowledgeRunDetail, type OrganizedDocumentDetail, type PageResult, type PipelineStage, type ResultCitation, type Segment, type Speaker, type SpeakerCorrectionProposalPart, type SpeakerCorrectionResult, type Task, type WorkspaceSnapshot } from './api'

type WorkspaceView = 'library' | 'document' | 'skills' | 'tools' | 'profile'
type DetailTab = 'transcript' | 'summary' | 'organized'

const token = ref(localStorage.getItem('voicenote_token') || '')
const signedInAccount = ref(localStorage.getItem('voicenote_account') || '')
const loginMode = ref<'login' | 'register'>('login')
const account = ref('')
const password = ref('')
const authError = ref('')
const tasks = ref<Task[]>([])
const documents = ref<KnowledgeDocument[]>([])
const runs = ref<KnowledgeRun[]>([])
const agentRuns = ref<AgentRun[]>([])
const agent = ref<AgentRunDetail | null>(null)
const conversations = ref<AgentConversation[]>([])
const activeConversation = ref<AgentConversationDetail | null>(null)
const conversationMemoryEnabled = ref(true)
const conversationActionError = ref('')
const pendingMemoryCandidateCount = ref(0)
const activeStepId = ref<string | null>(null)
const activeStepDetail = ref<AgentStepDetail | null>(null)
const stepDetailLoading = ref(false)
const replayingCheckpointId = ref<string | null>(null)
const traceActionError = ref('')
const agentSkills = ref<AgentSkill[]>([])
const agentCapabilities = ref<AgentCapabilities | null>(null)
const selectedSkillId = ref('')
const skillSelectionNotice = ref('')
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
const aiSpeakerCorrection = ref<AiSpeakerCorrectionDetail | null>(null)
const selectedAiSuggestionIds = ref<string[]>([])
const startingAiSpeakerCorrection = ref(false)
const applyingAiSpeakerCorrection = ref(false)
const aiSpeakerCorrectionError = ref('')
let lastSelectedSegmentIndex: number | null = null
const startingFormalDocument = ref(false)
const startingKnowledgeBuild = ref(false)
const workspaceView = ref<WorkspaceView>('library')
const detailTab = ref<DetailTab>('transcript')
const mobileAgentOpen = ref(false)
const voiceConversationOpen = ref(false)
const voiceLiveRunId = ref('')
const voiceLiveProgress = ref<AgentProgressEvent[]>([])
const voiceLiveBlocks = ref<AgentAnswerBlockEvent[]>([])
const voiceAwaitingRun = ref(false)
const summaryByTaskId = ref<Record<string, AnalysisRunDetail>>({})
const summaryLoadingTaskId = ref<string | null>(null)
const voiceRecognitionSupported = isSpeechRecognitionSupported()
let streamController: AbortController | null = null
let reconnectTimer: number | null = null
let streamClosed = false
let reconnectDelay = 1000
let clockTimer: number | null = null
let workspaceRequest: Promise<void> | null = null
let documentRequestVersion = 0

const isDocumentView = computed(() => workspaceView.value === 'document')
const isSkillsView = computed(() => workspaceView.value === 'skills')
const isToolsView = computed(() => workspaceView.value === 'tools')
const isProfileView = computed(() => workspaceView.value === 'profile')
const isUtilityView = computed(() => isSkillsView.value || isToolsView.value || isProfileView.value)
const agentScopeType = computed<AgentScopeType>(() => activeConversation.value?.conversation.scopeType
  || (isDocumentView.value ? 'CURRENT_DOCUMENT' : libraryScope.value === 'selected' ? 'SELECTED_DOCUMENTS' : 'ALL_DOCUMENTS'))
const selectedDocument = computed(() => documents.value.find(document => document.transcriptionTaskId === selected.value?.id))
const knowledgeBuild = computed<KnowledgeIndexBuild | undefined>(() => selectedDocument.value?.currentBuild || selected.value?.knowledgeDocument?.currentBuild)
const organizedTopics = computed(() => organized.value?.blocks.filter(block => block.type === 'TOPIC') || [])
const selectedTitle = computed(() => selected.value ? taskTitle(selected.value) : '从资料库选择一份听记')
const canAnalyzeCurrent = computed(() => selected.value?.qaCapabilities?.currentDocumentAvailable ?? Boolean(selected.value?.transcriptReady))
const indexedTaskCount = computed(() => tasks.value.filter(task => task.qaCapabilities?.crossDocumentEligible).length)
const canCancelTask = computed(() => Boolean(selected.value && !['SUCCEEDED', 'CANCELLED'].includes(selected.value.status)))
const canResubmitTask = computed(() => selected.value?.status === 'CANCELLED')
const canSummarize = computed(() => selected.value?.organizedDocument?.status === 'READY')
const canCreateFormalDocument = computed(() => selected.value?.status === 'WAITING_FOR_FORMAL_DOCUMENT' && (!selected.value?.organizedDocument || selected.value.organizedDocument.status === 'STALE'))
const canCreateKnowledgeBuild = computed(() => selected.value?.status === 'WAITING_FOR_KNOWLEDGE_BUILD' && selected.value?.organizedDocument?.status === 'READY')
const activeRun = computed(() => agent.value?.run)
const conversationLocked = computed(() => Boolean(activeConversation.value))
const activeEvidence = computed(() => agent.value?.evidence || [])
const voiceAgentBusy = computed(() => Boolean(asking.value || (activeRun.value?.id && ['PENDING', 'QUEUED', 'RUNNING'].includes(activeRun.value.status))))
const activeRunTerminal = computed(() => Boolean(activeRun.value && !['PENDING', 'QUEUED', 'RUNNING'].includes(activeRun.value.status)))
const initialReplayCheckpoint = computed(() => agent.value?.checkpoints?.find(checkpoint => !checkpoint.stepId && checkpoint.replayable))
const activeRunUsage = computed(() => activeRun.value
  ? `模型 ${activeRun.value.modelCallsUsed}/${activeRun.value.maxModelCalls} · 工具 ${activeRun.value.toolCallsUsed}/${activeRun.value.maxToolCalls}` : '')
const parsedAnswer = computed(() => parseResultDocument(activeRun.value?.resultDocument))
const scopeSceneTypes = computed<Task['sceneType'][]>(() => {
  if (agentScopeType.value === 'CURRENT_DOCUMENT') return selected.value ? [selected.value.sceneType] : []
  if (agentScopeType.value === 'SELECTED_DOCUMENTS') return tasks.value.filter(task => selectedTaskIds.value.includes(task.id)).map(task => task.sceneType)
  return tasks.value.filter(task => task.qaCapabilities?.crossDocumentEligible).map(task => task.sceneType)
})
function skillCompatibilityIssue(skill: AgentSkill) {
  if (!skill.scopeTypes.includes(agentScopeType.value)) return '不支持当前问答范围'
  if (scopeSceneTypes.value.length && !scopeSceneTypes.value.some(scene => skill.sceneTypes.includes(scene))) return '不支持当前场景'
  return ''
}
const selectedSkillIssue = computed(() => {
  const skill = agentSkills.value.find(value => value.id === selectedSkillId.value)
  return skill ? skillCompatibilityIssue(skill) : ''
})
const builtInAgentSkills = computed(() => agentSkills.value.filter(skill => skill.source === 'BUILTIN'))
const customAgentSkills = computed(() => agentSkills.value.filter(skill => skill.source === 'USER'))
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
const speakerCorrectionBlocked = computed(() => Boolean(selected.value && ((selected.value.status === 'RUNNING'
  && ['DOCUMENT_ORGANIZATION', 'KNOWLEDGE_PREPARE', 'KNOWLEDGE_INDEX'].includes(selected.value.currentStage || ''))
  || analysisRuns.value.some(run => run.transcriptionTaskId === selected.value?.id && run.organizedDocumentId
    && ['QUEUED', 'RUNNING'].includes(run.status)))))
const aiSpeakerCorrectionRunning = computed(() => Boolean(aiSpeakerCorrection.value && ['QUEUED', 'RUNNING'].includes(aiSpeakerCorrection.value.run.status)))
const selectedAiSuggestionCount = computed(() => selectedAiSuggestionIds.value.length)
const parsedSummary = computed(() => parseResultDocument(summaryDetail.value?.run.resultDocument))
const summaryLoading = computed(() => summaryLoadingTaskId.value === selected.value?.id)
const agentTitle = computed(() => agentScopeType.value === 'CURRENT_DOCUMENT' ? '当前文档问答' : '自主知识问答')
const currentQaMode = computed(() => selected.value?.qaCapabilities?.currentMode)
const agentModeLabel = computed(() => {
  if (agentScopeType.value !== 'CURRENT_DOCUMENT') return `知识库检索 · ${indexedTaskCount.value} 份已入库`
  return ({ TRANSCRIPT_LOCAL: '文内问答 · 原文定位', FORMAL_OVERVIEW: '文内问答 · 正式文档辅助', HYBRID_INDEX: '知识库检索 · 已入库' } as const)[currentQaMode.value || 'TRANSCRIPT_LOCAL']
})
const agentDescription = computed(() => agentScopeType.value === 'CURRENT_DOCUMENT'
  ? currentQaMode.value === 'FORMAL_OVERVIEW' ? '先用正式文档定位主题，再回到原始听记核实证据。'
    : currentQaMode.value === 'HYBRID_INDEX' ? '使用混合索引检索，并保留回到原始听记的证据链。'
      : '通过原文分段定位回答；超长全文总结会提示先生成正式文档。'
  : libraryScope.value === 'selected' ? `在勾选的 ${selectedTaskIds.value.length} 份资料中检索、比较并核实证据。` : '在全部已入库资料中自主选择工具、检索并核实证据。')
const agentPlaceholder = computed(() => agentScopeType.value === 'CURRENT_DOCUMENT'
  ? '例如：这场会议的结论和待办是什么？'
  : '例如：近期会议有哪些未决事项？')
const agentSuggestions = computed(() => {
  const selectedSkill = agentSkills.value.find(value => value.id === selectedSkillId.value)
  if (selectedSkill) return [selectedSkill.defaultPrompt, ...(selectedSkill.suggestedPrompts || [])].filter((value): value is string => Boolean(value)).slice(0, 3)
  return agentScopeType.value === 'CURRENT_DOCUMENT'
    ? ['提炼这份录音的重点内容', '有哪些明确的下一步行动？', '不同发言人的主要观点是什么？']
    : ['总结近期会议中的关键结论', '跨会议有哪些重复出现的风险？', '找出所有需要跟进的行动项']
})
const activeSkillName = computed(() => {
  if (!activeRun.value) return ''
  if (activeRun.value.skillId === 'auto' || activeRun.value.skillVersion === 'pending') return '正在匹配 Skill'
  return activeRun.value.skillDisplayName || agentSkills.value.find(item => item.id === activeRun.value?.skillId)?.displayName || activeRun.value.skillId
})
const voiceModeAvailable = computed(() => {
  if (!voiceRecognitionSupported || agentCapabilities.value?.enabled !== true) return false
  if (agentScopeType.value === 'CURRENT_DOCUMENT') return canAnalyzeCurrent.value
  if (agentScopeType.value === 'SELECTED_DOCUMENTS') return selectedTaskIds.value.length > 0
  return indexedTaskCount.value > 0
})
const voiceModeUnavailableReason = computed(() => {
  if (!voiceRecognitionSupported) return '当前浏览器不支持语音识别，请使用桌面版 Chrome 或 Edge。'
  if (agentCapabilities.value?.enabled !== true) return '当前部署未启用 Agent。'
  if (agentScopeType.value === 'CURRENT_DOCUMENT' && !canAnalyzeCurrent.value) return '当前音频完成转写后才能开始语音问答。'
  if (agentScopeType.value === 'SELECTED_DOCUMENTS' && !selectedTaskIds.value.length) return '请先勾选至少一份已收录资料。'
  if (agentScopeType.value === 'ALL_DOCUMENTS' && !indexedTaskCount.value) return '请先建立至少一份知识库文档。'
  return ''
})
const voiceSkillLabel = computed(() => {
  const skillId = activeConversation.value?.conversation.skillId || selectedSkillId.value
  if (!skillId || skillId === 'auto' || skillId === 'pending') return '自动匹配 Skill'
  return agentSkills.value.find(value => value.id === skillId)?.displayName || skillId
})
const voiceMemoryEnabled = computed(() => activeConversation.value?.conversation.memoryEnabled
  ?? (Boolean(agentCapabilities.value?.memoryEnabled) && conversationMemoryEnabled.value))
const shortSignedInAccount = computed(() => signedInAccount.value.length > 18 ? `${signedInAccount.value.slice(0, 15)}…` : signedInAccount.value || '账号')
const signedInInitial = computed(() => signedInAccount.value.trim().charAt(0).toUpperCase() || 'U')
const importElapsedMs = computed(() => importStartedAt.value == null ? 0 : Math.max(0, clockNow.value - importStartedAt.value))

function parseResultDocument(raw?: string) {
  if (!raw) return null
  try { return JSON.parse(raw) as AgentResultDocument }
  catch { return { answer: raw, findings: [] } }
}
function conversationTurnAnswer(raw?: string) {
  const result = parseResultDocument(raw)
  if (!result) return 'Agent 正在处理…'
  if (result.answer) return result.answer
  const first = result.blocks?.find(block => block.content || block.items?.length)
  return first?.content || first?.items?.[0]?.content || '已生成结构化结果'
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
function showSkills() { workspaceView.value = 'skills'; mobileAgentOpen.value = false }
function showTools() { workspaceView.value = 'tools'; mobileAgentOpen.value = false }
function showProfile() { workspaceView.value = 'profile'; mobileAgentOpen.value = false }
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
    signedInAccount.value = data.account || account.value
    localStorage.setItem('voicenote_token', token.value)
    localStorage.setItem('voicenote_account', signedInAccount.value)
    await loadWorkspace(); connectProgressEvents()
  } catch (error: any) { authError.value = error.response?.data?.message || '无法完成登录' }
}
async function loadWorkspace() {
  if (workspaceRequest) return workspaceRequest
  workspaceLoading.value = true
  workspaceRequest = Promise.all([loadTasks(), loadDocuments(), loadRuns(), loadAnalysisRuns(), loadAgentRuns(), loadAgentConversations(), loadPendingMemoryCount(), loadAgentSkills(), loadAgentCapabilities()])
    .then(() => { workspaceLoadError.value = '' })
    .catch((error) => {
      workspaceLoadError.value = isSessionExpiredError(error)
        ? ''
        : error.response?.data?.message || '后端服务暂时不可用，正在等待恢复连接。'
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
async function loadAgentConversations() {
  if (agentCapabilities.value && !agentCapabilities.value.memoryEnabled) { conversations.value = []; return }
  const { data } = await api.get<PageResult<AgentConversation>>('/agent-conversations', { params: { page: 0, size: 20 } })
  conversations.value = data.content
}
async function loadConversationDetail(conversationId: string) {
  const { data } = await api.get<AgentConversationDetail>(`/agent-conversations/${conversationId}`)
  activeConversation.value = data
  conversationMemoryEnabled.value = data.conversation.memoryEnabled
  return data
}
async function loadPendingMemoryCount() {
  if (!agentCapabilities.value?.memoryEnabled && agentCapabilities.value != null) { pendingMemoryCandidateCount.value = 0; return }
  const { data } = await api.get<unknown[]>('/user-memory-candidates', { params: { status: 'PENDING' } })
  pendingMemoryCandidateCount.value = data.length
}
async function loadAgentSkills() {
  const { data } = await api.get<AgentSkill[]>('/agent-runs/skills'); agentSkills.value = data
  if (selectedSkillId.value && !data.some(value => value.id === selectedSkillId.value)) {
    selectedSkillId.value = ''; skillSelectionNotice.value = '原 Skill 已归档或不可见，已恢复自动匹配。'
  }
}
async function refreshSkillCatalog() { try { await loadAgentSkills() } catch { /* Skill page keeps its own visible error state. */ } }
async function loadAgentCapabilities() {
  const { data } = await api.get<AgentCapabilities>('/agent-runs/capabilities')
  agentCapabilities.value = data
  if (!data.memoryEnabled) conversationMemoryEnabled.value = false
}
async function choose(task: Task) {
  agent.value = null
  activeConversation.value = null
  conversationActionError.value = ''
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
  aiSpeakerCorrection.value = null
  selectedAiSuggestionIds.value = []
  aiSpeakerCorrectionError.value = ''
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
    const [transcript, speakerResponse, organizedResponse, aiCorrectionResponse] = await Promise.all([
      api.get<Segment[]>(`/transcription-tasks/${task.id}/segments`),
      api.get<Speaker[]>(`/transcription-tasks/${task.id}/speakers`),
      task.organizedDocument?.status === 'READY' ? api.get<OrganizedDocumentDetail>(`/organized-documents/${task.organizedDocument.id}`) : Promise.resolve(null),
      api.get<AiSpeakerCorrectionDetail>(`/transcription-tasks/${task.id}/speaker-correction-runs/latest`)
    ])
    if (selected.value?.id !== task.id || requestVersion !== documentRequestVersion) return
    segments.value = transcript.data
    speakers.value = speakerResponse.data
    organized.value = organizedResponse?.data || null
    setAiSpeakerCorrection(aiCorrectionResponse.data || null)
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
function isScopeSelectable(task: Task) { return Boolean(task.qaCapabilities?.crossDocumentEligible) }
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
function setAiSpeakerCorrection(detail: AiSpeakerCorrectionDetail | null) {
  aiSpeakerCorrection.value = detail
  selectedAiSuggestionIds.value = detail?.run.status === 'READY'
    ? detail.suggestions.filter(suggestion => suggestion.defaultSelected && !suggestion.applied).map(suggestion => suggestion.id)
    : []
}
async function loadAiSpeakerCorrection(taskId: string, runId?: string) {
  const url = runId ? `/speaker-correction-runs/${runId}` : `/transcription-tasks/${taskId}/speaker-correction-runs/latest`
  const { data } = await api.get<AiSpeakerCorrectionDetail>(url)
  if (selected.value?.id === taskId) setAiSpeakerCorrection(data || null)
}
async function startAiSpeakerCorrection() {
  const task = selected.value
  if (!task || startingAiSpeakerCorrection.value || aiSpeakerCorrectionRunning.value) return
  startingAiSpeakerCorrection.value = true; aiSpeakerCorrectionError.value = ''
  try {
    const { data } = await api.post<AiSpeakerCorrectionDetail>(`/transcription-tasks/${task.id}/speaker-correction-runs`, {
      expectedRevision: task.speakerCorrectionRevision || 0
    }, { headers: { 'Idempotency-Key': key() } })
    if (selected.value?.id === task.id) setAiSpeakerCorrection(data)
  } catch (error: any) { aiSpeakerCorrectionError.value = error.response?.data?.message || 'AI 说话人分析启动失败。' }
  finally { startingAiSpeakerCorrection.value = false }
}
function toggleAiSuggestion(suggestionId: string) {
  selectedAiSuggestionIds.value = selectedAiSuggestionIds.value.includes(suggestionId)
    ? selectedAiSuggestionIds.value.filter(id => id !== suggestionId)
    : [...selectedAiSuggestionIds.value, suggestionId]
}
function selectHighConfidenceAiSuggestions() {
  selectedAiSuggestionIds.value = aiSpeakerCorrection.value?.suggestions.filter(value => value.confidence >= .8 && !value.applied).map(value => value.id) || []
}
function proposalParts(suggestion: AiSpeakerCorrectionSuggestion): SpeakerCorrectionProposalPart[] {
  if (suggestion.type !== 'SPLIT') return []
  try { return JSON.parse(suggestion.proposalDocument) as SpeakerCorrectionProposalPart[] }
  catch { return [] }
}
async function applyAiSpeakerCorrections() {
  const task = selected.value; const detail = aiSpeakerCorrection.value
  if (!task || !detail || !selectedAiSuggestionIds.value.length || applyingAiSpeakerCorrection.value || speakerCorrectionBlocked.value) return
  const invalidatesDerived = task.organizedDocument?.status === 'READY' || task.knowledgeDocument?.status === 'READY'
  if (invalidatesDerived && !window.confirm('应用 AI 说话人校正后，现有正式文档、摘要和知识索引需要重新生成。继续吗？')) return
  applyingAiSpeakerCorrection.value = true; aiSpeakerCorrectionError.value = ''
  try {
    const { data } = await api.post<AiSpeakerCorrectionApplyResult>(`/speaker-correction-runs/${detail.run.id}/apply`, {
      suggestionIds: selectedAiSuggestionIds.value, expectedRevision: task.speakerCorrectionRevision || 0
    }, { headers: { 'Idempotency-Key': key() } })
    upsertTask(data.task); organized.value = null
    const summaries = { ...summaryByTaskId.value }; delete summaries[task.id]; summaryByTaskId.value = summaries
    await Promise.all([refreshTranscript(task.id), loadDocuments(), loadAnalysisRuns(), loadAiSpeakerCorrection(task.id, detail.run.id)])
    speakerCorrectionMessage.value = `AI 已改派 ${data.relabeledSegmentCount} 句、拆分 ${data.splitSegmentCount} 句。${data.task.organizedDocument?.status === 'STALE' ? '请重新生成正式文档和知识库。' : ''}`
  } catch (error: any) {
    aiSpeakerCorrectionError.value = error.response?.data?.message || 'AI 说话人校正应用失败。'
    if (error.response?.data?.code === 'SPEAKER_REVISION_CONFLICT') {
      const { data } = await api.get<Task>(`/transcription-tasks/${task.id}`); upsertTask(data)
      await Promise.all([refreshTranscript(task.id), loadAiSpeakerCorrection(task.id)])
    }
  } finally { applyingAiSpeakerCorrection.value = false }
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
    await loadAiSpeakerCorrection(task.id)
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
function stepLabel(type: string, toolName?: string) {
  if (type === 'ROUTE') return '选择任务 Skill'
  if (type === 'MODEL') return 'Agent 决策'
  if (type === 'FINALIZE') return '校验证据并提交答案'
  if (type === 'RECOVERY') return '从 Checkpoint 恢复'
  return ({ document_list: '筛选文档范围', document_overview: '读取文档概览', knowledge_search: '混合检索与重排', transcript_context: '读取相邻原文' } as Record<string, string>)[toolName || ''] || toolName || '调用只读工具'
}
function resetTraceSelection() {
  activeStepId.value = null
  activeStepDetail.value = null
  stepDetailLoading.value = false
  traceActionError.value = ''
}
async function toggleStepDetail(step: AgentStep) {
  if (!activeRun.value || stepDetailLoading.value) return
  if (activeStepId.value === step.id) { activeStepId.value = null; activeStepDetail.value = null; return }
  activeStepId.value = step.id
  activeStepDetail.value = null
  stepDetailLoading.value = true
  traceActionError.value = ''
  try {
    const { data } = await api.get<AgentStepDetail>(`/agent-runs/${activeRun.value.id}/steps/${step.id}`)
    if (activeStepId.value === step.id) activeStepDetail.value = data
  } catch (error: any) {
    traceActionError.value = error.response?.data?.message || '无法读取该步详情。'
  } finally { stepDetailLoading.value = false }
}
function readableTraceValue(value: unknown) {
  if (value == null) return '无'
  return typeof value === 'string' ? value : JSON.stringify(value, null, 2)
}
async function replayFromCheckpoint(checkpointId: string) {
  const source = activeRun.value
  if (!source || replayingCheckpointId.value || !window.confirm('将从这个状态创建子 Run 并继续执行。原 Run 与 Trace 不会被修改，是否继续？')) return
  replayingCheckpointId.value = checkpointId
  traceActionError.value = ''
  try {
    const { data } = await api.post<AgentRun>(`/agent-runs/${source.id}/replays`, { checkpointId }, { headers: { 'Idempotency-Key': key() } })
    agent.value = { run: data, documentIds: [...(agent.value?.documentIds || [])], childRunIds: [], steps: [], checkpoints: [], evidence: [] }
    resetTraceSelection()
    upsertAgent(data)
  } catch (error: any) {
    traceActionError.value = error.response?.data?.message || '无法从该 Checkpoint 重新执行。'
  } finally { replayingCheckpointId.value = null }
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
async function submitAgentMessage(rawMessage: string) {
  const message = rawMessage.trim()
  if (!message) throw new Error('问题不能为空。')
  if (!agentCapabilities.value?.enabled) throw new Error('当前部署未启用 Agent。')
  if (agentScopeType.value === 'CURRENT_DOCUMENT' && !selected.value?.transcriptReady) throw new Error('当前音频仍在转写，完成后才能提问。')
  if (agentScopeType.value === 'SELECTED_DOCUMENTS' && !selectedTaskIds.value.length) throw new Error('请先勾选至少一份已收录资料。')
  if (agentScopeType.value === 'ALL_DOCUMENTS' && !indexedTaskCount.value) throw new Error('当前没有已入库资料。')
  asking.value = true
  conversationActionError.value = ''
  try {
    const transcriptionTaskIds = agentScopeType.value === 'CURRENT_DOCUMENT' ? [selected.value!.id]
      : agentScopeType.value === 'SELECTED_DOCUMENTS' ? selectedTaskIds.value : []
    let data: AgentRun
    let conversationId: string | undefined
    if (!agentCapabilities.value.memoryEnabled) {
      const response = await api.post<AgentRun>('/agent-runs', {
        question: message, scope: { type: agentScopeType.value, transcriptionTaskIds },
        skillId: selectedSkillId.value || null, timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone
      }, { headers: { 'Idempotency-Key': key() } })
      data = response.data
    } else {
      conversationId = activeConversation.value?.conversation.id
      if (!conversationId) {
        const { data: created } = await api.post<AgentConversation>('/agent-conversations', {
          scope: { type: agentScopeType.value, transcriptionTaskIds },
          skillId: selectedSkillId.value || null,
          timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
          memoryEnabled: conversationMemoryEnabled.value
        })
        conversationId = created.id
        activeConversation.value = { conversation: created, transcriptionTaskIds, turns: [] }
      }
      const response = await api.post<AgentRun>(`/agent-conversations/${conversationId}/turns`, {
        message
      }, { headers: { 'Idempotency-Key': key() } })
      data = response.data
    }
    agent.value = { run: data, documentIds: transcriptionTaskIds, childRunIds: [], steps: [], checkpoints: [], evidence: [] }
    resetTraceSelection()
    upsertAgent(data)
    if (conversationId) await Promise.all([loadConversationDetail(conversationId), loadAgentConversations()])
    return data
  } catch (error: any) {
    const failureMessage = error.response?.data?.message || '无法创建或继续 Agent 会话。'
    conversationActionError.value = failureMessage
    agent.value = { run: { id: '', question: message, status: 'FAILED', scopeType: agentScopeType.value, skillId: selectedSkillId.value || 'auto', skillVersion: '', scopeDocumentCount: 0, memoryEnabled: conversationMemoryEnabled.value,
      modelCallsUsed: 0, maxModelCalls: 0, agentTurnsUsed: 0, maxAgentTurns: 0, toolCallsUsed: 0, maxToolCalls: 0,
      failureMessage, recoveryCount: 0, createdAt: new Date().toISOString() }, documentIds: [], childRunIds: [], steps: [], checkpoints: [], evidence: [] }
    throw new Error(failureMessage)
  } finally { asking.value = false }
}
async function askAgent() {
  const message = question.value.trim()
  if (!message) return
  try {
    await submitAgentMessage(message)
    question.value = ''
  } catch { /* The shared submit path already exposes a user-facing error. */ }
}
async function submitVoiceAgentMessage(message: string) {
  voiceLiveRunId.value = ''
  voiceLiveProgress.value = []
  voiceLiveBlocks.value = []
  voiceAwaitingRun.value = true
  try {
    const run = await submitAgentMessage(message)
    voiceLiveRunId.value = run.id
  } finally { voiceAwaitingRun.value = false }
}
function openVoiceConversation() {
  if (!voiceModeAvailable.value) {
    conversationActionError.value = voiceModeUnavailableReason.value
    return
  }
  mobileAgentOpen.value = false
  voiceLiveRunId.value = activeRun.value?.id || ''
  voiceLiveProgress.value = []
  voiceLiveBlocks.value = []
  voiceAwaitingRun.value = false
  voiceConversationOpen.value = true
}
function closeVoiceConversation() {
  voiceConversationOpen.value = false
  voiceLiveRunId.value = ''
  voiceLiveProgress.value = []
  voiceLiveBlocks.value = []
  voiceAwaitingRun.value = false
}
async function openVoiceEvidence(citation: ResultCitation) {
  closeVoiceConversation()
  await nextTick()
  await openEvidence(citation)
}
async function loadAgentDetail(runId: string) {
  const { data } = await api.get<AgentRunDetail>(`/agent-runs/${runId}`)
  agent.value = data; resetTraceSelection(); upsertAgent(data.run)
  if (data.run.conversationId && activeConversation.value?.conversation.id !== data.run.conversationId) {
    await loadConversationDetail(data.run.conversationId)
  }
  return data
}
function startNewConversation() {
  activeConversation.value = null
  agent.value = null
  conversationActionError.value = ''
  selectedSkillId.value = ''
  skillSelectionNotice.value = ''
  conversationMemoryEnabled.value = Boolean(agentCapabilities.value?.memoryEnabled)
  question.value = ''
}
async function openConversation(conversation: AgentConversation) {
  conversationActionError.value = ''
  try {
    const detail = await loadConversationDetail(conversation.id)
    if (conversation.scopeType === 'CURRENT_DOCUMENT') {
      const task = tasks.value.find(value => value.id === detail.transcriptionTaskIds[0])
      if (task && (task.id !== selected.value?.id || !isDocumentView.value)) await choose(task)
    } else {
      showLibrary()
      libraryScope.value = conversation.scopeType === 'SELECTED_DOCUMENTS' ? 'selected' : 'all'
      selectedTaskIds.value = conversation.scopeType === 'SELECTED_DOCUMENTS' ? [...detail.transcriptionTaskIds] : selectedTaskIds.value
    }
    activeConversation.value = detail
    conversationMemoryEnabled.value = detail.conversation.memoryEnabled
    selectedSkillId.value = detail.conversation.skillId === 'auto' ? '' : detail.conversation.skillId
    const latestRun = [...detail.turns].reverse().find(turn => turn.runId)
    if (latestRun?.runId) await loadAgentDetail(latestRun.runId)
    else agent.value = null
  } catch (error: any) {
    conversationActionError.value = error.response?.data?.message || '无法读取该会话。'
  }
}
async function openConversationTurn(runId?: string) {
  if (!runId) return
  try { await loadAgentDetail(runId) }
  catch (error: any) { conversationActionError.value = error.response?.data?.message || '无法读取这轮结果。' }
}
async function toggleConversationMemory() {
  if (!activeConversation.value) return
  const previous = activeConversation.value.conversation.memoryEnabled
  try {
    const { data } = await api.patch<AgentConversation>(`/agent-conversations/${activeConversation.value.conversation.id}`, {
      memoryEnabled: conversationMemoryEnabled.value
    })
    activeConversation.value = { ...activeConversation.value, conversation: data }
    conversations.value = conversations.value.map(value => value.id === data.id ? data : value)
  } catch (error: any) {
    conversationMemoryEnabled.value = previous
    conversationActionError.value = error.response?.data?.message || '长期记忆开关更新失败。'
  }
}
async function deleteActiveConversation() {
  const current = activeConversation.value?.conversation
  if (!current || !window.confirm('删除会话会移除其中的消息、运行轨迹和引用；已确认的长期记忆会保留。确定删除吗？')) return
  try {
    await api.delete(`/agent-conversations/${current.id}`)
    startNewConversation()
    await Promise.all([loadAgentConversations(), loadAgentRuns()])
  } catch (error: any) {
    conversationActionError.value = error.response?.data?.message || '会话删除失败。'
  }
}
async function openLineageRun(runId: string) {
  try { await loadAgentDetail(runId) }
  catch (error: any) { traceActionError.value = error.response?.data?.message || '无法读取关联 Run。' }
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
  if (run.scopeType !== 'CURRENT_DOCUMENT') {
    showLibrary()
    if (!run.conversationId) activeConversation.value = null
    await loadAgentDetail(run.id)
    return
  }
  const detail = await loadAgentDetail(run.id)
  const conversation = activeConversation.value
  const task = tasks.value.find(value => value.id === detail.documentIds[0])
  if (task) await choose(task)
  agent.value = detail
  activeConversation.value = conversation
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
  if (evidence?.sourceKind === 'USER_MEMORY') { window.alert(`来自你确认的记忆：${evidence.text || '该记忆已不可用'}`); return }
  if (!evidence?.segmentId) return
  const detail = agent.value
  const conversation = activeConversation.value
  await openEvidenceForTask({ segmentId: evidence.segmentId }, evidence.transcriptionTaskId)
  agent.value = detail
  activeConversation.value = conversation
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
  selectedTaskIds.value = selectedTaskIds.value.filter(id => snapshot.tasks.some(task => task.id === id && task.qaCapabilities?.crossDocumentEligible))
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
  if (name === 'agent-run-progress') {
    const event = payload as AgentProgressEvent
    if (!voiceConversationOpen.value || !event.runId) return
    const expectedRunId = voiceLiveRunId.value || (voiceAwaitingRun.value ? undefined : activeRun.value?.id)
    if (expectedRunId && event.runId !== expectedRunId) return
    if (!expectedRunId && !asking.value) return
    voiceLiveRunId.value = event.runId
    if (!voiceLiveProgress.value.some(value => value.sequence === event.sequence)) voiceLiveProgress.value = [...voiceLiveProgress.value, event]
    return
  }
  if (name === 'agent-answer-block') {
    const event = payload as AgentAnswerBlockEvent
    if (!voiceConversationOpen.value || !event.runId) return
    const expectedRunId = voiceLiveRunId.value || (voiceAwaitingRun.value ? undefined : activeRun.value?.id)
    if (expectedRunId && event.runId !== expectedRunId) return
    if (!expectedRunId && !asking.value) return
    voiceLiveRunId.value = event.runId
    if (!voiceLiveBlocks.value.some(value => value.sequence === event.sequence)) {
      voiceLiveBlocks.value = [...voiceLiveBlocks.value, event].sort((left, right) => left.blockIndex - right.blockIndex)
    }
    return
  }
  if (name === 'knowledge-run-settled' && payload.run) {
    if (agent.value?.run.id === payload.run.id) {
      void loadAgentDetail(payload.run.id).then(() => Promise.all([loadAgentConversations(), activeConversation.value ? loadConversationDetail(activeConversation.value.conversation.id) : Promise.resolve()])).catch(() => loadKnowledgeDetail(payload.run.id))
    } else void Promise.all([loadAgentRuns(), loadAgentConversations()])
    return
  }
  if (name === 'user-memory-changed') {
    void loadPendingMemoryCount()
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
    return
  }
  if (name === 'speaker-correction-run-settled' && payload.run) {
    const run = payload.run as { id: string; transcriptionTaskId: string }
    if (selected.value?.id === run.transcriptionTaskId) void loadAiSpeakerCorrection(run.transcriptionTaskId, run.id)
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
    if (response.status === 401 || response.status === 403) {
      handleSessionExpired()
      return
    }
    if (!response.ok || !response.body) throw new Error('Progress stream is unavailable')
    reconnectDelay = 1000
    if (workspaceLoadError.value || documentLoadError.value || (voiceConversationOpen.value && activeRun.value?.id)) void recoverAfterReconnect()
    const reader = response.body.getReader(); const decoder = new TextDecoder(); let buffer = ''
    while (true) {
      const { done, value } = await reader.read(); if (done) break
      buffer += decoder.decode(value, { stream: true })
      const frames = buffer.split(/\r?\n\r?\n/); buffer = frames.pop() || ''
      frames.forEach(parseSseFrame)
    }
  } catch (error) {
    if ((error as DOMException).name !== 'AbortError') {
      voiceLiveBlocks.value = []
      if (voiceConversationOpen.value && activeRun.value?.id) void loadAgentDetail(activeRun.value.id).catch(() => undefined)
      scheduleReconnect()
    }
  }
  finally { streamController = null; if (!streamClosed && token.value && !reconnectTimer) scheduleReconnect() }
}
async function recoverAfterReconnect() {
  try {
    if (voiceConversationOpen.value && activeRun.value?.id) await loadAgentDetail(activeRun.value.id)
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
  voiceConversationOpen.value = false
  if (audioUrl.value) URL.revokeObjectURL(audioUrl.value)
  token.value = ''
  localStorage.removeItem('voicenote_token')
  localStorage.removeItem('voicenote_account')
  signedInAccount.value = ''
  selected.value = null
  documents.value = []
  runs.value = []
  agentRuns.value = []
  agent.value = null
  conversations.value = []
  activeConversation.value = null
  pendingMemoryCandidateCount.value = 0
  conversationActionError.value = ''
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
  selectedSkillId.value = ''
  skillSelectionNotice.value = ''
}
function handleSessionExpired() {
  if (!token.value) return
  logout()
  loginMode.value = 'login'
  authError.value = '登录状态已过期，请重新登录。'
}
watch(speakerDiarization, enabled => { if (!enabled) speakerCount.value = null })
watch(selectedSkillIssue, issue => {
  if (selectedSkillId.value && issue) {
    selectedSkillId.value = ''
    skillSelectionNotice.value = `所选 Skill ${issue}，已恢复自动匹配。`
  }
})
onMounted(() => {
  window.addEventListener(SESSION_EXPIRED_EVENT, handleSessionExpired)
  clockTimer = window.setInterval(() => { clockNow.value = Date.now() }, 1000)
  if (token.value) { void loadWorkspace().catch(() => {}); void connectProgressEvents() }
})
onBeforeUnmount(() => {
  window.removeEventListener(SESSION_EXPIRED_EVENT, handleSessionExpired)
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

  <main v-else class="app-shell" :class="{ 'utility-view': isUtilityView }">
    <header class="topbar">
      <button class="brand" type="button" @click="showLibrary" aria-label="返回音频资料库">voice<span>note</span></button>
      <p>音频听记与私人知识库</p>
      <div class="topbar-meta">
        <button class="skill-nav" :class="{ active: isSkillsView }" type="button" @click="showSkills"><i>✦</i><span>Skill 设置</span></button>
        <button class="skill-nav" :class="{ active: isToolsView }" type="button" @click="showTools"><i>⌘</i><span>Tools 中心</span></button>
        <button class="account-nav" :class="{ active: isProfileView }" type="button" @click="showProfile"><span class="account-avatar">{{ signedInInitial }}</span><span class="account-name">{{ shortSignedInAccount }}</span><em v-if="pendingMemoryCandidateCount" class="memory-count">{{ pendingMemoryCandidateCount > 99 ? '99+' : pendingMemoryCandidateCount }}</em></button>
      </div>
      <button v-if="!isUtilityView" class="mobile-agent-toggle" type="button" :aria-expanded="mobileAgentOpen" @click="toggleAgent">{{ mobileAgentOpen ? '关闭问答' : 'AI 问答' }}</button>
    </header>

    <section class="content-pane">
      <SkillManager v-if="workspaceView === 'skills'" @catalog-changed="refreshSkillCatalog" />
      <ToolsCenter v-else-if="workspaceView === 'tools'" :skills="agentSkills" :mcp-enabled="agentCapabilities?.mcpEnabled === true" />
      <ProfilePage v-else-if="workspaceView === 'profile'" :account="signedInAccount" @logout="logout" />
      <section v-else-if="workspaceView === 'library'" class="library-page page-reveal">
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
            <label class="record-select" :class="{ unavailable: !isScopeSelectable(task) }" :title="isScopeSelectable(task) ? '加入多文档问答范围' : '建立知识库后可参与跨文档检索'" @click.stop>
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
            <div v-if="segments.length" class="speaker-correction-guide" :class="{ editing: speakerEditMode }"><span><b>{{ speakerEditMode ? '正在修改说话人' : '说话人校对' }}</b><small>{{ speakerEditMode ? '点击整条句子即可选择，按住 Shift 可连续选择。' : '可让 AI 从语义分析整份原文，也可人工修改局部标注。' }}</small></span><div class="speaker-correction-guide-actions"><em>修订 {{ selected?.speakerCorrectionRevision || 0 }}</em><button v-if="!speakerEditMode" type="button" class="ai-correction-button" :disabled="startingAiSpeakerCorrection || aiSpeakerCorrectionRunning" @click="startAiSpeakerCorrection">{{ startingAiSpeakerCorrection || aiSpeakerCorrectionRunning ? 'AI 分析中…' : aiSpeakerCorrection && ['READY', 'APPLIED', 'FAILED', 'STALE'].includes(aiSpeakerCorrection.run.status) ? '重新 AI 分析' : 'AI 校正' }}</button><button v-if="!speakerEditMode" type="button" :disabled="speakerCorrectionBlocked" @click="enterSpeakerEditMode">修改说话人</button><button v-else type="button" class="finish" :disabled="savingSpeakerCorrection" @click="finishSpeakerEditMode">完成</button></div></div>
            <section v-if="aiSpeakerCorrection" class="ai-correction-panel" :class="aiSpeakerCorrection.run.status.toLowerCase()" aria-label="AI 说话人校正建议">
              <header><span><b>AI 语义校正</b><small>{{ aiSpeakerCorrection.run.modelId }} · {{ aiSpeakerCorrection.run.templateVersion }}</small></span><em>{{ ({ QUEUED: '等待分析', RUNNING: '正在分析', READY: '等待确认', APPLIED: '已应用', FAILED: '分析失败', STALE: '结果已过期' } as Record<string, string>)[aiSpeakerCorrection.run.status] }}</em></header>
              <p v-if="aiSpeakerCorrectionRunning" class="ai-correction-state">AI 正在对照相邻发言和说话人语义，完成后会在这里显示建议；期间仍可使用人工校正。</p>
              <p v-else-if="aiSpeakerCorrection.run.status === 'FAILED'" class="ai-correction-state error">{{ aiSpeakerCorrection.run.failureMessage || 'AI 分析失败，请重新尝试。' }}</p>
              <p v-else-if="aiSpeakerCorrection.run.status === 'STALE'" class="ai-correction-state">说话人标注已在分析后发生变化，这批建议不能再应用，请重新分析。</p>
              <p v-else-if="aiSpeakerCorrection.run.status === 'APPLIED'" class="ai-correction-state success">所选建议已经应用。你仍可以继续人工校正或重新运行 AI 分析。</p>
              <p v-else-if="aiSpeakerCorrection.run.status === 'READY' && !aiSpeakerCorrection.suggestions.length" class="ai-correction-state success">AI 没有发现足够确定的说话人标注问题。</p>
              <template v-else-if="aiSpeakerCorrection.run.status === 'READY'">
                <div class="ai-correction-summary"><span>发现 {{ aiSpeakerCorrection.suggestions.length }} 条建议<small v-if="aiSpeakerCorrection.run.rejectedCount"> · 已过滤 {{ aiSpeakerCorrection.run.rejectedCount }} 条不安全输出</small></span><button type="button" @click="selectHighConfidenceAiSuggestions">只选高置信</button></div>
                <article v-for="suggestion in aiSpeakerCorrection.suggestions" :key="suggestion.id" class="ai-suggestion" :class="{ selected: selectedAiSuggestionIds.includes(suggestion.id) }">
                  <label><input type="checkbox" :checked="selectedAiSuggestionIds.includes(suggestion.id)" @change="toggleAiSuggestion(suggestion.id)"><span><b>{{ suggestion.type === 'SPLIT' ? '建议拆分说话人' : '建议改派说话人' }}</b><small>置信度 {{ Math.round(suggestion.confidence * 100) }}% · {{ suggestion.reason }}</small></span></label>
                  <div class="ai-suggestion-original"><em>原标注</em><b :style="{ color: speakerColor(suggestion.originalSpeakerId) }">{{ speakerName(suggestion.originalSpeakerId) }}</b><time>{{ timecode(suggestion.originalStartMs) }}</time><p>{{ suggestion.originalText }}</p></div>
                  <div v-if="suggestion.type === 'RELABEL'" class="ai-suggestion-proposal"><em>建议</em><b :style="{ color: speakerColor(suggestion.targetSpeakerId) }">{{ speakerName(suggestion.targetSpeakerId) }}</b><p>{{ suggestion.originalText }}</p></div>
                  <div v-else class="ai-split-proposal"><article v-for="(part, partIndex) in proposalParts(suggestion)" :key="partIndex"><span><b :style="{ color: speakerColor(part.speakerId) }">{{ speakerName(part.speakerId) }}</b><time>{{ timecode(part.startMs) }}</time><small v-if="part.timingSource === 'PROPORTIONAL'">估算时间</small></span><p>{{ part.text }}</p></article></div>
                </article>
                <footer><span>已选 {{ selectedAiSuggestionCount }} 条；低置信建议默认不选。</span><button type="button" :disabled="!selectedAiSuggestionCount || applyingAiSpeakerCorrection || speakerCorrectionBlocked" @click="applyAiSpeakerCorrections">{{ applyingAiSpeakerCorrection ? '正在应用…' : '应用所选' }}</button></footer>
              </template>
            </section>
            <p v-if="selected?.organizedDocument?.status === 'STALE'" class="speaker-correction-notice">说话人标注已经修改。旧正式文档、摘要和知识索引已停用，请重新生成正式文档。</p>
            <div v-if="speakerEditMode" class="speaker-correction-toolbar" role="region" aria-label="批量修改所选句子的说话人"><b>{{ selectedSegmentIds.length ? `已选 ${selectedSegmentIds.length} 句` : '点击下方句子进行选择' }}</b><select v-model="speakerCorrectionTarget" :disabled="!selectedSegmentIds.length || savingSpeakerCorrection || speakerCorrectionBlocked"><option value="">选择正确的说话人</option><option v-for="speaker in speakers" :key="speaker.speakerId" :value="speaker.speakerId">{{ speaker.displayName || speaker.speakerId }}</option></select><button type="button" :disabled="!speakerCorrectionTarget || !selectedSegmentIds.length || savingSpeakerCorrection || speakerCorrectionBlocked" @click="applySelectedSpeakerCorrection">{{ savingSpeakerCorrection ? '保存中…' : '应用修改' }}</button><button type="button" class="speaker-reset" :disabled="!selectedSegmentIds.length || savingSpeakerCorrection || speakerCorrectionBlocked" @click="resetSelectedSpeakerCorrections">重置所选</button><button type="button" class="text-action" :disabled="!selectedSegmentIds.length || savingSpeakerCorrection" @click="clearSegmentSelection">清除选择</button></div>
            <p v-if="speakerCorrectionBlocked" class="speaker-correction-notice">正式文档、摘要或知识索引正在处理，完成后才能应用说话人修改。</p>
            <p v-if="speakerCorrectionMessage" class="speaker-correction-feedback success" role="status">{{ speakerCorrectionMessage }}</p>
            <p v-if="speakerCorrectionError" class="speaker-correction-feedback error" role="alert">{{ speakerCorrectionError }}</p>
            <p v-if="aiSpeakerCorrectionError" class="speaker-correction-feedback error" role="alert">{{ aiSpeakerCorrectionError }}</p>
            <article v-for="segment in segments" :key="segment.id" :id="`segment-${segment.id}`" class="segment" :class="{ editing: speakerEditMode, selected: selectedSegmentIds.includes(segment.id), corrected: segment.correctionSource !== 'ASR' || segment.parentSegmentId }" :role="speakerEditMode ? 'option' : 'button'" :aria-selected="speakerEditMode ? selectedSegmentIds.includes(segment.id) : undefined" tabindex="0" @click="handleSegmentClick(segment, $event)" @keydown="handleSegmentKeydown(segment, $event)">
              <div class="segment-marker"><span v-if="speakerEditMode" class="selection-indicator" aria-hidden="true">{{ selectedSegmentIds.includes(segment.id) ? '✓' : '' }}</span><time>{{ timecode(segment.startMs) }}</time></div>
              <div class="segment-copy"><b :style="{ color: speakerColor(segment.speakerId) }">{{ segment.speaker || '说话人' }}</b><small v-if="segment.correctionSource === 'HUMAN'" class="corrected-badge">已人工修正 · 原标注 {{ speakerName(segment.asrSpeakerId) }}</small><small v-else-if="segment.correctionSource === 'AI'" class="corrected-badge ai">{{ segment.parentSegmentId ? 'AI 拆分' : 'AI 修正' }} · 原标注 {{ speakerName(segment.asrSpeakerId) }}</small><small v-else-if="segment.parentSegmentId" class="corrected-badge">拆分片段 · 已恢复原标注</small><small v-if="segment.timingSource === 'PROPORTIONAL'" class="corrected-badge estimated">估算时间</small><p>{{ segment.text }}</p></div>
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
            <template v-if="organized?.document.status === 'READY'"><article v-for="topic in organizedTopics" :key="topic.id" class="organized-block"><button class="topic-link" @click="seekToTime(topic.startMs, true)">{{ topic.topic || '整理片段' }} <span>{{ timecode(topic.startMs) }}</span></button><article v-for="item in topicChildren(topic.id)" :key="item.id" class="organized-unit"><small>{{ item.type === 'QA_PAIR' ? '问答' : '叙述' }}</small><p>{{ item.text }}</p></article></article></template>
            <div v-else-if="documentLoading" class="content-empty"><span aria-hidden="true">◎</span><b>正在读取正式文档…</b><p>正在加载整理后的主题和内容。</p></div>
            <div v-else-if="documentLoadError" class="content-empty"><span aria-hidden="true">!</span><b>正式文档读取失败。</b><p>{{ documentLoadError }}</p><button class="stage-retry" type="button" @click="retrySelectedDocument">立即重试</button></div>
            <div v-else class="content-empty"><span aria-hidden="true">◎</span><b>正式文档尚未准备好。</b><p>请在原始文档完成后手动生成清洗、整理后的正式文档。</p></div>
          </section>
        </template>
      </section>
    </section>

    <aside v-if="!isUtilityView" class="agent-rail" :class="{ 'is-open': mobileAgentOpen }">
      <header class="agent-head"><div><p class="eyebrow">AI KNOWLEDGE</p><h3>{{ activeConversation?.conversation.title || agentTitle }}</h3></div><div class="agent-head-actions"><button class="voice-conversation-launch" type="button" :disabled="!voiceModeAvailable" :title="voiceModeUnavailableReason || '进入连续语音 Agent 会话'" @click="openVoiceConversation"><i aria-hidden="true"></i>{{ voiceRecognitionSupported ? '语音对话' : '不支持语音' }}</button><button class="new-conversation" type="button" @click="startNewConversation">新会话</button><button class="agent-close" type="button" @click="mobileAgentOpen = false" aria-label="关闭 AI 问答">×</button></div></header>
      <div class="agent-mode-row"><span aria-hidden="true"></span><b>{{ agentModeLabel }}</b></div>
      <p class="agent-description">{{ agentDescription }}</p>
      <div class="agent-suggestions"><button v-for="suggestion in agentSuggestions" :key="suggestion" type="button" @click="useSuggestion(suggestion)">{{ suggestion }} <span>↗</span></button></div>
      <div v-if="!isDocumentView" class="scope-switch" aria-label="问答范围"><button :class="{ active: libraryScope === 'all' }" :disabled="conversationLocked" @click="libraryScope = 'all'">全部已入库</button><button :class="{ active: libraryScope === 'selected' }" :disabled="conversationLocked" @click="libraryScope = 'selected'">已勾选 · {{ selectedTaskIds.length }}</button></div>
      <label class="skill-select">任务方式<select v-model="selectedSkillId" :disabled="conversationLocked" @change="skillSelectionNotice = ''"><option value="">自动匹配 Skill（推荐）</option><optgroup label="内置 Skill"><option v-for="skill in builtInAgentSkills" :key="skill.id" :value="skill.id" :disabled="Boolean(skillCompatibilityIssue(skill))">{{ skill.displayName }} · 内置{{ skillCompatibilityIssue(skill) ? `（${skillCompatibilityIssue(skill)}）` : '' }}</option></optgroup><optgroup v-if="customAgentSkills.length" label="我的 Skill"><option v-for="skill in customAgentSkills" :key="skill.id" :value="skill.id" :disabled="Boolean(skillCompatibilityIssue(skill))">{{ skill.displayName }} · 我的{{ skillCompatibilityIssue(skill) ? `（${skillCompatibilityIssue(skill)}）` : '' }}</option></optgroup></select></label>
      <p v-if="skillSelectionNotice" class="skill-selection-notice">{{ skillSelectionNotice }}</p>
      <label class="memory-toggle"><input v-model="conversationMemoryEnabled" type="checkbox" :disabled="!agentCapabilities?.memoryEnabled" @change="toggleConversationMemory"><span><b>使用并学习长期记忆</b><small>{{ agentCapabilities?.memoryEnabled ? '仅使用你确认过的记忆' : '当前部署未启用' }}</small></span></label>
      <p v-if="conversationLocked" class="conversation-lock-note">本会话的资料范围、时区和 Skill 已锁定；切换资料请新建会话。</p>
      <p v-if="conversationActionError" class="agent-note">{{ conversationActionError }}</p>
      <div class="ask-box"><textarea v-model="question" rows="4" :disabled="agentCapabilities?.enabled === false" :placeholder="agentPlaceholder"></textarea><div><span>{{ agentScopeType === 'CURRENT_DOCUMENT' ? '当前音频' : agentScopeType === 'SELECTED_DOCUMENTS' ? `${selectedTaskIds.length} 份已勾选` : `${indexedTaskCount} 份已入库` }}</span><button class="send-button" :disabled="agentCapabilities?.enabled !== true || asking || !question.trim() || (agentScopeType === 'CURRENT_DOCUMENT' && !canAnalyzeCurrent) || (agentScopeType === 'SELECTED_DOCUMENTS' && !selectedTaskIds.length) || (agentScopeType === 'ALL_DOCUMENTS' && !indexedTaskCount)" @click="askAgent">{{ asking ? '处理中' : '发送' }} <b>↑</b></button></div></div>
      <p v-if="agentCapabilities?.enabled === false" class="agent-note">自主 Agent 正在灰度中，请在部署环境启用 VOICENOTE_AGENT_ENABLED。</p>
      <p v-else-if="agentScopeType === 'CURRENT_DOCUMENT' && !canAnalyzeCurrent" class="agent-note">当前音频仍在转写，完成后即可提问。</p>
      <p v-else-if="agentScopeType === 'SELECTED_DOCUMENTS' && !selectedTaskIds.length" class="agent-note">请先在资料库勾选 1–50 份已收录文档。</p>
      <p v-else-if="agentScopeType === 'ALL_DOCUMENTS' && !indexedTaskCount" class="agent-note">当前没有已入库资料。请先打开一份正式文档并建立知识库。</p>

      <section v-if="activeConversation?.turns.length" class="conversation-turns" aria-label="会话消息">
        <button v-for="turn in activeConversation.turns" :key="turn.id" type="button" :class="{ active: turn.runId === activeRun?.id }" @click="openConversationTurn(turn.runId)"><span><b>你 · 第 {{ turn.turnIndex + 1 }} 轮</b><small>{{ turn.userMessage }}</small></span><p>{{ turn.failureMessage || conversationTurnAnswer(turn.resultDocument) }}</p></button>
      </section>

      <section v-if="activeRun" class="result-card">
        <div class="result-head"><span>{{ activeSkillName }}</span><small>{{ statusText(activeRun.status) }} · {{ activeRunUsage }}</small></div>
        <nav v-if="activeRun.parentRunId || (agent?.childRunIds || []).length" class="run-lineage" aria-label="Run 演进关系">
          <span>LINEAGE</span>
          <button v-if="activeRun.rootRunId && activeRun.rootRunId !== activeRun.id && activeRun.rootRunId !== activeRun.parentRunId" type="button" @click="openLineageRun(activeRun.rootRunId)">根 Run ↖</button>
          <button v-if="activeRun.parentRunId" type="button" @click="openLineageRun(activeRun.parentRunId)">父 Run ↖</button>
          <button v-for="(childRunId, index) in agent?.childRunIds || []" :key="childRunId" type="button" @click="openLineageRun(childRunId)">子 Run {{ index + 1 }} ↗</button>
        </nav>
        <div v-if="activeRun.failureCode" class="trace-failure">
          <b>{{ activeRun.failureCode }}</b><span>{{ activeRun.failureStage || '未知阶段' }}</span><p>{{ activeRun.failureMessage }}</p>
        </div>
        <template v-if="parsedAnswer"><AgentResultBlocks :result="parsedAnswer" :evidence="activeEvidence" @evidence="openEvidence" />
          <div v-if="parsedAnswer.coverage" class="coverage-strip"><span>范围 {{ parsedAnswer.coverage.scopeDocumentCount }}</span><span>概览 {{ parsedAnswer.coverage.overviewedDocumentIds.length }}</span><span>深入 {{ parsedAnswer.coverage.searchedDocumentIds.length }}</span><span>引用 {{ parsedAnswer.coverage.citedDocumentIds.length }}</span><p v-if="parsedAnswer.coverage.limitations.length">限制：{{ parsedAnswer.coverage.limitations.join('；') }}</p></div>
        </template>
        <p v-else-if="activeRun.failureMessage && !activeRun.failureCode" class="error">{{ activeRun.failureMessage }}</p>
        <p v-else class="waiting">Agent 正在规划检索、读取原文并校验证据…</p>
        <details v-if="agent?.steps.length || initialReplayCheckpoint" class="agent-trace">
          <summary>运行轨迹 <b>{{ agent?.steps.length || 0 }} 步<template v-if="activeRun.recoveryCount"> · {{ activeRun.recoveryCount }} 次恢复</template></b></summary>
          <div v-if="initialReplayCheckpoint && activeRunTerminal" class="initial-replay"><span>初始状态 · Checkpoint #{{ initialReplayCheckpoint.sequence }}</span><button type="button" :disabled="Boolean(replayingCheckpointId)" @click="replayFromCheckpoint(initialReplayCheckpoint.id)">从此重新执行</button></div>
          <ol>
            <li v-for="step in agent?.steps || []" :key="step.id" :class="[step.status.toLowerCase(), { expanded: activeStepId === step.id }]">
              <i></i>
              <button class="trace-step-open" type="button" :aria-expanded="activeStepId === step.id" @click="toggleStepDetail(step)">
                <span><b>{{ stepLabel(step.type, step.toolName) }}</b><small>{{ step.summary || step.errorMessage || statusText(step.status) }}</small></span>
                <span class="trace-metrics"><time>{{ step.durationMs == null ? '—' : formatDuration(step.durationMs) }}</time><em v-if="step.totalTokens != null">{{ step.totalTokens }} tok</em><em v-if="activeRun.recoveryCount">E{{ step.executionEpoch }}</em></span>
              </button>
              <button v-if="activeRunTerminal && step.replayable && step.checkpointId" class="trace-replay" type="button" :disabled="Boolean(replayingCheckpointId)" @click.stop="replayFromCheckpoint(step.checkpointId)">{{ replayingCheckpointId === step.checkpointId ? '创建中…' : '从此重新执行' }}</button>
              <div v-if="activeStepId === step.id" class="trace-detail">
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
          <p v-if="traceActionError" class="trace-action-error">{{ traceActionError }}</p>
        </details>
      </section>

      <section v-if="conversations.length" class="run-history conversation-history"><p class="eyebrow">RECENT CONVERSATIONS</p><button v-for="conversation in conversations.slice(0, 8)" :key="conversation.id" :class="{ active: conversation.id === activeConversation?.conversation.id }" @click="openConversation(conversation)"><span>{{ conversation.title }}</span><small>{{ conversation.status === 'ARCHIVED' ? '已归档' : formatCreatedAt(conversation.updatedAt) }}</small></button><button v-if="activeConversation" class="delete-conversation" type="button" @click="deleteActiveConversation">删除当前会话</button></section>
      <section v-if="visibleHistory.some(run => !run.conversationId)" class="run-history"><p class="eyebrow">LEGACY RUNS</p><button v-for="run in visibleHistory.filter(run => !run.conversationId).slice(0, 4)" :key="run.id" @click="openHistory(run)"><span>{{ run.question }}</span><small>{{ statusText(run.status) }}</small></button></section>
    </aside>

    <VoiceConversationOverlay v-if="voiceConversationOpen"
      :busy="voiceAgentBusy" :run-status="activeRun?.status" :run-question="activeRun?.question" :run-failure="activeRun?.failureMessage"
      :result="parsedAnswer" :evidence="activeEvidence" :scope-label="agentModeLabel" :skill-label="voiceSkillLabel"
      :memory-enabled="voiceMemoryEnabled" :submit-message="submitVoiceAgentMessage"
      :live-progress="voiceLiveProgress" :live-blocks="voiceLiveBlocks" :tts-enabled="agentCapabilities?.ttsEnabled === true"
      @close="closeVoiceConversation" @evidence="openVoiceEvidence" />
  </main>
</template>
