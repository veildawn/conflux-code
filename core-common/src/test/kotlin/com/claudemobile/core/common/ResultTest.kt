package com.claudemobile.core.common

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ResultTest {

    @Test
    fun `Success wraps value correctly`() {
        val result: AppResult<Int> = AppResult.Success(42)

        result.shouldBeInstanceOf<AppResult.Success<Int>>()
        (result as AppResult.Success).value shouldBe 42
    }

    @Test
    fun `Failure wraps error correctly`() {
        val error = AppError("something went wrong", ErrorCode.NOT_FOUND)
        val result: AppResult<Int> = AppResult.Failure(error)

        result.shouldBeInstanceOf<AppResult.Failure>()
        (result as AppResult.Failure).error shouldBe error
    }

    @Test
    fun `asSuccess extension creates Success`() {
        val result = 42.asSuccess()

        result.isSuccess shouldBe true
        result.isFailure shouldBe false
        result.getOrNull() shouldBe 42
    }

    @Test
    fun `asFailure extension creates Failure`() {
        val error = AppError("fail", ErrorCode.UNKNOWN)
        val result: AppResult<Int> = error.asFailure()

        result.isSuccess shouldBe false
        result.isFailure shouldBe true
        result.getOrNull() shouldBe null
    }

    @Test
    fun `getOrElse returns value on success`() {
        val result = "hello".asSuccess()

        result.getOrElse { "default" } shouldBe "hello"
    }

    @Test
    fun `getOrElse returns default on failure`() {
        val result: AppResult<String> = AppError("fail").asFailure()

        result.getOrElse { "default" } shouldBe "default"
    }

    @Test
    fun `getOrThrow returns value on success`() {
        val result = 42.asSuccess()

        result.getOrThrow() shouldBe 42
    }

    @Test
    fun `getOrThrow throws on failure with cause`() {
        val cause = RuntimeException("boom")
        val result: AppResult<Int> = AppError("fail", cause = cause).asFailure()

        val thrown = assertThrows<RuntimeException> { result.getOrThrow() }
        thrown shouldBe cause
    }

    @Test
    fun `getOrThrow throws IllegalStateException on failure without cause`() {
        val result: AppResult<Int> = AppError("fail message").asFailure()

        val thrown = assertThrows<IllegalStateException> { result.getOrThrow() }
        thrown.message shouldBe "fail message"
    }

    @Test
    fun `map transforms success value`() {
        val result = 21.asSuccess().map { it * 2 }

        result.getOrNull() shouldBe 42
    }

    @Test
    fun `map propagates failure`() {
        val error = AppError("fail")
        val result: AppResult<Int> = error.asFailure()
        val mapped = result.map { it * 2 }

        mapped.isFailure shouldBe true
        (mapped as AppResult.Failure).error shouldBe error
    }

    @Test
    fun `flatMap chains successful results`() {
        val result = 21.asSuccess().flatMap { (it * 2).asSuccess() }

        result.getOrNull() shouldBe 42
    }

    @Test
    fun `flatMap propagates failure from first result`() {
        val error = AppError("first fail")
        val result: AppResult<Int> = error.asFailure()
        val chained = result.flatMap { (it * 2).asSuccess() }

        chained.isFailure shouldBe true
    }

    @Test
    fun `flatMap propagates failure from transform`() {
        val result = 21.asSuccess().flatMap { _: Int ->
            AppError("transform fail").asFailure()
        }

        result.isFailure shouldBe true
    }

    @Test
    fun `mapError transforms error`() {
        val result: AppResult<Int> = AppError("original", ErrorCode.UNKNOWN).asFailure()
        val mapped = result.mapError { it.copy(code = ErrorCode.NOT_FOUND) }

        (mapped as AppResult.Failure).error.code shouldBe ErrorCode.NOT_FOUND
    }

    @Test
    fun `mapError does not affect success`() {
        val result = 42.asSuccess()
        val mapped = result.mapError { it.copy(code = ErrorCode.NOT_FOUND) }

        mapped.getOrNull() shouldBe 42
    }

    @Test
    fun `onSuccess executes action for success`() {
        var captured = 0
        val result = 42.asSuccess()

        result.onSuccess { captured = it }

        captured shouldBe 42
    }

    @Test
    fun `onSuccess does not execute action for failure`() {
        var captured = 0
        val result: AppResult<Int> = AppError("fail").asFailure()

        result.onSuccess { captured = it }

        captured shouldBe 0
    }

    @Test
    fun `onFailure executes action for failure`() {
        var captured: AppError? = null
        val error = AppError("fail")
        val result: AppResult<Int> = error.asFailure()

        result.onFailure { captured = it }

        captured shouldBe error
    }

    @Test
    fun `onFailure does not execute action for success`() {
        var captured: AppError? = null
        val result = 42.asSuccess()

        result.onFailure { captured = it }

        captured shouldBe null
    }

    @Test
    fun `runCatching returns success on normal execution`() {
        val result = com.claudemobile.core.common.runCatching { 42 }

        result.isSuccess shouldBe true
        result.getOrNull() shouldBe 42
    }

    @Test
    fun `runCatching returns failure on exception`() {
        val result = com.claudemobile.core.common.runCatching { throw RuntimeException("boom") }

        result.isFailure shouldBe true
        (result as AppResult.Failure).error.message shouldBe "boom"
        result.error.cause.shouldBeInstanceOf<RuntimeException>()
    }

    @Test
    fun `ErrorCode enum has expected values`() {
        ErrorCode.entries.map { it.name } shouldBe listOf(
            "UNKNOWN",
            "NOT_FOUND",
            "ALREADY_EXISTS",
            "INVALID_ARGUMENT",
            "PERMISSION_DENIED",
            "UNAVAILABLE",
            "NETWORK_ERROR",
            "TIMEOUT",
            "PROCESS_ERROR",
            "STORAGE_ERROR",
            "KEYSTORE_ERROR",
        )
    }
}
