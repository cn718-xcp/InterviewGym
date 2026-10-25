# Spec: 持久化会话存储（store/InterviewStore + SqliteInterviewStore）

> 契约立于 2026-10-25，v2 迭代动工前（AGENTS.md 红线 6）。对应决策 D010（SQLite）。取代 v1 的 `in-memory-store.md`（该 spec 随本文立契即标记 superseded）。

## 目的

v2 起练习记录与简历需跨天留存（D004 预留的选型时机），以 SQLite 单文件库替换 v1 内存存储；接口形状沿用 v1 spec 的承诺——存储无关，core/api 只依赖接口。

## 输入 / 输出边界

- 输入：`InterviewSession` / `EvaluationReport`（不可变对象，整体替换写入，payload 以 JSON 文档式落库）
- 输出：`Optional<InterviewSession>` / `Optional<EvaluationReport>` / 会话摘要列表
- 不变式（必须永远成立的事实）：
  - 数据只落 `backend/data/interviewgym.db` 一个文件（.gitignore 已挡 `backend/data/`、`*.db`），不写任何其他位置（红线 2）
  - 会话对象不可变：更新 = 复制新对象整体替换，不做字段级原地修改
  - `updateSession` 的原子性语义与 v1 等价：并发双评估只有一个成功，另一个 409（AI-FAILURES #6 防护不得回归）
  - 存储层不写内容日志（只允许 id 级别，红线 2）

## 行为流程 / 状态机

```
saveSession(session)          → INSERT OR REPLACE（payload = 整个 session 的 JSON）
findSession(id)               → Optional（payload 反序列化）
updateSession(id, fn)         → 事务内：读 → fn 应用 → UPDATE ... WHERE id=? AND status=<读到的旧值>（乐观锁）
                                 影响行数=0 → 并发冲突 → IllegalStateException → API 层 409
saveReport(report)            → INSERT OR REPLACE
findReport(sessionId)         → Optional
listSessions()                → 摘要列表（id、status、createdAt、jdText 截断、resumeAssisted），按 createdAt 倒序
```

- 表结构（`schema.sql`，幂等 DDL，启动自动执行）：
  - `sessions(id TEXT PK, status TEXT, created_at TEXT, resume_assisted INT, payload TEXT)`
  - `reports(session_id TEXT PK, payload TEXT)`
- 连接：Hikari `maximum-pool-size: 1`（SQLite 单写者，避免 database locked），短事务
- v1 内存数据不迁移（v1 明示重启即清，用户已知）

## 失败与降级

- 库文件/目录不可写：启动即失败（快速失败，不静默降级回内存——那会造成数据丢失假象）
- 输入非法时：null 入参 → `NullPointerException`；未知 id → 空 Optional → API 层 404
- 乐观锁冲突 → `IllegalStateException` → API 层 409

## 🚫 禁止 AI 自由发挥的区域

- 不得记录 JD / 简历 / 答案 / 报告内容到日志（红线 2）
- 不得引入 ORM / Spring Data JPA（D010 选的是 JdbcTemplate 文档式存储；加 ORM 是新决策）
- 接口签名保持存储无关（参数返回用域对象），不得让 SQL 类型漏进 core/api
- `updateSession` 乐观锁语义不得简化为先读后写的两步裸更新

## 验收测试

- [ ] 保存后可按 id 查回，字段一致（真实 SQLite 临时库跑，非 mock）
- [ ] 整体替换生效（同 id 二次 save 后查回新对象）
- [ ] 未知 id 返回空 Optional
- [ ] 报告与会话独立存取
- [ ] updateSession 并发冲突：两个线程同 id 同旧状态并发更新，恰一个成功
- [ ] listSessions 倒序、含摘要字段
- [ ] 重启"进程"（新建同路径库连接）后数据仍在（跨天留存的本质验证）
