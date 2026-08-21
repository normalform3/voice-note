import { onBeforeUnmount, ref } from 'vue'

interface BrowserSpeechRecognitionAlternative {
  transcript: string
}

interface BrowserSpeechRecognitionResult {
  readonly isFinal: boolean
  readonly length: number
  readonly [index: number]: BrowserSpeechRecognitionAlternative
}

interface BrowserSpeechRecognitionResultList {
  readonly length: number
  readonly [index: number]: BrowserSpeechRecognitionResult
}

interface BrowserSpeechRecognitionEvent extends Event {
  readonly results: BrowserSpeechRecognitionResultList
}

interface BrowserSpeechRecognitionErrorEvent extends Event {
  readonly error: string
  readonly message?: string
}

interface BrowserSpeechRecognition {
  continuous: boolean
  interimResults: boolean
  lang: string
  maxAlternatives: number
  onstart: ((event: Event) => void) | null
  onresult: ((event: BrowserSpeechRecognitionEvent) => void) | null
  onerror: ((event: BrowserSpeechRecognitionErrorEvent) => void) | null
  onend: ((event: Event) => void) | null
  start(): void
  stop(): void
  abort(): void
}

interface BrowserSpeechRecognitionConstructor {
  new(): BrowserSpeechRecognition
}

type SpeechRecognitionWindow = Window & {
  SpeechRecognition?: BrowserSpeechRecognitionConstructor
  webkitSpeechRecognition?: BrowserSpeechRecognitionConstructor
}

export type SpeechRecognitionFailure = {
  code: string
  message: string
}

type SpeechRecognitionCallbacks = {
  onStart?: () => void
  onFinal: (transcript: string, utteranceId: string) => void
  onNoSpeech: () => void
  onEndWithoutResult: () => void
  onError: (failure: SpeechRecognitionFailure) => void
}

function constructorForBrowser() {
  if (typeof window === 'undefined') return undefined
  const browser = window as SpeechRecognitionWindow
  return browser.SpeechRecognition || browser.webkitSpeechRecognition
}

export function isSpeechRecognitionSupported() {
  return Boolean(constructorForBrowser())
}

function utteranceId(sequence: number) {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  return `voice-${Date.now()}-${sequence}`
}

function failureMessage(code: string, providerMessage?: string): SpeechRecognitionFailure {
  const message = ({
    'not-allowed': '麦克风权限被拒绝。请在浏览器地址栏中允许麦克风后重试。',
    'service-not-allowed': '浏览器不允许使用语音识别服务，请检查站点权限。',
    'audio-capture': '没有检测到可用的麦克风，请检查设备连接。',
    network: '语音识别服务暂时无法连接，请检查网络后重试。',
    'language-not-supported': '当前浏览器不支持所选语音识别语言。'
  } as Record<string, string>)[code]
  return { code, message: message || providerMessage || '语音识别发生错误，请暂停后重试。' }
}

export function useSpeechRecognition(callbacks: SpeechRecognitionCallbacks) {
  const supported = isSpeechRecognitionSupported()
  const listening = ref(false)
  const interimTranscript = ref('')
  const finalTranscript = ref('')
  let recognition: BrowserSpeechRecognition | null = null
  let sequence = 0

  function start() {
    const Recognition = constructorForBrowser()
    if (!Recognition) {
      callbacks.onError({ code: 'unsupported', message: '当前浏览器不支持语音识别，请使用桌面版 Chrome 或 Edge。' })
      return false
    }
    if (recognition) return false

    const instance = new Recognition()
    const currentUtteranceId = utteranceId(++sequence)
    let submitted = false
    let expectedStop = false
    let noSpeech = false
    let fatalError = false
    interimTranscript.value = ''
    finalTranscript.value = ''
    recognition = instance

    instance.continuous = false
    instance.interimResults = true
    instance.maxAlternatives = 1
    instance.lang = typeof navigator !== 'undefined' && navigator.language ? navigator.language : 'zh-CN'
    instance.onstart = () => {
      if (recognition !== instance) return
      listening.value = true
      callbacks.onStart?.()
    }
    instance.onresult = event => {
      if (recognition !== instance) return
      const settled: string[] = []
      const interim: string[] = []
      for (let index = 0; index < event.results.length; index++) {
        const result = event.results[index]
        const transcript = result?.[0]?.transcript?.trim()
        if (!transcript) continue
        if (result.isFinal) settled.push(transcript)
        else interim.push(transcript)
      }
      interimTranscript.value = interim.join(' ').trim()
      const final = settled.join(' ').trim()
      if (!final || submitted) return
      submitted = true
      expectedStop = true
      finalTranscript.value = final
      interimTranscript.value = ''
      callbacks.onFinal(final, currentUtteranceId)
      try { instance.stop() } catch { /* The browser may already be ending the utterance. */ }
    }
    instance.onerror = event => {
      if (recognition !== instance) return
      if (event.error === 'aborted' && expectedStop) return
      if (event.error === 'no-speech') {
        noSpeech = true
        return
      }
      fatalError = true
      callbacks.onError(failureMessage(event.error, event.message))
    }
    instance.onend = () => {
      if (recognition !== instance) return
      recognition = null
      listening.value = false
      if (noSpeech) callbacks.onNoSpeech()
      else if (!submitted && !expectedStop && !fatalError) callbacks.onEndWithoutResult()
    }

    try {
      instance.start()
      return true
    } catch (error) {
      recognition = null
      listening.value = false
      callbacks.onError(failureMessage('start-failed', error instanceof Error ? error.message : undefined))
      return false
    }
  }

  function stop() {
    const instance = recognition
    recognition = null
    listening.value = false
    interimTranscript.value = ''
    if (!instance) return
    try { instance.abort() } catch { /* Recognition is already stopped. */ }
  }

  onBeforeUnmount(stop)

  return { supported, listening, interimTranscript, finalTranscript, start, stop }
}
