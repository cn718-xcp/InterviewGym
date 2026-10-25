# Spec: 评估引擎（core/EvaluationService）

> 契约立于 2026-10-25，v1 迭代动工前（AGENTS.md 红线 6）。评分锚点与聚合规则为红线 3 保护对象，2026-10-25 经 plan mode 人类确认，对应决策 D009。

## 目的

一场答完的模拟面试（全部问答）→ 评估报告：逐题得分、总评、薄弱知识点、复习建议。

## 输入 / 输出边界

- 输入：`InterviewSession`（status=ACTIVE，且**全部题目已作答**）
- 输出：`EvaluationReport`（perQuestion 每题 score/strength/improvement、summary、weakPoints、reviewAdvice）；averageScore 由**代码**计算
- 不变式（必须永远成立的事实）：
  - **averageScore = 逐题算术平均（一位小数），永远由代码计算，不信 LLM 算术**
  - **得分 ≤ 2 的题目，其 knowledgePoint 必出现在 weakPoints 中**——LLM 漏列时代码强制补入（兜底规则）
  - 每题 score ∈ [1,5] 整数；questionId 与会话题目一一对应，不得多、不得漏
  - 答案与评语内容永不写日志（红线 2）

## 行为流程 / 状态机

```
评估(session)
  ① 校验：全部题目已作答，否则抛 IllegalStateException（API 层转 409）
  ② 构造 prompt（JD + 题目含知识点标签 + 逐题答案 + 评分锚点）
  ③ LlmClient.chat → 解析校验（score 范围、questionId 匹配、字段非空）
  ④ 失败 → 修复重试 1 次 → 仍失败抛 LlmOutputParseException
  ⑤ 聚合（代码）：averageScore 均值一位小数；薄弱点兜底补全
  ⑥ 会话置 EVALUATED，报告存入 store
```

### 评分锚点（红线 3 定稿，改动需重新走确认流程）

| 分 | 锚点 |
|---|---|
| 5 | 理解透彻，有深度或实战洞察 |
| 4 | 正确且完整 |
| 3 | 基本正确，但有明显遗漏 |
| 2 | 部分正确，存在错误 |
| 1 | 基本错误或未答 |

### Prompt 要点（红线 3 定稿）

- **System**：你是一名严格但公正的技术面试评估官。
- **User**：JD + 逐题（题面 + 知识点标签 + 候选人答案）+ 规则——① 逐题按上表锚点打 1-5 分，`strength` 一句亮点、`improvement` 一句不足（指出错误处的正确答案要点）；② `summary` 总评一段；③ `weakPoints`：薄弱知识点列表（附原因，优先得分低的）；④ `reviewAdvice`：按优先级 2-3 条复习建议；⑤ 只输出严格 JSON。

## 失败与降级

- LLM 超时 / 不可用时：`LlmException` → API 层 502
- 解析失败：修复重试 1 次 → 仍失败 `LlmOutputParseException` → 502；**会话保持 ACTIVE**，可再次评估
- 输入非法时：未答完 → 409；会话已 EVALUATED 再评估 → 409（不重复计费）

## 🚫 禁止 AI 自由发挥的区域

- 评分锚点文本、聚合规则（均值/兜底补全）——红线 3
- score 范围校验（1-5）不得放宽；questionId 必须严格匹配
- 已评估会话不得覆盖重评（防刷分 / 防重复计费）

## 验收测试

- [x] 正常：逐题得分齐全，averageScore 等于手算均值
- [x] LLM 漏列低分知识点 → 代码兜底补入 weakPoints
- [x] score 越界（如 6）→ 触发重试
- [x] questionId 不匹配 → 触发重试
- [x] 有未答题 → IllegalStateException（API 层 409）
- [x] 重复评估已 EVALUATED 会话 → 409
