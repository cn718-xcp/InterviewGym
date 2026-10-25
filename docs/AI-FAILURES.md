# AI 翻车案例库

> 本仓库最值钱的文档。每条 = 一个面试故事：错在哪 → 怎么发现 → 怎么防再发。
> 记录纪律：当天发生当天记；宁可记小，不可漏记；禁止事后美化。

分类参考：边界条件 / 并发 / 依赖版本 / 幻觉 API / 安全 / 性能 / 需求理解偏差

| # | 日期 | 场景 | AI 错在哪 | 怎么发现的 | 修复 | 防再发措施 |
|---|---|---|---|---|---|---|
| 1 | 2026-10-25 | LlmClient 单测（llm 模块） | 用 MockRestServiceServer.bindTo(builder) 打桩后，LlmClient 构造器里又调 builder.requestFactory(设置超时)，把 mock 工厂整体替换成了真实 HTTP 工厂——6 个测试中 3 个根本没走 mock，请求直接打到真实网络域名；超时测试还因"域名不可达"碰巧给出符合断言的异常消息，制造假象 | mvn verify 单测失败：状态码断言与实际异常消息不符，追查后定位 | 弃用请求级 mock，改用 JDK 自带 HttpServer 起本地真实端点做测试（零新依赖，且多覆盖了真实的请求序列化/响应反序列化路径） | mock 与被测对象共享同一个 builder 时，后设置的 requestFactory 会覆盖 mock 绑定的工厂；HTTP 客户端类优先用本地真实服务端打桩 |
| 2 | 2026-10-25 | LlmClient 异常处理（llm 模块，生产代码 bug） | chat() 只捕获 RestClientResponseException 与 ResourceAccessException 两类；读超时的 SocketTimeoutException 在该路径被 Spring 包成通用 RestClientException 裸逃（用户会看到 500 而非友好的 502）；且 Spring 对 IO 类失败的包装类型不统一，按异常类型分支本身不可靠 | 单测：改用静默 ServerSocket 确定性模拟超时后，断言异常消息不符，暴露分类缺口 | 删除按类型的分支，改为按根因链分类（根因含 IOException → 判定"不可达或超时"） | 外部调用边界的异常处理必须测试"非预期包装"路径；失败分类基于根因链而非框架包装类型 |
| 3 | 2026-10-25 | API 错误分支断言（api 模块） | ① MockMvc 断言只写 `$.error` 存在性，而 Spring 默认错误体恰好也有 error 字段——异常映射器（Advice）从未被真正验证过，属假绿灯 ② 冒烟时在 git-bash 用 curl 发中文 body，被按 GBK 编码送出导致 JSON 解析失败，一度误判为 Advice 失效 | 真实启动冒烟：短 JD 的 400 返回体是 Spring 默认格式而非自定义消息；查启动日志确认首发现象是编码问题，改 ASCII payload 后定位真因 | 断言强化到具体错误消息文本；冒烟改用 ASCII payload 验证 400/404/502 三条自定义消息全通过 | 存在性断言（field exists）对"字段重名"的默认错误体无鉴别力，错误分支断言必须落到具体值；MockMvc 全绿 ≠ 真实运行正确，交付前冒烟一次；Windows git-bash 的 curl 中文 body 不可靠，冒烟用 ASCII 或从文件读 body |
