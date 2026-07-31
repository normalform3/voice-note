import axios from 'axios'

export const api = axios.create({ baseURL: '/api' })
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('echotrace_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

export type Task = { id: string; status: string; currentAttemptNumber: number; transcriptVersion: number; failureCode?: string; failureMessage?: string }
export type Segment = { id: string; index: number; speaker?: string; startMs: number; endMs: number; text: string }
export type Analysis = { run: { id: string; status: string; callsUsed: number; maxCalls: number; resultDocument?: string }; evidence: { resultPath: string; segmentId: string }[] }

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
