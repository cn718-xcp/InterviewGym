# InterviewGym · 面试健身房

一个用 ZCode 以 vibe coding 方式从 0 到 1 做出的 **AI 模拟面试官**，同时是一次**全程留痕的 AI 协作开发实验**——这个仓库里，产品和过程证据是同等重要的两件交付物。

> 输入目标公司 JD 和简历 → 针对性模拟面试（八股 / 项目深挖 / 场景题）→ 评估报告与薄弱点画像 → 间隔重复复习。

## 为什么做（问题）

秋招进行中，我需要大量针对性模拟面试：真人 mock 约不起，题库不针对我的简历和目标岗位。同时我想用两周时间回答一个自己好奇的问题：**AI 协作开发到底能把一个应届工程师的交付效率提升多少？边界在哪里？**——所以本仓库从第一天起就记录决策、数据与翻车案例。

## 关键决策（完整日志见 [docs/DECISIONS.md](docs/DECISIONS.md)）

| 决策 | 备选项 | 为什么 |
|---|---|---|
| 模块化单体 | 微服务 | 单人两周项目拆微服务是为简历服务的过度设计 |
| LLM 走 OpenAI 兼容可配置端点 | 绑定单一厂商 SDK | 换模型零改码，也是后续成本/质量路由的基础 |
| 编译门禁 + CI 自动拦截 | 纯人工审查 | 让"AI 改坏立刻被拦"成为默认态 |

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
# 可选：export LLM_READ_TIMEOUT_SECONDS=90
cd backend && mvn spring-boot:run
```

浏览器打开 **http://localhost:8080** → 粘贴目标岗位 JD → 开始面试（出题约 30~60 秒）→ 逐题作答 → 交卷出评估报告（逐题得分、总评、薄弱知识点、复习建议）。

> v1 为内存态：重启进程会话即清空，数据不落盘，只发送至你配置的 LLM 端点。

## 状态

- [x] Day 1：骨架 + 门禁 + 证据链 + CI
- [ ] v1 文本模拟面试（功能已完成，34 测试全绿 + 冒烟通过；待本人真实使用一次并愿意用第二次，即验收）　[ ] v2 RAG 针对性　[ ] v3 语音　[ ] v4 薄弱点面板
