class VoiceNotePcmRecorder extends AudioWorkletProcessor {
  constructor() {
    super()
    this.frameSize = Math.max(1, Math.round(sampleRate / 10))
    this.pending = new Float32Array(this.frameSize)
    this.pendingLength = 0
  }

  process(inputs) {
    const input = inputs[0]?.[0]
    if (!input) return true
    let sourceOffset = 0
    while (sourceOffset < input.length) {
      const available = this.frameSize - this.pendingLength
      const count = Math.min(available, input.length - sourceOffset)
      this.pending.set(input.subarray(sourceOffset, sourceOffset + count), this.pendingLength)
      this.pendingLength += count
      sourceOffset += count
      if (this.pendingLength === this.frameSize) this.flush()
    }
    return true
  }

  flush() {
    const pcm = new Int16Array(this.frameSize)
    let peak = 0
    for (let index = 0; index < this.frameSize; index += 1) {
      const sample = Math.max(-1, Math.min(1, this.pending[index]))
      peak = Math.max(peak, Math.abs(sample))
      pcm[index] = sample < 0 ? Math.round(sample * 0x8000) : Math.round(sample * 0x7fff)
    }
    this.port.postMessage({ type: 'pcm', buffer: pcm.buffer, level: peak }, [pcm.buffer])
    this.pendingLength = 0
  }
}

registerProcessor('voicenote-pcm-recorder', VoiceNotePcmRecorder)
