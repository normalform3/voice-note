import 'fake-indexeddb/auto'
import { beforeEach, describe, expect, it } from 'vitest'
import { reactive } from 'vue'
import { appendFinalCaption, nextArchiveChunkRange, reconcileArchiveProgress } from './realtimeRecordingLogic'
import {
  clearRecordingDatabaseForTests,
  deleteRecordingDraft,
  getRecordingChunks,
  getRecordingDraft,
  listRecordingDrafts,
  saveRecordingChunk,
  saveRecordingDraft,
  type RecordingDraft,
} from './realtimeRecordingStore'

function draft(overrides: Partial<RecordingDraft> = {}): RecordingDraft {
  return {
    id: 'draft-1', account: 'nate', serverSessionId: 'session-1', startedAt: '2026-09-04T02:00:00Z',
    filename: 'recording.webm', mimeType: 'audio/webm;codecs=opus', sampleRate: 48000, language: 'zh-en',
    speakerDiarization: true, status: 'RECORDING', chunkCount: 0, uploadedChunkCount: 0,
    nextPartNumber: 0, partBoundaries: [], finalCaptions: [], updatedAt: '2026-09-04T02:00:00Z',
    ...overrides,
  }
}

beforeEach(async () => { await clearRecordingDatabaseForTests() })

describe('realtime recording local archive', () => {
  it('persists local chunks in sequence while offline and restores the draft', async () => {
    const value = draft({ status: 'INTERRUPTED', chunkCount: 2, errorMessage: 'offline' })
    await saveRecordingDraft(value)
    await saveRecordingChunk({ draftId: value.id, index: 1, blob: new Blob(['b']), createdAt: value.updatedAt })
    await saveRecordingChunk({ draftId: value.id, index: 0, blob: new Blob(['a']), createdAt: value.updatedAt })

    expect(await getRecordingDraft(value.id)).toMatchObject({ status: 'INTERRUPTED', errorMessage: 'offline' })
    const chunks = await getRecordingChunks(value.id)
    expect(chunks.map(chunk => chunk.index)).toEqual([0, 1])
    expect(await chunks[0].blob.text()).toBe('a')
  })

  it('isolates drafts by account and removes chunks after archive completion', async () => {
    await saveRecordingDraft(draft())
    await saveRecordingDraft(draft({ id: 'draft-2', account: 'other' }))
    await saveRecordingChunk({ draftId: 'draft-1', index: 0, blob: new Blob(['audio']), createdAt: new Date().toISOString() })

    expect((await listRecordingDrafts('nate')).map(value => value.id)).toEqual(['draft-1'])
    await deleteRecordingDraft('draft-1')
    expect(await getRecordingDraft('draft-1')).toBeUndefined()
    expect(await getRecordingChunks('draft-1')).toEqual([])
  })

  it('persists a Vue reactive draft as a plain IndexedDB value', async () => {
    const value = reactive(draft({ hotwordLibraryId: 'library-1', finalCaptions: [{ sequence: 1, text: '实时字幕' }] }))

    await expect(saveRecordingDraft(value)).resolves.toBeUndefined()
    expect(await getRecordingDraft(value.id)).toMatchObject({
      id: 'draft-1',
      hotwordLibraryId: 'library-1',
      finalCaptions: [{ sequence: 1, text: '实时字幕' }],
    })
  })
})

describe('realtime recording resume logic', () => {
  it('uploads only complete ten-second groups until recording stops', () => {
    expect(nextArchiveChunkRange(draft(), 9, false)).toBeNull()
    expect(nextArchiveChunkRange(draft(), 10, false)).toEqual({ fromChunk: 0, toChunk: 10 })
    expect(nextArchiveChunkRange(draft({ uploadedChunkCount: 10 }), 14, true)).toEqual({ fromChunk: 10, toChunk: 14 })
  })

  it('uses the persisted pending boundary when the server accepted a response lost on refresh', () => {
    const interrupted = draft({
      chunkCount: 14, uploadedChunkCount: 10, nextPartNumber: 1, partBoundaries: [10],
      pendingPart: { idempotencyKey: 'part-key', partNumber: 1, fromChunk: 10, toChunk: 14, sha256: 'a'.repeat(64) },
    })
    const restored = reconcileArchiveProgress(interrupted, 2)
    expect(restored.uploadedChunkCount).toBe(14)
    expect(restored.nextPartNumber).toBe(2)
    expect(restored.pendingPart).toBeUndefined()
    expect(restored.partBoundaries).toEqual([10, 14])
  })

  it('deduplicates repeated realtime final sentences', () => {
    const caption = { sequence: 1, beginMs: 120, endMs: 950, text: '今天开始评审。' }
    const first = appendFinalCaption([], caption)
    expect(appendFinalCaption(first, { ...caption, sequence: 2 })).toBe(first)
    expect(appendFinalCaption(first, { ...caption, beginMs: 1000 })).toHaveLength(2)
  })
})
