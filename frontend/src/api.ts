import axios from 'axios'

export const api = axios.create({ baseURL: '/api' })
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('voicenote_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

export type PipelineStage = 'UPLOAD_COMPLETED' | 'ASR_SUBMIT' | 'ASR_POLL' | 'TRANSCRIPT_PERSIST' | 'RAW_DOCUMENT_READY' | 'DOCUMENT_ORGANIZATION' | 'FORMAL_DOCUMENT_READY' | 'KNOWLEDGE_PREPARE' | 'KNOWLEDGE_INDEX' | 'COMPLETED'
export type PipelinePhase = 'TRANSCRIPTION' | 'RAW_DOCUMENT_REVIEW' | 'DOCUMENT_ORGANIZATION' | 'FORMAL_DOCUMENT_REVIEW' | 'KNOWLEDGE_BUILD' | 'COMPLETED'
export type StageAttempt = { stage: PipelineStage; status: string; attemptNumber: number; queuedAt: string; startedAt?: string; completedAt?: string; waitDurationMs?: number; totalWaitDurationMs: number; nextRetryAt?: string; errorCode?: string; errorMessage?: string }
export type Task = {
  id: string; audioBlobId: string; status: string; currentPhase?: PipelinePhase; currentStage?: PipelineStage; progressPercent?: number; transcriptReady?: boolean
  currentAttemptNumber: number; transcriptVersion: number; failureCode?: string; failureMessage?: string; failedStage?: PipelineStage
  retryableStages?: PipelineStage[]; stages?: StageAttempt[]; knowledgeDocument?: { id: string; title: string; status: string; failureMessage?: string }; organizedDocument?: { id: string; title: string; status: string; failureMessage?: string }
}
export type Segment = { id: string; index: number; speakerId?: string; speaker?: string; role?: string; startMs: number; endMs: number; text: string }
export type Speaker = { speakerId: string; suggestedRole: string; suggestedConfidence?: number; confirmedRole?: string; resolvedRole: string; displayName?: string }
export type OrganizedBlock = { id: string; index: number; type: string; parentBlockId?: string; speaker?: string; speakerIds?: string; topic?: string; summary?: string; startMs: number; endMs: number; sourceSegmentIds: string; sourceFragments?: string; text: string }
export type OrganizedDocumentDetail = { document: { id: string; taskId: string; title: string; summary?: string; organizationMode?: string; status: string; structureDocument?: string; plainText?: string; failureMessage?: string }; blocks: OrganizedBlock[] }
export type KnowledgeDocument = { id: string; transcriptionTaskId: string; title: string; status: string; failureMessage?: string; updatedAt: string }
export type KnowledgeRun = { id: string; status: string; toolCallsUsed: number; maxToolCalls: number; resultDocument?: string; failureMessage?: string }
export type KnowledgeEvidence = { resultPath: string; documentId: string; chunkId: string; transcriptionTaskId?: string; segmentId: string; topic?: string; speakerId?: string; role?: string; speaker?: string; startMs?: number; endMs?: number; text?: string }
export type KnowledgeRunDetail = { run: KnowledgeRun; evidence: KnowledgeEvidence[] }
export type AnalysisRun = { id: string; transcriptionTaskId: string; status: string; callsUsed: number; maxCalls: number; resultDocument?: string; failureMessage?: string }
export type AnalysisEvidence = { resultPath: string; segmentId: string; startOffset?: number; endOffset?: number }
export type AnalysisRunDetail = { run: AnalysisRun; evidence: AnalysisEvidence[] }
export type WorkspaceSnapshot = { tasks: Task[]; documents: KnowledgeDocument[]; analyses: AnalysisRun[]; knowledgeRuns: KnowledgeRun[] }

export const key = () => crypto.randomUUID()

type UploadPhase = 'intent' | 'content' | 'task'
type ErrorBody = { code?: string; message?: string }

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
  PENDING: '等待调度', QUEUED: '等待处理', INDEXING: '建立索引', READY: '已收录', FAILED: '处理失败',
  SUCCEEDED: '已完成', RUNNING: '处理中', PROVIDER_RUNNING: '转写中', SUBMITTING: '提交中', ORGANIZING: '整理中', CANCELLED: '已取消',
  WAITING_FOR_FORMAL_DOCUMENT: '等待生成正式文档', WAITING_FOR_KNOWLEDGE_BUILD: '等待建立知识库',
  FINAL_FAILED: '转写失败', RETRYABLE_FAILED: '自动重试中', SUBMISSION_UNKNOWN: '状态未知', BUDGET_EXHAUSTED: '额度已用尽',
  RETRY_WAIT: '等待重试', UNKNOWN: '状态未知', RETRIED: '已重试'
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
