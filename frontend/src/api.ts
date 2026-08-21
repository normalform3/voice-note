import axios from 'axios'

export const api = axios.create({ baseURL: '/api' })
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('voicenote_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

export type PipelineStage = 'UPLOAD_COMPLETED' | 'ASR_SUBMIT' | 'ASR_POLL' | 'TRANSCRIPT_PERSIST' | 'RAW_DOCUMENT_READY' | 'DOCUMENT_ORGANIZATION' | 'FORMAL_DOCUMENT_READY' | 'KNOWLEDGE_PREPARE' | 'KNOWLEDGE_INDEX' | 'COMPLETED'
export type PipelinePhase = 'TRANSCRIPTION' | 'RAW_DOCUMENT_REVIEW' | 'DOCUMENT_ORGANIZATION' | 'FORMAL_DOCUMENT_REVIEW' | 'KNOWLEDGE_BUILD' | 'COMPLETED'
export type StageAttempt = { stage: PipelineStage; status: string; attemptNumber: number; queuedAt: string; startedAt?: string; completedAt?: string; waitDurationMs?: number; totalWaitDurationMs: number; nextRetryAt?: string; errorCode?: string; errorMessage?: string; modelId?: string }
export type KnowledgeIndexStage = { stage: 'INGEST' | 'CHUNK' | 'INDEX'; status: string; attemptNumber?: number; progressPercent: number; completedCount: number; totalCount: number; errorMessage?: string }
export type KnowledgeIndexBuild = { id: string; generation?: number; status: string; currentStage?: 'INGEST' | 'CHUNK' | 'INDEX'; progressPercent: number; topicCount: number; chunkCount: number; indexedChunkCount: number; failureMessage?: string; active?: boolean; stages: KnowledgeIndexStage[] }
export type KnowledgeDocument = { id: string; transcriptionTaskId: string; title: string; status: string; failureMessage?: string; updatedAt: string; currentBuild?: KnowledgeIndexBuild }
export type QaRetrievalMode = 'TRANSCRIPT_LOCAL' | 'FORMAL_OVERVIEW' | 'HYBRID_INDEX'
export type QaCapabilities = { currentDocumentAvailable: boolean; currentMode?: QaRetrievalMode; crossDocumentEligible: boolean; limitationCode?: string }
export type Task = {
  id: string; audioBlobId: string; status: string; currentPhase?: PipelinePhase; currentStage?: PipelineStage; progressPercent?: number; transcriptReady?: boolean
  currentAttemptNumber: number; transcriptVersion: number; speakerCorrectionRevision: number; failureCode?: string; failureMessage?: string; failedStage?: PipelineStage
  createdAt: string; durationMs?: number
  occurredAt: string; sceneType: 'INTERVIEW' | 'MEETING' | 'OTHER'; subject?: string; tags: string[]
  retryableStages?: PipelineStage[]; stages?: StageAttempt[]; qaCapabilities?: QaCapabilities; knowledgeDocument?: { id: string; title: string; status: string; failureMessage?: string; currentBuild?: KnowledgeIndexBuild }; organizedDocument?: { id: string; title: string; status: string; failureMessage?: string }
}
export type Segment = { id: string; index: number; speakerId?: string; asrSpeakerId?: string; correctedSpeakerId?: string; speakerCorrected: boolean; correctionSource: 'ASR' | 'AI' | 'HUMAN'; timingSource: 'ASR' | 'WORD_ALIGNED' | 'PROPORTIONAL'; rootSegmentId: string; parentSegmentId?: string; speaker?: string; role?: string; startMs: number; endMs: number; text: string }
export type SpeakerCorrectionResult = { changedSegmentCount: number; revision: number; task: Task }
export type SpeakerCorrectionProposalPart = { startOffset: number; endOffset: number; speakerId: string; text: string; startMs: number; endMs: number; timingSource: 'WORD_ALIGNED' | 'PROPORTIONAL' }
export type AiSpeakerCorrectionSuggestion = { id: string; index: number; sourceSegmentId: string; type: 'RELABEL' | 'SPLIT'; originalSpeakerId: string; originalStartMs: number; originalEndMs: number; originalText: string; targetSpeakerId?: string; proposalDocument: string; confidence: number; reason: string; defaultSelected: boolean; timingSource: 'ASR' | 'WORD_ALIGNED' | 'PROPORTIONAL'; applied: boolean }
export type AiSpeakerCorrectionRun = { id: string; transcriptionTaskId: string; transcriptVersion: number; baseRevision: number; templateVersion: string; modelId: string; status: 'QUEUED' | 'RUNNING' | 'READY' | 'APPLIED' | 'FAILED' | 'STALE'; suggestionCount: number; rejectedCount: number; failureCode?: string; failureMessage?: string; createdAt: string; completedAt?: string }
export type AiSpeakerCorrectionDetail = { run: AiSpeakerCorrectionRun; suggestions: AiSpeakerCorrectionSuggestion[] }
export type AiSpeakerCorrectionApplyResult = { relabeledSegmentCount: number; splitSegmentCount: number; revision: number; run: AiSpeakerCorrectionRun; task: Task }
export type Speaker = { speakerId: string; suggestedRole: string; suggestedConfidence?: number; confirmedRole?: string; resolvedRole: string; displayName?: string }
export type OrganizedBlock = { id: string; index: number; type: string; parentBlockId?: string; speaker?: string; speakerIds?: string; topic?: string; summary?: string; startMs: number; endMs: number; sourceSegmentIds: string; sourceFragments?: string; text: string }
export type OrganizedDocumentDetail = { document: { id: string; taskId: string; title: string; summary?: string; organizationMode?: string; status: string; structureDocument?: string; plainText?: string; failureMessage?: string }; blocks: OrganizedBlock[] }
export type KnowledgeRun = { id: string; status: string; toolCallsUsed: number; maxToolCalls: number; resultDocument?: string; failureMessage?: string }
export type KnowledgeEvidence = { resultPath: string; documentId: string; chunkId: string; transcriptionTaskId?: string; segmentId: string; topic?: string; speakerId?: string; role?: string; speaker?: string; startMs?: number; endMs?: number; text?: string }
export type KnowledgeRunDetail = { run: KnowledgeRun; evidence: KnowledgeEvidence[] }
export type AgentScopeType = 'CURRENT_DOCUMENT' | 'SELECTED_DOCUMENTS' | 'ALL_DOCUMENTS'
export type AgentRun = {
  id: string; question: string; status: string; scopeType: AgentScopeType; skillId: string; skillVersion: string; skillDisplayName?: string; scopeDocumentCount: number
  conversationId?: string; conversationTurnIndex?: number; memoryEnabled: boolean
  modelCallsUsed: number; maxModelCalls: number; agentTurnsUsed: number; maxAgentTurns: number; toolCallsUsed: number; maxToolCalls: number
  resultDocument?: string; failureMessage?: string; failureCode?: string; failureStage?: string; recoveryCount: number
  parentRunId?: string; rootRunId?: string; replayFromCheckpointId?: string; createdAt: string; completedAt?: string
}
export type AgentCheckpoint = { id: string; sequence: number; phase: string; stepId?: string; replayable: boolean; createdAt: string }
export type AgentStep = {
  id: string; index: number; executionEpoch: number; type: 'ROUTE' | 'MODEL' | 'TOOL' | 'FINALIZE' | 'RECOVERY'; status: string
  toolName?: string; summary?: string; errorCode?: string; errorMessage?: string; durationMs?: number; finishReason?: string
  inputTokens?: number; outputTokens?: number; totalTokens?: number; checkpointId?: string; replayable: boolean; createdAt: string; completedAt?: string
}
export type AgentStepDetail = Omit<AgentStep, 'checkpointId' | 'replayable'> & { inputCheckpointId?: string; outputCheckpointId?: string; input?: unknown; output?: unknown }
export type AgentEvidence = {
  resultPath: string; sourceKind: 'TRANSCRIPT_SEGMENT' | 'DOCUMENT_METADATA' | 'EXTERNAL' | 'USER_MEMORY'; sourceRef: string; documentId?: string; chunkId?: string
  transcriptionTaskId?: string; segmentId?: string; topic?: string; speakerId?: string; role?: string; speaker?: string; startMs?: number; endMs?: number
  text?: string; memoryId?: string; memoryVersionId?: string; externalLabel?: string; externalUrl?: string
}
export type AgentRunDetail = { run: AgentRun; documentIds: string[]; childRunIds: string[]; steps: AgentStep[]; checkpoints: AgentCheckpoint[]; evidence: AgentEvidence[] }
export type SkillSource = 'BUILTIN' | 'USER'
export type SkillStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'
export type SkillInvocationPolicy = 'MANUAL_ONLY' | 'AUTO'
export type SkillBlockType = 'SUMMARY' | 'FINDINGS' | 'DECISIONS' | 'ACTION_ITEMS' | 'OPEN_QUESTIONS' | 'QA_REVIEW' | 'ASSESSMENT_MATRIX' | 'COMPARISON_TABLE'
export type SkillResourceType = 'REFERENCE' | 'TEMPLATE' | 'EXAMPLE'
export type AgentSkill = {
  id: string; version: string; displayName: string; description: string; source: SkillSource; invocationPolicy: SkillInvocationPolicy
  sceneTypes: Task['sceneType'][]; scopeTypes: AgentScopeType[]; outputBlocks: SkillBlockType[]; defaultPrompt?: string; suggestedPrompts: string[]
}
export type SkillSummary = {
  id: string; displayName: string; description: string; source: SkillSource; status: SkillStatus; invocationPolicy: SkillInvocationPolicy
  version?: string; publishedVersion?: string; hasDraft: boolean; sceneTypes: Task['sceneType'][]; scopeTypes: AgentScopeType[]
  outputBlocks: SkillBlockType[]; updatedAt: string
}
export type SkillResource = { id?: string; key: string; type: SkillResourceType; name: string; purpose: string; markdownContent?: string; sizeBytes?: number }
export type SkillVersion = {
  id: string; version: string; instructions: string; allowedTools: string[]; outputBlocks: SkillBlockType[]; shouldTrigger: string[]
  shouldNotTrigger: string[]; defaultPrompt?: string; contentHash: string; triggerPreviewPassed: boolean; publishedAt?: string; resources: SkillResource[]
}
export type SkillDetail = {
  id: string; displayName: string; description: string; source: SkillSource; status: SkillStatus; invocationPolicy: SkillInvocationPolicy
  sceneTypes: Task['sceneType'][]; scopeTypes: AgentScopeType[]; draft?: SkillVersion; published?: SkillVersion; versions: string[]; createdAt: string; updatedAt: string
}
export type TriggerPreview = { passed: boolean; positiveCount: number; negativeCount: number; conflicts: { text: string; expected: boolean; actual: boolean; reason: string }[] }
export type Profile = { account: string; createdAt: string; statistics: { recordingCount: number; indexedDocumentCount: number; agentRunCount: number; customSkillCount: number } }
export type ResultCitation = { sourceRef?: string; chunkId?: string; segmentId?: string; kind?: AgentEvidence['sourceKind'] }
export type ResultStatement = { text: string; evidence?: ResultCitation[] }
export type ResultItem = { title?: string; content?: string; status?: string; owner?: string | null; dueAt?: string | null; question?: string; answer?: string; dimension?: string; assessment?: string; followUp?: string; label?: string; values?: string[]; statements?: ResultStatement[]; cells?: ResultStatement[]; evidence?: ResultCitation[] }
export type ResultBlock = { type: SkillBlockType; title?: string; content?: string; status?: string; statements?: ResultStatement[]; evidence?: ResultCitation[]; items?: ResultItem[]; columns?: string[]; rows?: ResultItem[] }
export type AgentResult = { resultSchemaVersion?: number; blocks?: ResultBlock[]; answer?: string; findings?: { title?: string; content?: string; evidence?: ResultCitation[] }[]; coverage?: { scopeDocumentCount: number; overviewedDocumentIds: string[]; searchedDocumentIds: string[]; citedDocumentIds: string[]; omittedDocumentIds: string[]; limitations: string[] } }
export type AgentCapabilities = { enabled: boolean; ttsEnabled: boolean; rerankEnabled: boolean; mcpEnabled: boolean; memoryEnabled: boolean; maxPendingMemoryCandidates: number; maxActiveMemories: number; recentConversationTurns: number; conversationContextMaxCharacters: number; conversationSummaryMaxCharacters: number; memorySearchLimit: number; maxScopeDocuments: number; maxModelCalls: number; maxTurns: number; maxToolCalls: number }
export type AgentProgressEvent = { runId: string; sequence: number; phase: string; message: string; speakable: boolean; occurredAt: string }
export type AgentAnswerBlockEvent = { runId: string; sequence: number; blockIndex: number; block: ResultBlock; spokenText: string; occurredAt: string }
export type AgentConversation = { id: string; title: string; status: 'ACTIVE' | 'ARCHIVED'; scopeType: AgentScopeType; timeZone: string; skillId: string; skillVersion: string; memoryEnabled: boolean; summaryStatus: string; summaryFailureMessage?: string; createdAt: string; updatedAt: string }
export type AgentConversationTurn = { id: string; turnIndex: number; userMessage: string; runId?: string; runStatus?: string; resultDocument?: string; failureMessage?: string; extractionStatus: string; extractionFailureMessage?: string; createdAt: string }
export type AgentConversationDetail = { conversation: AgentConversation; transcriptionTaskIds: string[]; turns: AgentConversationTurn[] }
export type PageResult<T> = { content: T[]; totalElements: number; totalPages: number; number: number; size: number }
export type UserMemoryCategory = 'PROFILE' | 'PREFERENCE' | 'WORK_STYLE' | 'PROJECT_CONTEXT' | 'LONG_TERM_GOAL'
export type UserMemoryCandidate = { id: string; category: UserMemoryCategory; semanticKey: string; content: string; sourceExcerpt: string; confidence: number; changeType: 'CREATE' | 'UPDATE'; targetMemoryId?: string; currentContent?: string; status: string; createdAt: string }
export type UserMemory = { id: string; category: UserMemoryCategory; semanticKey: string; versionId: string; versionNumber: number; content: string; indexStatus: string; confirmedAt: string; updatedAt: string; sourceConversationDeleted: boolean }
export type AgentToolView = {
  name: string; displayName: string; description: string; source: 'LOCAL' | 'MCP'; userGrantable: boolean
  enabledForSkill: boolean | null; disabledReason?: string; parameters: unknown; dynamicParameters: boolean
}
export type AgentToolCatalog = { skillId?: string; tools: AgentToolView[] }
export type McpServerStatus = { name: string; transport: string; connected: boolean; tools: string[]; failure?: string }
export type AnalysisRun = { id: string; transcriptionTaskId: string; organizedDocumentId?: string; analysisMode?: string; modelId?: string; status: string; callsUsed: number; maxCalls: number; resultDocument?: string; failureMessage?: string }
export type AnalysisEvidence = { resultPath: string; segmentId: string; startOffset?: number; endOffset?: number }
export type AnalysisRunDetail = { run: AnalysisRun; evidence: AnalysisEvidence[] }
export type WorkspaceSnapshot = { tasks: Task[]; documents: KnowledgeDocument[]; analyses: AnalysisRun[]; knowledgeRuns: KnowledgeRun[] }

export const key = () => crypto.randomUUID()

type UploadPhase = 'intent' | 'content' | 'task'
type ErrorBody = { code?: string; message?: string }

export const SESSION_EXPIRED_EVENT = 'voicenote:session-expired'

export function isSessionExpiredError(error: unknown): boolean {
  if (!axios.isAxiosError(error)) return false
  const authorization = error.config?.headers?.Authorization || error.config?.headers?.authorization
  if (!authorization) return false
  if (error.response?.status === 401) return true
  // Older backend versions returned an empty 403 when Spring Security rejected
  // an expired token. A business-level 403 has a structured response body.
  return error.response?.status === 403 && !error.response.data
}

api.interceptors.response.use(response => response, (error: unknown) => {
  if (isSessionExpiredError(error) && typeof window !== 'undefined') {
    window.dispatchEvent(new Event(SESSION_EXPIRED_EVENT))
  }
  return Promise.reject(error)
})

export function uploadErrorMessage(error: unknown, phase: UploadPhase): string {
  const response = axios.isAxiosError<ErrorBody>(error) ? error.response : undefined

  if (response?.status === 502) {
    if (phase === 'intent') {
      return '后端服务暂时不可用。请先启动 MySQL 或 SSH 隧道，再启动 Spring Boot 服务。'
    }
    if (phase === 'content') {
      if (response.data?.code === 'OBJECT_STORAGE_UNREACHABLE') {
        return '无法连接 MinIO。请检查 MinIO 地址，并确认 SSH 隧道仍在运行。'
      }
      if (response.data?.code === 'OBJECT_STORAGE_CREDENTIALS_REJECTED') {
        return 'MinIO 拒绝写入。请检查访问密钥、密钥以及该账号的 bucket 写权限。'
      }
      if (response.data?.code === 'OBJECT_STORAGE_BUCKET_UNAVAILABLE' || response.data?.code === 'OBJECT_STORAGE_CONFIGURATION_INVALID') {
        return 'MinIO bucket 配置无效或不可写。bucket 名只能使用小写字母、数字、连字符和句点。'
      }
      return '音频无法写入对象存储。请检查 MinIO 地址、访问密钥和 bucket 配置。'
    }
  }

  if (response?.data?.message) return response.data.message
  if (!response && phase === 'intent') {
    return '无法连接后端服务。请确认 MySQL 或 SSH 隧道以及 Spring Boot 服务已经启动。'
  }
  if (!response && phase === 'content') {
    return '音频上传连接失败。请确认后端服务与 MinIO 均可访问。'
  }
  return '上传失败，请稍后重试。'
}

export async function hashFile(file: File) {
  const bytes = await file.arrayBuffer()
  const digest = await crypto.subtle.digest('SHA-256', bytes)
  return Array.from(new Uint8Array(digest), value => value.toString(16).padStart(2, '0')).join('')
}
export const timecode = (milliseconds: number) => {
  const seconds = Math.floor(milliseconds / 1000)
  return `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`
}
export const statusText = (status: string) => ({
  PENDING: '等待调度', QUEUED: '等待处理', INDEXING: '建立索引', INGESTING: '知识库入库', CHUNKING: '按主题切块', READY: '已收录', RETIRED: '已替换', STALE: '需要重建', FAILED: '处理失败',
  SUCCEEDED: '已完成', RUNNING: '处理中', PROVIDER_RUNNING: '转写中', SUBMITTING: '提交中', ORGANIZING: '整理中', CANCELLED: '已取消',
  WAITING_FOR_FORMAL_DOCUMENT: '等待生成正式文档', WAITING_FOR_KNOWLEDGE_BUILD: '等待建立知识库',
  FINAL_FAILED: '转写失败', RETRYABLE_FAILED: '自动重试中', SUBMISSION_UNKNOWN: '状态未知', BUDGET_EXHAUSTED: '额度已用尽', TIMED_OUT: '执行超时',
  RETRY_WAIT: '等待重试', UNKNOWN: '状态未知', RETRIED: '已重试', INTERRUPTED: '已中断'
}[status] || status)
export const stageText = (stage?: PipelineStage) => {
  const labels: Record<PipelineStage, string> = {
  UPLOAD_COMPLETED: '音频已存入 MinIO', ASR_SUBMIT: '提交至转写服务', ASR_POLL: '异步转写任务已启动', TRANSCRIPT_PERSIST: '保存原始文档', RAW_DOCUMENT_READY: '原始文档已就绪', DOCUMENT_ORGANIZATION: '生成正式文档', FORMAL_DOCUMENT_READY: '正式文档已就绪',
  KNOWLEDGE_PREPARE: '准备知识文档', KNOWLEDGE_INDEX: '建立知识索引', COMPLETED: '处理完成'
  }
  return stage ? labels[stage] : '等待处理'
}
export const stageStatusText = (stage: StageAttempt) => {
  if (stage.stage === 'ASR_SUBMIT' && stage.status === 'RUNNING') return '正在从 MinIO 上传并提交给 DashScope'
  if (stage.stage === 'ASR_POLL' && (stage.status === 'QUEUED' || stage.status === 'RUNNING')) return '模型已受理，正在异步转写'
  return statusText(stage.status)
}
