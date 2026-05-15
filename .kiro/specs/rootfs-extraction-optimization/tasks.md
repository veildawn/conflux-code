# Implementation Plan: Rootfs Extraction Optimization

## Overview

将 BootstrapManagerImpl 的启动流程优化拆分为可增量实现的编码任务。核心实现路径：先创建三个新组件（ClaudeCliDetector、RootfsIntegrityChecker、BootstrapStateCache），再重构 BootstrapManagerImpl 注入这些组件并优化启动逻辑，最后通过集成测试验证快速启动路径和容错行为。

## Tasks

- [x] 1. 实现 ClaudeCliDetector
  - [x] 1.1 创建 ClaudeCliDetector 类
    - 在 `core-bridge/src/main/kotlin/com/claudemobile/core/bridge/bootstrap/` 下创建 `ClaudeCliDetector.kt`
    - 使用 `@Singleton` 和 `@Inject` 注解，注入 `@ApplicationContext context: Context`
    - 定义 `rootfsDir` 属性指向 `context.filesDir/rootfs`
    - 定义候选路径列表：`usr/bin/claude`, `usr/local/bin/claude`, `usr/lib/node_modules/@anthropic-ai/claude-code/bin/claude.exe`, `usr/local/lib/node_modules/@anthropic-ai/claude-code/bin/claude.exe`
    - _Requirements: 1.1_

  - [x] 1.2 实现 isInstalled() 和 checkBinaryExists() 方法
    - `isInstalled()` 遍历候选路径，任一通过 `checkBinaryExists()` 即返回 `true`
    - `checkBinaryExists()` 使用 `java.nio.file.Files.exists()` 检查文件/有效符号链接，验证 size > 0
    - 处理悬空符号链接：`Files.isSymbolicLink()` 为 true 时读取目标路径，在 rootfs 内重新解析
    - 捕获所有异常，失败时返回 `false`
    - _Requirements: 1.2, 1.3, 1.4_

  - [x] 1.3 实现 diagnose() 方法
    - 返回各候选路径的状态描述字符串（存在/不存在/悬空符号链接/异常）
    - 用于诊断日志输出
    - _Requirements: 1.5_

  - [ ]* 1.4 编写 ClaudeCliDetectorTest 单元测试
    - 使用临时目录模拟 rootfs 结构
    - 覆盖场景：普通文件存在、有效符号链接、悬空符号链接、文件大小为 0、所有路径不存在
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_

  - [ ]* 1.5 编写 ClaudeCliDetector 属性测试
    - **Property 1: Claude CLI 检测一致性**
    - **Validates: Requirements 1.1, 1.2, 1.4**

- [x] 2. 实现 RootfsIntegrityChecker
  - [x] 2.1 创建 RootfsIntegrityChecker 类和 IntegrityResult 数据类
    - 在 `core-bridge/src/main/kotlin/com/claudemobile/core/bridge/bootstrap/` 下创建 `RootfsIntegrityChecker.kt`
    - 使用 `@Singleton` 和 `@Inject` 注解，注入 `Context` 和 `ClaudeCliDetector`
    - 定义 `IntegrityResult` 数据类，包含 `directoryStructureValid`、`nodeInstalled`、`claudeCliInstalled` 字段和 `isComplete` 计算属性
    - _Requirements: 2.1_

  - [x] 2.2 实现 check() 方法
    - 验证 rootfs 关键目录：`etc`、`bin`、`usr` 是否为目录
    - 验证 Node.js 二进制：`usr/bin/node` 或 `usr/local/bin/node` 是否存在
    - 委托 `claudeCliDetector.isInstalled()` 检查 Claude CLI
    - 返回结构化的 `IntegrityResult`
    - _Requirements: 2.1, 2.3_

  - [x] 2.3 实现 recoverVersionMarker() 方法
    - 写入 `version=unknown-recovered\nsource=integrity-check\n` 到 rootfs 下的 `.claudemobile-bundled-version` 文件
    - _Requirements: 2.2, 2.4_

  - [ ]* 2.4 编写 RootfsIntegrityCheckerTest 单元测试
    - 覆盖场景：完整环境、缺少目录、缺少 Node、缺少 Claude CLI
    - 验证 `recoverVersionMarker()` 写入正确内容
    - _Requirements: 2.1, 2.2, 2.3, 2.4_

  - [ ]* 2.5 编写 RootfsIntegrityChecker 属性测试
    - **Property 2: 完整性检查幂等性**
    - **Validates: Requirements 2.1**

- [x] 3. 实现 BootstrapStateCache
  - [x] 3.1 创建 BootstrapStateCache 类
    - 在 `core-bridge/src/main/kotlin/com/claudemobile/core/bridge/bootstrap/` 下创建 `BootstrapStateCache.kt`
    - 使用 `@Singleton` 和 `@Inject` 注解，基于 SharedPreferences (`bootstrap_state_cache`)
    - 定义常量 KEY：`bootstrap_complete`、`last_success_ts`、`rootfs_version`
    - _Requirements: 5.1_

  - [x] 3.2 实现 markBootstrapComplete()、isBootstrapCachedAsComplete()、getCachedRootfsVersion()、invalidate() 方法
    - `markBootstrapComplete(version)`: 写入完成标记、时间戳、版本号
    - `isBootstrapCachedAsComplete()`: 读取 boolean 缓存
    - `getCachedRootfsVersion()`: 读取版本字符串
    - `invalidate()`: 清除所有缓存
    - _Requirements: 5.1, 5.2, 5.4_

  - [ ]* 3.3 编写 BootstrapStateCacheTest 单元测试
    - 验证写入后读取一致性
    - 验证 invalidate 后状态清除
    - _Requirements: 5.1, 5.4_

  - [ ]* 3.4 编写 BootstrapStateCache 属性测试
    - **Property 5: 缓存状态一致性（Round-trip）**
    - **Validates: Requirements 5.1, 5.4**

- [x] 4. Checkpoint - 确保新组件测试通过
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. 重构 BootstrapManagerImpl
  - [x] 5.1 注入新组件到 BootstrapManagerImpl 构造函数
    - 将 `ClaudeCliDetector`、`RootfsIntegrityChecker`、`BootstrapStateCache` 添加为构造函数参数
    - 替换 `isClaudeCliInstalled()` 内部实现为委托给 `ClaudeCliDetector.isInstalled()`
    - _Requirements: 1.1, 1.4_

  - [x] 5.2 重构 isReady() 方法：添加缓存快速路径
    - 实现 `quickVerify()` 方法：检查 prefix 版本文件、rootfs/etc 目录、Claude CLI 存在性
    - 优先读取 `stateCache.isBootstrapCachedAsComplete()`，若为 true 执行 `quickVerify()`
    - 快速路径通过时直接返回 `true`；失败时 `invalidate()` 缓存并回退到完整检查
    - 记录 `isReady()` 耗时和决策路径的诊断日志
    - _Requirements: 4.1, 4.2, 4.3, 5.2, 5.3, 6.1, 6.4_

  - [x] 5.3 重构 installRootfsReal() 方法：版本标记缺失时执行完整性检查
    - 当 `installedVersion == null && rootfsDir.exists()` 时调用 `integrityChecker.check()`
    - 完整性通过时调用 `recoverVersionMarker()` 并跳过解压
    - 完整性失败时继续正常解压流程
    - 记录诊断日志说明跳过/执行解压的原因
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 6.2, 6.3_

  - [x] 5.4 重构 installRootfsReal() 方法：版本升级前验证 asset 可访问性
    - 版本不匹配时先尝试 `context.assets.open(bundled).close()` 验证 asset 可读
    - asset 不可访问时返回 `Result.failure`，不执行 wipe
    - 记录升级日志包含旧版本号和新版本号
    - _Requirements: 3.2, 3.3, 3.4_

  - [x] 5.5 在 bootstrap() 成功后调用 stateCache.markBootstrapComplete()
    - 在 bootstrap 流程成功完成后持久化状态
    - _Requirements: 5.1_

- [x] 6. Checkpoint - 确保重构后编译通过
  - Ensure all tests pass, ask the user if questions arise.

- [x] 7. 集成测试与验证
  - [x] 7.1 编写 BootstrapManagerImplTest：快速启动路径
    - 验证缓存命中 + 环境完整 → 直接返回 Ready
    - 验证 `isReady()` 在快速路径下耗时 < 1000ms
    - _Requirements: 4.1, 5.2, 5.3_

  - [x] 7.2 编写测试：缓存失效场景
    - 验证缓存命中但环境损坏 → 自动 invalidate 缓存并回退到完整 bootstrap
    - _Requirements: 5.4_

  - [x] 7.3 编写测试：版本标记恢复场景
    - 验证标记缺失 + 完整性通过 → 恢复标记并跳过解压
    - _Requirements: 2.1, 2.2, 2.4_

  - [x] 7.4 编写测试：版本升级场景
    - 验证版本不匹配 + asset 可访问 → 执行升级
    - 验证版本不匹配 + asset 不可访问 → 不执行 wipe
    - _Requirements: 3.2, 3.3_

  - [ ]* 7.5 编写属性测试：版本标记恢复不触发重新解压
    - **Property 3: 版本标记恢复不触发重新解压**
    - **Validates: Requirements 2.2, 2.3**

  - [ ]* 7.6 编写属性测试：不会在 asset 不可访问时执行 wipe
    - **Property 6: 不会在 asset 不可访问时执行 wipe**
    - **Validates: Requirements 3.3**

- [x] 8. Final checkpoint - 确保所有测试通过
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- 所有代码使用 Kotlin 语言，遵循项目现有的 Hilt 依赖注入模式
- 单元测试使用临时目录模拟文件系统状态，避免依赖真实 rootfs
- 属性测试验证设计文档中定义的 Correctness Properties
- 每个 Checkpoint 确保增量验证，避免问题累积

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "3.1"] },
    { "id": 1, "tasks": ["1.2", "1.3", "2.1", "3.2"] },
    { "id": 2, "tasks": ["1.4", "1.5", "2.2", "2.3", "3.3", "3.4"] },
    { "id": 3, "tasks": ["2.4", "2.5"] },
    { "id": 4, "tasks": ["5.1"] },
    { "id": 5, "tasks": ["5.2", "5.3", "5.4", "5.5"] },
    { "id": 6, "tasks": ["7.1", "7.2", "7.3", "7.4"] },
    { "id": 7, "tasks": ["7.5", "7.6"] }
  ]
}
```
