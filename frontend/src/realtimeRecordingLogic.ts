import type { RealtimeCaption, RecordingDraft } from './realtimeRecordingStore'

export function appendFinalCaption(captions: RealtimeCaption[], candidate: RealtimeCaption): RealtimeCaption[] {
  if (captions.some(value => value.beginMs === candidate.beginMs && value.text === candidate.text)) return captions
  return [...captions, candidate]
}

export function reconcileArchiveProgress(draft: RecordingDraft, serverNextPartNumber: number): RecordingDraft {
  if (serverNextPartNumber <= draft.nextPartNumber) return draft
  const updated: RecordingDraft = {
    ...draft,
    partBoundaries: [...draft.partBoundaries],
    updatedAt: new Date().toISOString(),
  }
  for (let part = updated.nextPartNumber; part < serverNextPartNumber; part += 1) {
    const boundary = updated.partBoundaries[part]
      || (updated.pendingPart?.partNumber === part ? updated.pendingPart.toChunk : Math.min(updated.chunkCount, (part + 1) * 10))
    updated.partBoundaries[part] = boundary
    updated.uploadedChunkCount = Math.max(updated.uploadedChunkCount, boundary)
  }
  updated.nextPartNumber = serverNextPartNumber
  if (updated.pendingPart && updated.pendingPart.partNumber < serverNextPartNumber) updated.pendingPart = undefined
  return updated
}

export function nextArchiveChunkRange(draft: RecordingDraft, availableChunkCount: number, allowPartial: boolean) {
  const remaining = availableChunkCount - draft.uploadedChunkCount
  if (remaining <= 0 || (remaining < 10 && !allowPartial)) return null
  const count = Math.min(10, remaining)
  return { fromChunk: draft.uploadedChunkCount, toChunk: draft.uploadedChunkCount + count }
}
