# Spec: LLM 客户端（llm/）

> 契约立于 2026-10-25，v1 迭代动工前（AGENTS.md 红线 6）。对应决策：D005（可配置端点）、D007（自写 RestClient）。

## 目的

把「调用一次 OpenAI 兼容 chat/completions」封装成可靠的单次调用，供 core/ 出题与评估使用，core 不接触 HTTP 细节。

## 输入 / 输出边界

- 输入：system prompt + user prompt（String）；`LlmProperties`（base-url / api-key / model / read-timeout-seconds）
- 输出：LLM 回复的纯文本内容（String）。JSON 解析、schema 校验、修复重试**不在本层**，由 core/ 负责
- 不变式（必须永远成立的事实）：
  - api-key 只经环境变量 → `LlmProperties` 注入，代码/日志/异常信息中永不出现（红线 1）
  - 请求只发往配置的 base-url，不引入任何其他外部服务（红线 2）
  - 零新 Maven 依赖（D007 的核心收益）；测试与 CI 中**永不调用真实端点**

## 行为流程 / 状态机

无状态。每次调用：

```
chat(system, user)
  → POST {base-url}/chat/completions
      body: {model, messages:[{role:system},{role:user}], temperature: 0.3}
  → 读取 choices[0].message.content 返回
```

- 超时：连接 5s，读 90s（出题/评估单次生成较慢；`llm.read-timeout-seconds` 可配）
- temperature 固定 0.3：出题与评估要稳定，不要发散
- 端点未配置（base-url/model 为空）→ 直接抛 `LlmException`，不发请求

## 失败与降级

- LLM 超时 / 不可用时：抛 `LlmException`（携带原因类别，不携带密钥与用户内容）；本层**不做重试**——重试语义（JSON 修复重试）属 core/ 业务层
- 输入非法时：prompt 为空 → `IllegalArgumentException`；配置缺失 → `LlmException`

## 🚫 禁止 AI 自由发挥的区域

- 密钥的读取与传递路径（只准 `LlmProperties`）
- 不得为图省事引入 HTTP/JSON 新依赖（红线 5 + D007）
- 异常信息中不得回显请求体（内含 JD / 答案，红线 2）
- 不得在 src/test 中配置真实端点或密钥

## 验收测试

- [x] 正常响应：返回 choices[0].message.content
- [x] 端点返回 5xx / 超时 → LlmException，消息不含密钥与请求体
- [x] base-url 为空时直接抛 LlmException，不发 HTTP 请求
- [x] 请求体含 model 与两条 message（MockRestServiceServer 校验）
