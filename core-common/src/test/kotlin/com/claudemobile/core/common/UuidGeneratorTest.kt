package com.claudemobile.core.common

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test

class UuidGeneratorTest {

    private val uuidRegex = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    @Test
    fun `DefaultUuidGenerator generates valid UUID format`() {
        val generator = DefaultUuidGenerator()
        val uuid = generator.generate()

        uuid shouldMatch uuidRegex
    }

    @Test
    fun `DefaultUuidGenerator generates unique values`() {
        val generator = DefaultUuidGenerator()
        val uuids = (1..100).map { generator.generate() }.toSet()

        uuids.size shouldBe 100
    }

    @Test
    fun `UuidGenerator interface can be implemented for testing`() {
        val fixedUuid = "00000000-0000-0000-0000-000000000001"
        val testGenerator = object : UuidGenerator {
            override fun generate(): String = fixedUuid
        }

        testGenerator.generate() shouldBe fixedUuid
    }
}
