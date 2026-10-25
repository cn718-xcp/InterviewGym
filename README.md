# InterviewGym · 面试健身房

一个用 ZCode 以 vibe coding 方式从 0 到 1 做出的 **AI 模拟面试官**，同时是一次**全程留痕的 AI 协作开发实验**
——这个仓库里，产品和过程证据是同等重要的两件交付物。

> 输入目标公司 JD 和简历 → 针对性模拟面试（八股 / 项目深挖 / 场景题）→ 评估报告与薄弱点画像 → 间隔重复复习。

## 为什么做（问题）

秋招进行中，我需要大量针对性模拟面试：真人 mock 约不起，题库不针对我的简历和目标岗位。同时我想用两周时间回答一个自己好奇的问题：
**AI 协作开发到底能把一个应届工程师的交付效率提升多少？边界在哪里？**——所以本仓库从第一天起就记录决策、数据与翻车案例。

## 关键决策（完整日志见 [docs/DECISIONS.md](docs/DECISIONS.md)）

| 决策                   | 备选项        | 为什么                   |
|----------------------|------------|-----------------------|
| 模块化单体                | 微服务        | 单人两周项目拆微服务是为简历服务的过度设计 |
| LLM 走 OpenAI 兼容可配置端点 | 绑定单一厂商 SDK | 换模型零改码，也是后续成本/质量路由的基础 |
| 编译门禁 + CI 自动拦截       | 纯人工审查      | 让"AI 改坏立刻被拦"成为默认态     |

## 证据（过程数据，持续更新）

- 效率 / 质量 / 成本三张表 → [docs/METRICS.md](docs/METRICS.md)
- AI 翻车案例与防护体系演进 → [docs/AI-FAILURES.md](docs/AI-FAILURES.md)
- 实验口径（先定口径再收数据，防止事后编数）→ [docs/EXPERIMENT-DESIGN.md](docs/EXPERIMENT-DESIGN.md)
- 迭代路线：v1 文本模拟 → v2 RAG 针对性出题 → v3 语音模拟 → v4 薄弱点画像面板

## 边界（哪些事我不交给 AI）

出题与评估规则、隐私与密钥处理、全部技术选型——由人主导并记录于 AGENTS.md 红线。AI 负责测试、样板、前端与文档初稿。

## 快速开始

```bash
export LLM_BASE_URL=https://open.bigmodel.cn/api/paas/v4   # 任一 OpenAI 兼容端点
export LLM_API_KEY=你的key
export LLM_MODEL=glm-4-xx
# 可选：v2 简历针对性出题需要嵌入端点（如智谱 embedding-3；不配则 RAG 关闭，退回纯 JD 出题）
export LLM_EMBEDDING_MODEL=embedding-3
# 可选：chat 与嵌入用不同服务商时独立配置（D013）——如 chat 走 DeepSeek 官方（无嵌入端点）+ 智谱嵌入：
# export LLM_EMBEDDING_BASE_URL=https://open.bigmodel.cn/api/paas/v4
# export LLM_EMBEDDING_API_KEY=嵌入服务商的key
# 可选：export LLM_READ_TIMEOUT_SECONDS=90
# 可选：v3 语音作答需要 ASR 转写端点（OpenAI 兼容 /audio/transcriptions，如硅基流动，SenseVoiceSmall 免费）；不配则语音关闭退回打字
export LLM_ASR_MODEL=FunAudioLLM/SenseVoiceSmall
# 可选：ASR 与 chat 不同服务商时独立配置（同嵌入端点的 D013 模式）：
# export LLM_ASR_BASE_URL=https://api.siliconflow.cn/v1
# export LLM_ASR_API_KEY=转写服务商的key
cd backend && mvn spring-boot:run
```

> 排查提示：环境变量必须在**运行 mvn 的同一个终端**里设置（`setx` 或系统变量改动后需重开终端；IDE 启动则配在 Run
> Configuration）。启动后看日志行 `LLM 客户端就绪：base-url=[…], api-key=已配置` 与 `ASR 客户端就绪`——显示"未配置"说明变量没进进程，502
> 也会在日志打出根因（WARN `LLM 调用失败`）。若日志根因是「不可达或超时」且端点配置无误，检查本机系统代理（clash/v2ray 等会拦截
> JVM 默认网络请求），退出代理或设置 `NO_PROXY` 后重试。

浏览器打开 **http://localhost:8080** → 粘贴目标岗位 JD → 开始面试（出题约 30~60 秒）→ 逐题作答 →
交卷出评估报告（逐题得分、总评、薄弱知识点、复习建议）。
v2 起：可粘贴简历建立向量索引（存本地 SQLite，跨天可用）——项目深挖题将围绕简历中的真实项目出题，
八股题优先覆盖 JD 与简历技能的交集；首页有历史练习列表，已评估的可回看报告、未答完的可续答。
v3 起：作答页可「按下说话」——录音上传至你所配置的 ASR 端点转写为文本，回填输入框后可手动修改再交卷；
支持浏览器本地语音念题（🔊 朗读本题 / 自动朗读开关）。**录音转写后音频即弃：不落库、不写临时文件、不进日志，
仅转写文本与打字作答同地位参与评估**。录音建议用 Chrome / Edge。

> 数据存于本地 SQLite 单文件 `backend/data/interviewgym.db`（删除该文件即清空全部记录）；
> 简历与练习内容只发送至你所配置的 LLM 端点，不写日志、不经过任何其他外部服务。

## 状态

- [x] Day 1：骨架 + 门禁 + 证据链 + CI
- [x] v1 文本模拟面试（2026-10-25 本人自验通过：完整跑通一轮，体验尚可）
- [x] v2 RAG 针对性（2026-10-25 本人自验通过：简历索引 + 结合简历出题跑通，体验还不错）
- [ ] v3 语音模拟（2026-10-25 开发完成：92 测试全绿 + 冒烟通过，**待本人自验**——配 ASR 端点后真实跑通一轮语音）　[ ] v4 薄弱点面板
