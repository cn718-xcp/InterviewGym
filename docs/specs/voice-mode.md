# Spec: 语音作答模式（llm.transcribe + api/VoiceController + 前端录音/念题）

> 契约立于 2026-10-25，v3 迭代动工前（AGENTS.md 红线 6）。对应决策：D014（ASR = 服务端转发 OpenAI 兼容端点）、D015（TTS = 浏览器本地 + 音频转写后即弃）。
> 范围：固定 10 题的语音作答（录音转写为文本，走既有 /answers → /evaluate 流程）+ 浏览器念题。**动态追问明确不在本 spec**（留后续版本，触红线 3 需另立契约与决策）。

## 目的

让用户「说」出答案而非打字：浏览器录音上传 → 后端转发所配置 ASR 端点转写 → 文本回填输入框（可手改）→ 既有作答/评估流程零改动；题目可选浏览器本地语音朗读。

## 输入 / 输出边界

- 输入：录音文件（multipart `file`，浏览器 MediaRecorder 产出，`audio/webm;codecs=opus` 优先）；配置 `LLM_ASR_MODEL`（可空 = 语音关闭，如 `FunAudioLLM/SenseVoiceSmall`）+ 可选 `LLM_ASR_BASE_URL` / `LLM_ASR_API_KEY`（缺省回落 chat 端点，同 D013 模式）
- 输出：`GET /api/voice` → `{enabled}`（asr-model 非空即 true）；`POST /api/voice/transcribe` → `{text}` 转写文本
- 不变式（必须永远成立的事实）：
  - **音频只在内存过一道：不落库、不写临时文件、不进日志**；落库与评估只见与打字作答同等的文本（D015 隐私口径，红线 2）
  - 音频只发往显式配置的 ASR 端点（asr-base-url 未配置时 = chat base-url），不经过任何其他服务
  - 未配置 `LLM_ASR_MODEL` → 语音功能整体关闭（`enabled=false`，前端隐藏麦克风），打字主路径永不受影响
  - core/（出题/评估 prompt、评分规则）与 store/ 零改动——本切片不触碰红线 3 对象，v1/v2 回归面为零

## 行为流程 / 状态机

```
前端：🎤 按下 → MediaRecorder(webm/opus) 录音 → 再按停止（5:00 自动停）→「转写中…」
  → POST /api/voice/transcribe（multipart file）→ {text} 回填 textarea（已有内容则换行追加，可手改）
  → 之后与打字作答完全一致（交卷 → /answers 逐题 → /evaluate）

后端 transcribe(file)
  ① 校验：asr-model 空 → 400（明示需配 LLM_ASR_MODEL）；文件缺失/空字节 → 400
  ② LlmClient.transcribe(audio, filename, contentType)：POST {asr-base-url}/audio/transcriptions
     multipart：file（带文件名与音频 content-type）+ model=asr-model → 解析 {text} 返回
  ③ 端点解析（D014，同 D013）：base-url = asr-base-url ?? base-url；api-key = asr-api-key ?? api-key
     解析后任一为空 → LlmException（502 语义）；不要求 chat 的 model 已配置
```

- 念题（TTS，D015）：浏览器 `speechSynthesis` 本地 zh-CN 语音；每题「🔊 朗读本题」+「自动朗读」开关（localStorage 持久化，默认关）；切题/切屏即 cancel。零网络、零后端改动。
- multipart 配置（v3 审查轮补实现锚点）：`max-file-size: 20MB` / `max-request-size: 25MB` / **`file-size-threshold: 20MB`**——后者是「不写临时文件」不变式的落地关键：默认值 0 会让 Tomcat 在请求解析阶段就把录音写进磁盘临时文件，阈值 = 上限使录音全程留内存

## 失败与降级

- ASR 端点 5xx / 超时 / 不可达 / 返回空文本：`LlmException` → 502（固定文案 + 原因类别，不含密钥与音频内容），前端提示「转写失败，可重试或改手打」——**语音挂了不等于面试挂了**
- 浏览器不支持 MediaRecorder / speechSynthesis：对应按钮隐藏，退回纯打字/看题
- 上传超限（multipart `max-file-size: 20MB` / `max-request-size: 25MB`，前端 5 分钟自动停录双保险）：400 友好文案（`MaxUploadSizeExceededException` 映射）

## 🚫 禁止 AI 自由发挥的区域

- 音频的任何持久化（库 / 文件 / 日志）——D015 定稿「转写后即弃」，改动需重新走确认流程
- 音频与转写文本不得发往配置端点以外的任何服务（红线 2）；密钥只走环境变量（红线 1）
- 不得为 ASR / TTS 引入新 Maven 依赖或厂商 SDK（D007 纪律；multipart 用 spring-web 现有 `MultipartBodyBuilder`）
- 不得改动 core/ 出题评估与 store/（本 spec 的前提，越界需新决策）

## 验收测试

- [ ] transcribe 正常：请求体为 multipart 且含 model 字段与 file 部分（本地桩端点校验，沿用 LlmClientTest 的 JDK HttpServer 模式——见 AI-FAILURES #1 不用 MockRestServiceServer），返回 `{text}` 文本
- [ ] asr-model 未配置 → `IllegalStateException`（400 语义），不发请求
- [ ] 独立 asr-base-url / asr-api-key 配置后命中该端点且 chat 端点不被触碰；未配置独立端点时回落 chat 端点（镜像 D013 测试）
- [ ] ASR 端点 5xx → `LlmException`，消息不含密钥与音频内容
- [ ] `GET /api/voice`：配置 / 未配置 asr-model 双态 `enabled` 正确
- [ ] `POST /api/voice/transcribe`：未配置 → 400；文件空 → 400；`LlmException` → 502；上传超限 → 400
