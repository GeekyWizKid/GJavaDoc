# GJavaDoc 使用手册

本手册面向插件使用者，介绍安装、配置、运行、增量生成、失败处理以及界面说明。

版本信息
- 平台：IntelliJ IDEA 2024.2（基于 Gradle IntelliJ 插件）
- JDK：17+

1. 安装与启动
- 源码构建：
  - 构建：`./gradlew build`
  - 调试运行：`./gradlew runIde`
  - 提示：为避免 Gradle 插件在 sandbox 中因 JVM 版本矩阵解析报错，已自动在 sandbox 禁用 `com.intellij.gradle`。
- 打包安装：`./gradlew buildPlugin`，在 `build/distributions` 里获得 zip，IDE 中手动安装。

2. 基本概念
- 入口（Entry）：通过注解匹配到的类方法或“类本身”（类级文档）。
- 任务（Task）：对某个入口发起文档生成的一次尝试，包含状态、进度、结果路径等。
- 队列：控制并发与速率（RPS），按任务依次执行，支持重试与退避。
- 产物：
  - `docs/`：最终 Markdown 文档（判重依据）
  - `context-bundles/`：上下文文本包（供 LLM 生成使用）
  - `method-docs/`：可选 JSON 结构化输出

3. 界面总览
- 顶部过滤区（紧凑）：
  - Module、Status、Search、Apply、Compact 开关、Advanced（展开/收起）
  - 右侧实时队列状态：Running/Stopped、Running x/y、Waiting、RPS 等
- Advanced 区（默认收起）：
  - CRUD 勾选与 Run 按钮（按筛选执行一次扫描入队）
- 表格：列包含 No.、Entry、Status、Progress、Message、JSON、Markdown
  - Compact 开启时隐藏 JSON/Markdown 列，Entry 列自动加宽
- 工具栏（表格上方）：
 - Run Scan / Stop / Cancel / Cancel All / Retry / Retry Failed / Retry Timeouts / Requeue / Clear / Resume From List / Open Source / Open Context / Open JSON / Open Markdown / Edit Prompt

4. 设置说明（Settings → GJavaDoc）
- Annotation：扫描入口注解（默认 `@RpcService`，可写简名或全名；支持多个，逗号分隔。类或方法上任一匹配即入选）
- Analysis Backend：`STUB` 或 `WALA`
- Context：
  - `typeDepth` 关联类型深度；`collectCalled` 是否收集被调方法；`calledDepth` 深度
  - `maxChars` 上下文最大字符数（过长会截断并提示）
  - 类型筛选：`typeSuffixes`、`packageKeywords`、`annotationWhitelist`
  - CRUD Patterns：自定义方法名的 CRUD 前缀（逗号分隔；大小写不敏感）
    - CREATE: create, add, insert, save, new（可自定义）
    - READ: get, query, list, find, select, count, load（可自定义）
    - UPDATE: update, set, modify, patch, enable, disable（可自定义）
    - DELETE: delete, remove, del, clear（可自定义）
- Queue：`maxConcurrentRequests`、`requestsPerSecond`、`queueSize`、`requestTimeoutSec`
- Retry：`enabled`、`maxAttempts`、`backoffMs`
- Persist：`historyLimit`
 - UI：`lastStatusFilter`、`lastSearchText`、`pageSize`、`sortBy`、`sortAsc`

5. 扫描与入队
- Run Scan：
  - IDEA 等待索引完成后（Smart Mode）对全项目扫描符合注解的入口。
  - 可在工具窗选择 Module（或 ALL）只扫描指定模块。
  - 依据 CRUD 勾选过滤（Create/Read/Update/Delete/Other），分类规则使用“CRUD Patterns”中维护的前缀集合。
  - 增量入队：只对 `docs/` 中缺失的条目入队。
- Resume From List：
  - 支持行格式：
    - `com.pkg.Class#method`
    - `com.pkg.Class#method(argType1,argType2,...)`
    - `com.pkg.Class#CLASS`
    - 或直接粘贴 `docs/` 里生成的文件名/前缀（会做解析）
  - 可选择“跳过已成功”任务。

6. 增量规则（重要）
- 判重目录：仅看 `docs/`，因为它是最终产物。
- 方法级：文件名模式
  - `com.pkg.Clz_method_Param1_Param2__<timestamp>.md`
  - 解析逻辑：
    1) 去掉末尾 `_+数字` 的时间戳段
    2) 以第一个 `_` 将“类全名”和“方法部分”分开
    3) 方法部分的第一个 `_` 前是方法名，之后是参数安全化拼接 `Param1_Param2`
  - 再扫描时，会把 PSI 方法签名按同样规则安全化，再精确比对“同类 + 同签名”，已存在则跳过。
- 类级：`com.pkg.Clz_CLASS__<timestamp>.md` 存在则跳过。

7. 文档按模块分目录
- 设置中勾选“Group docs by module / 文档按模块分目录”后，输出路径为：
  - `docs/<module>/com.pkg.Clz_method_Param__<timestamp>.md`
- 仅影响 `docs/`（最终产物）。`context-bundles/`、`method-docs/` 不分目录。

7. 运行与失败处理
- 队列控制：在设置中配置最大并发、RPS；UI 顶部实时展示心跳（每秒最多 4 次）。
- 取消：对选中任务使用 Cancel；对所有运行中使用 Cancel All。
- 重试：
  - 单条：Retry（对选中任务）
  - 批量：Retry Failed（所有 FAILED），Retry Timeouts（仅失败类型/信息包含超时）
  - 队列内部也有自动重试（按设置的最大次数与退避时间）。

8. 产物说明
- `docs/`：Markdown（最终产物），建议按需纳入版本控制。
- `context-bundles/`：上下文文本，包含 Entry、源码片段、切片、关联类型、可选被调方法等；用于 LLM 生成提示，不作为判重依据。
- `method-docs/`：可选 JSON 返回（由 LLM 决定是否提供）。

9. 常见问题与规避
- IndexNotReadyException：索引未就绪；等待 Smart Mode 再执行。
- PSI TextRange 为 null（NPE）：常见于库/合成 PSI 或失效元素；可关闭 Collect Called/降低 typeDepth 后重试。
- 运行中数量与表格不一致：UI 采用 `max(内部并发计数, 仓库中 RUNNING 数)` 展示，避免短时不同步导致“少显示”。

10. 命名示例
- 方法文档：
  - `com.xxxx.xxx.xxx.xxx.service.XxxxService_xxxXXX_String_String__1756819782590.md`
- 类级文档：
  - `com.example.demo.UserService_CLASS__123456.md`

11. 小技巧
- Compact 模式默认开启，节省空间；需要看 JSON/Markdown 路径时关闭即可。
- Advanced 不常改的 CRUD 选项默认收起，保持顶栏紧凑；需要时再展开。
- 想只保留某些入口类型，可在 Search 里输入关键方法名前缀或类名过滤。

12. 版本与依赖
- 平台：`intellij.version=2024.2`
- Kotlin：`1.9.24`，JDK 17；WALA 1.6.0（可使用 `libs/` 本地 jar 或 Maven Central）

如需更多帮助，欢迎查看仓库根目录的 `README.md` 或提交 Issue。
