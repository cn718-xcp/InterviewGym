# AGENTS.md — InterviewGym AI 协作规范

> 本文件是本仓库所有 AI 会话（ZCode 等）的行为宪法，会话开始即生效。
> 人类负责人：项目负责人本人（应届求职者，秋招进行中）。
> 本项目双重身份：① 自用的 AI 模拟面试官产品；② 一次全程留痕的 AI 协作开发实验。过程证据与代码同等重要。

## 项目一句话

输入目标岗位 JD 与简历，生成针对性模拟面试（八股 / 项目深挖 / 场景题），输出评估报告与薄弱点画像，按间隔重复安排复习。

## 技术栈（未经 docs/DECISIONS.md 记录不得更换）

- Java 17 · Spring Boot 3.5 · Maven 单模块（模块化单体，刻意不拆微服务，理由见 D002）
- 存储：v1 先无库（内存）；持久化留给迭代决策（见 D004）
- LLM：OpenAI 兼容端点，可配置（GLM / DeepSeek / Qwen 均可），经 `config/LlmProperties` 注入
- 前端：极简优先，AI 可全权实现，不引入重前端工程链

## 目录约定

```
backend/src/main/java/com/interviewgym/
  api/      HTTP 接口层（AI 可实现，按 spec）
  core/     核心域：出题引擎、评估引擎（人类主导区，见红线 3）
  llm/      LLM 客户端封装
  store/    持久化（v2 起）
docs/       证据链文档（本项目的"第二产品"）
scripts/    门禁与验证脚本
```

## 红线（违反任何一条，改动必须回滚）

1. **密钥**：API Key 只准从环境变量读取（`LLM_API_KEY` 等），任何代码、配置、文档、测试中不得出现明文密钥。
2. **隐私**：用户数据（简历、练习记录）不得写入日志，不得发送给所配置 LLM API 以外的任何外部服务。
3. **核心域保护**：`core/` 下出题与评估的 prompt 模板、评分规则的改动，必须先出方案、人类确认、记入 DECISIONS.md，才准动手。
4. **绿色收尾**：任何 session 收尾时 `mvn verify` 必须通过；CI 是最终门禁，红了必须最先修。
5. **依赖纪律**：新增依赖必须在 DECISIONS.md 记录理由与被放弃的备选项。
6. **契约先行**：核心模块动手前必须在 docs/specs/ 立契约（用 _TEMPLATE.md），禁止无契约实现。

## 分工

- **AI 全权**：单元测试、样板代码、前端、脚本、文档初稿
- **人类主导（AI 只准辅助草案）**：`core/` 出题与评估逻辑、数据模型 schema、全部技术选型

## 留痕义务（过程即交付物）

- 技术决策 → `docs/DECISIONS.md`（一行一条，含备选项与理由）
- AI 出错（被门禁拦截 / 人工审查 / 自用中发现）→ `docs/AI-FAILURES.md`，**当天记录**
- 每个 session 结束 → `docs/METRICS.md` 填一行（5 分钟，宁缺毋假）
- 核心模块 → `docs/specs/` 先立契约再动工

## 提交规范

- Conventional Commits（feat / fix / docs / chore / spec ...）
- 尾注署名，用于统计 AI 参与度：`Authorship: ai | human | mixed`
- 一个能独立讲清的改动一个 commit，禁止巨型提交
