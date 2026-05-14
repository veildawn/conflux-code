package com.claudemobile.core.domain.parser

import com.claudemobile.core.domain.model.OutputEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll

/**
 * Property-based test for Parser Structural Invariant.
 *
 * **Validates: Requirements 4.1**
 *
 * Property 4: For any byte buffer provided to the Output_Parser, the sum of consumedBytes
 * across all parse calls SHALL equal the total number of bytes fed to the parser, and every
 * produced event SHALL be one of the defined event types with valid fields (non-null content
 * for Text, non-empty tool name for ToolCallStart, etc.).
 *
 * Tags: Feature: android-claude-termux-client, Property 4: Parser Structural Invariant
 */
class ParserStructuralInvariantPropertyTest : FunSpec({

    tags(
        io.kotest.core.Tag("Feature: android-claude-termux-client"),
        io.kotest.core.Tag("Property 4: Parser Structural Invariant")
    )

    test("sum of consumedBytes equals total bytes fed for buffers ending with newline") {
        checkAll(PropTestConfig(iterations = 100), arbByteBufferEndingWithNewline()) { buffer ->
            val parser = OutputParserImpl()
            val result = parser.parse(buffer)

            // For buffers ending with newline, the parser should consume all bytes
            // since it processes complete lines
            result.consumedBytes shouldBe buffer.size
        }
    }

    test("consumedBytes never exceeds buffer size for arbitrary buffers") {
        checkAll(PropTestConfig(iterations = 100), arbArbitraryByteBuffer()) { buffer ->
            val parser = OutputParserImpl()
            val result = parser.parse(buffer)

            result.consumedBytes shouldBeGreaterThanOrEqualTo 0
            (result.consumedBytes <= buffer.size) shouldBe true
        }
    }

    test("incremental feeding: sum of consumedBytes equals total bytes for newline-terminated chunks") {
        checkAll(
            PropTestConfig(iterations = 100),
            arbMultipleNewlineTerminatedChunks()
        ) { chunks ->
            val parser = OutputParserImpl()
            var totalConsumed = 0
            var totalFed = 0
            var unconsumed = byteArrayOf()

            for (chunk in chunks) {
                val buffer = unconsumed + chunk
                totalFed += chunk.size
                val result = parser.parse(buffer)
                totalConsumed += result.consumedBytes
                unconsumed = buffer.sliceArray(result.consumedBytes until buffer.size)
            }

            // After feeding all chunks (each ending with newline), everything should be consumed
            totalConsumed shouldBe totalFed
        }
    }

    test("all Text events have non-empty content") {
        checkAll(PropTestConfig(iterations = 100), arbByteBufferEndingWithNewline()) { buffer ->
            val parser = OutputParserImpl()
            val result = parser.parse(buffer)

            result.events.filterIsInstance<OutputEvent.Text>().forEach { event ->
                event.content.shouldNotBeEmpty()
            }
        }
    }

    test("all ToolCallStart events have non-empty toolName") {
        checkAll(PropTestConfig(iterations = 100), arbByteBufferEndingWithNewline()) { buffer ->
            val parser = OutputParserImpl()
            val result = parser.parse(buffer)

            result.events.filterIsInstance<OutputEvent.ToolCallStart>().forEach { event ->
                event.toolName.shouldNotBeEmpty()
            }
        }
    }

    test("all ToolCallResult events have non-empty toolName") {
        checkAll(PropTestConfig(iterations = 100), arbByteBufferEndingWithNewline()) { buffer ->
            val parser = OutputParserImpl()
            val result = parser.parse(buffer)

            result.events.filterIsInstance<OutputEvent.ToolCallResult>().forEach { event ->
                event.toolName.shouldNotBeEmpty()
            }
        }
    }

    test("all Prompt events have non-empty text") {
        checkAll(PropTestConfig(iterations = 100), arbByteBufferEndingWithNewline()) { buffer ->
            val parser = OutputParserImpl()
            val result = parser.parse(buffer)

            result.events.filterIsInstance<OutputEvent.Prompt>().forEach { event ->
                event.text.shouldNotBeEmpty()
            }
        }
    }

    test("all Error events have non-empty reason") {
        checkAll(PropTestConfig(iterations = 100), arbByteBufferEndingWithNewline()) { buffer ->
            val parser = OutputParserImpl()
            val result = parser.parse(buffer)

            result.events.filterIsInstance<OutputEvent.Error>().forEach { event ->
                event.reason.shouldNotBeEmpty()
            }
        }
    }

    test("all events are valid instances of defined event types") {
        checkAll(PropTestConfig(iterations = 100), arbByteBufferEndingWithNewline()) { buffer ->
            val parser = OutputParserImpl()
            val result = parser.parse(buffer)

            result.events.forEach { event ->
                val isValidType = event is OutputEvent.Text ||
                    event is OutputEvent.ToolCallStart ||
                    event is OutputEvent.ToolCallResult ||
                    event is OutputEvent.Prompt ||
                    event is OutputEvent.TurnComplete ||
                    event is OutputEvent.Error
                isValidType shouldBe true
            }
        }
    }
})

/**
 * Generates arbitrary byte buffers that end with a newline character.
 * This ensures the parser can fully consume the buffer since it only
 * processes complete lines.
 */
private fun arbByteBufferEndingWithNewline(): Arb<ByteArray> = arbitrary {
    val size = Arb.int(1, 512).bind()
    val bytes = ByteArray(size) { (Arb.int(0, 255).bind()).toByte() }
    // Ensure the buffer ends with newline
    bytes[bytes.size - 1] = '\n'.code.toByte()
    bytes
}

/**
 * Generates arbitrary byte buffers of varying sizes (may or may not end with newline).
 */
private fun arbArbitraryByteBuffer(): Arb<ByteArray> = arbitrary {
    val size = Arb.int(0, 512).bind()
    if (size == 0) {
        byteArrayOf()
    } else {
        ByteArray(size) { (Arb.int(0, 255).bind()).toByte() }
    }
}

/**
 * Generates a list of byte chunks where each chunk ends with a newline.
 * Simulates incremental feeding of complete lines to the parser.
 */
private fun arbMultipleNewlineTerminatedChunks(): Arb<List<ByteArray>> = arbitrary {
    val numChunks = Arb.int(1, 5).bind()
    val chunks = mutableListOf<ByteArray>()

    repeat(numChunks) {
        val size = Arb.int(1, 128).bind()
        val bytes = ByteArray(size) { (Arb.int(0, 255).bind()).toByte() }
        bytes[bytes.size - 1] = '\n'.code.toByte()
        chunks.add(bytes)
    }

    chunks
}
