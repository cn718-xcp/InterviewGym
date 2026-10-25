# Spec: LLM 客户端（llm/）

> 契约立于 2026-10-25，v1 迭代动工前（AGENTS.md 红线 6）。对应决策：D005（可配置端点）、D007（自写 RestClient）。v2（2026-10-25）补 embed() 契约，对应决策 D011。v3（2026-10-25）补 transcribe() 契约，对应决策 D014（整切片 spec 见 voice-mode.md）。

## 目的

把「调用一次 OpenAI 兼容 chat/completions」封装成可靠的单次调用，供 core/ 出题与评估使用，core 不接触 HTTP 细节。

## 输入 / 输出边界

- 输入：system prompt + user prompt（String）；`LlmProperties`（base-url / api-key / model / read-timeout-seconds / **v2 起：embedding-model、embedding-base-url、embedding-api-key——嵌入端点可独立配置，缺省复用 chat 端点，D013** / **v3 起：asr-model、asr-base-url、asr-api-key——ASR 端点独立可配，缺省回落 chat 端点，D014**）
- 输出：`chat` 返回 LLM 回复的纯文本内容（String）；`embed`（v2，D011）返回与输入等长的 `float[]` 向量列表；`transcribe`（v3，D014）返回转写文本（String）。JSON 解析、schema 校验、修复重试**不在本层**，由 core/ 负责
- 不变式（必须永远成立的事实）：
  - api-key（chat 与嵌入）只经环境变量 → `LlmProperties` 注入，代码/日志/异常信息中永不出现（红线 1）
  - chat 只发往 base-url；embed 发往 embedding-base-url（未配置时复用 base-url）——**除这两个显式配置的端点外不引入任何外部服务**（红线 2）
  - 零新 Maven 依赖（D007 的核心收益）；测试与 CI 中**永不调用真实端点**

## 行为流程 / 状态机

无状态。每次调用：

```
chat(system, user)
  → POST {base-url}/chat/completions
      body: {model, messages:[{role:system},{role:user}], temperature: 0.3}
  → 读取 choices[0].message.content 返回

embed(texts)                          [v2，D011]
  → POST {base-url}/embeddings
      body: {model: embedding-model, input: texts}
  → 按 data[].index 对齐返回 List<float[]>（维度由端点模型决定，本层不假设）

transcribe(audio, filename, contentType)   [v3，D014]
  → POST {asr-base-url}/audio/transcriptions
      multipart：file（带文件名与音频 content-type）+ model=asr-model
  → 返回响应 {text} 字段的文本（trim 后非空，空文本视为端点错误抛 LlmException）
```

- 超时：连接 5s，读 90s（出题/评估单次生成较慢；`llm.read-timeout-seconds` 可配）
- 配置校验：chat 的 base-url / api-key / model 任一为空即抛 `LlmException`，不发请求；base-url 尾部 `/` 自动归一化（chat 与嵌入端点各自归一化）
- `embed` 端点解析（D013）：base-url = embedding-base-url（未配置则回落 base-url）；api-key = embedding-api-key（未配置则回落 api-key）；**不要求 chat 的 model 已配置**
- `embed` 额外校验：`embedding-model` 为空 → `IllegalStateException`（区别于 LlmException：这是配置缺失而非端点故障，调用方据此区分 400/502 语义）；解析后的 base-url 或 api-key 为空 → `LlmException`（消息提示 LLM_BASE_URL/LLM_API_KEY 或 LLM_EMBEDDING_BASE_URL/LLM_EMBEDDING_API_KEY）；输入列表空 → `IllegalArgumentException`；端点返回条数或 index 与输入不对应 → `LlmException`
- `transcribe` 端点解析（D014，同 D013 模式）：base-url = asr-base-url（未配置则回落 base-url）；api-key = asr-api-key（未配置则回落 api-key）；不要求 chat 的 model 已配置
- `transcribe` 额外校验：`asr-model` 为空 → `IllegalStateException`（400 语义，调用方转 400）；解析后的 base-url 或 api-key 为空 → `LlmException`（消息提示 LLM_BASE_URL/LLM_API_KEY 或 LLM_ASR_BASE_URL/LLM_ASR_API_KEY）；audio 空字节数组 → `IllegalArgumentException`；响应 text 缺失或空白 → `LlmException`。**音频字节与转写文本永不写日志**（voice-mode.md 不变式）
- temperature 固定 0.3（仅 chat）：出题与评估要稳定，不要发散；embed 无 temperature
- 端点未配置（base-url/model 为空）→ 直接抛 `LlmException`，不发请求

## 失败与降级

- LLM 超时 / 不可用时：抛 `LlmException`（携带原因类别，不携带密钥与用户内容）；本层**不做重试**——重试语义（JSON 修复重试）属 core/ 业务层
- 输入非法时：prompt 为空 → `IllegalArgumentException`；配置缺失 → `LlmException`

## 🚫 禁止 AI 自由发挥的区域

- 密钥的读取与传递路径（只准 `LlmProperties`）
- 不得为图省事引入 HTTP/JSON 新依赖（红线 5 + D007）
- 异常信息中不得回显请求体（内含 JD / 简历块 / 答案，红线 2）
- 不得在 src/test 中配置真实端点或密钥

## 验收测试

- [x] 正常响应：返回 choices[0].message.content
- [x] 端点返回 5xx / 超时 → LlmException，消息不含密钥与请求体
- [x] base-url 为空时直接抛 LlmException，不发 HTTP 请求
- [x] 请求体含 model 与两条 message（MockRestServiceServer 校验）
- [ ] v2：embed 正常响应按 index 对齐返回等长向量列表，请求体含 embedding-model 与 input
- [ ] v2：embedding-model 未配置 → IllegalStateException，不发请求；空列表入参 → IllegalArgumentException
- [ ] v2：embed 端点 5xx → LlmException，消息不含密钥与请求体
- [ ] v2（D013）：配置独立 embedding-base-url/api-key 后 embed 命中该端点且 chat 端点不被触碰；未配置独立端点时回落 chat 端点；全未配置 → LlmException 不发请求
- [ ] v3（D014）：transcribe 正常返回 text，请求体为 multipart 且含 model 与 file 部分；asr-model 未配置 → IllegalStateException 不发请求；独立 asr 端点命中且 chat 端点不被触碰；端点 5xx → LlmException 消息不含密钥与内容
