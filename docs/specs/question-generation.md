# Spec: 出题引擎（core/QuestionGenerationService）

> 契约立于 2026-10-25，v1 迭代动工前（AGENTS.md 红线 6）。prompt 模板与题目构成为红线 3 保护对象，2026-10-25 经 plan mode 人类确认，对应决策 D009。

## 目的

岗位 JD 文本 → 一场结构化的 10 题模拟面试（八股 / 项目深挖 / 场景设计）。

## 输入 / 输出边界

- 输入：jdText（非空白且 ≥ 20 字符，由 API 层校验）
- 输出：`List<Question>`，恒为 10 题，每题含 index(1..10)、type、difficulty、knowledgePoint、content，服务端生成唯一 id
- 不变式（必须永远成立的事实）：
  - 题目数量恒为 10，type/difficulty 枚举合法，字段全部非空——不满足即视为解析失败，不得降级放行
  - JD 与生成的题目内容永不写日志（只记 sessionId 与题数，红线 2）
  - JD 只发往所配置 LLM 端点

## 行为流程 / 状态机

```
生成(jdText)
  ① 构造 prompt（模板定稿见下）
  ② LlmClient.chat
  ③ 解析：剥 markdown 围栏 → Jackson → 校验（题数=10、枚举合法、index 连续、字段非空）
  ④ 失败 → 带错误信息修复重试 1 次 → 仍失败抛 LlmOutputParseException
```

### Prompt 模板（红线 3 定稿，改动需重新走确认流程）

- **System**：你是一名资深的技术面试官，有十年以上后端与系统设计面试经验。你的任务是根据候选人目标岗位的 JD，出一场高质量的模拟面试。
- **User**：JD 全文 + 以下规则——
  1. 先从 JD 中提取核心技术要求（语言、框架、中间件、领域知识）
  2. 出 **10 题：6 题八股（FUNDAMENTAL，基础知识）+ 2 题项目深挖（PROJECT，围绕该岗位典型项目经历的开放追问）+ 2 题场景设计（SCENARIO，真实工作场景问题）**，覆盖 JD 核心技术点
  3. 难度比例：基础:中等:较难 ≈ 4:4:2
  4. 每题附 knowledgePoint 知识点标签（如 "MySQL 索引"、"并发编程"）
  5. 只输出严格 JSON，无任何其他文字或 markdown 围栏：`{"questions":[{"index":1,"type":"FUNDAMENTAL|PROJECT|SCENARIO","difficulty":"BASIC|MEDIUM|HARD","knowledgePoint":"...","content":"..."}]}`

### 修复重试的 User 追加段

「你上一次的输出不是合法 JSON 或不符合 schema（原因：{具体原因}）。请严格按 schema 只输出 JSON。」

## 失败与降级

- LLM 超时 / 不可用时：`LlmException` 向上传播 → API 层转 502，提示用户稍后再试
- 解析失败：修复重试 1 次 → 仍失败 `LlmOutputParseException` → 502
- 输入非法时：jdText 空白 / 过短 → API 层 400，不触发 LLM 调用

## 🚫 禁止 AI 自由发挥的区域

- System/User 模板文本与题目构成比例（6/2/2）、难度比例（4:4:2）——红线 3
- 解析失败的校验规则（题数、枚举、index 连续性）不得放宽
- temperature=0.3 不得调高

## 验收测试

- [x] 正常：stub LLM 返回合法 JSON → 10 题、id 唯一、type 枚举正确
- [x] 带围栏的 JSON（\`\`\`json ... \`\`\`）能正确剥出
- [x] 题数不足 / 枚举非法 → 触发重试；重试合法 → 成功
- [x] 重试仍非法 → 抛 LlmOutputParseException
- [x] prompt 中包含 JD 全文与构成规则（防模板意外丢失）
