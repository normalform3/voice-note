import { stageText, type KnowledgeIndexBuild, type PipelineStage, type StageAttempt, type Task } from './api'

const PIPELINE_STAGES: PipelineStage[] = [
  'UPLOAD_COMPLETED', 'ASR_SUBMIT', 'ASR_POLL', 'TRANSCRIPT_PERSIST', 'RAW_DOCUMENT_READY',
  'DOCUMENT_ORGANIZATION', 'FORMAL_DOCUMENT_READY', 'KNOWLEDGE_PREPARE', 'KNOWLEDGE_INDEX', 'COMPLETED'
]

const KNOWLEDGE_STAGE_LABELS = {
  INGEST: '知识库入库',
  CHUNK: '按主题切块',
  INDEX: '构建检索索引'
} as const

export function latestStageAttempts(task: Pick<Task, 'stages'>): StageAttempt[] {
  const latest = new Map<PipelineStage, StageAttempt>()
  for (const attempt of task.stages || []) {
    const current = latest.get(attempt.stage)
    if (!current || attempt.attemptNumber > current.attemptNumber) latest.set(attempt.stage, attempt)
  }
  return PIPELINE_STAGES.flatMap(stage => latest.has(stage) ? [latest.get(stage)!] : [])
}

export function visibleStageAttempts(task: Pick<Task, 'stages' | 'currentStage'>): StageAttempt[] {
  const currentIndex = task.currentStage ? PIPELINE_STAGES.indexOf(task.currentStage) : PIPELINE_STAGES.length - 1
  return latestStageAttempts(task).filter(attempt => PIPELINE_STAGES.indexOf(attempt.stage) <= currentIndex)
}

export function latestKnowledgeStages(build?: KnowledgeIndexBuild): KnowledgeIndexBuild['stages'] {
  if (!build) return []
  const latest = new Map<KnowledgeIndexBuild['stages'][number]['stage'], KnowledgeIndexBuild['stages'][number]>()
  for (const attempt of build.stages) {
    const current = latest.get(attempt.stage)
    if (!current || (attempt.attemptNumber || 1) > (current.attemptNumber || 1)) latest.set(attempt.stage, attempt)
  }
  return (['INGEST', 'CHUNK', 'INDEX'] as const).flatMap(stage => latest.has(stage) ? [latest.get(stage)!] : [])
}

export function knowledgeBuildProgress(build: KnowledgeIndexBuild): number {
  if (build.status === 'READY') return 100
  return latestKnowledgeStages(build).reduce((progress, stage) => Math.max(progress, {
    INGEST: stage.progressPercent * 15 / 100,
    CHUNK: stage.status === 'QUEUED' ? 0 : 15 + stage.progressPercent * 25 / 100,
    INDEX: stage.status === 'QUEUED' ? 0 : 40 + stage.progressPercent * 60 / 100
  }[stage.stage]), 0)
}

function attemptProgress(attempt?: StageAttempt): number {
  if (!attempt) return 0
  if (attempt.status === 'SUCCEEDED') return 100
  if (attempt.status === 'RUNNING') return 50
  if (attempt.status === 'FAILED' || attempt.status === 'UNKNOWN' || attempt.status === 'CANCELLED') return attempt.startedAt ? 50 : 0
  return 0
}

function currentAttempt(task: Task): StageAttempt | undefined {
  return latestStageAttempts(task).find(attempt => attempt.stage === task.currentStage)
}

function shouldShowKnowledgeBuild(task: Task, build?: KnowledgeIndexBuild): build is KnowledgeIndexBuild {
  if (!build || build.status === 'RETIRED' || task.organizedDocument?.status === 'STALE') return false
  if (['KNOWLEDGE_PREPARE', 'KNOWLEDGE_INDEX'].includes(task.currentStage || '')) return true
  return ['PENDING', 'QUEUED', 'INDEXING', 'FAILED'].includes(build.status) || (build.generation || 1) > 1
}

export function taskDisplayProgress(task: Task, build?: KnowledgeIndexBuild): number {
  if (task.organizedDocument?.status === 'STALE') return 0
  if (shouldShowKnowledgeBuild(task, build)) return Math.round(knowledgeBuildProgress(build))
  if (task.currentStage === 'DOCUMENT_ORGANIZATION') return attemptProgress(currentAttempt(task))
  if (task.currentStage === 'FORMAL_DOCUMENT_READY' && task.status === 'WAITING_FOR_KNOWLEDGE_BUILD') return 100
  const attempt = currentAttempt(task)
  if (attempt && attempt.attemptNumber > 1) return attemptProgress(attempt)
  return task.progressPercent || 0
}

export function taskProgressLabel(task: Task, build?: KnowledgeIndexBuild): string {
  if (task.organizedDocument?.status === 'STALE') return '待重新生成正式文档'
  if (shouldShowKnowledgeBuild(task, build)) {
    return build.currentStage ? KNOWLEDGE_STAGE_LABELS[build.currentStage] : build.status === 'READY' ? '知识库构建完成' : '准备知识库重建'
  }
  return stageText(task.currentStage)
}
