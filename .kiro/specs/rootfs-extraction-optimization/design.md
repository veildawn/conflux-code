# Design Document: Rootfs Extraction Optimization

## Overview

优化 BootstrapManagerImpl 的启动流程，解决每次打开 App 都重新解压 rootfs 的问题。核心策略：增强文件检测健壮性、引入完整性检查容错机制、添加启动状态缓存，将已安装环境的启动验证时间从分钟级降低到秒级。

## Architecture

### 组件变更

```
┌─────────────────────────────────────────────────────┐
│                  BootstrapManagerImpl                 │
│                                                      │
│  ┌──────────────┐  ┌──────────────┐  ┌───────────┐ │
│  │ RootfsInteg- │  │ BootstrapSta-│  │ ClaudeCli │ │
│  │ rityChecker  │  │ teCache      │  │ Detector  │ │
│  └──────────────┘  └──────────────┘  └───────────┘ │
│                                                      │
│  ┌──────────────────────────────────────────────┐   │
│  │         Optimized Bootstrap Flow              │   │
│  │                                               │   │
│  │  1. Read cached state                         │   │
│  │  2. Quick verify (file existence only)        │   │
│  │  3. If valid → Ready (fast path)              │   │
│  │  4. If invalid → Full bootstrap (slow path)   │   │
│  └──────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────┘
```

### 新增组件

1. **ClaudeCliDetector** — 封装 Claude CLI 检测逻辑，处理符号链接、硬链接等边界情况
2. **RootfsIntegrityChecker** — 执行 rootfs 完整性检查，在版本标记缺失时判断是否需要重新解压
3. **BootstrapStateCache** — 基于 SharedPreferences 的启动状态缓存，支持快速启动路径

### 修改组件

1. **BootstrapManagerImpl** — 重构 `isReady()`、`installRootfsReal()`、`isClaudeCliInstalled()` 方法

## Components and Interfaces

### ClaudeCliDetector

负责 Claude CLI 二进制文件的存在性检测，正确处理符号链接和硬链接场景。

**接口：**
- `isInstalled(): Boolean` — 检测 Claude CLI 是否已安装
- `diagnose(): String` — 返回各路径的详细状态描述，用于诊断日志

**依赖：**
- `Context` (Android Application Context)

### RootfsIntegrityChecker

执行 rootfs 环境完整性检查，在版本标记文件缺失时判断是否需要重新解压。

**接口：**
- `check(): IntegrityResult` — 执行完整性检查，返回结构化结果
- `recoverVersionMarker()` — 当完整性检查通过时恢复版本标记文件

**依赖：**
- `Context` (Android Application Context)
- `ClaudeCliDetector`

### BootstrapStateCache

基于 SharedPreferences 的启动状态缓存，支持快速启动路径。

**接口：**
- `markBootstrapComplete(version: String?)` — 标记 bootstrap 成功完成
- `isBootstrapCachedAsComplete(): Boolean` — 查询缓存的完成状态
- `getCachedRootfsVersion(): String?` — 获取缓存的 rootfs 版本
- `invalidate()` — 清除所有缓存状态

**依赖：**
- `Context` (Android Application Context)

### BootstrapManagerImpl（修改）

重构现有的 bootstrap 编排逻辑，注入上述新组件。

**修改的接口方法：**
- `isReady(): Boolean` — 增加快速路径，优先使用缓存状态
- `installRootfsReal(): Result<Unit>` — 增加完整性检查容错逻辑

**新增依赖：**
- `ClaudeCliDetector`
- `RootfsIntegrityChecker`
- `BootstrapStateCache`

## Data Models

### IntegrityResult

```kotlin
data class IntegrityResult(
    val directoryStructureValid: Boolean,  // /etc, /bin, /usr 目录是否存在
    val nodeInstalled: Boolean,            // Node.js 二进制是否可用
    val claudeCliInstalled: Boolean,       // Claude CLI 是否可用
) {
    val isComplete: Boolean
        get() = directoryStructureValid && nodeInstalled && claudeCliInstalled
}
```

### BootstrapStateCache 持久化数据

| Key | Type | Description |
|-----|------|-------------|
| `bootstrap_complete` | Boolean | bootstrap 是否成功完成 |
| `last_success_ts` | Long | 上次成功完成的时间戳 |
| `rootfs_version` | String? | 已安装的 rootfs 版本号 |

### Version Marker 文件格式

```
version=<semantic_version | unknown-recovered>
source=<install | integrity-check>
```

## Detailed Design

### 1. ClaudeCliDetector

```kotlin
// core-bridge/src/main/kotlin/com/claudemobile/core/bridge/bootstrap/ClaudeCliDetector.kt

@Singleton
class ClaudeCliDetector @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val rootfsDir: File
        get() = File(context.filesDir, "rootfs")

    /**
     * 检测 Claude CLI 是否已安装。使用 NIO Files API 正确处理符号链接。
     * 
     * 检查顺序：
     * 1. /usr/bin/claude (wrapper symlink)
     * 2. /usr/local/bin/claude (wrapper symlink)
     * 3. usr/lib/node_modules/@anthropic-ai/claude-code/bin/claude.exe (native binary)
     * 4. usr/local/lib/node_modules/@anthropic-ai/claude-code/bin/claude.exe (native binary)
     */
    fun isInstalled(): Boolean {
        val candidates = listOf(
            "usr/bin/claude",
            "usr/local/bin/claude",
            "usr/lib/node_modules/@anthropic-ai/claude-code/bin/claude.exe",
            "usr/local/lib/node_modules/@anthropic-ai/claude-code/bin/claude.exe",
        )
        
        for (rel in candidates) {
            val path = File(rootfsDir, rel).toPath()
            if (checkBinaryExists(path)) return true
        }
        return false
    }

    /**
     * 使用 NIO API 检查二进制文件，正确处理符号链接场景：
     * - 普通文件：exists() && size > 0
     * - 有效符号链接：exists() (follows link) && size > 0
     * - 悬空符号链接：isSymbolicLink() && 尝试在 rootfs 内解析目标
     */
    private fun checkBinaryExists(path: java.nio.file.Path): Boolean {
        try {
            if (java.nio.file.Files.exists(path)) {
                // File or valid symlink — check size
                val size = java.nio.file.Files.size(path)
                return size > 0L
            }
            // Check if it's a dangling symlink
            if (java.nio.file.Files.isSymbolicLink(path)) {
                // Try to resolve within rootfs
                val target = java.nio.file.Files.readSymbolicLink(path)
                val resolvedInRootfs = rootfsDir.toPath().resolve(
                    target.toString().removePrefix("/")
                )
                return java.nio.file.Files.exists(resolvedInRootfs) &&
                    java.nio.file.Files.size(resolvedInRootfs) > 0L
            }
        } catch (_: Exception) {
            // Fall through to false
        }
        return false
    }

    /**
     * 返回检测结果的详细信息，用于诊断日志。
     */
    fun diagnose(): String {
        // ... 返回各路径的状态描述
    }
}
```

### 2. RootfsIntegrityChecker

```kotlin
// core-bridge/src/main/kotlin/com/claudemobile/core/bridge/bootstrap/RootfsIntegrityChecker.kt

@Singleton
class RootfsIntegrityChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val claudeCliDetector: ClaudeCliDetector,
) {
    private val rootfsDir: File
        get() = File(context.filesDir, "rootfs")

    /**
     * 执行完整性检查。验证 rootfs 的关键目录和文件是否存在。
     */
    fun check(): IntegrityResult {
        val hasEtc = File(rootfsDir, "etc").isDirectory
        val hasBin = File(rootfsDir, "bin").isDirectory
        val hasUsr = File(rootfsDir, "usr").isDirectory
        val hasNode = File(rootfsDir, "usr/bin/node").exists() ||
            File(rootfsDir, "usr/local/bin/node").exists()
        val hasClaude = claudeCliDetector.isInstalled()

        return IntegrityResult(
            directoryStructureValid = hasEtc && hasBin && hasUsr,
            nodeInstalled = hasNode,
            claudeCliInstalled = hasClaude,
        )
    }

    /**
     * 当版本标记缺失但完整性检查通过时，恢复版本标记文件。
     */
    fun recoverVersionMarker() {
        val marker = File(rootfsDir, ".claudemobile-bundled-version")
        marker.writeText("version=unknown-recovered\nsource=integrity-check\n")
    }
}

data class IntegrityResult(
    val directoryStructureValid: Boolean,
    val nodeInstalled: Boolean,
    val claudeCliInstalled: Boolean,
) {
    val isComplete: Boolean
        get() = directoryStructureValid && nodeInstalled && claudeCliInstalled
}
```

### 3. BootstrapStateCache

```kotlin
// core-bridge/src/main/kotlin/com/claudemobile/core/bridge/bootstrap/BootstrapStateCache.kt

@Singleton
class BootstrapStateCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences(
        "bootstrap_state_cache", Context.MODE_PRIVATE
    )

    fun markBootstrapComplete(version: String?) {
        prefs.edit()
            .putBoolean(KEY_BOOTSTRAP_COMPLETE, true)
            .putLong(KEY_LAST_SUCCESS_TIMESTAMP, System.currentTimeMillis())
            .putString(KEY_ROOTFS_VERSION, version)
            .apply()
    }

    fun isBootstrapCachedAsComplete(): Boolean {
        return prefs.getBoolean(KEY_BOOTSTRAP_COMPLETE, false)
    }

    fun getCachedRootfsVersion(): String? {
        return prefs.getString(KEY_ROOTFS_VERSION, null)
    }

    fun invalidate() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_BOOTSTRAP_COMPLETE = "bootstrap_complete"
        private const val KEY_LAST_SUCCESS_TIMESTAMP = "last_success_ts"
        private const val KEY_ROOTFS_VERSION = "rootfs_version"
    }
}
```

### 4. BootstrapManagerImpl 重构

#### 优化后的 `isReady()` 流程

```kotlin
override suspend fun isReady(): Boolean = withContext(dispatchers.io) {
    val startTime = System.currentTimeMillis()
    
    // Fast path: check cached state first
    if (stateCache.isBootstrapCachedAsComplete()) {
        val quickValid = quickVerify()
        val elapsed = System.currentTimeMillis() - startTime
        logDiagnostic("isReady() fast path: valid=$quickValid, elapsed=${elapsed}ms")
        if (quickValid) return@withContext true
        // Cache is stale, invalidate
        stateCache.invalidate()
    }
    
    // Slow path: full verification
    val result = isPrefixExtracted() && isShellExecutable() && 
        isRootfsInstalled() && claudeCliDetector.isInstalled()
    val elapsed = System.currentTimeMillis() - startTime
    logDiagnostic("isReady() full path: ready=$result, elapsed=${elapsed}ms")
    result
}

/**
 * 快速验证：仅检查关键文件存在性，不执行进程。
 */
private fun quickVerify(): Boolean {
    val prefixVersion = File(prefixDir, PrefixExtractorImpl.VERSION_FILE_NAME)
    val rootfsEtc = File(rootfsDir, "etc")
    return prefixVersion.exists() && rootfsEtc.exists() && claudeCliDetector.isInstalled()
}
```

#### 优化后的 `installRootfsReal()` 流程

```kotlin
private suspend fun installRootfsReal(): Result<Unit> {
    // ... (resolve bundled asset, read manifest - unchanged)

    val installedVersion = readInstalledRootfsVersion()
    val bundledVersion = manifestEntry?.version
    val versionMismatch = bundledVersion != null && installedVersion != null && 
        installedVersion != bundledVersion
    val versionMissing = installedVersion == null && rootfsDir.exists()

    // NEW: When version marker is missing, run integrity check before wiping
    if (versionMissing) {
        val integrity = integrityChecker.check()
        if (integrity.isComplete) {
            logDiagnostic("Version marker missing but integrity check passed; recovering marker")
            integrityChecker.recoverVersionMarker()
            emitProgress(BootstrapStep.INSTALL_ROOTFS, 1.0f, "Rootfs verified via integrity check")
            return Result.success(Unit)
        }
        logDiagnostic("Version marker missing and integrity check failed: $integrity")
    }

    // Skip if already installed and version matches
    if (isRootfsInstalled() && claudeCliDetector.isInstalled() && 
        !versionMismatch && !versionMissing) {
        emitProgress(BootstrapStep.INSTALL_ROOTFS, 1.0f, "Rootfs already installed")
        return Result.success(Unit)
    }

    // Version upgrade path
    if (versionMismatch) {
        logDiagnostic("Rootfs upgrade: $installedVersion -> $bundledVersion")
        // Verify new asset is accessible before wiping
        val assetAccessible = runCatching { 
            context.assets.open(bundled).close() 
        }.isSuccess
        if (!assetAccessible) {
            return Result.failure(BootstrapException(
                step = BootstrapStep.INSTALL_ROOTFS,
                message = "Cannot access bundled rootfs asset before upgrade; aborting wipe"
            ))
        }
        wipeRootfsDir()
    }

    // ... (proceed with extraction - unchanged)
}
```

## Error Handling

### 文件系统错误

| 场景 | 处理策略 |
|------|----------|
| 符号链接读取失败（SecurityException） | 捕获异常，记录日志，该路径视为不存在，继续检查下一路径 |
| rootfs 目录不可访问 | `IntegrityResult` 所有字段为 `false`，触发完整 bootstrap |
| SharedPreferences 读写失败 | 捕获异常，回退到完整 bootstrap 流程（不依赖缓存） |
| 版本标记文件写入失败 | 记录警告日志，不阻塞启动流程，下次启动会重新检查 |

### Asset 访问错误

| 场景 | 处理策略 |
|------|----------|
| Bundled rootfs asset 不存在 | 返回 `Result.failure`，不执行 wipe，保留现有环境 |
| Asset 读取中断（IOException） | 返回 `Result.failure`，设置 BootstrapStep.INSTALL_ROOTFS 为 Failed |
| 磁盘空间不足 | 在解压前检查可用空间，不足时返回包含空间信息的错误 |

### 状态恢复

| 场景 | 处理策略 |
|------|----------|
| 缓存状态与实际环境不一致 | `quickVerify()` 失败时自动 `invalidate()` 缓存，回退到完整流程 |
| 完整性检查部分通过 | 视为不完整，触发重新解压 |
| wipe 后解压失败 | 记录错误，设置 Failed 状态，包含可操作的错误信息供用户查看 |

## Correctness Properties

### Property 1: Claude CLI 检测一致性

*For any* rootfs 目录状态，若其中包含有效 claude 二进制文件（大小 > 0），无论该文件是通过普通文件、有效符号链接还是硬链接存在，`ClaudeCliDetector.isInstalled()` 都应返回 `true`。

**Validates: Requirements 1.1, 1.2, 1.4**

### Property 2: 完整性检查幂等性

*For any* 完整的 rootfs 环境，连续多次调用 `RootfsIntegrityChecker.check()` 应返回相同的 `IntegrityResult`。

**Validates: Requirements 2.1**

### Property 3: 版本标记恢复不触发重新解压

*For any* rootfs 环境，若 `IntegrityResult.isComplete == true`，则 `installRootfsReal()` 不应执行 `wipeRootfsDir()` 或解压操作。

**Validates: Requirements 2.2, 2.3**

### Property 4: 快速启动路径性能

*For any* 已缓存为完成状态且实际环境完整的场景，`isReady()` 应在 1000ms 内返回 `true`。

**Validates: Requirements 4.1, 4.2**

### Property 5: 缓存状态一致性（Round-trip）

*For any* version 字符串，`markBootstrapComplete(version)` 后立即调用 `isBootstrapCachedAsComplete()` 应返回 `true`，且 `getCachedRootfsVersion()` 应返回相同的 version 值。`invalidate()` 后 `isBootstrapCachedAsComplete()` 应返回 `false`。

**Validates: Requirements 5.1, 5.4**

### Property 6: 不会在 asset 不可访问时执行 wipe

*For any* 版本不匹配的场景，若 bundled rootfs asset 不可读取，`installRootfsReal()` 不应执行 `wipeRootfsDir()`。

**Validates: Requirements 3.3**

## File Changes

### 新增文件

| File | Purpose |
|------|---------|
| `core-bridge/src/main/kotlin/com/claudemobile/core/bridge/bootstrap/ClaudeCliDetector.kt` | Claude CLI 存在性检测，处理符号链接 |
| `core-bridge/src/main/kotlin/com/claudemobile/core/bridge/bootstrap/RootfsIntegrityChecker.kt` | Rootfs 完整性检查 |
| `core-bridge/src/main/kotlin/com/claudemobile/core/bridge/bootstrap/BootstrapStateCache.kt` | 启动状态缓存 |
| `core-bridge/src/test/kotlin/.../bootstrap/ClaudeCliDetectorTest.kt` | ClaudeCliDetector 单元测试 |
| `core-bridge/src/test/kotlin/.../bootstrap/RootfsIntegrityCheckerTest.kt` | RootfsIntegrityChecker 单元测试 |
| `core-bridge/src/test/kotlin/.../bootstrap/BootstrapStateCacheTest.kt` | BootstrapStateCache 单元测试 |
| `core-bridge/src/test/kotlin/.../bootstrap/BootstrapManagerImplTest.kt` | 重构后的集成测试 |

### 修改文件

| File | Changes |
|------|---------|
| `core-bridge/src/main/kotlin/com/claudemobile/core/bridge/bootstrap/BootstrapManagerImpl.kt` | 注入新组件，重构 `isReady()`、`installRootfsReal()`、`isClaudeCliInstalled()` |

## Dependencies

- `java.nio.file.Files` — Android API 26+ (minSdk 已满足)
- 无新外部依赖

## Testing Strategy

- **单元测试**: 使用临时目录模拟 rootfs 结构，测试各种文件状态（普通文件、符号链接、悬空符号链接）
- **属性测试**: 对 ClaudeCliDetector 和 IntegrityChecker 使用 property-based testing 验证各种文件系统状态组合
- **集成测试**: 验证完整 bootstrap 流程在已安装环境下的快速路径行为
