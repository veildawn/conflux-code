# 实现计划: Android Claude Termux Client

## 概述

本实现计划将设计文档中的架构分解为可执行的编码任务。项目采用多模块 Gradle 结构，使用 Kotlin + Jetpack Compose + Hilt + Room + Kotest 属性测试。每个任务递增构建，确保无孤立代码。

## 任务列表

- [x] 1. 项目基础设施与核心接口搭建
  - [x] 1.1 完善 core-common 模块基础工具类
    - 确认并补全 `CoroutineDispatchers`、`TimeProvider` 接口及实现
    - 创建 `CommonModule` Hilt 模块提供单例绑定
    - 确保 `core-common/build.gradle.kts` 包含所有必要依赖
    - _需求: 12.1, 12.5, 12.6_

  - [x] 1.2 完善 core-domain 模块领域模型与接口
    - 补全 `Session`、`Message`、`OutputEvent`、`ParseResult` 数据类定义
    - 补全 `AppSettings` 模型（含 `ThemeMode` 枚举）
    - 确认 `ConversationRepository`、`SettingsStore`、`CredentialStore`、`DiagnosticsRepository` 接口完整性
    - 确认 `CliBridge`、`BootstrapManager` 接口包含设计文档中所有方法签名
    - 定义 `SpawnConfig`、`BridgeEvent`、`ProcessState`、`ExitCause`、`PosixSignal`、`ProcessHandle` 类型
    - _需求: 12.2, 12.4_

  - [x] 1.3 完善 core-domain 用例层
    - 实现 `SendMessageUseCase`：持久化用户消息后转发到 Bridge
    - 实现 `StreamResponseUseCase`：监听 Bridge 输出流并通过 OutputParser 解析
    - 实现 `CancelTurnUseCase`：调用 Bridge 发送 SIGINT
    - 实现 `CreateSessionUseCase`：创建新会话并持久化
    - 实现 `DeleteSessionUseCase`：删除会话及关联消息
    - 实现 `RenameSessionUseCase`：更新会话标题
    - 实现 `GetSessionsUseCase`：获取按 lastActivityAt 降序排列的会话列表
    - 实现 `GetSessionMessagesUseCase`：获取按 position 升序排列的消息列表
    - 实现 `RetryFailedTurnUseCase`：重试失败消息而不重复用户消息
    - _需求: 3.5, 3.7, 5.3, 5.4, 5.6, 5.7, 5.8, 5.9, 11.3_

- [x] 2. Output_Parser 与 Pretty_Printer 实现
  - [x] 2.1 实现 OutputParser 核心解析逻辑
    - 在 `core-domain/parser/OutputParserImpl.kt` 中实现字节流解析状态机
    - 支持事件类型：`Text`、`ToolCallStart`、`ToolCallResult`、`Prompt`、`TurnComplete`、`Error`
    - 实现 ANSI 转义序列剥离，保留 `StyleHint` 元数据
    - 实现 sentinel 识别：`[tool_call_start:NAME]`、`[tool_call_result:NAME:STATUS]`、`[turn_complete]`
    - 实现错误恢复：畸形 sentinel 发出单个 Error 事件后在下一换行符处重新同步
    - 确保 OutputParser 为纯函数实现（无 I/O、无全局状态）
    - _需求: 4.1, 4.2, 14.1, 14.4, 14.5_

  - [x] 2.2 实现 PrettyPrinter 序列化逻辑
    - 在 `core-domain/parser/PrettyPrinterImpl.kt` 中实现事件到字节流的序列化
    - 实现 `eventsToBytes(events: List<OutputEvent>): ByteArray` 方法
    - 确保序列化输出可被 OutputParser 重新解析为等价事件序列
    - _需求: 14.2, 14.3_

  - [x] 2.3 编写属性测试：ANSI 剥离保留文本内容
    - **属性 5: ANSI 剥离保留文本内容**
    - **验证: 需求 4.2**

  - [x] 2.4 编写属性测试：流重组往返一致性
    - **属性 6: 流重组往返一致性**
    - **验证: 需求 4.7**

  - [x] 2.5 编写属性测试：Parser/Printer 往返（字节方向）
    - **属性 7: Parser/Printer 往返（字节方向）**
    - **验证: 需求 14.3**

  - [x] 2.6 编写属性测试：Parser/Printer 往返（事件方向）
    - **属性 8: Parser/Printer 往返（事件方向）**
    - **验证: 需求 14.2**

  - [x] 2.7 编写属性测试：Parser 错误恢复
    - **属性 9: Parser 错误恢复**
    - **验证: 需求 14.4**

- [-] 3. 检查点 - 确保所有测试通过
  - 确保所有测试通过，如有问题请询问用户。

- [x] 4. 数据持久化层实现 (core-data)
  - [x] 4.1 实现 Room 数据库 Schema 与 DAO
    - 创建 `SessionEntity`、`MessageEntity`、`DiagnosticsLogEntity` 实体类
    - 实现 `SessionDao`：getAllSessionsFlow、getById、insert、update、deleteById
    - 实现 `MessageDao`：getMessagesForSessionFlow、getMessagesForSession、insert、update、deleteBySessionId、getMaxPosition
    - 实现 `DiagnosticsLogDao`：insert、getRecentLogs、deleteOldLogs
    - 创建 `AppDatabase` 抽象类并配置 Room
    - 创建 `DatabaseModule` Hilt 模块
    - _需求: 5.1, 5.2, 5.6, 5.7, 5.8, 13.1_

  - [x] 4.2 实现 ConversationRepositoryImpl
    - 在 `core-data/repository/ConversationRepositoryImpl.kt` 中实现 `ConversationRepository` 接口
    - 实现会话 CRUD 操作（创建、读取、更新、删除）
    - 实现消息持久化（含流式增量保存，每 2 秒至少一次）
    - 实现删除会话时级联删除消息（单事务）
    - 创建 `RepositoryModule` Hilt 绑定
    - _需求: 5.1, 5.2, 5.3, 5.4, 5.5, 5.8, 5.10_

  - [x] 4.3 编写属性测试：Session/Message 持久化往返
    - **属性 10: Session/Message 持久化往返**
    - **验证: 需求 5.10**

  - [x] 4.4 编写属性测试：Session 列表排序不变量
    - **属性 11: Session 列表排序不变量**
    - **验证: 需求 5.6**

  - [x] 4.5 编写属性测试：Message 位置排序不变量
    - **属性 12: Message 位置排序不变量**
    - **验证: 需求 5.7**

  - [x] 4.6 实现 SettingsStoreImpl (DataStore)
    - 在 `core-data/settings/SettingsStoreImpl.kt` 中实现 `SettingsStore` 接口
    - 使用 Preferences DataStore 持久化所有设置键
    - 实现范围校验：fontScale [0.5, 3.0]、streamingRenderRate [16, 1000]
    - 超出范围时回退到默认值
    - 实现写入后 200ms 内通知观察者
    - 创建 `SettingsModule` Hilt 绑定
    - _需求: 9.1, 9.2, 9.3, 9.4, 9.6_

  - [x] 4.7 编写属性测试：设置往返
    - **属性 13: 设置往返**
    - **验证: 需求 9.6**

  - [x] 4.8 编写属性测试：设置范围校验回退
    - **属性 14: 设置范围校验回退**
    - **验证: 需求 9.4**

  - [x] 4.9 实现 CredentialStoreImpl (Android Keystore)
    - 在 `core-data/credentials/CredentialStoreImpl.kt` 中实现 `CredentialStore` 接口
    - 使用 EncryptedSharedPreferences + Android Keystore 存储 API key
    - 实现掩码显示：最多显示最后 4 个字符，其余用 `*` 替代
    - 实现 Keystore 不可用时的错误恢复（提示重新输入）
    - 实现删除凭据功能
    - 创建 `CredentialStoreModule` Hilt 绑定
    - _需求: 6.1, 6.2, 6.3, 6.6, 6.7_

  - [x] 4.10 编写属性测试：API Key 掩码
    - **属性 15: API Key 掩码**
    - **验证: 需求 6.3**

  - [x] 4.11 实现 DiagnosticsRepositoryImpl
    - 在 `core-data/diagnostics/DiagnosticsRepositoryImpl.kt` 中实现 `DiagnosticsRepository` 接口
    - 记录 bootstrap 事件、Bridge 生命周期事件、Claude CLI stderr（每会话最后 256 行）
    - 实现导出分享功能：导出为文本文件并脱敏 API key
    - 创建 `DiagnosticsModule` Hilt 绑定
    - _需求: 13.1, 13.3, 13.4, 13.5_

  - [x] 4.12 编写属性测试：诊断日志脱敏
    - **Property 16: Diagnostics log redaction**
    - **验证: 需求 13.5**

- [-] 5. 检查点 - 确保所有测试通过
  - 确保所有测试通过，如有问题请询问用户。

- [x] 6. Bridge 层实现 (core-bridge)
  - [x] 6.1 实现 Terminal_Emulator_Lib JNI 集成与 ProcessExecutor
    - 确认 `terminal-emulator` JitPack 依赖配置正确
    - 实现 `JniProcessExecutor`：封装 `TerminalJni.createSubprocess()` 调用
    - 实现 `PtyChannel`：封装 PTY fd 的读写操作（使用 `Dispatchers.IO`）
    - 实现 `ProcessExecutor` 接口的 `execute(config)` 和 `sendSignal(pid, signal)` 方法
    - 创建 `ProcessModule` Hilt 模块
    - _需求: 2.1, 2.5, 2.8, 2.9_

  - [x] 6.2 实现 Embedded_Prefix 提取与 BootstrapManagerImpl 重构
    - 重构 `BootstrapManagerImpl` 为自包含模式（不依赖外部 Termux）
    - 实现 `PrefixExtractor`：从 APK assets 提取 Embedded_Prefix 到应用私有目录
    - 实现版本标记文件 (`.version` JSON) 的读写与升级检测
    - 实现 proot 二进制权限设置 (chmod 755)
    - 实现 Ubuntu rootfs 下载与提取
    - 实现 Node.js 和 Claude CLI 安装验证
    - 实现进度回调和错误处理（存储空间不足提示、重试机制）
    - 实现健康检查操作
    - _需求: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.10, 1.11_

  - [x] 6.3 实现 ProotConfig 与 proot 命令构建
    - 实现 `ProotConfig` 数据类及 `buildCommandArgs()` 方法
    - 配置 bind mounts：workspace → `/workspace`、`/dev`、`/proc`、`/sys`
    - 构建环境变量映射：`HOME`、`PATH`、`TERM`、`LANG`、`ANTHROPIC_API_KEY`
    - 确保 API key 不写入任何日志或文件
    - _需求: 2.2, 2.3, 8.3, 8.6_

  - [x] 6.4 编写属性测试：Proot 命令构建正确性
    - **属性 1: Proot 命令构建正确性**
    - **验证: 需求 2.2, 8.3, 8.6**

  - [x] 6.5 编写属性测试：环境变量完整性
    - **属性 2: 环境变量完整性**
    - **验证: 需求 2.3**

  - [x] 6.6 完善 CliBridgeImpl 进程管理
    - 确认 `CliBridgeImpl` 的 spawn/terminate/write/sendSignal 实现完整
    - 实现退出码分类：0→NORMAL、130→USER_CANCELLED、137→KILLED_BY_OS、>128→CRASH
    - 实现信号升级协议：SIGINT → 5s → SIGTERM → 5s → SIGKILL
    - 实现单进程不变量：新 spawn 前终止旧进程
    - 实现 spawn 失败报告：命令行 + 错误码 + 最后 4096 字节 stderr
    - _需求: 2.6, 2.7, 2.10, 2.11, 2.12, 2.13_

  - [x] 6.7 编写属性测试：退出码分类确定性
    - **属性 3: 退出码分类确定性**
    - **验证: 需求 2.10**

  - [x] 6.8 编写属性测试：单进程不变量
    - **属性 4: 单进程不变量**
    - **验证: 需求 2.13**

  - [x] 6.9 实现网络监控 (NetworkMonitor)
    - 完善 `NetworkMonitorImpl`：使用 ConnectivityManager 监听网络状态变化
    - 实现 `NetworkErrorParser`：识别 Claude CLI 输出中的网络错误签名
    - 通过 StateFlow 暴露网络状态供 UI 层消费
    - _需求: 11.1, 11.2, 11.5_

  - [x] 6.10 实现 Workspace 管理 (WorkspaceManager)
    - 完善 `WorkspaceManagerImpl`：管理 workspace 路径列表
    - 实现 `WorkspaceBoundaryValidator`：验证路径访问权限
    - 集成 SAF URI 权限持久化 (`takePersistableUriPermission`)
    - 处理权限获取失败的降级方案
    - _需求: 8.1, 8.2, 8.4, 8.5, 8.6_

  - [x] 6.11 实现前台服务 (ClaudeSessionService)
    - 完善 `ClaudeSessionService`：Foreground Service 实现
    - 配置持久通知（显示会话标题和当前轮次状态）
    - 实现通知点击跳转到对应会话
    - 实现所有进程终止后自动停止服务
    - 实现 OS kill 检测与恢复（标记消息为 `killed_by_os`）
    - 声明 `FOREGROUND_SERVICE_DATA_SYNC` 权限
    - _需求: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6_

- [ ] 7. 检查点 - 确保所有测试通过
  - 确保所有测试通过，如有问题请询问用户。

- [ ] 8. UI 层实现 (core-ui + feature 模块)
  - [x] 8.1 实现 core-ui 共享组件与主题
    - 实现 `ClaudeMobileTheme`：支持 system/light/dark 三种模式
    - 确保所有颜色组合满足 WCAG 对比度要求（标准文本 4.5:1，大文本 3:1）
    - 实现共享 Composable：`MessageBubble`、`CodeBlock`、`ToolCallBlock`、`StreamingIndicator`
    - 实现 Markdown 渲染组件（支持标题、列表、粗体、斜体、行内代码、代码围栏、表格、链接）
    - 实现代码围栏语法高亮（不支持的语言回退到等宽字体）
    - 实现复制操作（消息级和代码块级）
    - _需求: 4.3, 4.4, 4.5, 4.6, 4.8, 15.3_

  - [x] 8.2 编写属性测试：对比度合规性
    - **属性 18: 对比度合规性**
    - **验证: 需求 15.3**

  - [x] 8.3 实现 feature-chat 模块
    - 实现 `ChatViewModel`：UDF 模式，暴露 `StateFlow<ChatUiState>` 和 `Action` 密封类
    - 实现 `ChatScreen` Composable：消息列表、输入框、发送/取消按钮
    - 实现流式渲染：100ms 内追加文本块到活跃助手消息
    - 实现流式指示器显示与隐藏
    - 实现取消控件：流式进行时替换发送按钮
    - 实现工具调用块渲染（工具名、可折叠参数、可折叠结果）
    - 实现错误事件处理：追加系统消息并允许下一轮用户输入
    - 实现重试操作（不重复用户消息）
    - 实现会话头部显示 workspace 路径
    - _需求: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 8.7, 11.2, 11.3_

  - [x] 8.4 编写属性测试：重试不重复消息
    - **属性 17: 重试不重复消息**
    - **验证: 需求 11.3**

  - [x] 8.5 实现 feature-sessions 模块
    - 实现 `SessionsViewModel`：加载会话列表、处理删除和重命名
    - 实现 `SessionsScreen` Composable：按 lastActivityAt 降序显示会话列表
    - 实现空状态占位符
    - 实现加载失败的可恢复错误状态（含重试操作）
    - 实现新建会话流程（选择 workspace）
    - 实现滑动删除和长按重命名
    - 实现离线时仍可浏览、搜索、复制、删除会话
    - _需求: 5.6, 5.7, 5.8, 5.9, 8.1, 11.4_

  - [x] 8.6 实现 feature-settings 模块
    - 实现 `SettingsViewModel`：加载和保存所有偏好设置
    - 实现 `SettingsScreen` Composable：展示所有可配置项
    - 实现 API key 输入与掩码显示（最多显示最后 4 字符）
    - 实现 API key 删除确认
    - 实现无 API key 时阻止新建会话并提示配置
    - 实现数值输入范围校验（fontScale、streamingRenderRate）
    - 实现主题切换即时生效（一个 recomposition 周期内）
    - 实现健康检查结果显示（存储用量、各组件版本）
    - 实现诊断日志查看与分享（脱敏 API key）
    - _需求: 1.9, 6.2, 6.3, 6.5, 6.6, 9.1, 9.2, 9.3, 9.5, 13.2, 13.3_

  - [x] 8.7 实现 Bootstrap UI 流程
    - 完善 `BootstrapScreen`：显示当前步骤名称、进度指示器、下载百分比、最近一行安装输出
    - 实现失败时显示具体错误 + 重试按钮 + 空间建议
    - 实现 `BootstrapViewModel`：监听 BootstrapManager 状态并驱动 UI
    - _需求: 1.5, 1.6, 1.7_

- [x] 9. 无障碍与权限合规
  - [x] 9.1 实现无障碍支持
    - 为所有交互式 Composable 添加非空 contentDescription（发送、取消、复制、重试）
    - 确保字体缩放至 2.0 时消息不水平裁剪
    - 实现键盘导航：所有交互控件可通过外部键盘到达和激活
    - 实现 TalkBack 支持：流式消息更新每 2 秒最多播报一次
    - _需求: 15.1, 15.2, 15.4, 15.5_

  - [x] 9.2 实现权限声明与运行时请求
    - 确认 AndroidManifest 仅声明：`INTERNET`、`POST_NOTIFICATIONS`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`
    - 确认不请求 `READ_EXTERNAL_STORAGE`/`WRITE_EXTERNAL_STORAGE`
    - 确认 API 33+ 运行时请求 `POST_NOTIFICATIONS`
    - 实现权限拒绝后的降级处理（警告通知可能被抑制）
    - _需求: 10.1, 10.2, 10.3, 10.4, 10.5_

- [x] 10. 集成与导航连接
  - [x] 10.1 完善 app 模块导航与 DI 连接
    - 确认 `AppNavGraph` 正确连接所有 feature 模块的屏幕
    - 确认 `NavRoutes` 包含所有路由定义（bootstrap、sessions、chat/{id}、settings）
    - 确认 `MainActivity` 正确判断启动目标（bootstrap vs sessions）
    - 确认前台服务通知深链接正确跳转到对应会话
    - 确认离线横幅在网络不可用时显示
    - _需求: 1.1, 7.4, 11.1, 12.1_

  - [x] 10.2 实现端到端数据流连接
    - 连接 ChatViewModel → SendMessageUseCase → ConversationRepository + CliBridge
    - 连接 CliBridge.outputFlow → StreamResponseUseCase → OutputParser → ChatViewModel
    - 连接 CancelTurnUseCase → CliBridge.sendSignal
    - 确保流式消息每 2 秒增量持久化
    - 确保进程退出事件正确传播到 UI
    - _需求: 2.8, 2.9, 3.1, 5.4, 5.5_

- [ ] 11. 最终检查点 - 确保所有测试通过
  - 确保所有测试通过，如有问题请询问用户。

## 备注

- 标记 `*` 的任务为可选任务，可跳过以加速 MVP 交付
- 每个任务引用具体需求编号以确保可追溯性
- 检查点确保增量验证
- 属性测试验证通用正确性属性（使用 Kotest Property Testing）
- 单元测试验证具体示例和边界条件
- 项目已有部分脚手架代码，任务聚焦于补全和完善实现

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2"] },
    { "id": 2, "tasks": ["1.3", "2.1"] },
    { "id": 3, "tasks": ["2.2", "2.3", "2.4"] },
    { "id": 4, "tasks": ["2.5", "2.6", "2.7"] },
    { "id": 5, "tasks": ["4.1", "6.1"] },
    { "id": 6, "tasks": ["4.2", "4.6", "4.9", "4.11", "6.2", "6.3"] },
    { "id": 7, "tasks": ["4.3", "4.4", "4.5", "4.7", "4.8", "4.10", "4.12", "6.4", "6.5"] },
    { "id": 8, "tasks": ["6.6", "6.9", "6.10", "6.11"] },
    { "id": 9, "tasks": ["6.7", "6.8"] },
    { "id": 10, "tasks": ["8.1"] },
    { "id": 11, "tasks": ["8.2", "8.3", "8.5", "8.6", "8.7"] },
    { "id": 12, "tasks": ["8.4", "9.1", "9.2"] },
    { "id": 13, "tasks": ["10.1", "10.2"] }
  ]
}
```
