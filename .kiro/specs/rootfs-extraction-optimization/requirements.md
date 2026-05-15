# Requirements Document

## Introduction

本功能优化 ClaudeMobile 应用的 rootfs 环境启动流程。当前实现中，每次打开 App 都可能触发不必要的 rootfs 重新解压，导致启动体验极差（解压耗时数分钟）。

根本原因分析：
1. `isClaudeCliInstalled()` 通过 `File.exists()` 检查 `claude.exe`，但 Android 私有存储中的硬链接在 `tar` 解压时可能退化为悬空符号链接，导致 `exists()` 返回 `false`
2. `.claudemobile-bundled-version` 标记文件缺失时，即使 rootfs 完整也会触发全量重新解压
3. 版本比对逻辑在标记文件丢失时过于激进，直接 wipe 整个 rootfs 目录

优化目标：在保证环境正确性的前提下，最大限度避免不必要的重复解压，将冷启动时间从分钟级降低到秒级。

## Glossary

- **Bootstrap_Manager**: 负责编排嵌入式 Linux 环境设置的组件（`BootstrapManagerImpl`）
- **Rootfs**: 预构建的 Ubuntu 根文件系统，包含 Node.js 和 Claude CLI，以 tar.xz 格式打包在 APK assets 中
- **Prefix**: 从 APK assets 解压的本地工具链目录，包含 proot、tar、xz 等二进制文件
- **Claude_CLI**: rootfs 内安装的 Claude Code 命令行工具，位于 `/usr/local/bin/claude` 或 `/usr/bin/claude`
- **Version_Marker**: rootfs 内的 `/.claudemobile-bundled-version` 文件，记录当前安装的 rootfs 版本
- **Integrity_Check**: 验证 rootfs 环境完整性的检查流程，决定是否需要重新解压
- **Dangling_Symlink**: 目标文件不存在的符号链接，`File.exists()` 对其返回 `false`
- **Proot**: 用户空间的 root 模拟工具，用于在 Android 上运行 Linux 环境

## Requirements

### Requirement 1: 健壮的 Claude CLI 存在性检测

**User Story:** 作为用户，我希望应用能正确检测 Claude CLI 是否已安装，避免因符号链接问题导致误判为未安装而触发不必要的重新解压。

#### Acceptance Criteria

1. WHEN `isClaudeCliInstalled()` 被调用, THE Bootstrap_Manager SHALL 检查 claude 二进制文件的多个已知路径（`/usr/bin/claude`, `/usr/local/bin/claude`, `bin/claude.exe`）
2. WHEN 检测到符号链接时, THE Bootstrap_Manager SHALL 使用 `java.nio.file.Files.exists()` 和 `Files.isSymbolicLink()` 区分悬空符号链接与有效符号链接
3. WHEN 符号链接目标不存在但符号链接本身存在时, THE Bootstrap_Manager SHALL 尝试解析符号链接目标路径并检查目标文件是否存在于 rootfs 内的其他位置
4. WHEN claude 二进制文件通过路径检查确认存在且文件大小大于 1 MiB 时, THE Bootstrap_Manager SHALL 返回 `true` 表示 Claude CLI 已安装
5. IF 所有已知路径均不存在有效的 claude 二进制文件, THEN THE Bootstrap_Manager SHALL 返回 `false` 并记录诊断日志说明具体失败原因

### Requirement 2: 版本标记文件容错处理

**User Story:** 作为用户，我希望即使版本标记文件意外丢失，只要 rootfs 环境实际完整可用，应用也不会触发重新解压。

#### Acceptance Criteria

1. WHEN Version_Marker 文件不存在但 rootfs 目录结构完整时, THE Bootstrap_Manager SHALL 执行 Integrity_Check 而非直接触发重新解压
2. WHEN Integrity_Check 确认 rootfs 环境完整（目录结构存在、claude 二进制可用）时, THE Bootstrap_Manager SHALL 重新生成 Version_Marker 文件并跳过解压
3. WHEN Integrity_Check 确认 rootfs 环境不完整时, THE Bootstrap_Manager SHALL 触发重新解压流程
4. THE Bootstrap_Manager SHALL 在重新生成 Version_Marker 时写入 `version=unknown-recovered` 和 `source=integrity-check` 标记以区分正常安装和恢复场景

### Requirement 3: 增量版本升级策略

**User Story:** 作为用户，我希望应用升级时只在确实需要时才重新解压 rootfs，而不是每次版本字符串不同就全量重装。

#### Acceptance Criteria

1. WHEN APK 打包的 rootfs 版本与已安装版本不一致时, THE Bootstrap_Manager SHALL 比较语义化版本号而非简单字符串比较
2. WHEN 版本比较确认需要升级时, THE Bootstrap_Manager SHALL 在 wipe rootfs 之前记录诊断日志包含旧版本号和新版本号
3. WHEN 版本比较确认需要升级时, THE Bootstrap_Manager SHALL 先验证新 rootfs asset 文件存在且可读，再执行 wipe 操作
4. IF wipe 操作后解压失败, THEN THE Bootstrap_Manager SHALL 记录错误并将 bootstrap 状态设置为 Failed，包含可操作的错误信息

### Requirement 4: 快速启动路径优化

**User Story:** 作为用户，我希望在 rootfs 已正确安装的情况下，应用启动时的环境检查能在 1 秒内完成。

#### Acceptance Criteria

1. WHEN `isReady()` 被调用且所有组件已安装时, THE Bootstrap_Manager SHALL 在 1000 毫秒内返回结果
2. THE Bootstrap_Manager SHALL 使用轻量级文件存在性检查（而非进程执行）作为 `isReady()` 的主要判断依据
3. WHEN rootfs 已安装且版本匹配时, THE Bootstrap_Manager SHALL 跳过 `installRootfsReal()` 中的 asset 文件解析和空间检查
4. THE Bootstrap_Manager SHALL 缓存 `isPrefixExtracted()` 和 `isRootfsInstalled()` 的结果在单次 bootstrap 调用期间，避免重复文件系统访问

### Requirement 5: 启动状态持久化

**User Story:** 作为用户，我希望应用能记住上次成功启动的状态，在下次启动时快速跳过已完成的步骤。

#### Acceptance Criteria

1. WHEN bootstrap 成功完成时, THE Bootstrap_Manager SHALL 将成功状态持久化到 SharedPreferences，包含完成时间戳和版本信息
2. WHEN 应用启动时, THE Bootstrap_Manager SHALL 首先读取持久化的成功状态，若存在则执行快速验证路径
3. WHEN 快速验证路径确认环境完整时, THE Bootstrap_Manager SHALL 直接返回 Ready 状态而不执行完整 bootstrap 流程
4. IF 快速验证路径发现环境损坏, THEN THE Bootstrap_Manager SHALL 清除持久化状态并回退到完整 bootstrap 流程

### Requirement 6: 诊断与可观测性

**User Story:** 作为开发者，我希望能通过日志了解每次启动时 bootstrap 的决策路径，便于排查用户反馈的重复解压问题。

#### Acceptance Criteria

1. THE Bootstrap_Manager SHALL 在每次 `isReady()` 调用时记录诊断日志，包含各检查项的结果（prefix、shell、rootfs、claude-cli）
2. WHEN 决定跳过解压时, THE Bootstrap_Manager SHALL 记录跳过原因（版本匹配、完整性检查通过等）
3. WHEN 决定执行解压时, THE Bootstrap_Manager SHALL 记录触发原因（版本不匹配、文件缺失、完整性检查失败等）
4. THE Bootstrap_Manager SHALL 记录每次启动的 `isReady()` 检查耗时（毫秒）
