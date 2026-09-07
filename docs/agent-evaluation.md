# Agent 评测

仓库内的 `backend/src/test/resources/agent-evaluation-set.jsonl` 定义了首批单文档、多文档、相对日期、拒答、越权、未索引当前文档、跨会话记忆召回和跨用户记忆零泄漏场景。它只保存问题、脱敏 setup 与期望，不包含真实转写或用户数据。

在目标环境执行这些场景后，将脱敏结果导出为 JSONL。基础字段保持兼容；Chunk v4 评测应在每行额外记录有序的 Chunk 和场景维度：

- `relevantDocumentIds`、`retrievedDocumentIds`、`scopeDocumentIds`、`citedDocumentIds`
- `relevantSegmentIds`
- `retrievedChunks`：按最终返回顺序记录 `chunkId`、`segmentIds`、`oversized`；最多前 8 个用于 Segment Recall@8、MRR@8 和 nDCG@8
- `queryType`：例如 `FACT`、`CROSS_CHUNK`、`MULTI_DOCUMENT`、`ASR_NOISE`
- `sceneProfile`：`SHORT_DOCUMENT`、`INTERVIEW_QA`、`MEETING_DISCUSSION`、`MONOLOGUE` 或 `CONVERSATION`
- `latencyMs`
- `citationCount`、`validCitationCount`
- `shouldRefuse`、`didRefuse`、`budgetExhausted`

运行：

```bash
python3 scripts/evaluate_agent_results.py exported-results.jsonl
```

脚本记录文档 Recall、文档覆盖率、Segment Recall@8、MRR@8、nDCG@8、上下文精度、重复 Segment 比例、引用有效率、oversized Chunk 比例和 P95 延迟，并按 `sceneProfile` 与 `queryType` 分组。没有 Chunk 级标注时，对应指标输出 `null`。

v3/v4 应使用同一批脱敏音频并行建索引和导出结果。v4 上线门槛：引用有效率保持 `1.0`；任一场景 Segment Recall@8 下降不超过 2 个百分点；整体至少提升 5 个百分点，`CROSS_CHUNK` 至少提升 10 个百分点；P95 检索延迟增幅不超过 20%。仓库不伪造未在目标模型和真实 Qdrant 上复现的分数。

建议场景集至少覆盖：长面试回答、会议决策与行动负责人、多人争论、单人讲座、短语音备忘、ASR 噪声、跨 Chunk 指代和多文档比较。原始音频、完整转写和私人服务配置不得写入评测文件。
