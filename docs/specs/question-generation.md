# Spec: 出题引擎（core/QuestionGenerationService）

> 契约立于 2026-10-25，v1 迭代动工前（AGENTS.md 红线 6）。prompt 模板与题目构成为红线 3 保护对象，2026-10-25 经 plan mode 人类确认，对应决策 D009。
> v2 修订（2026-10-25，经 plan mode 人类确认，对应决策 D012）：新增 `generate(jdText, resumeContext)` 重载，模板增加简历片段分支；**构成 10 题 6:2:2、难度 4:4:2、System prompt、temperature 与 D009 定稿完全不变**；resumeContext 为空时 User prompt 与 v1 **逐字节一致**（回归测试断言）。

## 目的

岗位 JD 文本（+ 可选简历检索片段，v2）→ 一场结构化的 10 题模拟面试（八股 / 项目深挖 / 场景设计）。

## 输入 / 输出边界

- 输入：jdText（非空白且 ≥ 20 字符，由 API 层校验）；resumeContext（v2，可空——空 = 无简历，走 v1 分支）
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

### Prompt 模板（红线 3 定稿；v2 分支经 D012 确认，改动需重新走确认流程）

- **System（v1/v2 共用，不变）**：你是一名资深的技术面试官，有十年以上后端与系统设计面试经验。你的任务是根据候选人目标岗位的 JD，出一场高质量的模拟面试。
- **User · 无简历（v1 原版，逐字节保持）**：JD 全文 + 以下规则——
  1. 先从 JD 中提取核心技术要求（语言、框架、中间件、领域知识）
  2. 出 **10 题：6 题八股（FUNDAMENTAL，基础知识）+ 2 题项目深挖（PROJECT，围绕该岗位典型项目经历的开放追问）+ 2 题场景设计（SCENARIO，真实工作场景问题）**，覆盖 JD 核心技术点
  3. 难度比例：基础:中等:较难 ≈ 4:4:2
  4. 每题附 knowledgePoint 知识点标签（如 "MySQL 索引"、"并发编程"）
  5. 只输出严格 JSON，无任何其他文字或 markdown 围栏：`{"questions":[{"index":1,"type":"FUNDAMENTAL|PROJECT|SCENARIO","difficulty":"BASIC|MEDIUM|HARD","knowledgePoint":"...","content":"..."}]}`
- **User · 有简历（v2，D012 定稿）**：

```
【岗位 JD】
{jdText}

【候选人简历（检索片段）】
{resumeContext——按余弦相似度选出的 top-6 块，格式「### {节标题}\n{块文本}」逐块连接}

出题规则：
1. 先从 JD 中提取核心技术要求（语言、框架、中间件、领域知识）
2. 出 10 题：6 题八股（FUNDAMENTAL，基础知识）+ 2 题项目深挖（PROJECT）+ 2 题场景设计（SCENARIO，真实工作场景问题），覆盖 JD 核心技术点
3. 2 道项目深挖题必须围绕【候选人简历】中出现过的真实项目出题，追问该项目中的技术决策、难点与权衡；若简历片段与 JD 技术栈交集不足，则围绕简历中最有深度的项目出题
4. 八股题优先覆盖 JD 技术要求与候选人简历技能的交集
5. 难度比例：基础:中等:较难 ≈ 4:4:2
6. 每题附 knowledgePoint 知识点标签（如 "MySQL 索引"、"并发编程"）
7. 只输出严格 JSON，无任何其他文字或 markdown 围栏：{"questions":[{"index":1,"type":"FUNDAMENTAL|PROJECT|SCENARIO","difficulty":"BASIC|MEDIUM|HARD","knowledgePoint":"...","content":"..."}]}
```

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
- [ ] v2：无简历时 User prompt 与 v1 原版**逐字节一致**（回归，保 v1/v2 对比实验口径干净）
- [ ] v2：有简历时 prompt 含【候选人简历（检索片段）】段与规则 3/4 原文
- [ ] v2：resumeContext 空白 → 走 v1 分支（不抛错）
