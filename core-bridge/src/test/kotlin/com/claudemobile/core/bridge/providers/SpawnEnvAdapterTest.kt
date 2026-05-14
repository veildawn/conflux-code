package com.claudemobile.core.bridge.providers

import com.claudemobile.core.bridge.cli.CliBridgeImpl
import com.claudemobile.core.domain.bridge.BridgeError
import com.claudemobile.core.domain.bridge.SpawnConfig
import com.claudemobile.core.domain.providers.AuthHeaderStyle
import com.claudemobile.core.domain.providers.PresetReference
import com.claudemobile.core.domain.providers.ProviderProfile
import com.claudemobile.core.domain.providers.ProviderProfileStore
import com.claudemobile.core.domain.providers.ProviderProfileStoreError
import com.claudemobile.core.domain.repository.CredentialStore
import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SpawnEnvAdapterTest {

    // ---------------------------------------------------------------
    // prepareEnv: active profile present → buildClaudeEnv applied
    // ---------------------------------------------------------------

    @Test
    fun `prepareEnv with active profile builds Anthropic env from profile (AuthToken)`() = runTest {
        val profile = profile(
            apiKey = "sk-secret-token-1234",
            authHeaderStyle = AuthHeaderStyle.AuthToken,
            baseUrl = "https://open.bigmodel.cn/api/anthropic",
            model = "glm-4.6",
            smallFastModel = null,
        )
        val store = FakeProviderProfileStore(active = profile)
        val adapter = SpawnEnvAdapterImpl(store)

        val baseEnv = mapOf(
            "HOME" to "/home/user",
            "PATH" to "/usr/bin",
            "TERM" to "xterm-256color",
            "LANG" to "en_US.UTF-8",
        )

        val result = adapter.prepareEnv(baseEnv)

        result.isSuccess shouldBe true
        val env = result.getOrThrow()

        // Anthropic-compatible variables derived from the profile.
        env shouldContain ("ANTHROPIC_BASE_URL" to profile.baseUrl)
        env shouldContain ("ANTHROPIC_AUTH_TOKEN" to profile.apiKey)
        env shouldContain ("ANTHROPIC_MODEL" to profile.model)

        // Mutual exclusion of API_KEY vs AUTH_TOKEN.
        env shouldNotContainKey "ANTHROPIC_API_KEY"
        env shouldNotContainKey "ANTHROPIC_SMALL_FAST_MODEL"

        // Base env preserved.
        env shouldContain ("HOME" to "/home/user")
        env shouldContain ("PATH" to "/usr/bin")
        env shouldContain ("TERM" to "xterm-256color")
        env shouldContain ("LANG" to "en_US.UTF-8")

        // Store was consulted exactly once for this single spawn.
        store.getActiveCalls shouldBe 1
    }

    @Test
    fun `prepareEnv with active profile uses ANTHROPIC_API_KEY when authHeaderStyle is ApiKey`() = runTest {
        val profile = profile(
            apiKey = "sk-ant-direct-9999",
            authHeaderStyle = AuthHeaderStyle.ApiKey,
            baseUrl = "https://api.anthropic.com",
            model = "claude-3-5-sonnet-20241022",
            smallFastModel = "claude-3-haiku-20240307",
        )
        val store = FakeProviderProfileStore(active = profile)
        val adapter = SpawnEnvAdapterImpl(store)

        val result = adapter.prepareEnv(baseEnv = mapOf("HOME" to "/home/user"))

        result.isSuccess shouldBe true
        val env = result.getOrThrow()

        env shouldContain ("ANTHROPIC_BASE_URL" to profile.baseUrl)
        env shouldContain ("ANTHROPIC_API_KEY" to profile.apiKey)
        env shouldContain ("ANTHROPIC_MODEL" to profile.model)
        env shouldContain ("ANTHROPIC_SMALL_FAST_MODEL" to "claude-3-haiku-20240307")
        env shouldNotContainKey "ANTHROPIC_AUTH_TOKEN"
    }

    @Test
    fun `prepareEnv strips stale ANTHROPIC_API_KEY and ANTHROPIC_AUTH_TOKEN from baseEnv`() = runTest {
        val profile = profile(
            apiKey = "fresh-key",
            authHeaderStyle = AuthHeaderStyle.AuthToken,
        )
        val store = FakeProviderProfileStore(active = profile)
        val adapter = SpawnEnvAdapterImpl(store)

        val baseEnv = mapOf(
            "HOME" to "/h",
            "ANTHROPIC_API_KEY" to "stale-key-value",
            "ANTHROPIC_AUTH_TOKEN" to "another-stale-token",
            "ANTHROPIC_SMALL_FAST_MODEL" to "stale-small",
        )

        val env = adapter.prepareEnv(baseEnv).getOrThrow()

        // Only the auth-token branch is set, stale values are gone.
        env shouldContain ("ANTHROPIC_AUTH_TOKEN" to "fresh-key")
        env shouldNotContainKey "ANTHROPIC_API_KEY"
        // smallFastModel was null on the profile → stale entry removed.
        env shouldNotContainKey "ANTHROPIC_SMALL_FAST_MODEL"
    }

    // ---------------------------------------------------------------
    // prepareEnv: active null → BridgeError.NoActiveProfile
    // ---------------------------------------------------------------

    @Test
    fun `prepareEnv with no active profile fails with NoActiveProfile`() = runTest {
        val store = FakeProviderProfileStore(active = null)
        val adapter = SpawnEnvAdapterImpl(store)

        val result = adapter.prepareEnv(baseEnv = mapOf("HOME" to "/h"))

        result.isFailure shouldBe true
        val error = result.exceptionOrNull()
        error.shouldBeInstanceOf<BridgeError.NoActiveProfile>()
        // The store was consulted, even though it returned null.
        store.getActiveCalls shouldBe 1
    }

    // ---------------------------------------------------------------
    // prepareEnv re-reads the active profile on every call (no cache,
    // no StateFlow.value snapshot). Underpins Property 9.
    // ---------------------------------------------------------------

    @Test
    fun `prepareEnv re-reads active profile on every call`() = runTest {
        val first = profile(
            profileId = "profile-1",
            apiKey = "key-1",
            baseUrl = "https://first.example/api",
            model = "model-1",
            authHeaderStyle = AuthHeaderStyle.AuthToken,
        )
        val second = profile(
            profileId = "profile-2",
            apiKey = "key-2",
            baseUrl = "https://second.example/api",
            model = "model-2",
            authHeaderStyle = AuthHeaderStyle.ApiKey,
        )
        val store = FakeProviderProfileStore(active = first)
        val adapter = SpawnEnvAdapterImpl(store)

        val env1 = adapter.prepareEnv(emptyMap()).getOrThrow()
        env1["ANTHROPIC_BASE_URL"] shouldBe "https://first.example/api"
        env1["ANTHROPIC_AUTH_TOKEN"] shouldBe "key-1"

        // Switch the active profile between spawns — the adapter must
        // see the new value without any cache flushing.
        store.active = second

        val env2 = adapter.prepareEnv(emptyMap()).getOrThrow()
        env2["ANTHROPIC_BASE_URL"] shouldBe "https://second.example/api"
        env2["ANTHROPIC_API_KEY"] shouldBe "key-2"
        env2 shouldNotContainKey "ANTHROPIC_AUTH_TOKEN"

        // Two spawns ⇒ two store reads (no caching).
        store.getActiveCalls shouldBe 2
    }

    // ---------------------------------------------------------------
    // SpawnConfig.toSafeString does not contain apiKey or baseUrl
    // ---------------------------------------------------------------

    @Test
    fun `toSafeString contains env keys but no env values`() {
        val apiKey = "sk-very-secret-12345"
        val baseUrl = "https://open.bigmodel.cn/api/anthropic"
        val config = SpawnConfig(
            command = "/data/data/com.claudemobile/lib/libproot.so",
            args = listOf("--rootfs=/rootfs", "claude", "--chat"),
            envVars = mapOf(
                "HOME" to "/home/user",
                "PATH" to "/usr/bin",
                "TERM" to "xterm-256color",
                "LANG" to "en_US.UTF-8",
                "ANTHROPIC_BASE_URL" to baseUrl,
                "ANTHROPIC_AUTH_TOKEN" to apiKey,
                "ANTHROPIC_MODEL" to "glm-4.6",
            ),
            workingDir = "/workspace/project",
            rows = 30,
            cols = 100,
        )

        val safe = config.toSafeString()

        // Keys are mentioned …
        safe shouldContain "ANTHROPIC_BASE_URL"
        safe shouldContain "ANTHROPIC_AUTH_TOKEN"
        safe shouldContain "ANTHROPIC_MODEL"
        safe shouldContain "HOME"
        safe shouldContain "PATH"

        // … but values never are.
        safe shouldNotContain apiKey
        safe shouldNotContain baseUrl
        safe shouldNotContain "/home/user"
        safe shouldNotContain "/usr/bin"
        safe shouldNotContain "xterm-256color"
        safe shouldNotContain "glm-4.6"

        // Structural fields are surfaced.
        safe shouldContain "command="
        safe shouldContain "/data/data/com.claudemobile/lib/libproot.so"
        safe shouldContain "workingDir="
        safe shouldContain "/workspace/project"
        safe shouldContain "rows=30"
        safe shouldContain "cols=100"
    }

    @Test
    fun `toSafeString redacts apiKey when env contains the key under any name`() {
        // Defence-in-depth: even if a future caller mistakenly placed
        // an apiKey under a non-Anthropic key name, toSafeString still
        // omits values across the board.
        val secret = "sk-leak-bait-7777"
        val config = SpawnConfig(
            command = "/bin/sh",
            workingDir = "/tmp",
            envVars = mapOf(
                "TOTALLY_UNRELATED" to secret,
                "ANTHROPIC_API_KEY" to secret,
            ),
        )

        val safe = config.toSafeString()

        safe shouldNotContain secret
        safe shouldContain "TOTALLY_UNRELATED"
        safe shouldContain "ANTHROPIC_API_KEY"
    }

    @Test
    fun `toSafeString is deterministic across env key ordering`() {
        val a = SpawnConfig(
            command = "/bin/sh",
            workingDir = "/tmp",
            envVars = linkedMapOf(
                "HOME" to "/h",
                "PATH" to "/p",
                "ANTHROPIC_API_KEY" to "k",
            ),
        )
        val b = SpawnConfig(
            command = "/bin/sh",
            workingDir = "/tmp",
            envVars = linkedMapOf(
                "ANTHROPIC_API_KEY" to "k",
                "PATH" to "/p",
                "HOME" to "/h",
            ),
        )

        a.toSafeString() shouldBe b.toSafeString()
    }

    // ---------------------------------------------------------------
    // Regression: CliBridgeImpl must NOT reference CredentialStore
    // in its dependency graph (task 9.6 — superseded by SpawnEnvAdapter).
    // ---------------------------------------------------------------

    /**
     * Verifies that [CliBridgeImpl]'s primary constructor contains no
     * parameter whose declared type is [CredentialStore] (or any subtype).
     *
     * This is a structural regression guard: if a future refactor
     * accidentally re-introduces a direct `CredentialStore` injection into
     * the bridge, this test will fail immediately, making the violation
     * visible before it reaches production.
     *
     * The check is intentionally shallow (constructor parameters only) because
     * the Dagger component graph is not available in unit tests. The
     * constructor is the canonical injection point for `@Inject`-annotated
     * classes, so it is the correct place to look.
     *
     * Requirements: 6.1, 6.2, 6.3, 6.4 (environment injection must come
     * exclusively from [SpawnEnvAdapter], not from [CredentialStore]).
     */
    @Test
    fun `CliBridgeImpl constructor has no CredentialStore parameter`() {
        // CliBridgeImpl uses constructor injection (@Inject on the primary
        // constructor). Inspect every declared constructor and assert that
        // none of their parameter types is assignable from CredentialStore.
        val credentialStoreClass = CredentialStore::class.java
        val cliBridgeClass = CliBridgeImpl::class.java

        val offendingParams = cliBridgeClass.declaredConstructors
            .flatMap { constructor -> constructor.parameterTypes.toList() }
            .filter { paramType -> credentialStoreClass.isAssignableFrom(paramType) }

        offendingParams shouldBe emptyList()
    }

    /**
     * Verifies that [CliBridgeImpl]'s declared fields contain no field
     * whose type is [CredentialStore] (or any subtype).
     *
     * This complements the constructor check: if a `CredentialStore` were
     * injected via field injection (`@Inject lateinit var`) rather than
     * constructor injection, the constructor check alone would miss it.
     *
     * Requirements: 6.1, 6.2, 6.3, 6.4.
     */
    @Test
    fun `CliBridgeImpl declared fields contain no CredentialStore field`() {
        val credentialStoreClass = CredentialStore::class.java
        val cliBridgeClass = CliBridgeImpl::class.java

        val offendingFields = cliBridgeClass.declaredFields
            .filter { field -> credentialStoreClass.isAssignableFrom(field.type) }
            .map { field -> field.name }

        offendingFields shouldBe emptyList()
    }
}

// ---------------------------------------------------------------------------
// Test fakes
// ---------------------------------------------------------------------------

private fun profile(
    profileId: String = "p-1",
    apiKey: String,
    authHeaderStyle: AuthHeaderStyle,
    baseUrl: String = "https://api.example.com",
    model: String = "test-model",
    smallFastModel: String? = null,
): ProviderProfile = ProviderProfile(
    profileId = profileId,
    displayName = "Test profile",
    presetReference = PresetReference.Custom,
    baseUrl = baseUrl,
    apiKey = apiKey,
    model = model,
    smallFastModel = smallFastModel,
    authHeaderStyle = authHeaderStyle,
    createdAt = 1L,
    updatedAt = 1L,
)

/**
 * In-memory [ProviderProfileStore] tailored for [SpawnEnvAdapterTest]:
 * exposes only what the adapter consumes (`getActive`) plus a counter so
 * tests can assert that reads are not cached. The remaining methods are
 * implemented minimally to satisfy the interface.
 */
private class FakeProviderProfileStore(
    var active: ProviderProfile?,
) : ProviderProfileStore {

    var getActiveCalls: Int = 0
        private set

    override fun observeProfiles(): Flow<List<ProviderProfile>> =
        MutableStateFlow(listOfNotNull(active)).asStateFlow()

    override fun observeActiveProfile(): Flow<ProviderProfile?> =
        MutableStateFlow(active).asStateFlow()

    override suspend fun list(): List<ProviderProfile> = listOfNotNull(active)

    override suspend fun get(profileId: String): ProviderProfile? =
        active?.takeIf { it.profileId == profileId }

    override suspend fun getActive(): ProviderProfile? {
        getActiveCalls += 1
        return active
    }

    override suspend fun upsert(profile: ProviderProfile): Result<Unit> {
        active = profile
        return Result.success(Unit)
    }

    override suspend fun delete(profileId: String): Result<Unit> {
        if (active?.profileId == profileId) active = null
        return Result.success(Unit)
    }

    override suspend fun setActive(profileId: String?): Result<Unit> {
        if (profileId == null) {
            active = null
            return Result.success(Unit)
        }
        val current = active
        return if (current?.profileId == profileId) {
            Result.success(Unit)
        } else {
            Result.failure(ProviderProfileStoreError.NotFound(profileId))
        }
    }

    override suspend fun deleteAll(): Result<Unit> {
        active = null
        return Result.success(Unit)
    }
}
