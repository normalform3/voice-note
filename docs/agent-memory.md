# Agent 长短期记忆

## 边界

- 短期记忆属于单个 `agent_conversation`，由滚动摘要和最近 Turn 组成。
- 会话创建后固定文档身份、IANA 时区和 Skill。后续 Run 会重新冻结这些文档当前可用的内容版本。
- 长期候选只读取目标 Turn 的用户原话。助手输出、录音内容、工具输出和模型推断都不能成为候选来源。
- 长期记忆只有在用户确认后才可检索；关闭会话记忆会同时禁用候选提取和 `user_memory_search`。
- MySQL 是权威数据源；Qdrant 的 `voicenote_user_memories` collection 只保存可重建索引。

## 数据流

~~~mermaid
flowchart LR
    Turn["用户 Turn"] --> Run["独立 Agent Run"]
    Conversation["会话摘要 + 最近 Turn"] --> Run
    Run -->|"结束后异步"| Extract["候选提取与确定性过滤"]
    Extract --> Pending["待确认候选"]
    Pending -->|"用户确认或编辑确认"| Mysql["MySQL 记忆版本"]
    Mysql --> Outbox["Outbox / Inbox"]
    Outbox --> Qdrant["Qdrant Dense + BM25"]
    Run -->|"按需只读"| Search["user_memory_search"]
    Search --> Qdrant
    Qdrant --> Verify["按 owner、状态、当前版本回查 MySQL"]
    Verify --> Run
~~~

同一会话的 Turn 创建会锁定会话行；已有未结束 Run 时返回冲突，避免追问乱序。Checkpoint 仍只恢复单次 Run。Replay 保留原开关和 Checkpoint 中的上下文快照，但不会创建会话 Turn 或候选。

## 候选与确认

允许类别：`PROFILE`、`PREFERENCE`、`WORK_STYLE`、`PROJECT_CONTEXT`、`LONG_TERM_GOAL`。

服务端会再次验证：

- `sourceExcerpt` 必须逐字存在于用户消息并包含第一人称表述；
- 置信度不低于配置阈值，每轮不超过配置上限；
- 过滤秘密形态、敏感内容和第三方隐私；
- 相同语义键和内容不会重复进入待确认列表；冲突内容显示旧值与新值；
- 每用户待确认候选和有效记忆分别受硬上限约束。

编辑已确认记忆会创建新版本并重建索引。删除会先硬删除 MySQL 内容和候选，再通过持久化删除任务清理 Qdrant；清理延迟期间，检索也会因 MySQL 复核失败而丢弃旧点。

## API

- `/api/agent-conversations`：创建、分页读取、更新和删除会话。
- `/api/agent-conversations/{id}/turns`：使用 `Idempotency-Key` 创建后续 Turn。
- `/api/user-memory-candidates`：读取、确认、拒绝或重试候选。
- `/api/user-memories`：读取、编辑或删除已确认记忆。

所有资源先绑定认证用户，跨用户访问统一表现为 404。删除会话会删除消息、Run、Checkpoint、证据与轨迹，但已确认的长期记忆继续保留。

## 配置与验证

`VOICENOTE_MEMORY_ENABLED` 默认 `false`。其余默认值位于 `app.memory`：最近 6 个 Turn、16,000 字符上下文、4,000 字符摘要、0.75 候选阈值、200 个待确认候选、500 条有效记忆和 8 条检索结果。

~~~bash
cd backend
mvn test

cd ../frontend
npm run build
~~~

候选提取和会话摘要 Prompt 分别保存在 `prompts/user-memory-extraction-v1.md` 与 `prompts/conversation-summary-v1.md`。处理状态记录输入哈希、Prompt 版本、模型、失败码和耗时，不保存隐式推理。
