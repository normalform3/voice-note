<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from './api'
import {
  deleteRecordingDraft,
  getRecordingChunks,
  getRecordingDraft,
  saveRecordingChunk,
  saveRecordingDraft,
  type RealtimeCaption,
  type RecordingDraft,
} from './realtimeRecordingStore'
import { appendFinalCaption, nextArchiveChunkRange, reconcileArchiveProgress } from './realtimeRecordingLogic'

type Capabilities = { enabled: boolean; maxDurationSeconds: number; defaultLanguages: string[]; unavailableCode?: string }
type SessionView = {
  id: string
  status: 'RECORDING' | 'FINALIZING' | 'PROCESSING' | 'READY' | 'FAILED' | 'ABORTED' | 'EXPIRED'
  nextPartNumber: number
  totalBytes: number
  startedAt: string
  audioBlobId?: string
  taskId?: string
  failureCode?: string
  failureMessage?: string
  expiresAt: string
}
type RealtimeTicket = { ticket: string; expiresAt: string; websocketPath: string }
type CreatedSession = { session: SessionView; realtime: RealtimeTicket }
type RealtimeEvent = {
  type: 'ready' | 'realtime_transcript' | 'status' | 'gap' | 'error' | 'finished'
  sequence?: number
  final?: boolean
  beginMs?: number
  endMs?: number
  text?: string
  status?: string
  message?: string
  code?: string
}

const props = defineProps<{ account: string; draftId?: string | null }>()
const emit = defineEmits<{
  close: []
  archived: [taskId: string]
  'drafts-changed': []
}>()

const capabilities = ref<Capabilities | null>(null)
const phase = ref<'PREFLIGHT' | 'PREPARING' | 'RECORDING' | 'STOPPING' | 'RECOVERY' | 'UPLOADING' | 'FINALIZING' | 'FAILED'>('PREFLIGHT')
const draft = ref<RecordingDraft | null>(null)
const speakerDiarization = ref(true)
const speakerCount = ref<number | null>(null)
const language = ref<'zh-en' | 'zh' | 'en'>('zh-en')
const elapsedMs = ref(0)
const microphoneLevel = ref(0)
const realtimeStatus = ref<'idle' | 'connecting' | 'connected' | 'reconnecting' | 'degraded' | 'finished'>('idle')
const archiveStatus = ref('等待开始')
const interimCaption = ref('')
const finalCaptions = ref<RealtimeCaption[]>([])
const gapCount = ref(0)
const errorMessage = ref('')
const storageEstimate = ref<{ usage: number; quota: number } | null>(null)

let mediaStream: MediaStream | null = null
let mediaRecorder: MediaRecorder | null = null
let audioContext: AudioContext | null = null
let workletNode: AudioWorkletNode | null = null
let silentGain: GainNode | null = null
let realtimeSocket: WebSocket | null = null
let elapsedTimer: number | null = null
let reconnectTimer: number | null = null
let pollTimer: number | null = null
let pendingChunkWrites: Promise<void> = Promise.resolve()
let archivePump: Promise<boolean> | null = null
let allowPartialArchivePart = false
let stopRequested = false
let localStorageFailed = false
let finalizationStartedAt = 0

const isRecording = computed(() => phase.value === 'RECORDING' || phase.value === 'STOPPING')
const canStart = computed(() => capabilities.value?.enabled && browserSupported.value && phase.value === 'PREFLIGHT')
const browserSupported = computed(() => {
  const chromium = /Chrome\//.test(navigator.userAgent) || /Edg\//.test(navigator.userAgent)
  return chromium && !/Mobile|Android/.test(navigator.userAgent) && typeof MediaRecorder !== 'undefined'
    && typeof AudioWorkletNode !== 'undefined' && typeof indexedDB !== 'undefined'
})
const durationLabel = computed(() => formatDuration(elapsedMs.value))
const maxDurationLabel = computed(() => formatDuration((capabilities.value?.maxDurationSeconds || 7200) * 1000))
const uploadProgress = computed(() => {
  if (!draft.value?.chunkCount) return 0
  return Math.min(100, Math.round(draft.value.uploadedChunkCount / draft.value.chunkCount * 100))
})
const levelBars = computed(() => Array.from({ length: 28 }, (_, index) => microphoneLevel.value * 28 > index))
const storageLabel = computed(() => {
  const value = storageEstimate.value
  if (!value?.quota) return ''
  return `浏览器可用 ${formatBytes(Math.max(0, value.quota - value.usage))}`
})
const realtimeStatusLabel = computed(() => ({
  idle: '尚未连接', connecting: '正在连接', connected: '字幕在线', reconnecting: '正在重连', degraded: '字幕已降级', finished: '字幕已结束',
})[realtimeStatus.value])

function formatDuration(milliseconds: number) {
  const total = Math.max(0, Math.floor(milliseconds / 1000))
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor(total % 3600 / 60)
  const seconds = total % 60
  return hours > 0
    ? `${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
    : `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
}

function formatBytes(bytes: number) {
  if (bytes < 1024 * 1024) return `${Math.max(0, Math.round(bytes / 1024))} KB`
  return `${(bytes / 1024 / 1024).toFixed(bytes < 100 * 1024 * 1024 ? 1 : 0)} MB`
}

function apiError(error: unknown, fallback: string) {
  const candidate = error as { response?: { data?: { message?: string } }; message?: string }
  if (candidate.response?.data?.message) return candidate.response.data.message
  return candidate.message && /[\u3400-\u9fff]/.test(candidate.message) ? candidate.message : fallback
}

function recordingStartError(error: unknown) {
  if (error instanceof DOMException) {
    const messages: Record<string, string> = {
      NotAllowedError: '麦克风权限被拒绝。请在浏览器站点设置中允许麦克风后重试。',
      NotFoundError: '没有检测到可用的麦克风，请连接录音设备后重试。',
      NotReadableError: '麦克风暂时无法读取，可能正被其他应用占用。请关闭占用设备的应用后重试。',
      SecurityError: '当前页面不允许访问麦克风，请使用 localhost 或 HTTPS 打开。',
      DataCloneError: '本地录音草稿保存失败，请刷新页面后重试。',
      AbortError: '浏览器中断了麦克风初始化，请重试。',
      NotSupportedError: '浏览器无法加载实时录音组件，请改用最新版桌面 Chrome 或 Edge。',
    }
    return messages[error.name] || `浏览器无法启动录音：${error.message || error.name}`
  }
  return apiError(error, '无法开始录音，请检查麦克风权限和后端配置。')
}

function chosenLanguages() {
  return language.value === 'zh-en' ? ['zh', 'en'] : [language.value]
}

function recordingMimeType() {
  const choices = ['audio/webm;codecs=opus', 'audio/webm']
  return choices.find(value => MediaRecorder.isTypeSupported(value)) || ''
}

function filenameAt(date: Date) {
  const stamp = date.toLocaleString('sv-SE', { hour12: false }).replace(/:/g, '-').replace(' ', '_')
  return `实时录音_${stamp}.webm`
}

async function loadCapabilities() {
  try {
    const { data } = await api.get<Capabilities>('/realtime-recordings/capabilities')
    capabilities.value = data
  } catch (error) {
    errorMessage.value = apiError(error, '无法读取实时录音配置')
  }
}

async function updateStorageEstimate() {
  if (!navigator.storage?.estimate) return
  const value = await navigator.storage.estimate()
  storageEstimate.value = { usage: value.usage || 0, quota: value.quota || 0 }
}

async function startRecording() {
  if (!canStart.value) return
  const mimeType = recordingMimeType()
  if (!mimeType) {
    errorMessage.value = '当前浏览器不能生成 WebM/Opus 音频，请改用桌面版 Chrome 或 Edge。'
    return
  }
  phase.value = 'PREPARING'
  errorMessage.value = ''
  archiveStatus.value = '正在建立安全归档会话'
  stopRequested = false
  localStorageFailed = false
  try {
    await navigator.storage?.persist?.()
    await updateStorageEstimate()
    mediaStream = await navigator.mediaDevices.getUserMedia({
      audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
    })
    audioContext = new AudioContext({ latencyHint: 'interactive' })
    await audioContext.audioWorklet.addModule('/pcm-recorder-worklet.js')
    if (audioContext.state === 'suspended') await audioContext.resume()
    const startedAt = new Date()
    const { data } = await api.post<CreatedSession>('/realtime-recordings', {
      startedAt: startedAt.toISOString(),
      contentType: mimeType,
      originalFilename: filenameAt(startedAt),
      sampleRate: audioContext.sampleRate,
      languageHints: chosenLanguages(),
      asrConfig: {
        languageHints: chosenLanguages(),
        diarizationEnabled: speakerDiarization.value,
        speakerCount: speakerDiarization.value ? speakerCount.value : null,
      },
    })
    draft.value = {
      id: crypto.randomUUID(), account: props.account, serverSessionId: data.session.id,
      startedAt: startedAt.toISOString(), filename: filenameAt(startedAt), mimeType,
      sampleRate: audioContext.sampleRate, language: language.value,
      speakerDiarization: speakerDiarization.value, speakerCount: speakerCount.value || undefined,
      status: 'RECORDING', chunkCount: 0, uploadedChunkCount: 0, nextPartNumber: 0,
      partBoundaries: [], finalCaptions: [], updatedAt: new Date().toISOString(),
    }
    await saveRecordingDraft(draft.value)
    emit('drafts-changed')
    prepareAudioGraph()
    await connectRealtime(data.realtime)
  } catch (error) {
    await cleanupCapture()
    if (draft.value) {
      await api.delete(`/realtime-recordings/${draft.value.serverSessionId}`).catch(() => {})
      await deleteRecordingDraft(draft.value.id).catch(() => {})
      draft.value = null
      emit('drafts-changed')
    }
    phase.value = 'PREFLIGHT'
    archiveStatus.value = '尚未开始'
    errorMessage.value = recordingStartError(error)
  }
}

function prepareAudioGraph() {
  if (!audioContext || !mediaStream) throw new Error('录音设备尚未准备完成')
  const source = audioContext.createMediaStreamSource(mediaStream)
  workletNode = new AudioWorkletNode(audioContext, 'voicenote-pcm-recorder')
  silentGain = audioContext.createGain()
  silentGain.gain.value = 0
  source.connect(workletNode)
  workletNode.connect(silentGain)
  silentGain.connect(audioContext.destination)
  workletNode.port.onmessage = event => {
    if (event.data?.type !== 'pcm' || phase.value !== 'RECORDING') return
    microphoneLevel.value = Math.min(1, Number(event.data.level || 0) * 3.2)
    if (realtimeSocket?.readyState === WebSocket.OPEN) realtimeSocket.send(event.data.buffer)
  }
}

async function connectRealtime(ticket?: RealtimeTicket) {
  if (!draft.value || stopRequested) return
  realtimeStatus.value = ticket ? 'connecting' : 'reconnecting'
  const credentials = ticket || (await api.post<RealtimeTicket>(`/realtime-recordings/${draft.value.serverSessionId}/realtime-ticket`)).data
  const scheme = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  const socket = new WebSocket(`${scheme}//${window.location.host}${credentials.websocketPath}`, [
    'voicenote.realtime.v1', `voicenote.ticket.${credentials.ticket}`,
  ])
  realtimeSocket = socket
  socket.binaryType = 'arraybuffer'
  socket.onmessage = event => handleRealtimeEvent(JSON.parse(event.data) as RealtimeEvent)
  socket.onerror = () => { realtimeStatus.value = 'degraded' }
  socket.onclose = () => {
    if (realtimeSocket === socket) realtimeSocket = null
    if (!stopRequested && phase.value === 'PREPARING') {
      void failPreparation('实时字幕连接未能建立，请检查后端实时 ASR 配置后重试。')
      return
    }
    if (!stopRequested && phase.value === 'RECORDING') {
      gapCount.value += 1
      realtimeStatus.value = 'reconnecting'
      reconnectTimer = window.setTimeout(() => { void connectRealtime().catch(() => { realtimeStatus.value = 'degraded' }) }, 1200)
    }
  }
}

function handleRealtimeEvent(event: RealtimeEvent) {
  if (event.type === 'ready') {
    realtimeStatus.value = 'connected'
    if (phase.value === 'PREPARING') beginMediaRecorder()
    return
  }
  if (event.type === 'status') {
    if (event.status === 'reconnecting') realtimeStatus.value = 'reconnecting'
    if (event.status === 'degraded') realtimeStatus.value = 'degraded'
    return
  }
  if (event.type === 'gap') {
    gapCount.value += 1
    return
  }
  if (event.type === 'error') {
    realtimeStatus.value = 'degraded'
    if (phase.value === 'PREPARING') {
      void failPreparation(event.message || '实时字幕服务暂时不可用。')
      return
    }
    if (event.code === 'RECORDING_DURATION_EXCEEDED') void stopRecording()
    return
  }
  if (event.type === 'finished') {
    realtimeStatus.value = 'finished'
    return
  }
  if (event.type !== 'realtime_transcript' || !event.text?.trim()) return
  if (!event.final) {
    interimCaption.value = event.text.trim()
    return
  }
  const caption: RealtimeCaption = {
    sequence: event.sequence || Date.now(), beginMs: event.beginMs, endMs: event.endMs, text: event.text.trim(),
  }
  const updatedCaptions = appendFinalCaption(finalCaptions.value, caption)
  if (updatedCaptions !== finalCaptions.value) {
    finalCaptions.value = updatedCaptions
    if (draft.value) {
      draft.value.finalCaptions = [...finalCaptions.value]
      draft.value.updatedAt = new Date().toISOString()
      void saveRecordingDraft(draft.value).catch(() => {})
    }
  }
  interimCaption.value = ''
  void nextTick(() => document.querySelector('.realtime-caption-tail')?.scrollIntoView({ block: 'nearest' }))
}

function beginMediaRecorder() {
  if (!mediaStream || !draft.value) return
  mediaRecorder = new MediaRecorder(mediaStream, { mimeType: draft.value.mimeType, audioBitsPerSecond: 64_000 })
  mediaRecorder.ondataavailable = event => {
    if (!event.data.size || !draft.value) return
    const current = draft.value
    pendingChunkWrites = pendingChunkWrites.then(async () => {
      const index = current.chunkCount
      await saveRecordingChunk({ draftId: current.id, index, blob: event.data, createdAt: new Date().toISOString() })
      current.chunkCount = index + 1
      current.updatedAt = new Date().toISOString()
      await saveRecordingDraft(current)
      archiveStatus.value = navigator.onLine ? `本地已保存 ${current.chunkCount} 秒，持续上传中` : '网络中断，音频仍在本地安全保存'
      await updateStorageEstimate()
      void pumpArchive(false)
    }).catch(error => {
      localStorageFailed = true
      errorMessage.value = apiError(error, '浏览器存储写入失败，正在安全结束录音。')
      window.setTimeout(() => { void stopRecording() }, 0)
    })
  }
  mediaRecorder.onerror = () => {
    errorMessage.value = '浏览器录音编码失败，正在保存已经录到的内容。'
    void stopRecording()
  }
  mediaRecorder.start(1000)
  phase.value = 'RECORDING'
  archiveStatus.value = '录音已在本地持续保存'
  elapsedTimer = window.setInterval(() => {
    if (!draft.value) return
    elapsedMs.value = Date.now() - new Date(draft.value.startedAt).getTime()
    if (elapsedMs.value >= (capabilities.value?.maxDurationSeconds || 7200) * 1000) void stopRecording()
  }, 250)
}

async function pumpArchive(allowPartial: boolean): Promise<boolean> {
  allowPartialArchivePart ||= allowPartial
  if (archivePump) return archivePump
  archivePump = (async () => {
    if (!draft.value) return false
    const current = draft.value
    try {
      while (true) {
        const chunks = await getRecordingChunks(current.id)
        const range = nextArchiveChunkRange(current, chunks.length, allowPartialArchivePart)
        if (!range) break
        const selected = chunks.slice(range.fromChunk, range.toChunk)
        if (selected.length !== range.toChunk - range.fromChunk) throw new Error('本地录音分片不连续')
        const blob = new Blob(selected.map(value => value.blob), { type: 'application/octet-stream' })
        const sha256 = await hashBlob(blob)
        const matchingPending = current.pendingPart?.partNumber === current.nextPartNumber
          && current.pendingPart.fromChunk === range.fromChunk && current.pendingPart.toChunk === range.toChunk
          && current.pendingPart.sha256 === sha256 ? current.pendingPart : null
        const pending = matchingPending || {
          idempotencyKey: crypto.randomUUID(),
          partNumber: current.nextPartNumber,
          fromChunk: range.fromChunk,
          toChunk: range.toChunk,
          sha256,
        }
        current.pendingPart = pending
        current.updatedAt = new Date().toISOString()
        await saveRecordingDraft(current)
        archiveStatus.value = `正在归档第 ${pending.partNumber + 1} 个音频分片`
        await api.put(`/realtime-recordings/${current.serverSessionId}/parts/${pending.partNumber}`, blob, {
          headers: { 'Content-Type': 'application/octet-stream', 'X-Content-SHA256': sha256, 'Idempotency-Key': pending.idempotencyKey },
        })
        current.uploadedChunkCount = pending.toChunk
        current.partBoundaries[pending.partNumber] = pending.toChunk
        current.nextPartNumber = pending.partNumber + 1
        current.pendingPart = undefined
        current.errorMessage = undefined
        current.updatedAt = new Date().toISOString()
        await saveRecordingDraft(current)
      }
      archiveStatus.value = current.uploadedChunkCount === current.chunkCount
        ? '音频分片已全部上传'
        : `已上传 ${current.uploadedChunkCount} / ${current.chunkCount} 个本地块`
      return true
    } catch (error) {
      current.status = phase.value === 'RECORDING' ? 'RECORDING' : 'FAILED'
      current.errorMessage = apiError(error, '音频归档暂时中断')
      current.updatedAt = new Date().toISOString()
      await saveRecordingDraft(current).catch(() => {})
      archiveStatus.value = navigator.onLine ? '上传暂时失败，可继续录音并稍后重试' : '网络中断，音频保留在本地'
      emit('drafts-changed')
      return false
    } finally {
      archivePump = null
    }
  })()
  return archivePump
}

async function hashBlob(blob: Blob) {
  const digest = await crypto.subtle.digest('SHA-256', await blob.arrayBuffer())
  return Array.from(new Uint8Array(digest), value => value.toString(16).padStart(2, '0')).join('')
}

async function stopRecording() {
  if (stopRequested || (phase.value !== 'RECORDING' && phase.value !== 'PREPARING')) return
  stopRequested = true
  phase.value = 'STOPPING'
  archiveStatus.value = '正在封存本地录音'
  clearTimers(false)
  const stoppedAt = new Date()
  elapsedMs.value = draft.value
    ? stoppedAt.getTime() - new Date(draft.value.startedAt).getTime()
    : elapsedMs.value
  if (draft.value) {
    draft.value.stoppedAt = stoppedAt.toISOString()
    draft.value.status = 'STOPPED'
    draft.value.updatedAt = stoppedAt.toISOString()
  }

  // Stop the physical capture synchronously before waiting for IndexedDB or the
  // network. This guarantees that a slow final chunk cannot leave the mic live.
  const recorderStopped = stopMediaRecorder(mediaRecorder)
  workletNode && (workletNode.port.onmessage = null)
  mediaStream?.getTracks().forEach(track => track.stop())
  if (draft.value) {
    await saveRecordingDraft(draft.value)
    emit('drafts-changed')
  }
  await recorderStopped
  await pendingChunkWrites
  realtimeSocket?.send(JSON.stringify({ type: 'finish' }))
  await cleanupCapture(false)
  const finishingSocket = realtimeSocket
  window.setTimeout(() => {
    if (realtimeSocket === finishingSocket) realtimeSocket = null
    finishingSocket?.close()
  }, 800)
  if (!draft.value) return
  draft.value.status = 'STOPPED'
  draft.value.updatedAt = new Date().toISOString()
  await saveRecordingDraft(draft.value)
  emit('drafts-changed')
  if (localStorageFailed && draft.value.chunkCount === 0) {
    phase.value = 'FAILED'
    return
  }
  await finalizeArchive()
}

function stopMediaRecorder(recorder: MediaRecorder | null): Promise<void> {
  if (!recorder || recorder.state === 'inactive') return Promise.resolve()
  return new Promise(resolve => {
    let settled = false
    const finish = () => {
      if (settled) return
      settled = true
      window.clearTimeout(timeout)
      resolve()
    }
    const timeout = window.setTimeout(finish, 3000)
    recorder.addEventListener('stop', finish, { once: true })
    try {
      recorder.requestData()
      recorder.stop()
    } catch {
      finish()
    }
  })
}

async function finalizeArchive() {
  if (!draft.value) return
  phase.value = 'UPLOADING'
  draft.value.status = 'UPLOADING'
  draft.value.updatedAt = new Date().toISOString()
  await saveRecordingDraft(draft.value)
  allowPartialArchivePart = true
  const uploaded = await pumpArchive(true)
  if (!uploaded || draft.value.uploadedChunkCount !== draft.value.chunkCount || draft.value.chunkCount === 0) {
    phase.value = 'RECOVERY'
    draft.value.status = 'FAILED'
    draft.value.updatedAt = new Date().toISOString()
    await saveRecordingDraft(draft.value)
    emit('drafts-changed')
    return
  }
  try {
    const { data } = await api.post<SessionView>(`/realtime-recordings/${draft.value.serverSessionId}/complete`, {
      partCount: draft.value.nextPartNumber,
    })
    draft.value.status = 'FINALIZING'
    draft.value.errorMessage = undefined
    draft.value.updatedAt = new Date().toISOString()
    await saveRecordingDraft(draft.value)
    phase.value = 'FINALIZING'
    finalizationStartedAt = Date.now()
    archiveStatus.value = '完整音频已保存，正在创建最终转写任务'
    emit('drafts-changed')
    await handleSessionState(data)
  } catch (error) {
    draft.value.status = 'FAILED'
    draft.value.errorMessage = apiError(error, '请求服务端合并音频失败')
    draft.value.updatedAt = new Date().toISOString()
    await saveRecordingDraft(draft.value)
    errorMessage.value = draft.value.errorMessage
    phase.value = 'RECOVERY'
    emit('drafts-changed')
  }
}

async function restoreDraft() {
  if (!props.draftId) return
  try {
    const local = await getRecordingDraft(props.draftId)
    if (!local || local.account !== props.account) {
      errorMessage.value = '本地录音草稿不存在或不属于当前账号。'
      phase.value = 'FAILED'
      return
    }
    draft.value = local
    finalCaptions.value = [...local.finalCaptions]
    language.value = (local.language === 'zh' || local.language === 'en') ? local.language : 'zh-en'
    speakerDiarization.value = local.speakerDiarization
    speakerCount.value = local.speakerCount || null
    const durationEnd = local.stoppedAt
      || (local.status === 'RECORDING' || local.status === 'INTERRUPTED' ? new Date().toISOString() : local.updatedAt)
    elapsedMs.value = new Date(durationEnd).getTime() - new Date(local.startedAt).getTime()
    const { data } = await api.get<SessionView>(`/realtime-recordings/${local.serverSessionId}`)
    reconcileServerProgress(data)
    if (data.status === 'READY') return completeLocalDraft(data.taskId)
    if (data.status === 'FINALIZING' || data.status === 'PROCESSING') {
      phase.value = 'FINALIZING'
      finalizationStartedAt = Date.now()
      archiveStatus.value = '服务端正在合并完整音频'
      return schedulePoll()
    }
    if (data.status === 'ABORTED' || data.status === 'EXPIRED') throw new Error('服务端录音会话已失效，请下载本地副本。')
    local.status = 'INTERRUPTED'
    local.updatedAt = new Date().toISOString()
    await saveRecordingDraft(local)
    phase.value = 'RECOVERY'
    archiveStatus.value = data.status === 'FAILED' ? '上次合并失败，可重新归档' : '发现未完成录音，可从断点继续归档'
    errorMessage.value = data.failureMessage || local.errorMessage || ''
  } catch (error) {
    phase.value = 'RECOVERY'
    errorMessage.value = apiError(error, '恢复本地录音草稿失败')
  }
}

async function failPreparation(message: string) {
  if (phase.value !== 'PREPARING') return
  stopRequested = true
  errorMessage.value = message
  realtimeStatus.value = 'degraded'
  await cleanupCapture()
  if (draft.value) {
    await api.delete(`/realtime-recordings/${draft.value.serverSessionId}`).catch(() => {})
    await deleteRecordingDraft(draft.value.id).catch(() => {})
    draft.value = null
    emit('drafts-changed')
  }
  stopRequested = false
  phase.value = 'PREFLIGHT'
  archiveStatus.value = '尚未开始'
}

function reconcileServerProgress(session: SessionView) {
  if (!draft.value) return
  const updated = reconcileArchiveProgress(draft.value, session.nextPartNumber)
  if (updated === draft.value) return
  draft.value = updated
  void saveRecordingDraft(updated)
}

async function retryArchive() {
  if (!draft.value) return
  errorMessage.value = ''
  try {
    const { data } = await api.get<SessionView>(`/realtime-recordings/${draft.value.serverSessionId}`)
    reconcileServerProgress(data)
    if (data.status === 'READY') return completeLocalDraft(data.taskId)
    if (data.status === 'FAILED') {
      // A failed merge may mean an object disappeared while its database row
      // survived. Re-send every local part; the server handles these writes
      // idempotently without incrementing its byte or part counters again.
      draft.value.uploadedChunkCount = 0
      draft.value.nextPartNumber = 0
      draft.value.partBoundaries = []
      draft.value.pendingPart = undefined
      draft.value.updatedAt = new Date().toISOString()
      await saveRecordingDraft(draft.value)
    }
    await finalizeArchive()
  } catch (error) {
    errorMessage.value = apiError(error, '暂时无法继续归档')
    phase.value = 'RECOVERY'
  }
}

async function handleSessionState(session: SessionView) {
  if (session.status === 'READY') return completeLocalDraft(session.taskId)
  if (session.status === 'FAILED') {
    phase.value = 'RECOVERY'
    archiveStatus.value = '服务端合并失败，本地与临时分片均已保留'
    errorMessage.value = session.failureMessage || '服务端合并失败'
    if (draft.value) {
      draft.value.status = 'FAILED'
      draft.value.errorMessage = errorMessage.value
      draft.value.updatedAt = new Date().toISOString()
      await saveRecordingDraft(draft.value)
    }
    return
  }
  if (session.status === 'FINALIZING' || session.status === 'PROCESSING') {
    finalizationStartedAt ||= Date.now()
    phase.value = 'FINALIZING'
    archiveStatus.value = session.status === 'FINALIZING'
      ? '归档请求已提交，正在等待后台处理'
      : '后台正在校验并合并完整音频'
    if (Date.now() - finalizationStartedAt >= 30_000) {
      archiveStatus.value = '后台仍在处理，本地录音已安全保留，可关闭窗口稍后查看'
    }
  }
  schedulePoll()
}

function schedulePoll() {
  if (pollTimer) window.clearTimeout(pollTimer)
  pollTimer = window.setTimeout(async () => {
    if (!draft.value) return
    try {
      const { data } = await api.get<SessionView>(`/realtime-recordings/${draft.value.serverSessionId}`)
      await handleSessionState(data)
    } catch {
      archiveStatus.value = '等待后端恢复连接，本地草稿仍保留'
      schedulePoll()
    }
  }, 1800)
}

async function completeLocalDraft(taskId?: string) {
  if (!draft.value || !taskId) {
    phase.value = 'RECOVERY'
    errorMessage.value = '录音已保存，但服务端尚未返回最终任务 ID。'
    return
  }
  const id = draft.value.id
  archiveStatus.value = '归档完成，正在打开最终转写任务'
  await deleteRecordingDraft(id)
  draft.value = null
  emit('drafts-changed')
  emit('archived', taskId)
}

async function downloadLocalCopy() {
  if (!draft.value) return
  const chunks = await getRecordingChunks(draft.value.id)
  const blob = new Blob(chunks.map(value => value.blob), { type: draft.value.mimeType })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = draft.value.filename
  link.click()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}

async function discardDraft() {
  const confirmed = window.confirm(isRecording.value
    ? '放弃后将停止录音，并删除本地草稿与服务端临时分片。确定继续吗？'
    : '确定删除这份本地录音草稿和服务端临时分片吗？建议先下载副本。')
  if (!confirmed) return
  stopRequested = true
  clearTimers(true)
  if (mediaRecorder?.state === 'recording') mediaRecorder.stop()
  await cleanupCapture()
  if (draft.value) {
    await api.delete(`/realtime-recordings/${draft.value.serverSessionId}`).catch(() => {})
    await deleteRecordingDraft(draft.value.id)
  }
  emit('drafts-changed')
  emit('close')
}

function requestClose() {
  if (isRecording.value || phase.value === 'PREPARING') return
  emit('close')
}

function clearTimers(all: boolean) {
  if (elapsedTimer) window.clearInterval(elapsedTimer)
  elapsedTimer = null
  if (reconnectTimer) window.clearTimeout(reconnectTimer)
  reconnectTimer = null
  if (all && pollTimer) window.clearTimeout(pollTimer)
  if (all) pollTimer = null
}

async function cleanupCapture(closeSocket = true) {
  if (workletNode) workletNode.port.onmessage = null
  workletNode?.disconnect()
  silentGain?.disconnect()
  mediaStream?.getTracks().forEach(track => track.stop())
  if (audioContext && audioContext.state !== 'closed') await audioContext.close().catch(() => {})
  if (closeSocket) realtimeSocket?.close()
  workletNode = null
  silentGain = null
  mediaStream = null
  mediaRecorder = null
  audioContext = null
  microphoneLevel.value = 0
}

onMounted(async () => {
  await Promise.all([loadCapabilities(), updateStorageEstimate()])
  if (props.draftId) await restoreDraft()
})

onBeforeUnmount(() => {
  stopRequested = true
  clearTimers(true)
  if (phase.value === 'RECORDING' && draft.value) {
    draft.value.status = 'INTERRUPTED'
    draft.value.updatedAt = new Date().toISOString()
    void saveRecordingDraft(draft.value)
  }
  void cleanupCapture()
})
</script>

<template>
  <Teleport to="body">
    <div class="realtime-recording-backdrop" role="dialog" aria-modal="true" aria-labelledby="recording-title">
      <section class="realtime-recording-workbench">
        <header class="recording-workbench-header">
          <div>
            <p>LIVE CAPTURE · 双链路归档</p>
            <h2 id="recording-title">实时录音</h2>
          </div>
          <button type="button" :disabled="isRecording || phase === 'PREPARING'" aria-label="关闭实时录音" @click="requestClose">×</button>
        </header>

        <div v-if="phase === 'PREFLIGHT' || phase === 'PREPARING'" class="recording-preflight">
          <div class="recording-orbit" :class="{ preparing: phase === 'PREPARING' }" aria-hidden="true"><span>●</span><i></i><i></i></div>
          <p class="recording-kicker">AUDIO ARCHIVE + LIVE CAPTIONS</p>
          <h3>{{ phase === 'PREPARING' ? '正在连接实时字幕…' : '声音会被完整保存，字幕只负责此刻。' }}</h3>
          <p>录音期间持续保存 WebM 音频，并显示低延迟字幕；结束后系统会对完整音频重新转写，最终结果才进入文档、摘要和知识库。</p>
          <div class="recording-options">
            <label>实时语言
              <select v-model="language" :disabled="phase === 'PREPARING'"><option value="zh-en">中文 + 英文</option><option value="zh">中文</option><option value="en">英文</option></select>
            </label>
            <label class="recording-check"><input v-model="speakerDiarization" type="checkbox" :disabled="phase === 'PREPARING'"> 最终转写识别说话人</label>
            <label v-if="speakerDiarization">说话人数（可选）<input v-model.number="speakerCount" type="number" min="2" max="100" placeholder="自动判断" :disabled="phase === 'PREPARING'"></label>
          </div>
          <div v-if="!browserSupported" class="recording-alert">首版仅支持桌面版 Chrome / Edge，并需要允许麦克风与本地存储。</div>
          <div v-else-if="capabilities && !capabilities.enabled" class="recording-alert">服务端尚未启用实时 ASR，请配置 DashScope 实时识别后重试。</div>
          <div v-if="errorMessage" class="recording-alert">{{ errorMessage }}</div>
          <button class="recording-start-button" type="button" :disabled="!canStart || phase === 'PREPARING'" @click="startRecording">
            <span></span>{{ phase === 'PREPARING' ? '准备中…' : '开始实时录音' }}
          </button>
          <small>最长 {{ maxDurationLabel }} · {{ storageLabel || '录音按秒保存到浏览器本地' }}</small>
        </div>

        <template v-else>
          <div class="recording-live-head">
            <div class="recording-clock"><span :class="{ live: phase === 'RECORDING' }"></span><time>{{ durationLabel }}</time><small>/ {{ maxDurationLabel }}</small></div>
            <div class="recording-status-grid">
              <span><i :class="`state-${realtimeStatus}`"></i><b>实时字幕</b><small>{{ realtimeStatusLabel }}</small></span>
              <span><i :class="uploadProgress === 100 ? 'state-connected' : 'state-connecting'"></i><b>音频归档</b><small>{{ uploadProgress }}% · {{ archiveStatus }}</small></span>
            </div>
          </div>

          <div v-if="phase === 'RECORDING' || phase === 'STOPPING'" class="recording-meter" aria-label="麦克风电平">
            <i v-for="(active, index) in levelBars" :key="index" :class="{ active }" :style="{ height: `${8 + (index % 7) * 3}px` }"></i>
          </div>

          <section class="realtime-caption-panel" aria-live="polite">
            <header><div><p>REALTIME TRANSCRIPT</p><h3>实时字幕</h3></div><span>{{ finalCaptions.length }} 句已确认</span></header>
            <div class="realtime-caption-scroll">
              <p v-if="!finalCaptions.length && !interimCaption" class="caption-placeholder">开始说话后，低延迟字幕会出现在这里。它不会写入最终文档。</p>
              <p v-for="caption in finalCaptions" :key="`${caption.sequence}-${caption.beginMs}`" class="caption-final"><time>{{ formatDuration(caption.beginMs || 0) }}</time><span>{{ caption.text }}</span></p>
              <p v-if="interimCaption" class="caption-interim"><time>···</time><span>{{ interimCaption }}</span></p>
              <div class="realtime-caption-tail"></div>
            </div>
            <footer v-if="gapCount"><span>△</span> 实时字幕出现 {{ gapCount }} 个连接缺口；完整音频仍在持续归档，最终转写不受影响。</footer>
          </section>

          <div v-if="errorMessage" class="recording-alert recording-live-alert">{{ errorMessage }}</div>

          <footer class="recording-actions">
            <template v-if="phase === 'RECORDING' || phase === 'STOPPING'">
              <button class="recording-discard" type="button" :disabled="phase === 'STOPPING'" @click="discardDraft">放弃录音</button>
              <button class="recording-finish" type="button" :disabled="phase === 'STOPPING'" @click="stopRecording"><span>■</span>{{ phase === 'STOPPING' ? '正在安全封存…' : '结束并归档' }}</button>
            </template>
            <template v-else-if="phase === 'UPLOADING' || phase === 'FINALIZING'">
              <button class="recording-secondary" type="button" @click="downloadLocalCopy">下载本地副本</button>
              <button class="recording-secondary" type="button" @click="requestClose">关闭窗口（后台继续）</button>
              <div class="recording-processing"><span></span><p><b>{{ phase === 'UPLOADING' ? '正在补传本地音频' : '正在准备最终转写' }}</b><small>{{ archiveStatus }}；完成后会自动打开任务</small></p></div>
            </template>
            <template v-else>
              <button class="recording-discard" type="button" @click="discardDraft">确认删除</button>
              <button class="recording-secondary" type="button" @click="downloadLocalCopy">下载本地副本</button>
              <button class="recording-finish" type="button" @click="retryArchive">继续归档 <span>→</span></button>
            </template>
          </footer>
        </template>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.realtime-recording-backdrop { position: fixed; inset: 0; z-index: 120; display: grid; place-items: center; padding: 24px; overflow: auto; background: rgba(23, 29, 42, .64); backdrop-filter: blur(12px); }
.realtime-recording-workbench { width: min(920px, 100%); min-height: min(760px, calc(100vh - 48px)); display: flex; flex-direction: column; overflow: hidden; border: 1px solid rgba(255,255,255,.42); border-radius: 26px; color: #222939; background: #f7f7f2; box-shadow: 0 30px 100px rgba(14, 20, 36, .34); }
.recording-workbench-header { display: flex; align-items: center; justify-content: space-between; padding: 24px 28px 18px; border-bottom: 1px solid #dddeda; }
.recording-workbench-header p, .recording-kicker, .realtime-caption-panel header p { margin: 0 0 4px; color: #7e8490; font: 600 10px/1.3 'DM Mono', monospace; letter-spacing: .16em; }
.recording-workbench-header h2 { margin: 0; font: 700 25px/1.1 'Noto Serif SC', serif; }
.recording-workbench-header > button { width: 38px; height: 38px; border: 1px solid #d4d6d4; border-radius: 50%; color: #5b606b; background: transparent; font-size: 24px; cursor: pointer; }
.recording-workbench-header > button:disabled { opacity: .35; cursor: not-allowed; }
.recording-preflight { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 48px 11%; text-align: center; }
.recording-orbit { position: relative; width: 118px; height: 118px; display: grid; place-items: center; margin-bottom: 26px; border: 1px solid #cfd2d1; border-radius: 50%; }
.recording-orbit::before, .recording-orbit::after { content: ''; position: absolute; border: 1px solid #dfe1df; border-radius: 50%; }
.recording-orbit::before { inset: 14px; }.recording-orbit::after { inset: 31px; }
.recording-orbit span { z-index: 1; color: #bc5a4c; font-size: 28px; text-shadow: 0 0 20px rgba(188,90,76,.4); }
.recording-orbit i { position: absolute; width: 8px; height: 8px; top: 5px; border-radius: 50%; background: #59669f; transform-origin: 4px 54px; animation: recording-orbit 4s linear infinite; }
.recording-orbit.preparing i { animation-duration: 1s; }
@keyframes recording-orbit { to { transform: rotate(360deg); } }
.recording-preflight h3 { max-width: 590px; margin: 4px 0 12px; font: 700 30px/1.35 'Noto Serif SC', serif; }
.recording-preflight > p:not(.recording-kicker) { max-width: 620px; margin: 0; color: #686e78; font-size: 14px; line-height: 1.85; }
.recording-options { width: min(620px, 100%); display: grid; grid-template-columns: 1fr 1.2fr 1fr; align-items: end; gap: 10px; margin: 30px 0 18px; text-align: left; }
.recording-options label { display: grid; gap: 7px; color: #676d77; font-size: 11px; }
.recording-options select, .recording-options input[type='number'] { min-width: 0; height: 40px; border: 1px solid #d7d8d4; border-radius: 9px; padding: 0 11px; color: #2d3340; background: #fff; }
.recording-options .recording-check { height: 40px; display: flex; align-items: center; padding: 0 11px; border: 1px solid #d7d8d4; border-radius: 9px; color: #414752; background: #fff; white-space: nowrap; }
.recording-alert { width: min(620px, 100%); margin: 0 0 14px; padding: 10px 13px; border: 1px solid #e4c9c2; border-radius: 9px; color: #854c43; background: #fbefeb; font-size: 12px; text-align: left; }
.recording-start-button { display: inline-flex; align-items: center; gap: 11px; margin: 8px 0 12px; padding: 13px 24px; border: 0; border-radius: 12px; color: #fff; background: #293149; font-weight: 700; cursor: pointer; box-shadow: 0 12px 26px rgba(41,49,73,.2); }
.recording-start-button span { width: 10px; height: 10px; border: 2px solid rgba(255,255,255,.8); border-radius: 50%; background: #c5584b; }
.recording-start-button:disabled { opacity: .46; cursor: not-allowed; }
.recording-preflight > small { color: #90949c; font-size: 10px; }
.recording-live-head { display: grid; grid-template-columns: auto 1fr; align-items: center; gap: 34px; padding: 24px 28px 18px; }
.recording-clock { display: flex; align-items: baseline; gap: 8px; min-width: 245px; }
.recording-clock > span { align-self: center; width: 10px; height: 10px; margin-right: 3px; border-radius: 50%; background: #b9bdc1; }
.recording-clock > span.live { background: #c5584b; box-shadow: 0 0 0 6px rgba(197,88,75,.1); animation: live-pulse 1.5s ease-in-out infinite; }
@keyframes live-pulse { 50% { opacity: .55; } }
.recording-clock time { font: 500 44px/1 'DM Mono', monospace; letter-spacing: -.06em; }
.recording-clock small { color: #969aa2; font: 10px 'DM Mono', monospace; }
.recording-status-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.recording-status-grid > span { display: grid; grid-template-columns: 9px auto; column-gap: 8px; padding: 10px 12px; border: 1px solid #e0e1de; border-radius: 10px; background: rgba(255,255,255,.55); }
.recording-status-grid i { width: 7px; height: 7px; align-self: center; border-radius: 50%; background: #aaa; }
.recording-status-grid b { font-size: 11px; }.recording-status-grid small { grid-column: 2; overflow: hidden; color: #858992; font-size: 9px; white-space: nowrap; text-overflow: ellipsis; }
.recording-status-grid .state-connected { background: #5c8b74; }.recording-status-grid .state-connecting, .recording-status-grid .state-reconnecting { background: #c1944c; }.recording-status-grid .state-degraded { background: #bd6255; }
.recording-meter { height: 40px; display: flex; align-items: center; justify-content: center; gap: 4px; margin: 0 28px 14px; overflow: hidden; }
.recording-meter i { width: 3px; border-radius: 3px; background: #d8d9d5; transition: background .1s, transform .1s; }.recording-meter i.active { background: #66729f; transform: scaleY(1.25); }
.realtime-caption-panel { flex: 1; min-height: 350px; display: flex; flex-direction: column; margin: 0 28px; overflow: hidden; border: 1px solid #dcddd8; border-radius: 16px; background: #fffefa; }
.realtime-caption-panel header { display: flex; align-items: end; justify-content: space-between; padding: 16px 18px; border-bottom: 1px solid #e7e7e2; }
.realtime-caption-panel h3 { margin: 0; font: 700 20px 'Noto Serif SC', serif; }.realtime-caption-panel header > span { color: #9699a0; font-size: 10px; }
.realtime-caption-scroll { flex: 1; max-height: 400px; overflow: auto; padding: 18px; scroll-behavior: smooth; }
.caption-placeholder { margin: 75px auto; max-width: 360px; color: #aaadaf; font-size: 13px; line-height: 1.8; text-align: center; }
.caption-final, .caption-interim { display: grid; grid-template-columns: 46px 1fr; gap: 10px; margin: 0 0 14px; font-size: 15px; line-height: 1.75; }
.caption-final time, .caption-interim time { padding-top: 5px; color: #a0a3a7; font: 9px 'DM Mono', monospace; }.caption-final span { color: #303641; }
.caption-interim span { color: #9c9fa4; }.caption-interim { border-left: 2px solid #d5d7df; padding-left: 9px; }
.realtime-caption-panel footer { padding: 8px 18px; border-top: 1px solid #eee3ce; color: #8b7448; background: #fff9ed; font-size: 10px; }
.recording-live-alert { margin: 12px 28px 0; width: auto; }
.recording-actions { min-height: 82px; display: flex; align-items: center; justify-content: flex-end; gap: 10px; padding: 16px 28px; }
.recording-actions button { min-height: 42px; border-radius: 10px; padding: 0 16px; font-weight: 650; cursor: pointer; }
.recording-discard { margin-right: auto; border: 0; color: #99594f; background: transparent; }.recording-secondary { border: 1px solid #d7d8d5; color: #555c68; background: #fff; }
.recording-finish { display: inline-flex; align-items: center; gap: 10px; border: 0; color: #fff; background: #2b334a; box-shadow: 0 8px 18px rgba(43,51,74,.16); }.recording-finish span { font-size: 10px; }
.recording-actions button:disabled { opacity: .45; cursor: not-allowed; }
.recording-processing { display: flex; align-items: center; gap: 12px; margin-left: auto; min-width: 275px; padding: 9px 13px; border: 1px solid #dddeda; border-radius: 11px; background: #fff; }
.recording-processing > span { width: 18px; height: 18px; border: 2px solid #d5d7de; border-top-color: #59669f; border-radius: 50%; animation: recording-orbit .8s linear infinite; }.recording-processing p { display: grid; margin: 0; }.recording-processing b { font-size: 11px; }.recording-processing small { color: #90949b; font-size: 9px; }
@media (max-width: 700px) {
  .realtime-recording-backdrop { padding: 0; }.realtime-recording-workbench { min-height: 100vh; border-radius: 0; }
  .recording-preflight { padding: 34px 22px; }.recording-options { grid-template-columns: 1fr; }.recording-live-head { grid-template-columns: 1fr; gap: 16px; }
  .recording-clock { justify-content: center; }.recording-status-grid { grid-template-columns: 1fr; }.realtime-caption-panel { margin: 0 14px; }.recording-actions { padding: 14px; flex-wrap: wrap; }
}
</style>
