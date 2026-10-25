# Spec: 内存会话存储（store/InMemoryInterviewStore）

> **[SUPERSEDED 2026-10-25]** v2 起被 `persistent-store.md`（SQLite，D010）取代，实现随 v2 删除。本文保留作为 v1 契约存档。
>
> 契约立于 2026-10-25，v1 迭代动工前（AGENTS.md 红线 6）。对应决策 D004（v1 无库）。

## 目的

v1 不引入数据库（D004），以进程内存储保管面试会话与评估报告，接口形状为 v2 换持久化实现预留。

## 输入 / 输出边界

- 输入：`InterviewSession`（不可变对象，整体替换写入）、`EvaluationReport`
- 输出：`Optional<InterviewSession>` / `Optional<EvaluationReport>` 查询结果
- 不变式（必须永远成立的事实）：
  - 用户内容（JD、题目、答案、报告）**只存在于内存**，进程重启即清空——README 已向用户告知
  - 永不落盘（.gitignore 已挡 `backend/data/`、`*.db`；红线 2 隐私）
  - 会话对象不可变：更新 = 复制新对象整体替换，不做字段级原地修改
  - 存储层不写内容日志（只允许 id 级别）

## 行为流程 / 状态机

```
saveSession(session)      → 全量替换（key = sessionId UUID）
findSession(id)           → Optional
saveReport(report)        → 全量替换（key = sessionId）
findReport(sessionId)     → Optional
```

- 数据结构：`ConcurrentHashMap<UUID, InterviewSession>` + `ConcurrentHashMap<UUID, EvaluationReport>`
- v1 单用户自用，不设容量上限、不做过期淘汰（重启即清，天然有界）

## 失败与降级

- LLM 超时 / 不可用时：不适用（本层不接触 LLM）
- 输入非法时：null 入参 → `NullPointerException`（快速失败）；未知 id → 空 Optional → API 层 404

## 🚫 禁止 AI 自由发挥的区域

- 不得加任何持久化手段（文件/H2/嵌入式库）——v2 的迭代决策，提前引入违反 D004
- 不得记录会话内容到日志（红线 2）
- 接口签名保持存储无关（参数返回用域对象，不用存储实现类型），v2 换库时 core/api 零改动

## 验收测试

- [x] 保存后可按 id 查回，字段一致
- [x] 整体替换生效（同 id 二次 save 后查回新对象）
- [x] 未知 id 返回空 Optional
- [x] 报告与会话独立存取
