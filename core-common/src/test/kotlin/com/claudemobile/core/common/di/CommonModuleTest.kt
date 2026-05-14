package com.claudemobile.core.common.di

import com.claudemobile.core.common.DefaultCoroutineDispatchers
import com.claudemobile.core.common.DefaultTimeProvider
import com.claudemobile.core.common.DefaultUuidGenerator
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class CommonModuleTest {

    @Test
    fun `provideCoroutineDispatchers returns DefaultCoroutineDispatchers`() {
        val dispatchers = CommonModule.provideCoroutineDispatchers()

        dispatchers.shouldBeInstanceOf<DefaultCoroutineDispatchers>()
    }

    @Test
    fun `provideTimeProvider returns DefaultTimeProvider`() {
        val timeProvider = CommonModule.provideTimeProvider()

        timeProvider.shouldBeInstanceOf<DefaultTimeProvider>()
    }

    @Test
    fun `provideUuidGenerator returns DefaultUuidGenerator`() {
        val uuidGenerator = CommonModule.provideUuidGenerator()

        uuidGenerator.shouldBeInstanceOf<DefaultUuidGenerator>()
    }

    @Test
    fun `provideCoroutineDispatchers returns new instance each call`() {
        val first = CommonModule.provideCoroutineDispatchers()
        val second = CommonModule.provideCoroutineDispatchers()

        // Hilt @Singleton ensures single instance at runtime;
        // the provider itself creates new instances (Hilt handles caching)
        (first !== second) shouldBe true
    }
}
