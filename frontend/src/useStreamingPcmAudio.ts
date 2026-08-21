import { ref } from 'vue'

export function splitSpeechText(text: string, maxLength = 180) {
  const normalized = text.replace(/\s+/g, ' ').trim()
  if (!normalized) return []
  const sentences = normalized.split(/(?<=[。！？!?；;])/u).filter(Boolean)
  const chunks: string[] = []
  let current = ''
  for (const sentence of sentences) {
    let remaining = sentence
    while (remaining.length > maxLength) {
      if (current) { chunks.push(current); current = '' }
      chunks.push(remaining.slice(0, maxLength)); remaining = remaining.slice(maxLength)
    }
    if (current && current.length + remaining.length > maxLength) { chunks.push(current); current = '' }
    current += remaining
  }
  if (current) chunks.push(current)
  return chunks
}

export function useStreamingPcmAudio() {
  const playing = ref(false)
  let context: AudioContext | null = null
  let request: AbortController | null = null
  let generation = 0
  const sources = new Set<AudioBufferSourceNode>()

  async function ensureContext() {
    if (!context) context = new AudioContext({ sampleRate: 24000 })
    if (context.state === 'suspended') await context.resume()
    return context
  }

  async function prepare() { await ensureContext() }

  async function speak(text: string) {
    const playbackGeneration = ++generation
    request?.abort()
    request = new AbortController()
    const audioContext = await ensureContext()
    const token = localStorage.getItem('voicenote_token') || ''
    playing.value = true
    let scheduledUntil = audioContext.currentTime
    let carry: number | null = null
    try {
      const response = await fetch('/api/voice/tts', {
        method: 'POST', signal: request.signal,
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ utteranceId: crypto.randomUUID(), text })
      })
      if (!response.ok || !response.body) throw new Error(response.status === 404 ? '当前部署未启用语音朗读。' : '语音朗读服务暂时不可用。')
      const reader = response.body.getReader()
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        if (playbackGeneration !== generation) throw new DOMException('Playback interrupted', 'AbortError')
        let bytes: Uint8Array = value
        if (carry != null) {
          bytes = new Uint8Array(value.length + 1); bytes[0] = carry; bytes.set(value, 1)
        }
        const evenLength: number = bytes.length - (bytes.length % 2)
        carry = evenLength < bytes.length ? bytes[bytes.length - 1] : null
        if (!evenLength) continue
        const samples = new Float32Array(evenLength / 2)
        const view = new DataView(bytes.buffer, bytes.byteOffset, evenLength)
        for (let index = 0; index < samples.length; index++) samples[index] = view.getInt16(index * 2, true) / 32768
        const buffer = audioContext.createBuffer(1, samples.length, 24000)
        buffer.copyToChannel(samples, 0)
        const source = audioContext.createBufferSource()
        source.buffer = buffer; source.connect(audioContext.destination); sources.add(source)
        source.onended = () => sources.delete(source)
        scheduledUntil = Math.max(scheduledUntil, audioContext.currentTime + .025)
        source.start(scheduledUntil); scheduledUntil += buffer.duration
      }
      const remaining = Math.max(0, scheduledUntil - audioContext.currentTime)
      if (remaining) await new Promise<void>((resolve, reject) => {
        const timer = window.setTimeout(resolve, remaining * 1000 + 30)
        request?.signal.addEventListener('abort', () => { window.clearTimeout(timer); reject(new DOMException('Playback interrupted', 'AbortError')) }, { once: true })
      })
    } finally {
      if (playbackGeneration === generation) { playing.value = false; request = null }
    }
  }

  function stop() {
    generation++
    request?.abort(); request = null
    for (const source of sources) { try { source.stop() } catch { /* Already stopped. */ } }
    sources.clear(); playing.value = false
  }

  function dispose() {
    stop()
    if (context) void context.close()
    context = null
  }

  return { playing, prepare, speak, stop, dispose }
}
