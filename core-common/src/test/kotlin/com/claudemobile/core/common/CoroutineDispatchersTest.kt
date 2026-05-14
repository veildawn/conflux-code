package com.claudemobile.core.common

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Test

class CoroutineDispatchersTest {

    @Test
    fun `DefaultCoroutineDispatchers provides standard dispatchers`() {
        val dispatchers = DefaultCoroutineDispatchers()

        dispatchers.default shouldBe Dispatchers.Default
        dispatchers.io shouldBe Dispatchers.IO
        dispatchers.main shouldBe Dispatchers.Main
        dispatchers.mainImmediate shouldBe Dispatchers.Main.immediate
        dispatchers.unconfined shouldBe Dispatchers.Unconfined
    }

    @Test
    fun `CoroutineDispatchers interface can be implemented for testing`() {
        val testDispatchers = object : CoroutineDispatchers {
            override val default = Dispatchers.Unconfined
            override val io = Dispatchers.Unconfined
            override val main = Dispatchers.Unconfined
            override val mainImmediate = Dispatchers.Unconfined
            override val unconfined = Dispatchers.Unconfined
        }

        testDispatchers.default shouldBe Dispatchers.Unconfined
        testDispatchers.io shouldBe Dispatchers.Unconfined
        testDispatchers.main shouldBe Dispatchers.Unconfined
    }
}
