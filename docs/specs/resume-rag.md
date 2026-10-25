# Spec: 简历索引与检索（rag/）

> 契约立于 2026-10-25，v2 迭代动工前（AGENTS.md 红线 6）。对应决策 D011（OpenAI 兼容 /embeddings + 库内余弦检索）。

## 目的

把用户简历切块、向量化入库，出题时按 JD 检索最相关的片段交给出题引擎，使项目深挖题命中简历真实项目（v2 验收线：同一 JD 下 v1/v2 针对性可感知提升）。

## 输入 / 输出边界

- 输入：简历全文文本（粘贴，≥ 100 字符，API 层校验）；JD 文本（检索时刻）
- 输出：top-k（k=6）简历片段拼装成的上下文字符串（含节标题），供 `QuestionGenerationService.generate(jdText, resumeContext)` 使用
- 不变式（必须永远成立的事实）：
  - 简历文本只发往所配置**嵌入端点**的 `/embeddings`（D013：`LLM_EMBEDDING_BASE_URL` / `LLM_EMBEDDING_API_KEY` 可独立于 chat 端点，缺省复用 chat 配置），不写日志、不落库外文件（红线 2；本地 db 文件属 D010 已告知范围）
  - 单活跃简历：新上传整体替换旧简历及其索引
  - 检索不改变出题构成（10 题 6:2:2 不动，那是 D012 的事，本模块只供上下文）

## 行为流程 / 状态机

```
上传(PUT /api/resume)
  ① 校验文本长度 → ② ResumeChunker 切块 → ③ LlmClient.embed(全部块)（一次请求；嵌入输入 = 节标题 + 块文本，标题信号进向量）
  ④ 事务内：清旧 chunks + upsert resume 行（source_text、embed_model、embed_dim）+ 写新 chunks（embedding 为 float[] 小端 BLOB）
  模型/维度与库中不一致 → 同样走全量重建（每次上传都是全量重建，天然覆盖）

检索(出题时)
  ① 取活跃简历 chunks → ② embed(JD 全文) → ③ 余弦相似度 top-6 → ④ 拼装：「### {节标题}\n{块文本}」逐块连接
```

- 切块规则：按 markdown/行为标题或空行分段聚合，目标块长 300~500 字符，块间重叠约 50 字符，保留节标题上下文
- 表结构（并入 `schema.sql`）：
  - `resume(id INTEGER PK CHECK(id=1), updated_at TEXT, source_text TEXT, embed_model TEXT, embed_dim INT)`
  - `resume_chunks(seq INTEGER PK AUTOINCREMENT, chunk_index INT, section TEXT, text TEXT, embedding BLOB)`

## 失败与降级

- 未配置 `LLM_EMBEDDING_MODEL`：`PUT /api/resume` → 400，明示「上传简历需配置嵌入模型环境变量」
- embed 调用失败/超时：上传路径 → `LlmException` → 502（**不在上传路径静默吞错**，用户需知道索引没建成）；出题路径检索失败 → 降级为无简历出题（v1 行为）+ WARN（只记异常类别，不记内容），session 不标 resumeAssisted
- 简历过短切块后为 0 块 → 400

## 🚫 禁止 AI 自由发挥的区域

- 简历文本永不写日志、永不发往配置端点以外（红线 2）
- 不引入向量库 / 本地嵌入模型依赖（D011 已否决）
- top-k、块长参数调整属本 spec 契约，改动需走 spec 修订
- 检索结果只进出题 prompt，不得进评估 prompt（D012 范围限定：仅出题侧）

## 验收测试

- [ ] 切块：长简历切成多块、块长在目标区间、节标题保留
- [ ] 上传后元信息可查（块数、更新时间、模型名）；二次上传替换旧索引（旧 chunks 清空）
- [ ] 检索：桩向量下余弦 top-k 排序正确，输出含节标题格式
- [ ] 未配嵌入模型上传 → 400；embed 失败 → 502
- [ ] 出题路径检索失败 → 降级 v1 出题，session.resumeAssisted=false
