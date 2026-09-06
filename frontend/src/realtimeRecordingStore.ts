export type RealtimeCaption = {
  sequence: number
  beginMs?: number
  endMs?: number
  text: string
}

export type PendingArchivePart = {
  idempotencyKey: string
  partNumber: number
  fromChunk: number
  toChunk: number
  sha256: string
}

export type RecordingDraft = {
  id: string
  account: string
  serverSessionId: string
  startedAt: string
  stoppedAt?: string
  filename: string
  mimeType: string
  sampleRate: number
  language: string
  speakerDiarization: boolean
  speakerCount?: number
  status: 'RECORDING' | 'INTERRUPTED' | 'STOPPED' | 'UPLOADING' | 'FINALIZING' | 'FAILED'
  chunkCount: number
  uploadedChunkCount: number
  nextPartNumber: number
  partBoundaries: number[]
  pendingPart?: PendingArchivePart
  finalCaptions: RealtimeCaption[]
  errorMessage?: string
  updatedAt: string
}

export type RecordingChunk = {
  draftId: string
  index: number
  blob: Blob
  createdAt: string
}

const DATABASE_NAME = 'voicenote-realtime-recordings'
const DATABASE_VERSION = 1
const DRAFT_STORE = 'drafts'
const CHUNK_STORE = 'chunks'
const DRAFT_INDEX = 'account'
const CHUNK_INDEX = 'draftId'

let databasePromise: Promise<IDBDatabase> | null = null

function requestResult<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error || new Error('IndexedDB 请求失败'))
  })
}

function transactionComplete(transaction: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    transaction.oncomplete = () => resolve()
    transaction.onabort = () => reject(transaction.error || new Error('IndexedDB 事务已中止'))
    transaction.onerror = () => reject(transaction.error || new Error('IndexedDB 事务失败'))
  })
}

function openDatabase(): Promise<IDBDatabase> {
  if (databasePromise) return databasePromise
  databasePromise = new Promise((resolve, reject) => {
    const request = indexedDB.open(DATABASE_NAME, DATABASE_VERSION)
    request.onupgradeneeded = () => {
      const database = request.result
      const drafts = database.createObjectStore(DRAFT_STORE, { keyPath: 'id' })
      drafts.createIndex(DRAFT_INDEX, 'account', { unique: false })
      const chunks = database.createObjectStore(CHUNK_STORE, { keyPath: ['draftId', 'index'] })
      chunks.createIndex(CHUNK_INDEX, 'draftId', { unique: false })
    }
    request.onsuccess = () => {
      const database = request.result
      database.onversionchange = () => {
        database.close()
        databasePromise = null
      }
      resolve(database)
    }
    request.onerror = () => {
      databasePromise = null
      reject(request.error || new Error('无法打开本地录音存储'))
    }
  })
  return databasePromise
}

export async function saveRecordingDraft(draft: RecordingDraft): Promise<void> {
  const database = await openDatabase()
  const transaction = database.transaction(DRAFT_STORE, 'readwrite')
  // Vue refs expose objects as reactive proxies. IndexedDB cannot structured-clone
  // a Proxy, so persist an explicit plain snapshot instead of the component value.
  const snapshot: RecordingDraft = {
    ...draft,
    partBoundaries: [...draft.partBoundaries],
    pendingPart: draft.pendingPart ? { ...draft.pendingPart } : undefined,
    finalCaptions: draft.finalCaptions.map(caption => ({ ...caption })),
  }
  transaction.objectStore(DRAFT_STORE).put(snapshot)
  await transactionComplete(transaction)
}

export async function getRecordingDraft(id: string): Promise<RecordingDraft | undefined> {
  const database = await openDatabase()
  const transaction = database.transaction(DRAFT_STORE, 'readonly')
  const result = await requestResult(transaction.objectStore(DRAFT_STORE).get(id))
  await transactionComplete(transaction)
  return result as RecordingDraft | undefined
}

export async function listRecordingDrafts(account: string): Promise<RecordingDraft[]> {
  const database = await openDatabase()
  const transaction = database.transaction(DRAFT_STORE, 'readonly')
  const result = await requestResult(transaction.objectStore(DRAFT_STORE).index(DRAFT_INDEX).getAll(IDBKeyRange.only(account)))
  await transactionComplete(transaction)
  return (result as RecordingDraft[]).sort((left, right) => right.updatedAt.localeCompare(left.updatedAt))
}

export async function saveRecordingChunk(chunk: RecordingChunk): Promise<void> {
  const database = await openDatabase()
  const transaction = database.transaction(CHUNK_STORE, 'readwrite')
  transaction.objectStore(CHUNK_STORE).put(chunk)
  await transactionComplete(transaction)
}

export async function getRecordingChunks(draftId: string): Promise<RecordingChunk[]> {
  const database = await openDatabase()
  const transaction = database.transaction(CHUNK_STORE, 'readonly')
  const result = await requestResult(transaction.objectStore(CHUNK_STORE).index(CHUNK_INDEX).getAll(IDBKeyRange.only(draftId)))
  await transactionComplete(transaction)
  return (result as RecordingChunk[]).sort((left, right) => left.index - right.index)
}

export async function deleteRecordingDraft(id: string): Promise<void> {
  const database = await openDatabase()
  const transaction = database.transaction([DRAFT_STORE, CHUNK_STORE], 'readwrite')
  transaction.objectStore(DRAFT_STORE).delete(id)
  const chunkIndex = transaction.objectStore(CHUNK_STORE).index(CHUNK_INDEX)
  const cursorRequest = chunkIndex.openKeyCursor(IDBKeyRange.only(id))
  cursorRequest.onsuccess = () => {
    const cursor = cursorRequest.result
    if (!cursor) return
    transaction.objectStore(CHUNK_STORE).delete(cursor.primaryKey)
    cursor.continue()
  }
  await transactionComplete(transaction)
}

export async function clearRecordingDatabaseForTests(): Promise<void> {
  const database = await databasePromise?.catch(() => null)
  database?.close()
  databasePromise = null
  await new Promise<void>((resolve, reject) => {
    const request = indexedDB.deleteDatabase(DATABASE_NAME)
    request.onsuccess = () => resolve()
    request.onerror = () => reject(request.error || new Error('无法清理测试数据库'))
    request.onblocked = () => reject(new Error('测试数据库仍被占用'))
  })
}
