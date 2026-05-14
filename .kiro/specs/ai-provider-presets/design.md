# 技术设计文档

## 概述

本文档描述 `ai-provider-presets` 功能的技术设计方案。该功能扩展基础规格 `android-claude-termux-client` 的凭据管理与环境注入流程：将单一 Anthropic API key 的配置入口替换为**多 Provider 配置档案（Provider_Profile）**。用户可从内置预设（GLM Coding Plan / MiniMax Token Plan / Kimi Code Plan）中选择并仅输入 API key，或通过 Custom 选项自定义 `baseUrl`、`apiKey`、`model` 等字段。被选中的 Active_Profile 在每次 Session 启动时被 Bridge 读取并转换为标准的 Anthropic 兼容环境变量（`ANTHROPIC_BASE_URL` / `ANTHROPIC_API_KEY` 或 `ANTHROPIC_AUTH_TOKEN` / `ANTHROPIC_MODEL` / 可选 `ANTHROPIC_SMALL_FAST_MODEL`）。

### 与基础规格的关系

本设计是基础规格的**增量设计**，不重新设计整体架构。下列既有设计继续有效并在此复用：

- 整体分层（UI → Domain → Data/Bridge），见基础设计 "架构" 一节。
- Bridge 层与 PTY 通信流程，见基础设计 "Bridge 层通信架构"。
- proot 命令构建与 bind mount 配置，见基础设计 "Proot 调用与 Bind Mount 配置"。
- EncryptedSharedPreferences + Android Keystore 凭据存储机制，见基础设计 "Hilt 依赖注入模块结构" 中的 `CredentialStoreModule`。
- 多模块 Gradle 组织，见基础设计 "多模块 Gradle 项目组织"。

本设计只描述相对于基础设计的**变更 (delta)**：

1. 新增 `Provider_Registry`（内置预设代码注册表）。
2. 新增 `Provider_Profile` 领域模型与 `Provider_Profile_Store`（替代原单 key 存储）。
3. 新增 `Active_Profile` 追踪与 `Flow<ProviderProfile?>` 订阅。
4. 改造 Bridge 层的 `SpawnConfig` 构建（基础规格 R2 AC3 的增量）。
5. 新增 Provider 选择 / 编辑 / 列表 UI 与 onboarding 集成。
6. 新增 Connection_Test 网络探针。
7. 新增旧版单 key 迁移逻辑。
8. 扩展诊断脱敏以覆盖所有 profile 的 key。

### 核心设计原则

1. **纯函数优先**：`Provider_Registry`、环境变量构建 (`buildClaudeEnv`)、`Provider_Profile` 模型均为 `core-domain` 中的纯 Kotlin 类型，可直接用 Kotest property 测试覆盖 R12 的所有正确性属性。
2. **每次启动读新值**：Bridge 在每次 spawn Claude_CLI 时**重新**读取 Active_Profile，不缓存跨越 `SpawnConfig` 构造的任何 model / key 拷贝（满足 R11 AC2）。
3. **纵深防御脱敏**：API key 永远不进入日志（源头不写），导出时仍然做子串替换（第二道防线）。
4. **兼容基础规格**：所有既有环境变量（`HOME` / `PATH` / `TERM` / `LANG`）与进程管理、信号升级、诊断、Foreground Service 行为保持不变。

---

## 架构

### 组件关系增量图

```mermaid
graph TB
    subgraph UI["UI 层 (Compose)"]
        ProviderSelect["ProviderSelectionScreen"]
        ProviderEditor["ProviderEditorScreen"]
        ProviderList["ProviderListScreen"]
        SettingsScreen["SettingsScreen (既有)"]
        Onboarding["BootstrapScreen (既有, 增加 Configure Provider 步骤)"]
        ProviderVM["ProviderViewModel(s)"]
    end

    subgraph Domain["Domain 层 (纯 Kotlin, core-domain)"]
        Registry["ProviderRegistry (内置预设)"]
        ProfileModel["ProviderProfile 数据类"]
        EnvBuilder["buildClaudeEnv (纯函数)"]
        UseCases["Provider Use Cases"]
    end

    subgraph Data["Data 层 (core-data)"]
        ProfileStore["ProviderProfileStoreImpl<br/>(EncryptedSharedPreferences)"]
        Migrator["LegacyKeyMigrator"]
        ConnTester["ConnectionTester (OkHttp)"]
    end

    subgraph Bridge["Bridge 层 (core-bridge)"]
        SpawnEnvAdapter["SpawnEnvAdapter<br/>(读取 Active_Profile + 调用 buildClaudeEnv)"]
        CliBridge["CliBridgeImpl (既有)"]
    end

    UI --> ProviderVM
    ProviderVM --> UseCases
    UseCases --> Registry
    UseCases --> ProfileStore
    UseCases --> ConnTester
    Migrator --> ProfileStore
    CliBridge --> SpawnEnvAdapter
    SpawnEnvAdapter --> ProfileStore
    SpawnEnvAdapter --> EnvBuilder
    EnvBuilder -.pure.-> Bridge
```

### 模块放置（多模块 Gradle）

沿用基础规格的模块分层，本特性的代码按下表分配：

| 组件 | 放置模块 | 备注 |
|------|----------|------|
| `ProviderProfile` 数据类、`PresetReference` 密封类 | `core-domain` | 纯 Kotlin，无 Android 依赖；可直接属性测试 |
| `ProviderRegistry` + 3 个内置预设常量 | `core-domain` | 纯 Kotlin 对象；`displayName` 通过 `@StringRes` id 引用，由 UI 层翻译 |
| `buildClaudeEnv` 纯函数 | `core-domain` | 便于 property 测试；无任何 Android / I/O 依赖 |
| `ProviderProfileStore` 接口 | `core-domain` | 由 Data 层实现 |
| `ProviderProfileStoreImpl` (EncryptedSharedPreferences) | `core-data` | 与基础规格 `CredentialStoreImpl` 同模块、同加密机制 |
| `LegacyKeyMigrator` | `core-data` | 读取旧 `CredentialStore` 与 `SettingsStore` |
| `ConnectionTester` (OkHttp) | `core-data` | 网络 I/O 在 Data 层；依赖 `ProviderProfile` |
| `SpawnEnvAdapter` (薄适配器) | `core-bridge` | 在 Bridge 层注入 `ProviderProfileStore` 与 `buildClaudeEnv`，改造既有 `SpawnConfig` 构造点 |
| Provider UI Screens + ViewModels | `feature-settings`（不新建模块） | 理由：功能紧密耦合于"设置"入口（R11 AC3 要求 Settings 有 "Providers" 入口）；沿用基础规格中 9 模块布局更简洁，避免为三个小屏幕独立建模块 |
| 诊断脱敏扩展 | `core-data`（跟随既有 DiagnosticsExporter） | 增加遍历所有 profile 的 key 与 URL userinfo |

**关于 feature-providers vs feature-settings 的决定**：基础规格的 9 模块布局已经定型（R12 AC1），其中 `feature-settings` 是 Provider 管理的自然入口（R11 AC3）。为三个新屏幕（Select / Editor / List）独立建一个 `feature-providers` 模块会：(a) 增加 9 → 10 模块的结构改动；(b) 与 Settings 相互依赖（Settings 中嵌入 Provider 入口）造成反向引用。结论：**将 Provider UI 放入 `feature-settings`**，并在该模块内用子包 `com.claudemobile.features.settings.providers` 组织代码。

### Hilt 模块增量

在基础规格既有 Hilt 模块基础上新增以下绑定；不删除任何既有绑定。

```kotlin
// ===== core-data =====
@Module
@InstallIn(SingletonComponent::class)
abstract class ProviderProfileModule {
    @Binds
    @Singleton
    abstract fun bindProviderProfileStore(
        impl: ProviderProfileStoreImpl
    ): ProviderProfileStore
}

@Module
@InstallIn(SingletonComponent::class)
object ProviderNetworkModule {
    @Provides
    @Singleton
    @ProviderTestClient  // Qualifier，防止与其他 OkHttp 客户端冲突
    fun provideConnectionTestOkHttp(): OkHttpClient =
        OkHttpClient.Builder()
            .callTimeout(15, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    fun provideConnectionTester(
        @ProviderTestClient client: OkHttpClient,
        dispatchers: CoroutineDispatchers,
    ): ConnectionTester = ConnectionTesterImpl(client, dispatchers)
}

// ===== core-domain (纯工厂, 无 Android 依赖) =====
@Module
@InstallIn(SingletonComponent::class)
object ProviderRegistryModule {
    @Provides
    @Singleton
    fun provideProviderRegistry(): ProviderRegistry = ProviderRegistry.Default
}
```

### 导航图

```mermaid
graph LR
    Bootstrap["BootstrapScreen<br/>(首次启动)"]
    Bootstrap -->|无 Profile| Select
    Sessions["SessionsScreen"]
    Sessions -->|无 Active_Profile 启动 Session| Select
    SettingsEntry["SettingsScreen<br/>Providers 入口"]
    SettingsEntry --> ProviderList
    Select["ProviderSelectionScreen<br/>(列出预设 + Custom)"]
    Select -->|选预设| PresetEditor["ProviderEditorScreen<br/>(Preset 模式: 仅 key 必填)"]
    Select -->|选 Custom| CustomEditor["ProviderEditorScreen<br/>(Custom 模式: 全字段)"]
    PresetEditor -->|Save| Confirm
    CustomEditor -->|Save| Confirm
    PresetEditor -.Test.-> PresetEditor
    CustomEditor -.Test.-> CustomEditor
    Confirm["Active_Profile 设置"]
    Confirm --> ProviderList
    ProviderList["ProviderListScreen<br/>(列表 + 编辑 / 删除 / 测试 / 设为 Active)"]
    ProviderList -->|Edit| PresetEditor
    ProviderList -->|Edit Custom| CustomEditor
    ProviderList -->|Add New| Select
```

---

## 组件与接口

### 1. Provider_Registry（内置预设）

`ProviderRegistry` 是一个纯 Kotlin 对象，在每次 App 启动时返回相同的内置预设列表（R1 AC1-AC3）。预设字面值在本设计阶段**最终化**（R1 要求）。

```kotlin
// core-domain: com.claudemobile.core.domain.providers.ProviderRegistry
enum class AuthHeaderStyle { ApiKey, AuthToken }

data class ProviderPreset(
    val presetId: String,            // 稳定标识，用于序列化 PresetReference
    val displayNameResId: Int,       // 本地化字符串资源 id
    val baseUrl: String,             // https://... (无尾斜杠)
    val defaultModel: String,
    val defaultSmallFastModel: String? = null,
    val authHeaderStyle: AuthHeaderStyle,
)

interface ProviderRegistry {
    fun allPresets(): List<ProviderPreset>
    fun findById(presetId: String): ProviderPreset?

    object Default : ProviderRegistry {
        override fun allPresets(): List<ProviderPreset> = BUILTIN_PRESETS
        override fun findById(presetId: String): ProviderPreset? =
            BUILTIN_PRESETS.firstOrNull { it.presetId == presetId }
    }
}

// 字面值（设计期最终化）
internal val GLM_CODING_PLAN = ProviderPreset(
    presetId = "glm_coding_plan",
    displayNameResId = R.string.provider_preset_glm_coding_plan,
    baseUrl = "https://open.bigmodel.cn/api/anthropic",
    defaultModel = "glm-4.6",
    defaultSmallFastModel = null,
    authHeaderStyle = AuthHeaderStyle.AuthToken,
)

internal val MINIMAX_TOKEN_PLAN = ProviderPreset(
    presetId = "minimax_token_plan",
    displayNameResId = R.string.provider_preset_minimax_token_plan,
    baseUrl = "https://api.minimaxi.com/anthropic",
    defaultModel = "MiniMax-M2",
    defaultSmallFastModel = null,
    authHeaderStyle = AuthHeaderStyle.AuthToken,
)

internal val KIMI_CODE_PLAN = ProviderPreset(
    presetId = "kimi_code_plan",
    displayNameResId = R.string.provider_preset_kimi_code_plan,
    baseUrl = "https://api.moonshot.cn/anthropic",
    defaultModel = "kimi-k2-turbo-preview",
    defaultSmallFastModel = null,
    authHeaderStyle = AuthHeaderStyle.AuthToken,
)

internal val BUILTIN_PRESETS = listOf(
    GLM_CODING_PLAN,
    MINIMAX_TOKEN_PLAN,
    KIMI_CODE_PLAN,
)
```

**说明**：
- 三家供应商（智谱 / MiniMax / 月之暗面 Kimi）均公开文档提供 Anthropic 兼容端点，使用 `Authorization: Bearer <key>` 形式（即 `ANTHROPIC_AUTH_TOKEN`）。Anthropic 原生 API 使用 `x-api-key`（即 `ANTHROPIC_API_KEY`），这也是迁移路径中 `"Anthropic (default)"` custom profile 的缺省值。
- `displayNameResId` 引用 `core-ui` 中的字符串资源，以支持中文 / 英文 / 其他语言；`ProviderRegistry` 本身不做本地化。
- `ProviderRegistry` 不发起任何网络请求（R1 AC3）；预设只能通过 App 更新升级。

### 2. Provider_Profile 领域模型

```kotlin
// core-domain: com.claudemobile.core.domain.providers.ProviderProfile

/** 档案来源: 来自内置预设，或用户自定义。 */
sealed class PresetReference {
    data class Preset(val presetId: String) : PresetReference()
    data object Custom : PresetReference()
}

/**
 * 用户拥有的 Provider 配置档案。所有字段均为不可变 (immutable)。
 * 写入时由 ProviderProfileStore 更新 updatedAt。
 */
data class ProviderProfile(
    val profileId: String,                        // UUIDv4，store 内唯一
    val displayName: String,                      // 用户可编辑
    val presetReference: PresetReference,         // Preset(id) | Custom
    val baseUrl: String,                          // https://... (preset 模式下强制等于 preset.baseUrl)
    val apiKey: String,                           // 明文在内存，存储时加密
    val model: String,
    val smallFastModel: String? = null,           // null 或空字符串 => 不注入 ANTHROPIC_SMALL_FAST_MODEL
    val authHeaderStyle: AuthHeaderStyle,
    val createdAt: Long,                          // epoch millis
    val updatedAt: Long,                          // epoch millis
) {
    /** 掩码表示 (R9 AC2)：只保留最后 4 位。 */
    fun maskedApiKey(): String =
        if (apiKey.length <= 4) "••••" else "••••" + apiKey.takeLast(4)
}
```

#### 验证规则

在 `ProviderProfile.Companion` 中暴露一个纯函数 `validate(draft: ProviderProfileDraft): ValidationResult`。验证发生在 ViewModel 提交前（R3 per-field state）与 Store 写入前（防御性）。

| 字段 | 规则 | 违反后果 |
|------|------|----------|
| `displayName` | 去首尾空白后非空，长度 ≤ 80 | 字段级错误，Save 禁用 |
| `baseUrl` | 能被 `okhttp3.HttpUrl.parse()` 接受 **且** scheme=`https` | 字段级错误，Save 禁用 |
| `baseUrl` (preset 模式下) | 必须 **等于** `preset.baseUrl` | Store 拒绝写入 (R4 AC4) |
| `apiKey` | 非空（长度 ≥ 1） | 字段级错误 |
| `model` | 去首尾空白后非空 | 字段级错误 |
| `smallFastModel` | 允许 null / 空；否则去首尾空白后非空 | 仅警告 |
| `authHeaderStyle` | `ApiKey` 或 `AuthToken` | 枚举保证 |

### 3. Provider_Profile_Store（持久化）

`ProviderProfileStore` 使用与基础规格 `CredentialStore` **同一套** EncryptedSharedPreferences + Android Keystore 机制（R9 AC1），但存储在不同的 shared-preferences 文件名下（`provider_profiles.xml`）避免与旧 key 混淆。

#### 存储模式选择

**选择：单一 EncryptedSharedPreferences 实例 + 每个 profile 一个 JSON blob key + 独立 active-profile-id key。**

| 方案 | 优点 | 缺点 | 结论 |
|------|------|------|------|
| A) 每字段一个 SP key（`profile.{id}.displayName` 等） | 直观 | 字段多（9 字段 × N profile）、部分更新易产生不一致、删除需要 N 次 remove | 否决 |
| B) 单 JSON blob 列出所有 profile + active id | 单次写入所有数据 | 任何写操作都必须重写全量 blob，竞态风险大 | 否决 |
| **C) 每 profile 一个 JSON blob key + 独立 active id key** | 原子写入单 profile；列表扫描只需一次 SP 读 | 需要扫描 key 前缀 | **选用** |

SP 键命名：
- `profile.{profileId}` → `String`，JSON 序列化后的 `ProviderProfile`
- `active_profile_id` → `String?`，当前 Active_Profile 的 `profileId`（或不存在）

JSON schema（使用 kotlinx.serialization）：

```json
{
  "profileId": "a7f8...",
  "displayName": "GLM Coding",
  "presetReference": { "type": "preset", "presetId": "glm_coding_plan" },
  "baseUrl": "https://open.bigmodel.cn/api/anthropic",
  "apiKey": "...",
  "model": "glm-4.6",
  "smallFastModel": null,
  "authHeaderStyle": "AuthToken",
  "createdAt": 1730000000000,
  "updatedAt": 1730000000000
}
```

`presetReference` 的 `type` 字段取值 `"preset"` 或 `"custom"`；`Custom` 不含其他字段。

#### 接口

```kotlin
// core-domain
interface ProviderProfileStore {
    fun observeProfiles(): Flow<List<ProviderProfile>>       // 按 updatedAt 降序
    fun observeActiveProfile(): Flow<ProviderProfile?>       // 订阅 Active_Profile 变化
    suspend fun list(): List<ProviderProfile>
    suspend fun get(profileId: String): ProviderProfile?
    suspend fun getActive(): ProviderProfile?                 // 每次重新读 (不缓存)
    suspend fun upsert(profile: ProviderProfile): Result<Unit>
    suspend fun delete(profileId: String): Result<Unit>
    suspend fun setActive(profileId: String?): Result<Unit>
    suspend fun deleteAll(): Result<Unit>                     // R9 AC5
}
```

#### 读写流程

```mermaid
sequenceDiagram
    participant VM as ViewModel
    participant Store as ProviderProfileStore
    participant SP as EncryptedSharedPreferences
    participant KS as Android Keystore

    VM->>Store: upsert(profile)
    Store->>Store: validate(profile)
    Store->>Store: JSON 序列化
    Store->>SP: edit { putString("profile.{id}", json) }
    SP->>KS: 加密 (master key)
    SP-->>Store: commit 结果
    Store->>Store: 触发 observeProfiles / observeActiveProfile Flow
    Store-->>VM: Result.success(Unit)

    VM->>Store: observeActiveProfile().collect
    Store->>SP: 读 active_profile_id
    SP->>KS: 解密
    SP-->>Store: profileId
    Store->>SP: 读 profile.{profileId}
    SP-->>Store: JSON
    Store->>Store: JSON 反序列化
    Store-->>VM: ProviderProfile
```

**往返保证**（属性 1）：写入的 `ProviderProfile` 经 JSON 序列化 → EncryptedSP 加密存储 → 读取时解密 → JSON 反序列化，所有字段（含 `apiKey`）均等值返回。这是一个纯函数式对 JSON codec 的 round-trip，可直接用 Kotest property 测试覆盖。

**Keystore 不可用恢复**（R9 AC4）：
1. `ProviderProfileStoreImpl` 捕获 `KeyStoreException` / `InvalidKeyException` / `AEADBadTagException`。
2. 返回 `Result.failure(ProviderProfileStoreError.KeystoreUnavailable(profileId))`。
3. ViewModel 将错误转成 `UiState.ProfileUnreadable`，提示用户"凭据已失效，请重新输入 API key"。
4. 用户通过 ProviderEditorScreen 重写 profile，Store 覆盖旧 JSON blob 并重置加密。
5. 当所有密文都无法解密时，提供"重置凭据"按钮（调用 `deleteAll()`）。

**删除时覆写**（R4 AC5）：
1. 读出原 JSON → 用零字节覆写 apiKey 字段，再次写回一次。
2. 调用 `SharedPreferences.Editor.remove("profile.{id}").commit()`。
3. 由于 SP 的底层 xml 文件按块写入，无法保证物理磁盘上的旧 cipher text 被立即擦除；这是 EncryptedSharedPreferences 的既知局限，已在基础规格中被接受。

### 4. Active_Profile 追踪

`observeActiveProfile(): Flow<ProviderProfile?>` 的实现：

```kotlin
// core-data: ProviderProfileStoreImpl
override fun observeActiveProfile(): Flow<ProviderProfile?> =
    activeIdChanges                                   // SharedPreferences listener flow
        .onStart { emit(sp.getString(ACTIVE_ID_KEY, null)) }
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else profileChanges(id)                   // 订阅 profile.{id} 的变更
        }
        .map { profile -> profile }                   // null 表示被删除
        .distinctUntilChanged()
        .flowOn(dispatchers.io)
```

**200ms 约束**（R5 AC2, R11 AC6）：
- `EncryptedSharedPreferences.edit { ... }.apply()` 后立即触发 `OnSharedPreferenceChangeListener` 回调（Android 保证 < 100ms on commit）。
- 本设计在 `setActive` 与 `upsert` 路径上都使用 `commit()`（同步），不使用 `apply()`（异步），确保 200ms 内 downstream 可见。
- 测试验证方法：Turbine 上 `active.test { setActive(id); awaitItem() }` 计时 < 200ms。

**每次 spawn 重新读**（R5 AC4, R11 AC2）：
- Bridge 层 `SpawnEnvAdapter` 在 spawn 时调用 `store.getActive()`（挂起），**不**使用 cached value、**不**使用 `StateFlow.value` 的 snapshot。
- 如果 `getActive()` 返回 `null`，Bridge 中止 spawn 并发出 `BridgeEvent.Error(NO_ACTIVE_PROFILE)`（R6 AC8）。

### 5. Bridge 集成（R2 AC3 增量）

**基础规格 R2 AC3**：Bridge 在 exec 前设置 `HOME` / `PATH` / `TERM` / `LANG` / `ANTHROPIC_API_KEY`。

**本特性增量**（R6）：
- 移除固定的 `ANTHROPIC_API_KEY` 注入；改为从 Active_Profile 派生 5 个变量。
- 保留 `HOME` / `PATH` / `TERM` / `LANG`。
- 新增纯函数 `buildClaudeEnv`，使环境变量构造可属性测试。

```kotlin
// core-domain: com.claudemobile.core.domain.providers.EnvBuilder

/**
 * 将 Active_Profile 转换为 Anthropic 兼容环境变量。纯函数、无 I/O、不依赖 Android。
 *
 * 与基础规格的关系：base 参数对应基础规格 R2 AC3 中的 {HOME, PATH, TERM, LANG}。
 * 本函数在其之上叠加 Anthropic 兼容变量。
 *
 * 互斥: 根据 profile.authHeaderStyle，只设置 ANTHROPIC_API_KEY 和
 * ANTHROPIC_AUTH_TOKEN 中的一个；另一个即使 base 中存在也会被移除。
 *
 * @param base 既有环境变量 (HOME / PATH / TERM / LANG 等)
 * @param profile 当前 Active_Profile
 * @return 新的 env map，保留 base 中非冲突键 + 追加 Anthropic 变量
 */
fun buildClaudeEnv(
    base: Map<String, String>,
    profile: ProviderProfile,
): Map<String, String> {
    val out = base.toMutableMap()

    // 移除可能遗留的旧值，保证互斥
    out.remove("ANTHROPIC_API_KEY")
    out.remove("ANTHROPIC_AUTH_TOKEN")
    out.remove("ANTHROPIC_SMALL_FAST_MODEL")

    out["ANTHROPIC_BASE_URL"] = profile.baseUrl

    when (profile.authHeaderStyle) {
        AuthHeaderStyle.ApiKey    -> out["ANTHROPIC_API_KEY"]    = profile.apiKey
        AuthHeaderStyle.AuthToken -> out["ANTHROPIC_AUTH_TOKEN"] = profile.apiKey
    }

    out["ANTHROPIC_MODEL"] = profile.model

    profile.smallFastModel
        ?.takeIf { it.isNotBlank() }
        ?.let { out["ANTHROPIC_SMALL_FAST_MODEL"] = it }

    return out
}
```

#### Bridge 层 spawn 流程（增量 sequence）

```mermaid
sequenceDiagram
    participant VM as ChatViewModel
    participant Bridge as CliBridgeImpl
    participant Adapter as SpawnEnvAdapter
    participant Store as ProviderProfileStore
    participant EnvBuilder as buildClaudeEnv (纯)
    participant Exec as ProcessExecutor
    participant JNI as TerminalJni

    VM->>Bridge: openSession(sessionId, workspace)
    Bridge->>Adapter: prepareEnv(baseEnv)
    Adapter->>Store: getActive()
    Store-->>Adapter: ProviderProfile? (每次重新读)
    alt profile == null
        Adapter-->>Bridge: NoActiveProfileError
        Bridge-->>VM: BridgeEvent.Error(NO_ACTIVE_PROFILE)
    else profile != null
        Adapter->>EnvBuilder: buildClaudeEnv(baseEnv, profile)
        EnvBuilder-->>Adapter: env map
        Adapter-->>Bridge: SpawnConfig(env = envMap, ...)
        Bridge->>Exec: execute(spawnConfig)
        Exec->>JNI: createSubprocess(cmd, cwd, args, envArray, rows, cols)
        JNI-->>Exec: [pid, ptyFd]
        Exec-->>Bridge: Process
    end
```

**不记录 apiKey**（R6 AC7）：`SpawnConfig` 在传入 JNI 前 `toString()` 被重写为**不**包含 env map 值的形式（只包含 keys）；所有诊断日志点记录的是 `SpawnConfig.toSafeString()`。

### 6. UI 设计

#### 6.1 屏幕结构

```
ProviderSelectionScreen (Compose)
├── TopAppBar: "选择 Provider"
├── LazyColumn
│   ├── 3 × BuiltinPresetRow (显示 displayName + 简述)
│   ├── Divider
│   └── CustomProviderRow (最末)
└── 每行点击 → 导航到 ProviderEditorScreen(mode)

ProviderEditorScreen (Compose)
├── mode: Preset(presetId) | Custom | Edit(profileId)
├── Preset 模式:
│   ├── 只读 baseUrl (禁用编辑, 显示 "固定" tooltip)
│   ├── 可编辑 displayName (默认为 preset.displayName)
│   ├── 可编辑 model (默认为 preset.defaultModel)
│   ├── 可编辑 smallFastModel (可空)
│   ├── 必填 apiKey (密码框, 支持查看最后 4 位)
│   └── authHeaderStyle: 不显示 (固定为 preset 定义的)
├── Custom 模式:
│   ├── 必填 displayName
│   ├── 必填 baseUrl (https:// 校验)
│   ├── 必填 apiKey
│   ├── 必填 model
│   ├── 可选 smallFastModel
│   └── 单选 authHeaderStyle: ApiKey (默认) | AuthToken
├── 底部: "Test Connection" + "Save"
└── 每字段右侧实时显示字段级校验状态 ✓ / ✗

ProviderListScreen (Compose)
├── TopAppBar: "Providers" + "+ 添加" FAB
├── LazyColumn (按 updatedAt 降序)
│   └── 每行: displayName / preset 或 "custom" / maskedApiKey / model
│            + 徽标 (Active) / (最近测试成功)
│            + 溢出菜单: 设为 Active / 编辑 / 测试 / 删除
└── 空列表: 跳转到 ProviderSelectionScreen

SettingsScreen (既有, 增量)
├── ... 既有偏好项 ...
├── + "Providers" 入口 (R11 AC3) → ProviderListScreen
└── + Active_Profile 摘要 (R5 AC3): displayName + 有效 model
```

#### 6.2 ViewModel UiState（UDF）

```kotlin
// feature-settings/providers/ProviderEditorViewModel.kt

sealed class ProviderEditorUiState {
    data object Loading : ProviderEditorUiState()
    data class Editing(
        val mode: EditorMode,                        // Preset(id) | Custom | Edit(profileId)
        val form: FormState,                         // 字段 + 每字段校验状态
        val testResult: ConnectionTestOutcome? = null,
        val isSaving: Boolean = false,
    ) : ProviderEditorUiState()
    data class Saved(val profileId: String) : ProviderEditorUiState()
    data class Error(val reason: String) : ProviderEditorUiState()
}

data class FormState(
    val displayName: Field<String>,
    val baseUrl: Field<String>,        // Preset 模式下 readonly
    val apiKey: Field<String>,
    val model: Field<String>,
    val smallFastModel: Field<String>,
    val authHeaderStyle: AuthHeaderStyle,
) {
    val submitEnabled: Boolean
        get() = displayName.valid && baseUrl.valid && apiKey.valid && model.valid
}

data class Field<T>(
    val value: T,
    val valid: Boolean,
    val error: String? = null,  // 本地化字符串或 null
)

sealed class ProviderEditorIntent {
    data class UpdateField(val field: FieldId, val value: String) : ProviderEditorIntent()
    data class UpdateAuthStyle(val style: AuthHeaderStyle) : ProviderEditorIntent()
    data object TestConnection : ProviderEditorIntent()
    data object Save : ProviderEditorIntent()
    data object Cancel : ProviderEditorIntent()
}
```

其他 ViewModel（`ProviderSelectionViewModel`、`ProviderListViewModel`）遵循相同模式。

#### 6.3 首次启动 Onboarding 集成（R11 AC4）

基础规格中的 `BootstrapScreen` 在 5 个步骤（extract prefix / verify proot / install rootfs / install node / install claude）完成后，新增**第 6 步"配置 Provider"**：

```
Step 1-5: (基础规格)
Step 6: Configure Provider
  │
  ├─ 检测 ProviderProfileStore.list() 是否为空
  ├─ 如为空 → 导航到 ProviderSelectionScreen (不可跳过)
  └─ 用户完成首个 profile 创建并设为 Active → 返回主界面
```

**迁移优先**：在 Step 6 之前，如果检测到旧版凭据（见第 8 节 "Migration"），先执行迁移，完成后跳过 Step 6。

### 7. Connection_Test 设计

#### 7.1 技术方案

用 OkHttp 发送一次 `POST {baseUrl}/v1/messages`，payload 最小化：

```http
POST {baseUrl}/v1/messages
Host: ...
Content-Type: application/json
anthropic-version: 2023-06-01
x-api-key: {key}                  ← 当 authHeaderStyle=ApiKey
Authorization: Bearer {key}       ← 当 authHeaderStyle=AuthToken

{
  "model": "{profile.model}",
  "max_tokens": 1,
  "messages": [{"role": "user", "content": "ping"}]
}
```

最小化原因：`max_tokens=1` 让计费 / token 消耗趋近 0，同时能触发 provider 的鉴权与 model 校验。

#### 7.2 响应分类

```kotlin
enum class ConnectionTestOutcome {
    Ok,             // 2xx, 或 400 (因 max_tokens=1 可能触发 content 错误但鉴权 OK)
    Unauthorized,   // 401, 403
    Unreachable,    // SocketTimeoutException, UnknownHostException, ConnectException, SSLException (非证书)
    InvalidUrl,     // baseUrl 本身无法构造 HttpUrl, 或 scheme != https
    InvalidModel,   // 404 + 响应体包含 "model"; 或结构化错误 error.type == "not_found_error"
    UnknownError,   // 5xx、其他非预期响应
}

data class ConnectionTestResult(
    val outcome: ConnectionTestOutcome,
    val userReason: String,        // 已本地化且不含 apiKey
)
```

分类规则（decision table）：

| 事件 | 分类 |
|------|------|
| `HttpUrl.parse(baseUrl) == null` 或 scheme != `https` | `InvalidUrl` |
| `UnknownHostException` / `ConnectException` / `SocketTimeoutException` | `Unreachable` |
| HTTP 401 / 403 | `Unauthorized` |
| HTTP 200~299 | `Ok` |
| HTTP 400 + 响应体包含 `"invalid_request_error"` 且不含 `"authentication"` | `Ok`（最小 payload 不是鉴权问题） |
| HTTP 404 或响应体 `error.type == "not_found_error"` 且消息含 `"model"` | `InvalidModel` |
| HTTP 5xx 或其他 | `UnknownError` |

#### 7.3 超时与接口

```kotlin
interface ConnectionTester {
    /** 15 秒总超时 (R7 AC2)；永不抛异常，永不返回 apiKey。 */
    suspend fun test(profile: ProviderProfile): ConnectionTestResult
}

class ConnectionTesterImpl @Inject constructor(
    @ProviderTestClient private val client: OkHttpClient,  // callTimeout(15, SECONDS)
    private val dispatchers: CoroutineDispatchers,
) : ConnectionTester {
    override suspend fun test(profile: ProviderProfile): ConnectionTestResult =
        withContext(dispatchers.io) {
            val url = HttpUrl.parse("${profile.baseUrl.trimEnd('/')}/v1/messages")
                ?: return@withContext invalidUrl()

            val body = """{"model":${jsonEscape(profile.model)},"max_tokens":1,
                           "messages":[{"role":"user","content":"ping"}]}"""
                .toRequestBody("application/json".toMediaType())

            val req = Request.Builder()
                .url(url)
                .header("anthropic-version", "2023-06-01")
                .apply {
                    when (profile.authHeaderStyle) {
                        AuthHeaderStyle.ApiKey    -> header("x-api-key", profile.apiKey)
                        AuthHeaderStyle.AuthToken -> header("Authorization",
                                                            "Bearer ${profile.apiKey}")
                    }
                }
                .post(body)
                .build()

            runCatching { client.newCall(req).execute().use { classify(it) } }
                .recover { classifyException(it) }
                .getOrDefault(unknownError())
        }
}
```

**不记录 key**（R7 AC5, R10 AC4）：
- `OkHttpClient` **不安装** `HttpLoggingInterceptor`；
- `ConnectionTestResult.userReason` 永远基于分类枚举构造本地化文案，不回显 HTTP header；
- 异常在日志里只记录类名与类型，不记录 `Request.headers` 的任何值。

### 8. 迁移（旧版单 key → 新版 Profile）

#### 8.1 触发条件（R8 AC1）

```
legacy API key 存在 (CredentialStore 非空) 
    AND ProviderProfileStore.list().isEmpty() 
    AND DataStore[provider_migration_v1_done] != true
```

#### 8.2 算法（幂等、失败可回滚）

```
1. 读 DataStore["provider_migration_v1_done"]
   若 = true  → 跳过，结束
2. 读 legacyKey = CredentialStore.getApiKey()
   若 null  → 写 provider_migration_v1_done = true 并结束
              (无 legacy key, 但将标记写入以避免下次重复判断)
3. 读 legacyModel = SettingsStore.getModel() (来自基础规格 R9 AC1 的 selected Claude model identifier)
   若空 → legacyModel = "claude-3-5-sonnet-20241022"  // 设计期定义的 fallback
4. 构造迁移 profile:
   ProviderProfile(
       profileId       = UUID.randomUUID().toString(),
       displayName     = "Anthropic (default)",
       presetReference = PresetReference.Custom,
       baseUrl         = "https://api.anthropic.com",
       apiKey          = legacyKey,
       model           = legacyModel,
       smallFastModel  = null,
       authHeaderStyle = AuthHeaderStyle.ApiKey,
       createdAt       = now, updatedAt = now,
   )
5. 调用 ProviderProfileStore.upsert(profile)
   失败 → 不做任何后续步骤，上报 MigrationError, UI 展示 "migration failed, 重试" 按钮 (R8 AC4)
          legacy key 保留在 CredentialStore, migration 标记保持 false (下次重试)
6. 调用 ProviderProfileStore.setActive(profile.profileId)  (R8 AC2)
   失败 → 同 5
7. 删除 legacy: CredentialStore.clearApiKey() (R8 AC3)
   删除 legacy: SettingsStore.clearModel()
8. 写 DataStore["provider_migration_v1_done"] = true  (R8 AC5)
```

**标记定义**：`booleanPreferencesKey("provider_migration_v1_done")`，存在且为 `true` 即视为已完成。

**可回滚性**：步骤 5-6 失败时，不删除 legacy 凭据，因此下次启动可重试；`upsert` 为单 JSON blob，写入原子，不会留下半个 profile。

**幂等性**：迁移标记 + "list 非空即跳过" 的双重条件确保绝不重复执行。

```mermaid
sequenceDiagram
    participant App as App Launch
    participant Migrator as LegacyKeyMigrator
    participant DS as DataStore
    participant Legacy as Legacy CredentialStore
    participant LegacySettings as Legacy SettingsStore (model key)
    participant Store as ProviderProfileStore

    App->>Migrator: runIfNeeded()
    Migrator->>DS: read "provider_migration_v1_done"
    alt 已完成
        DS-->>Migrator: true
        Migrator-->>App: skip
    else 未完成
        DS-->>Migrator: false / null
        Migrator->>Store: list()
        Store-->>Migrator: emptyList (条件满足)
        Migrator->>Legacy: getApiKey()
        Legacy-->>Migrator: "sk-ant-..." (或 null)
        Migrator->>LegacySettings: getModel()
        LegacySettings-->>Migrator: "claude-3-..." (或 null → fallback)
        Migrator->>Store: upsert(migratedProfile)
        Store-->>Migrator: Success
        Migrator->>Store: setActive(profileId)
        Store-->>Migrator: Success
        Migrator->>Legacy: clearApiKey()
        Migrator->>LegacySettings: clearModel()
        Migrator->>DS: write "provider_migration_v1_done" = true
        Migrator-->>App: MigratedOk(profileId)
    end
```

### 9. 诊断脱敏扩展

基础规格的 `DiagnosticsExporter` 已实现单 key 的子串替换（R13 AC3, AC5）。本特性扩展为遍历所有 profile。

#### 9.1 扩展函数签名

```kotlin
// core-data: com.claudemobile.core.data.diagnostics.ProviderRedaction

/**
 * 在导出文本中脱敏所有 ProviderProfile 的敏感信息。
 *
 * - 遍历传入 profiles 并对每个 apiKey 做子串替换为 "•••REDACTED•••"。
 * - 对每个 profile 的 baseUrl 检查是否含 userinfo (形如 scheme://user:token@host)，
 *   如有，将 "user:token@" 整体替换为 "REDACTED@"。
 *
 * 纯函数：相同输入始终产生相同输出；无 I/O。
 *
 * @param text  原始诊断导出文本
 * @param profiles 当前 Store 中所有 profile (包括非 Active 的)
 * @return 脱敏后的文本
 */
fun redactProviderSecrets(
    text: String,
    profiles: List<ProviderProfile>,
): String
```

#### 9.2 脱敏规则

| 场景 | 规则 |
|------|------|
| 每个 `p.apiKey`（长度 ≥ 4） | `text.replace(p.apiKey, "•••REDACTED•••")` |
| 每个 `p.baseUrl` 含 `://user:token@host` | 将 userinfo 整段替换 |
| 不再遍历时 UI 中的 `maskedApiKey()` | 无需处理（本就不含全 key） |

#### 9.3 纵深防御

R10 AC4 明确要求：诊断日志在**写入时就不应包含 apiKey**。本设计落实：
- 基础规格 `CliBridgeImpl` 的诊断日志记录的是 `SpawnConfig.toSafeString()`（见第 5 节）——env map 的 keys 有，values 被 `[REDACTED]` 占位。
- `ConnectionTester` 只记录 outcome 枚举与类名；
- 任何捕获到的 exception 先通过相同的 `redactProviderSecrets(ex.message ?: "", profiles)` 过滤再写入。

因此，`redactProviderSecrets` 是**第二道防线**，属于"永远不应触发替换，但触发了也能捕获"的安全网。属性 3 与属性 4 分别验证写入时非泄漏与导出时非泄漏。

---

## 数据模型

### ProviderProfile（Domain 层）

定义见第 2 节。作为值对象 (`data class`)，所有字段 `val`，通过 `copy()` 派生更新。

### ProviderPreset / PresetReference

定义见第 1 节与第 2 节。

### JSON 存储 schema（Data 层）

每个 `profile.{profileId}` 键存储一个 `ProviderProfile` 的 JSON。kotlinx.serialization 配置：

```kotlin
@Serializable
private data class ProviderProfileDto(
    val profileId: String,
    val displayName: String,
    val presetReference: PresetReferenceDto,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val smallFastModel: String? = null,
    val authHeaderStyle: String,            // "ApiKey" | "AuthToken"
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
private sealed class PresetReferenceDto {
    @Serializable @SerialName("preset")
    data class Preset(val presetId: String) : PresetReferenceDto()

    @Serializable @SerialName("custom")
    data object Custom : PresetReferenceDto()
}
```

`ProviderProfileStoreImpl` 提供 `ProviderProfile <-> ProviderProfileDto` 的双向 mapper，mapper 本身为纯函数，是属性 1 的一部分。

### DataStore 增量键

在基础规格 `SettingsKeys` 基础上新增：

```kotlin
object ProviderMigrationKeys {
    val PROVIDER_MIGRATION_V1_DONE = booleanPreferencesKey("provider_migration_v1_done")
}
```

**移除**（R11 AC1）：基础规格中的 `SettingsKeys.MODEL_ID` 不再被使用，但**保留 key 定义一个发布周期**以便迁移读取；迁移完成后 clearModel() 清除值。未来版本可删除 key 常量本身。

### 本地化字符串资源（增量）

在 `core-ui/src/main/res/values/strings.xml` 与 `values-zh-rCN/strings.xml` 中新增：

```
provider_preset_glm_coding_plan        "GLM Coding Plan"
provider_preset_minimax_token_plan     "MiniMax Token Plan"
provider_preset_kimi_code_plan         "Kimi Code Plan"
provider_custom_label                  "Custom (Anthropic compatible)"
provider_field_displayname             "Display name"
... (每个字段标签、错误文案、Connection_Test outcome 文案)
```

---

## 正确性属性

*属性（Property）是一种在系统所有有效执行中都应保持为真的特征或行为——本质上是关于系统应该做什么的形式化陈述。属性是人类可读规范与机器可验证正确性保证之间的桥梁。*

本节属性由第 12 节需求（Requirement 12）驱动，并通过 prework 分析在 Requirement 1–11 中识别出的相关通用属性加以补充。所有属性针对纯函数（`buildClaudeEnv`、JSON codec、校验器、脱敏器）或由 `ProviderProfileStore` 封装的可注入组件，均可在单元测试环境中用 Kotest property 测试覆盖 100+ 次迭代。

### 属性 1: Provider_Profile 写读往返

*对于任意*通过 `ProviderProfileStore.upsert` 写入的 `ProviderProfile` `p`，随后调用 `get(p.profileId)` 读回的 `p'` 应当满足：`p'.displayName`、`p'.presetReference`、`p'.baseUrl`、`p'.apiKey`、`p'.model`、`p'.smallFastModel`、`p'.authHeaderStyle`、`p'.createdAt` 与 `p` 对应字段全部相等；`p'.updatedAt >= p.updatedAt`。

**验证: 需求 4.7, 12.1**

### 属性 2: 表单校验的通用性

*对于任意* `ProviderProfileDraft`，`FormState.submitEnabled` 为 `true` 当且仅当 (a) `displayName` 去空白后非空且长度 ≤ 80，(b) `baseUrl` 被 `okhttp3.HttpUrl.parse` 接受且 scheme 为 `https`，(c) `apiKey` 非空，(d) `model` 去空白后非空；且若 `submitEnabled == false`，则 `ProviderProfileStore.upsert` 永不被 ViewModel 调用。

**验证: 需求 2.4, 3.2, 3.4, 3.5, 3.6**

### 属性 3: 字段级校验独立性

*对于任意*表单 `form` 与任意字段 `f ∈ {displayName, baseUrl, apiKey, model}`，固定其他字段的值，`form.f.valid` 的值仅依赖于 `form.f.value` 的值，不依赖其他字段的取值。

**验证: 需求 3.3**

### 属性 4: Profile 列表按 updatedAt 降序

*对于任意*存储在 `ProviderProfileStore` 中的 `ProviderProfile` 列表，`observeProfiles().first()` 返回的列表中任意相邻元素 `p_i`、`p_{i+1}` 满足 `p_i.updatedAt >= p_{i+1}.updatedAt`。

**验证: 需求 4.1**

### 属性 5: 编辑时 updatedAt 单调、createdAt 不变

*对于任意*已存在的 `ProviderProfile` `p_old` 与任意有效编辑后的 `p_new`，执行 `upsert(p_new)` 后再读回的 `p_persisted` 满足 `p_persisted.createdAt == p_old.createdAt` 且 `p_persisted.updatedAt >= p_old.updatedAt`。

**验证: 需求 4.3**

### 属性 6: 预设衍生 Profile 的 baseUrl 不可变

*对于任意* `presetReference = Preset(presetId)` 的 `ProviderProfile` `p` 与任意 `baseUrl' != registry.findById(presetId).baseUrl`，将 `p` 的 `baseUrl` 更改为 `baseUrl'` 后调用 `upsert` 应当返回 `Result.failure(BaseUrlLocked)`。

**验证: 需求 4.4**

### 属性 7: 删除 Active_Profile 清空 active 引用

*对于任意*存储状态，若 `activeId == p.profileId`，则 `delete(p.profileId)` 后 `getActive() == null`。

**验证: 需求 4.6**

### 属性 8: 最多一个 Active_Profile

*对于任意* `setActive` / `upsert` / `delete` 的操作序列，在任一时刻 `EncryptedSharedPreferences` 中与 `active_profile_id` 键关联的值最多为一个字符串或空。

**验证: 需求 5.1**

### 属性 9: 每次 spawn 重新读取 Active_Profile（无缓存）

*对于任意*两次相邻的 Bridge spawn 请求 `S1`、`S2`，若在 `S1` 与 `S2` 之间 Active_Profile 被从 `p1` 变更为 `p2`，则 `S1` 使用的 env 源于 `p1`、`S2` 使用的 env 源于 `p2`；Bridge 层不存在任何跨越 `SpawnConfig` 构造、缓存 `p1.model` / `p1.apiKey` / `p1.baseUrl` 的中间变量。

**验证: 需求 5.4, 6.1, 11.2**

### 属性 10: 环境变量注入属性

*对于任意* `ProviderProfile` `p` 和任意基础环境 `base`，设 `E = buildClaudeEnv(base, p)`，则：

- `E["ANTHROPIC_BASE_URL"] == p.baseUrl`；
- 恰好一个等式成立：`E["ANTHROPIC_API_KEY"] == p.apiKey`（当 `p.authHeaderStyle == ApiKey`）或 `E["ANTHROPIC_AUTH_TOKEN"] == p.apiKey`（当 `p.authHeaderStyle == AuthToken`），另一键不存在于 `E` 中（互斥性）；
- `E["ANTHROPIC_MODEL"] == p.model`；
- 若 `p.smallFastModel != null && p.smallFastModel.isNotBlank()`，则 `E["ANTHROPIC_SMALL_FAST_MODEL"] == p.smallFastModel`；否则 `"ANTHROPIC_SMALL_FAST_MODEL" !in E`；
- 对 `base` 中任意与以上键不冲突的 `(k, v)`，`E[k] == v`（`HOME` / `PATH` / `TERM` / `LANG` 等基础规格环境变量被保留）。

**验证: 需求 6.2, 6.3, 6.4, 6.5, 6.6, 12.2**

### 属性 11: `buildClaudeEnv` 纯函数确定性

*对于任意*相同的输入 `(base, profile)`，两次独立调用 `buildClaudeEnv(base, profile)` 返回的 `Map<String,String>` 相等；`buildClaudeEnv` 不写任何 I/O、不读取任何外部状态。

**验证: 需求 12.5**

### 属性 12: Session 内存日志不泄漏

*对于任意* `ProviderProfile` `p` 作为 Active_Profile 启动的 Claude_CLI Session，在该 Session 生命周期内由 Bridge、Output_Parser、Diagnostics 组件写入的所有内存日志条目 `L` 的拼接字符串中，`p.apiKey` 不作为子串出现。

**验证: 需求 6.7, 10.4, 12.3**

### 属性 13: Connection_Test 结果不含 apiKey

*对于任意* `ProviderProfile` `p` 与任意 HTTP 响应（含成功、鉴权失败、网络错误、畸形 URL、模型不存在、未知错误），`ConnectionTester.test(p).userReason` 与诊断日志中该次调用的任何条目均不包含 `p.apiKey` 作为子串。

**验证: 需求 7.5, 10.4**

### 属性 14: 导出诊断不泄漏 apiKey

*对于任意*诊断导出文本 `D` 以及导出时刻 `ProviderProfileStore` 中的任意 `ProviderProfile` `p`，`p.apiKey` 不作为 `D` 的子串出现。

**验证: 需求 10.1, 10.3, 12.4**

### 属性 15: baseUrl userinfo 脱敏

*对于任意*包含 `scheme://user:token@host[...]` 形式 userinfo 的 URL 字符串 `u`，`redactProviderSecrets` 对包含 `u` 的文本的输出中不再存在 `:token@` 片段；脱敏后的 URL 保留 scheme、host、path。

**验证: 需求 10.2**

### 属性 16: maskedApiKey 掩码属性

*对于任意*非空字符串 `apiKey`，`ProviderProfile.maskedApiKey()` 返回的字符串 `m` 满足：(a) 若 `apiKey.length > 4`，则 `m` 包含且仅包含 `apiKey` 的最后 4 个字符作为可见子串；(b) `m` 不包含 `apiKey` 的前 `apiKey.length - 4` 个字符构成的任何非空子串（除非该子串恰好等于最后 4 个字符的前缀）。

**验证: 需求 6.3 of base spec, 9.2**

### 属性 17: deleteAll 清空所有数据

*对于任意*存储状态，调用 `ProviderProfileStore.deleteAll()` 后，`list()` 返回空列表且 `getActive()` 返回 `null`。

**验证: 需求 9.5**

### 属性 18: profileId 唯一性

*对于任意*通过同一个 `ProviderProfileStore` 实例执行的 `upsert` 创建操作序列（非更新既有 profile），产生的所有 `profileId` 两两不等。

**验证: 需求 2.2**

### 属性 19: 保存后 apiKey 输入被清空

*对于任意*有效的 `ProviderProfileDraft`，当 ViewModel 观察到 `ProviderEditorUiState.Saved` 后的 `FormState.apiKey.value` 等于空字符串。

**验证: 需求 2.5, 9.3**

### 属性 20: 从预设创建复制预设字段

*对于任意* `ProviderPreset` `preset` 与任意非空 `apiKey`，通过 "从预设创建" 用例生成的 `ProviderProfile` `p` 满足 `p.baseUrl == preset.baseUrl`，`p.model == preset.defaultModel`，`p.smallFastModel == preset.defaultSmallFastModel`，`p.authHeaderStyle == preset.authHeaderStyle`，`p.apiKey == apiKey`，`p.presetReference == Preset(preset.presetId)`。

**验证: 需求 2.1, 1.5**

### 属性 21: 无 Active_Profile 时禁止启动 Session

*对于任意* UI 状态，若 `activeProfile == null`，则 `SessionsUiState.canStartNewSession == false` 且 ViewModel 对 `startSession` 意图响应为 `NavigateTo(ProviderSelectionScreen)` 而非 Bridge.openSession。

**验证: 需求 5.5, 11.5**

### 属性 22: 迁移幂等

*对于任意*包含/不包含旧 legacy key、包含/不包含既有 profile、包含/不包含迁移标记的初始状态 `S0`，依次执行 `LegacyKeyMigrator.runIfNeeded()` `N` 次后的最终状态 `S_N` 等于执行一次后的状态 `S_1`（在 profile 集合、Active_Profile、legacy 凭据与迁移标记四个维度上相等）。

**验证: 需求 8.5**

---

## 错误处理

本节描述在基础规格错误处理之上**新增**的错误场景。基础规格的 Bridge / PTY / Bootstrap 错误处理（退出码分类、信号升级、Foreground Service 复活等）保持不变。

### 错误分类增量

| 错误类别 | 来源 | 处理策略 | 对应需求 |
|----------|------|----------|----------|
| `ProviderProfileStoreError.KeystoreUnavailable` | Android Keystore 失效 | UI 提示重新输入该 profile 的 apiKey；提供"清除所有凭据"按钮 | R9 AC4 |
| `ProviderProfileStoreError.BaseUrlLocked` | 尝试修改 preset-derived profile 的 baseUrl | UI 显示"此字段受预设锁定"inline 提示 | R4 AC4 |
| `ProviderProfileStoreError.NotFound` | `get` / `delete` 未找到 profile | 由调用方决定是否 silent ignore | 派生 |
| `BridgeError.NoActiveProfile` | spawn 时 Active_Profile 为 null | 中止 spawn；UI 跳转 ProviderSelectionScreen | R5 AC5, R6 AC8, R11 AC5 |
| `ConnectionTestOutcome.*`（非 `Ok`） | Connection_Test 响应分类 | UI 显示 outcome label + 本地化原因；不自动重试 | R7 AC3-AC7 |
| `MigrationError` | `LegacyKeyMigrator` 中途失败 | 不清理 legacy 凭据；UI 提示"迁移失败"+重试按钮 | R8 AC4 |
| `ValidationError.*` | 表单字段校验失败 | 字段级 inline 错误；阻止 Save | R3 AC3-AC6 |

### 错误传播路径

```mermaid
graph LR
    Store["ProviderProfileStore"] -->|Result.failure| VM["ProviderViewModel"]
    Migrator["LegacyKeyMigrator"] -->|MigrationError| AppBoot["Application onCreate"]
    AppBoot -->|阻塞启动| BootstrapScreen["BootstrapScreen (show retry)"]
    Tester["ConnectionTester"] -->|ConnectionTestResult| EditorVM["ProviderEditorVM"]
    BridgeAdapter["SpawnEnvAdapter"] -->|BridgeError| CliBridge["CliBridgeImpl"]
    CliBridge -->|BridgeEvent.Error| ChatVM["ChatViewModel"]
    VM -->|UiState.Error| UI["Compose UI"]
    EditorVM -->|UiState.Editing(testResult)| UI
    ChatVM -->|系统消息 + 导航| UI
```

### Keystore 失效恢复流程

1. `EncryptedSharedPreferences.edit` 或读操作抛 `AEADBadTagException` / `InvalidKeyException`。
2. `ProviderProfileStoreImpl` 将其包装为 `ProviderProfileStoreError.KeystoreUnavailable(profileId)` 并在 `Result.failure` 返回；同时将该 `profileId` 加入内部"不可读" set。
3. `observeProfiles()` Flow 继续发射但用占位条目替代不可读的 profile（displayName = "⚠ 凭据损坏"，disabled）。
4. UI 对不可读条目显示"重新输入 API key"按钮，进入 EditorScreen 的 Edit 模式但 apiKey 字段为空。
5. 用户重新保存时 `upsert` 用新的 key 覆盖旧 JSON blob，加密路径重置。
6. 若用户选择"清除所有凭据"，调用 `deleteAll()` 并导航到 ProviderSelectionScreen。

### 迁移失败回滚流程

见第 8.2 节算法步骤 5-6 的失败分支：失败时不写 `provider_migration_v1_done` 标记、不删除 legacy 凭据，下次启动重试。

### Connection_Test 的失败不改变 profile 状态

`ConnectionTester.test` 是**只读探针**：无论结果如何，`ProviderProfile` 不被修改。UI 只在该 profile 的本次会话状态中展示测试结果，profile 本身不持久化 `lastTestOutcome`（避免因测试状态泄漏到持久层污染 round-trip 属性 1）。

---

## 测试策略

### 双重测试方法

沿用基础规格的**单元测试 + 属性测试**策略：

- **单元测试**：具体示例、边界条件、UI 渲染、错误路径。
- **属性测试**：第 "正确性属性" 节中的 22 个属性。
- **集成测试**：Keystore 集成（Androidx test 设备）、EncryptedSharedPreferences 真实文件系统往返、Connection_Test 对 MockWebServer 的分类测试。

### 属性测试配置

- **库**: Kotest Property (`io.kotest:kotest-property`)
- **最小迭代次数**: 每个属性测试 100 次迭代
- **标签格式**: `Feature: ai-provider-presets, Property {number}: {property_text}`

```kotlin
// 属性测试示例：属性 10 (环境变量注入)
class EnvBuilderPropertyTest : FunSpec({
    test("Feature: ai-provider-presets, Property 10: Env injection property") {
        checkAll(100, baseEnvArb(), providerProfileArb()) { baseEnv, profile ->
            val env = buildClaudeEnv(baseEnv, profile)

            env["ANTHROPIC_BASE_URL"] shouldBe profile.baseUrl
            env["ANTHROPIC_MODEL"] shouldBe profile.model

            // 互斥性
            when (profile.authHeaderStyle) {
                AuthHeaderStyle.ApiKey -> {
                    env["ANTHROPIC_API_KEY"] shouldBe profile.apiKey
                    env shouldNotContainKey "ANTHROPIC_AUTH_TOKEN"
                }
                AuthHeaderStyle.AuthToken -> {
                    env["ANTHROPIC_AUTH_TOKEN"] shouldBe profile.apiKey
                    env shouldNotContainKey "ANTHROPIC_API_KEY"
                }
            }

            // smallFastModel 条件注入
            val sfm = profile.smallFastModel
            if (sfm != null && sfm.isNotBlank()) {
                env["ANTHROPIC_SMALL_FAST_MODEL"] shouldBe sfm
            } else {
                env shouldNotContainKey "ANTHROPIC_SMALL_FAST_MODEL"
            }
        }
    }
})

// 属性测试示例：属性 1 (Provider_Profile 往返)
class ProviderProfileStorePropertyTest : FunSpec({
    val store: ProviderProfileStore = newFakeStoreBackedByInMemorySP()

    test("Feature: ai-provider-presets, Property 1: Provider_Profile write/read round-trip") {
        checkAll(100, providerProfileArb()) { p ->
            store.upsert(p).getOrThrow()
            val p2 = store.get(p.profileId)!!
            p2.copy(updatedAt = p.updatedAt) shouldBe p  // updatedAt 允许增加
            p2.updatedAt shouldBeGreaterThanOrEqualTo p.updatedAt
        }
    }
})
```

### 生成器设计

```kotlin
// core-domain/src/test/... 共享生成器
object ProviderProfileArbs {
    fun providerProfileArb(): Arb<ProviderProfile> = arbitrary { rs ->
        ProviderProfile(
            profileId = Arb.uuid().bind().toString(),
            displayName = Arb.string(1..80, Codepoint.printable()).bind().trim()
                .ifBlank { "Default" },
            presetReference = Arb.choice(
                Arb.element(listOf("glm_coding_plan", "minimax_token_plan", "kimi_code_plan"))
                    .map { PresetReference.Preset(it) },
                Arb.constant(PresetReference.Custom),
            ).bind(),
            baseUrl = httpsUrlArb().bind(),
            apiKey = Arb.string(1..80, Codepoint.printable()).bind(),
            model = Arb.string(1..32, Codepoint.alphanumeric()).bind(),
            smallFastModel = Arb.choice(
                Arb.constant(null),
                Arb.string(1..32, Codepoint.alphanumeric()),
            ).bind(),
            authHeaderStyle = Arb.enum<AuthHeaderStyle>().bind(),
            createdAt = Arb.long(0..Long.MAX_VALUE / 2).bind(),
            updatedAt = Arb.long(0..Long.MAX_VALUE / 2).bind(),
        )
    }

    fun httpsUrlArb(): Arb<String> = arbitrary { rs ->
        val host = Arb.string(3..30, Codepoint.alphanumeric()).bind()
        val path = Arb.string(0..40, Codepoint.alphanumeric()).bind()
        "https://${host}.example.com/${path}".trimEnd('/')
    }

    fun baseEnvArb(): Arb<Map<String, String>> = arbitrary { rs ->
        mapOf(
            "HOME" to Arb.string(1..40, Codepoint.alphanumeric()).bind(),
            "PATH" to "/bin:/usr/bin",
            "TERM" to "xterm-256color",
            "LANG" to "en_US.UTF-8",
        )
    }
}
```

### 测试覆盖矩阵

| 组件 | 单元测试 | 属性测试 | 集成测试 |
|------|----------|----------|----------|
| `ProviderRegistry` | 3 preset 均暴露、字段非空 | — | — |
| `ProviderProfile.maskedApiKey` | 边界长度 0/1/4/5/100 | 属性 16 | — |
| `ProviderProfile.validate` / `FormState` | 每字段边界 | 属性 2, 3, 4 | — |
| `buildClaudeEnv` | 典型组合 | 属性 10, 11 | — |
| `ProviderProfileStoreImpl` | CRUD、Active 设置 | 属性 1, 5, 7, 8, 17, 18 | EncryptedSharedPreferences 真实 fs |
| `ProviderProfileStoreImpl.observeActiveProfile` | 200ms 响应 | — | Turbine + Android 测试 |
| `ProviderProfileStoreImpl` BaseUrlLocked | — | 属性 6 | — |
| `SpawnEnvAdapter` | env 透传 | 属性 9, 12 | Bridge 与 JNI |
| `ConnectionTester` | 每分类一个示例 | 属性 13 | MockWebServer |
| `LegacyKeyMigrator` | 各初始状态 | 属性 22 | Legacy store 集成 |
| `DiagnosticsExporter.redactProviderSecrets` | 典型文本 | 属性 14, 15 | — |
| UI (`ProviderSelectionScreen` / `EditorScreen` / `ListScreen`) | Compose UI test | 属性 19, 20, 21 | Navigation 集成 |
| `ProviderEditorViewModel` | 各 UI flow | 属性 2, 19 | — |

### PBT 适用性说明

本特性**高度适合**属性测试，因其核心逻辑均为纯函数：

1. `buildClaudeEnv`：纯函数映射（属性 10, 11）。
2. `ProviderProfile` 序列化 round-trip（属性 1）——经典 PBT 场景。
3. 脱敏函数 `redactProviderSecrets`：字符串变换（属性 14, 15）。
4. 表单校验 `FormState`：字段独立的纯函数（属性 2, 3）。
5. 迁移器幂等性（属性 22）——经典 PBT 场景。

**不适合 PBT 的部分**（采用示例测试）：

- Connection_Test 超时（15s 计时，单一计时场景）。
- Keystore 失效恢复（需要注入故障，枚举性质）。
- UI 渲染的具体视觉元素（Compose UI test）。
- 导航行为（集成测试）。

### CI 覆盖率要求

- `core-domain` 中 `buildClaudeEnv` / 数据类 / 校验器：≥ 95%（关键纯函数）。
- `ProviderProfileStoreImpl`：≥ 90%。
- `ConnectionTesterImpl`：≥ 85%。
- `LegacyKeyMigrator`：≥ 90%（迁移是 one-shot 路径，回归风险高）。
- UI ViewModel：≥ 80%。
