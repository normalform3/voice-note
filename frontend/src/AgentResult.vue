<script setup lang="ts">
import { type AgentEvidence, type AgentResult, type ResultCitation, type ResultItem, timecode } from './api'

const props = defineProps<{ result: AgentResult; evidence: AgentEvidence[] }>()
const emit = defineEmits<{ evidence: [citation: ResultCitation] }>()

const blockLabels: Record<string, string> = {
  SUMMARY: '总结', FINDINGS: '关键发现', DECISIONS: '决策', ACTION_ITEMS: '行动项', OPEN_QUESTIONS: '未决问题',
  QA_REVIEW: '问答复盘', ASSESSMENT_MATRIX: '评价矩阵', COMPARISON_TABLE: '比较表'
}
function citationLabel(citation: ResultCitation) {
  const evidence = props.evidence.find(item => citation.sourceRef ? item.sourceRef === citation.sourceRef : item.chunkId === citation.chunkId && item.segmentId === citation.segmentId)
  if (!evidence) return '原文证据 ↗'
  if (evidence.sourceKind === 'EXTERNAL') return `${evidence.externalLabel || '外部来源'} ↗`
  if (evidence.sourceKind === 'DOCUMENT_METADATA') return `${evidence.topic || '文档元数据'} · ${evidence.text || '范围信息'}`
  return `${evidence.topic || '原文'} · ${evidence.speaker || evidence.speakerId || '说话人'} · ${timecode(evidence.startMs || 0)} ↗`
}
function itemTitle(item: ResultItem, index: number) { return item.title || item.question || item.dimension || item.label || `条目 ${index + 1}` }
function itemBody(item: ResultItem) { return item.content || item.answer || item.assessment || '' }
function itemMeta(item: ResultItem) {
  return [item.status, item.owner ? `负责人 ${item.owner}` : '', item.dueAt ? `期限 ${item.dueAt}` : '', item.followUp ? `追问 ${item.followUp}` : ''].filter(Boolean).join(' · ')
}
</script>

<template>
  <template v-if="result.resultSchemaVersion === 2 && result.blocks">
    <article v-for="(block, blockIndex) in result.blocks" :key="`${block.type}-${blockIndex}`" class="agent-result-block" :class="`block-${block.type.toLowerCase()}`">
      <header><b>{{ block.title || blockLabels[block.type] || block.type }}</b><small>{{ block.type }}</small></header>
      <p v-if="block.content" class="block-content">{{ block.content }}</p>
      <button v-for="citation in block.evidence || []" :key="citation.sourceRef || citation.segmentId" class="citation" type="button" @click="emit('evidence', citation)">{{ citationLabel(citation) }}</button>

      <div v-if="block.items?.length" class="block-items">
        <section v-for="(item, index) in block.items" :key="index" class="block-item">
          <div><b>{{ itemTitle(item, index) }}</b><em v-if="item.status || item.assessment">{{ item.status || item.assessment }}</em></div>
          <p v-if="itemBody(item)">{{ itemBody(item) }}</p><small v-if="itemMeta(item)">{{ itemMeta(item) }}</small>
          <button v-for="citation in item.evidence || []" :key="citation.sourceRef || citation.segmentId" class="citation" type="button" @click="emit('evidence', citation)">{{ citationLabel(citation) }}</button>
        </section>
      </div>

      <div v-if="block.columns?.length && block.rows" class="comparison-scroll">
        <table><thead><tr><th v-for="column in block.columns" :key="column">{{ column }}</th><th>证据</th></tr></thead>
          <tbody><tr v-for="(row, index) in block.rows" :key="index"><td>{{ row.label || row.title || `对象 ${index + 1}` }}</td><td v-for="(value, valueIndex) in row.values || []" :key="valueIndex">{{ value }}</td><td><button v-for="citation in row.evidence || []" :key="citation.sourceRef || citation.segmentId" class="citation compact" type="button" @click="emit('evidence', citation)">原文 ↗</button></td></tr></tbody>
        </table>
      </div>
    </article>
  </template>
  <template v-else>
    <p class="answer">{{ result.answer }}</p>
    <article v-for="(finding, index) in result.findings || []" :key="index" class="finding"><b>{{ finding.title || `发现 ${index + 1}` }}</b><p>{{ finding.content }}</p><button v-for="citation in finding.evidence || []" :key="citation.sourceRef || `${citation.chunkId || 'local'}-${citation.segmentId}`" class="citation" type="button" @click="emit('evidence', citation)">{{ citationLabel(citation) }}</button></article>
  </template>
</template>

<style scoped>
.agent-result-block { padding: 15px 0 2px; border-top: 1px solid #e4e3dc; }.agent-result-block:first-child { margin-top: 14px; }.agent-result-block > header { display: flex; align-items: center; justify-content: space-between; gap: 10px; }.agent-result-block > header b { color: #27313d; font-size: 13px; }.agent-result-block > header small { color: #aeb2b6; font-family: 'DM Mono', monospace; font-size: 8px; }.block-content { margin: 8px 0 0; color: #3a4554; font-family: 'Noto Serif SC', serif; font-size: 14px; font-weight: 600; line-height: 1.8; white-space: pre-line; }
.block-items { display: grid; gap: 10px; margin-top: 10px; }.block-item { border-left: 2px solid #d7dbee; padding: 3px 0 3px 10px; }.block-item > div { display: flex; align-items: start; justify-content: space-between; gap: 8px; }.block-item b { color: #3c4651; font-size: 12px; }.block-item em { flex: 0 0 auto; border-radius: 5px; padding: 2px 5px; color: #59669f; background: #edf0fb; font-family: 'DM Mono', monospace; font-size: 8px; font-style: normal; }.block-item p { margin: 5px 0 0; color: #68717c; font-size: 12px; line-height: 1.7; white-space: pre-line; }.block-item > small { display: block; margin-top: 5px; color: #92979d; font-size: 9px; }
.citation { display: block; margin-top: 7px; border: 1px solid #dde0ed; border-radius: 7px; padding: 5px 7px; color: #59669f; background: #fafbff; font-size: 9px; text-align: left; }.citation:hover { border-color: #c9cee5; background: #edf0fb; }.citation.compact { display: inline-flex; margin: 0; white-space: nowrap; }
.comparison-scroll { margin-top: 10px; overflow-x: auto; }.comparison-scroll table { width: 100%; border-collapse: collapse; color: #586270; font-size: 10px; }.comparison-scroll th, .comparison-scroll td { min-width: 78px; border: 1px solid #e4e3dc; padding: 7px; text-align: left; vertical-align: top; }.comparison-scroll th { color: #59669f; background: #f4f5fb; font-weight: 600; }
.answer { margin: 15px 0 0; color: #3a4554; font-family: 'Noto Serif SC', serif; font-size: 15px; font-weight: 600; line-height: 1.85; }.finding { padding: 14px 0 2px; border-top: 1px solid #e4e3dc; }.finding:first-of-type { margin-top: 15px; }.finding b { color: #27313d; font-size: 14px; }.finding p { margin: 7px 0 0; color: #65707a; font-size: 15px; line-height: 1.85; }
</style>
