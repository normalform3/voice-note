import axios from 'axios'

export const api = axios.create({ baseURL: '/api' })
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('voicenote_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

export type Task = { id: string; status: string; currentAttemptNumber: number; transcriptVersion: number; failureCode?: string; failureMessage?: string }
export type Segment = { id: string; index: number; speaker?: string; startMs: number; endMs: number; text: string }
export type KnowledgeDocument = { id: string; transcriptionTaskId: string; title: string; status: string; failureMessage?: string; updatedAt: string }
export type KnowledgeRun = { id: string; status: string; toolCallsUsed: number; maxToolCalls: number; resultDocument?: string; failureMessage?: string }
export type KnowledgeEvidence = { resultPath: string; documentId: string; chunkId: string; transcriptionTaskId?: string; segmentId: string }
export type KnowledgeRunDetail = { run: KnowledgeRun; evidence: KnowledgeEvidence[] }

export const key = () => crypto.randomUUID()
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
  SUCCEEDED: '已完成', RUNNING: '处理中', PROVIDER_RUNNING: '转写中', SUBMITTING: '提交中',
  FINAL_FAILED: '转写失败', RETRYABLE_FAILED: '可重试', SUBMISSION_UNKNOWN: '状态未知', BUDGET_EXHAUSTED: '额度已用尽'
}[status] || status)
