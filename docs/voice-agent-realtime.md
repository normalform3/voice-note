# 语音 Agent 实时反馈

语音模式在现有 Agent Run 上增加瞬时进度、可信答案区块和可选朗读。文字聊天仍只消费 Run 的终态结果；实时区块不写入业务表，也不保证刷新后恢复。

## 实时事件

`/api/progress-events` 可能发送两类仅供语音覆盖层使用的事件：

- `agent-run-progress`：固定阶段和用户可见文案，不包含 Prompt、工具参数、原始工具输出或模型推理。
- `agent-answer-block`：从流式 `finalize_answer` 中提取的完整 v3 区块。发布前会校验 Skill 输出类型、字段与大小限制，并确认全部 `sourceRef` 来自本轮证据账本。

SSE 断线后，前端丢弃瞬时区块并重新读取当前 Run；终态答案仍以完整 `finalize_answer` 的校验、持久化结果为准。

## 可选 DashScope TTS

TTS 默认关闭。只有下列配置完整时，`GET /api/agent-runs/capabilities` 才返回 `ttsEnabled: true`：

```text
VOICENOTE_TTS_ENABLED=true
DASHSCOPE_ENABLED=true
DASHSCOPE_API_KEY=<api-key>
DASHSCOPE_TTS_MODEL=qwen3-tts-flash-realtime
DASHSCOPE_TTS_VOICE=Cherry
DASHSCOPE_TTS_WS_URL=wss://dashscope.aliyuncs.com/api-ws/v1/realtime
```

启用后，前端把确定性生成的口语摘要切成不超过约 180 字的句段，串行调用受 JWT 保护的 `POST /api/voice/tts`。接口返回 24kHz、16-bit、单声道 PCM；音频只在内存中传输和播放。单次请求最多 500 字，单轮答案最多朗读 600 字。

朗读失败只会让当前轮降级为文字反馈，不会改变 Agent Run 的状态。用户打断朗读后可以立即说出下一轮问题；如果当前 Run 尚未终止，识别文字会暂存到当前 Run 成功、失败或超时后再提交。

## 手动验证

分别在 TTS 关闭和开启的环境用桌面 Chrome 或 Edge 验证连续三轮语音会话。重点检查权限拒绝、停顿提交、进度顺序、区块先于终态出现、SSE 重连、朗读失败、打断暂存、暂停和退出。TTS 关闭时，浏览器不应出现 `/api/voice/tts` 请求、朗读状态或播放控件。
