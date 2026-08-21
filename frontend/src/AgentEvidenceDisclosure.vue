<script setup lang="ts">
import { computed, ref, useId, watch } from 'vue'
import { timecode, type AgentEvidence, type ResultCitation, type ResultStatement } from './api'

const props = withDefaults(defineProps<{
  statements?: ResultStatement[]
  text?: string
  citations?: ResultCitation[]
  evidence: AgentEvidence[]
  compact?: boolean
  toggleLabel?: string
}>(), {
  statements: () => [],
  text: '',
  citations: () => [],
  compact: false,
  toggleLabel: '查看依据'
})
const emit = defineEmits<{ evidence: [citation: ResultCitation] }>()

type DisplayStatement = { text: string; evidence: ResultCitation[] }
type EvidenceEntry = { key: string; citation: ResultCitation; record?: AgentEvidence; number: number }

const expanded = ref(false)
const panelId = `agent-evidence-${useId().replace(/:/g, '')}`
const displayStatements = computed<DisplayStatement[]>(() => props.statements.length
  ? props.statements.filter(statement => statement.text?.trim()).map(statement => ({ text: statement.text.trim(), evidence: statement.evidence || [] }))
  : props.text.trim() ? [{ text: props.text, evidence: props.citations }] : [])

function citationKey(citation: ResultCitation) {
  return citation.sourceRef || `${citation.chunkId || 'local'}:${citation.segmentId || 'unknown'}`
}
function resolveEvidence(citation: ResultCitation) {
  return props.evidence.find(item => citation.sourceRef
    ? item.sourceRef === citation.sourceRef
    : item.chunkId === citation.chunkId && item.segmentId === citation.segmentId)
}

const evidenceEntries = computed<EvidenceEntry[]>(() => {
  const citations = [...displayStatements.value.flatMap(statement => statement.evidence), ...props.citations]
  const unique = new Map<string, ResultCitation>()
  citations.forEach(citation => unique.has(citationKey(citation)) || unique.set(citationKey(citation), citation))
  return [...unique.entries()].map(([key, citation], index) => ({ key, citation, record: resolveEvidence(citation), number: index + 1 }))
})
const numberByKey = computed(() => new Map(evidenceEntries.value.map(entry => [entry.key, entry.number])))
const hasEvidence = computed(() => evidenceEntries.value.length > 0)
const contentSignature = computed(() => JSON.stringify({
  statements: displayStatements.value.map(statement => ({ text: statement.text, evidence: statement.evidence.map(citationKey) })),
  citations: props.citations.map(citationKey)
}))

watch(contentSignature, () => { expanded.value = false })

function citationNumbers(citations: ResultCitation[]) {
  return [...new Set(citations.map(citation => numberByKey.value.get(citationKey(citation))).filter((value): value is number => value != null))].join(', ')
}
function separator(index: number) {
  if (!index) return ''
  const previous = displayStatements.value[index - 1]?.text || ''
  const current = displayStatements.value[index]?.text || ''
  return /[\u3400-\u9fff][。！？!?；;：:]?$/.test(previous) || /^[\u3400-\u9fff]/.test(current) ? '' : ' '
}
function sourceTitle(entry: EvidenceEntry) {
  const source = entry.record
  if (!source) return '原文证据'
  if (source.sourceKind === 'EXTERNAL') return source.externalLabel || '外部来源'
  if (source.sourceKind === 'USER_MEMORY') return '来自你确认的记忆'
  if (source.sourceKind === 'DOCUMENT_METADATA') return source.topic || source.externalLabel || '文档信息'
  return source.speaker || source.speakerId || '原文说话人'
}
function sourceMeta(entry: EvidenceEntry) {
  const source = entry.record
  if (!source) return ''
  if (source.sourceKind === 'TRANSCRIPT_SEGMENT') return [source.topic, timecode(source.startMs || 0)].filter(Boolean).join(' · ')
  if (source.sourceKind === 'EXTERNAL') return '外部来源'
  if (source.sourceKind === 'USER_MEMORY') return '已确认记忆'
  return '文档元数据'
}
function sourceExcerpt(entry: EvidenceEntry) {
  return entry.record?.text || (entry.record?.sourceKind === 'DOCUMENT_METADATA' ? entry.record.topic : '') || ''
}
function isActionable(entry: EvidenceEntry) {
  const source = entry.record
  return Boolean(source && (
    (source.sourceKind === 'TRANSCRIPT_SEGMENT' && source.segmentId)
    || (source.sourceKind === 'EXTERNAL' && source.externalUrl)
    || source.sourceKind === 'USER_MEMORY'
  ))
}
function openEvidence(entry: EvidenceEntry) {
  if (isActionable(entry)) emit('evidence', entry.citation)
}
</script>

<template>
  <div class="evidence-disclosure" :class="{ compact }">
    <div v-if="displayStatements.length" class="evidence-copy">
      <template v-for="(statement, index) in displayStatements" :key="`${index}-${statement.text}`">
        <span v-if="separator(index)" aria-hidden="true">{{ separator(index) }}</span><span class="evidence-statement">{{ statement.text }}</span><sup v-if="expanded && statement.evidence.length" class="evidence-marker" :aria-label="`这句话对应依据 ${citationNumbers(statement.evidence)}`">[{{ citationNumbers(statement.evidence) }}]</sup>
      </template>
    </div>
    <slot name="after-copy" />
    <button v-if="hasEvidence" class="evidence-toggle" type="button" :aria-expanded="expanded" :aria-controls="panelId" @click="expanded = !expanded">
      <span>{{ expanded ? '收起依据' : `${toggleLabel} · ${evidenceEntries.length}` }}</span><i aria-hidden="true"></i>
    </button>
    <div v-if="expanded && hasEvidence" :id="panelId" class="evidence-panel">
      <component :is="isActionable(entry) ? 'button' : 'div'" v-for="entry in evidenceEntries" :key="entry.key"
        class="evidence-source" :class="{ actionable: isActionable(entry) }" :type="isActionable(entry) ? 'button' : undefined"
        @click="openEvidence(entry)">
        <span class="source-number">{{ entry.number }}</span>
        <span class="source-content"><b>{{ sourceTitle(entry) }}</b><small v-if="sourceMeta(entry)">{{ sourceMeta(entry) }}</small><q v-if="sourceExcerpt(entry)">{{ sourceExcerpt(entry) }}</q></span>
        <span v-if="isActionable(entry)" class="source-arrow" aria-hidden="true">↗</span>
      </component>
    </div>
  </div>
</template>

<style scoped>
.evidence-disclosure { min-width: 0; }
.evidence-copy { white-space: pre-line; }
.evidence-marker { margin-left: .16em; color: #6672a3; font-family: 'DM Mono', monospace; font-size: .58em; font-weight: 700; line-height: 0; vertical-align: super; }
.evidence-toggle { display: inline-flex; align-items: center; gap: 7px; margin-top: 7px; border: 0; padding: 3px 0; color: #858a96; background: transparent; font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .04em; transition: color .18s ease; }
.evidence-toggle::before { width: 13px; height: 1px; background: #cfd2dc; content: ''; transition: width .18s ease, background .18s ease; }
.evidence-toggle i { width: 5px; height: 5px; border-right: 1px solid currentColor; border-bottom: 1px solid currentColor; transform: translateY(-1px) rotate(45deg); transition: transform .18s ease; }
.evidence-toggle[aria-expanded='true'] i { transform: translateY(1px) rotate(225deg); }
.evidence-toggle:hover { color: #59669f; }.evidence-toggle:hover::before { width: 19px; background: #8d97bd; }
.evidence-toggle:focus-visible { border-radius: 3px; outline: 2px solid rgba(89, 102, 159, .28); outline-offset: 3px; }
.evidence-panel { display: grid; margin-top: 8px; border-top: 1px solid #e3e4df; animation: evidence-reveal .18s ease-out both; }
.evidence-source { display: grid; grid-template-columns: 18px minmax(0, 1fr) auto; gap: 8px; width: 100%; border: 0; border-bottom: 1px solid #e8e8e2; padding: 9px 2px; color: #66707b; background: transparent; text-align: left; }
button.evidence-source { cursor: pointer; }.evidence-source.actionable:hover { color: #39445d; background: linear-gradient(90deg, rgba(237, 240, 251, .7), transparent 88%); }
.source-number { display: grid; place-items: center; width: 16px; height: 16px; border: 1px solid #d7d9e3; border-radius: 50%; color: #6975a4; font-family: 'DM Mono', monospace; font-size: 7px; }
.source-content { display: grid; min-width: 0; gap: 2px; }.source-content b { overflow: hidden; color: inherit; font-size: 9px; font-weight: 700; text-overflow: ellipsis; white-space: nowrap; }.source-content small { color: #9a9da2; font-family: 'DM Mono', monospace; font-size: 7px; }.source-content q { display: -webkit-box; overflow: hidden; margin-top: 2px; color: #7a8088; font-size: 9px; font-style: normal; line-height: 1.55; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }.source-content q::before, .source-content q::after { color: #b6b8b8; }
.source-arrow { align-self: center; color: #8b94b7; font-size: 10px; }
.compact .evidence-copy { font-size: inherit; line-height: 1.55; }.compact .evidence-toggle { margin-top: 4px; font-size: 7px; }.compact .evidence-source { grid-template-columns: 15px minmax(0, 1fr) auto; gap: 6px; padding: 7px 1px; }.compact .source-number { width: 14px; height: 14px; }.compact .source-content q { font-size: 8px; }
@keyframes evidence-reveal { from { opacity: 0; transform: translateY(-3px); } to { opacity: 1; transform: translateY(0); } }
@media (prefers-reduced-motion: reduce) { .evidence-panel { animation: none; }.evidence-toggle, .evidence-toggle::before, .evidence-toggle i { transition: none; } }
</style>
