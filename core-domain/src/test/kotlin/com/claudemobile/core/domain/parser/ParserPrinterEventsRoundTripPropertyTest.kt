package com.claudemobile.core.domain.parser

import com.claudemobile.core.domain.model.OutputEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll

/**
 * Property 8: Parser/Printer Round-Trip (Events Direction)
 *
 * **Validates: Requirements 14.2**
 *
 * For any valid OutputEvent list `events`, serializing via PrettyPrinter's
 * `eventsToBytes(events)` and re-parsing with OutputParser produces events
 * equivalent to the original `events`.
 *
 * Feature: android-claude-termux-client, Property 8: Parser/Printer round-trip (events direction)
 */
class ParserPrinterEventsRoundTripPropertyTest : FunSpec({

    // --- Event equivalence ---

    fun eventEquivalent(a: OutputEvent, b: OutputEvent): Boolean {
        return when {
            a is OutputEvent.Text && b is OutputEvent.Text ->
                a.content == b.content
            a is OutputEvent.ToolCallStart && b is OutputEvent.ToolCallStart ->
                a.toolName == b.toolName && a.arguments == b.arguments
            a is OutputEvent.ToolCallResult && b is OutputEvent.ToolCallResult ->
                a.toolName == b.toolName && a.result == b.result && a.success == b.success
            a is OutputEvent.TurnComplete && b is OutputEvent.TurnComplete -> true
            else -> false
        }
    }

    fun eventsEquivalent(a: List<OutputEvent>, b: List<OutputEvent>): Boolean {
        if (a.size != b.size) return false
        return a.zip(b).all { (ea, eb) -> eventEquivalent(ea, eb) }
    }

    // --- Generators for valid OutputEvents ---

    // Safe text characters: printable ASCII without sentinel-like patterns
    val safeTextChar = Arb.of(
        ('a'..'z').toList() + ('A'..'Z').toList() + ('0'..'9').toList() +
            listOf(' ', ',', '.', '!', '?', '-', '(', ')', '{', '}', ':', ';', '"', '\'', '/', '+', '=')
    )

    // Generate safe text content (no sentinel patterns, no prompt patterns)
    val safeTextContent: Arb<String> = arbitrary {
        val length = Arb.int(1, 60).bind()
        buildString {
            repeat(length) {
                append(safeTextChar.bind())
            }
        }
    }

    // Generate valid tool names: start with letter, alphanumeric + underscores
    val toolNameChar = Arb.of(('a'..'z').toList() + ('0'..'9').toList() + listOf('_'))

    val arbToolName: Arb<String> = arbitrary {
        val length = Arb.int(1, 20).bind()
        buildString {
            append(Arb.of(('a'..'z').toList()).bind())
            repeat(length - 1) {
                append(toolNameChar.bind())
            }
        }
    }

    // Generate tool arguments (safe text without newlines or sentinel patterns)
    val arbToolArguments: Arb<String> = arbitrary {
        val length = Arb.int(0, 40).bind()
        if (length == 0) ""
        else buildString {
            repeat(length) {
                append(safeTextChar.bind())
            }
        }
    }

    // Generate a valid Text event (content ends with \n, no sentinel patterns in content)
    val arbTextEvent: Arb<OutputEvent> = arbitrary {
        val text = safeTextContent.bind()
        // Text events from the parser always include the trailing newline
        // Ensure content doesn't start with '[' (sentinel prefix) or match prompt regex
        val safeText = if (text.startsWith("[") || text.matches(Regex("""\w*>\s?"""))) {
            "text $text"
        } else {
            text
        }
        OutputEvent.Text(content = "$safeText\n", styleHints = emptyList())
    }

    // Generate a valid ToolCallStart event
    val arbToolCallStartEvent: Arb<OutputEvent> = arbitrary {
        val name = arbToolName.bind()
        val args = arbToolArguments.bind()
        OutputEvent.ToolCallStart(toolName = name, arguments = args)
    }

    // Generate a valid ToolCallResult event
    val arbToolCallResultEvent: Arb<OutputEvent> = arbitrary {
        val name = arbToolName.bind()
        val result = arbToolArguments.bind()
        val success = Arb.boolean().bind()
        OutputEvent.ToolCallResult(toolName = name, result = result, success = success)
    }

    // Generate a TurnComplete event
    val arbTurnCompleteEvent: Arb<OutputEvent> = arbitrary {
        OutputEvent.TurnComplete
    }

    // Generate a valid event (any of the serializable types, excluding Error)
    val arbValidEvent: Arb<OutputEvent> = Arb.choice(
        arbTextEvent,
        arbToolCallStartEvent,
        arbToolCallResultEvent,
        arbTurnCompleteEvent
    )

    // Generate a valid list of OutputEvents
    val arbValidEventList: Arb<List<OutputEvent>> = Arb.list(arbValidEvent, 1..8)

    test("Feature: android-claude-termux-client, Property 8: Parser/Printer round-trip (events direction)") {
        val parser = OutputParserImpl()
        val printer = PrettyPrinterImpl()

        checkAll(PropTestConfig(iterations = 100), arbValidEventList) { events ->
            // Step 1: Serialize events to bytes via PrettyPrinter
            val serializedBytes = printer.eventsToBytes(events)

            // Step 2: Parse the serialized bytes with OutputParser
            parser.reset()
            val parseResult = parser.parse(serializedBytes)
            val reparsedEvents = parseResult.events

            // Step 3: Verify no errors in reparsed events
            val hasErrors = reparsedEvents.any { it is OutputEvent.Error }
            hasErrors shouldBe false

            // Step 4: Verify reparsed events are equivalent to original events
            eventsEquivalent(events, reparsedEvents) shouldBe true
        }
    }

    test("Parser/Printer events round-trip holds for text-only event lists") {
        val parser = OutputParserImpl()
        val printer = PrettyPrinterImpl()

        checkAll(PropTestConfig(iterations = 100), Arb.list(arbTextEvent, 1..5)) { events ->
            val serializedBytes = printer.eventsToBytes(events)

            parser.reset()
            val parseResult = parser.parse(serializedBytes)
            val reparsedEvents = parseResult.events

            reparsedEvents.any { it is OutputEvent.Error } shouldBe false
            eventsEquivalent(events, reparsedEvents) shouldBe true
        }
    }

    test("Parser/Printer events round-trip holds for tool-call event lists") {
        val parser = OutputParserImpl()
        val printer = PrettyPrinterImpl()

        val arbToolEventList: Arb<List<OutputEvent>> = arbitrary {
            val count = Arb.int(1, 4).bind()
            buildList {
                repeat(count) {
                    add(arbToolCallStartEvent.bind())
                    add(arbToolCallResultEvent.bind())
                }
            }
        }

        checkAll(PropTestConfig(iterations = 100), arbToolEventList) { events ->
            val serializedBytes = printer.eventsToBytes(events)

            parser.reset()
            val parseResult = parser.parse(serializedBytes)
            val reparsedEvents = parseResult.events

            reparsedEvents.any { it is OutputEvent.Error } shouldBe false
            eventsEquivalent(events, reparsedEvents) shouldBe true
        }
    }

    test("Parser/Printer events round-trip holds for mixed events with TurnComplete") {
        val parser = OutputParserImpl()
        val printer = PrettyPrinterImpl()

        val arbMixedEventList: Arb<List<OutputEvent>> = arbitrary {
            val textCount = Arb.int(1, 3).bind()
            buildList {
                repeat(textCount) {
                    add(arbTextEvent.bind())
                }
                add(arbToolCallStartEvent.bind())
                add(arbToolCallResultEvent.bind())
                add(arbTextEvent.bind())
                add(OutputEvent.TurnComplete as OutputEvent)
            }
        }

        checkAll(PropTestConfig(iterations = 100), arbMixedEventList) { events ->
            val serializedBytes = printer.eventsToBytes(events)

            parser.reset()
            val parseResult = parser.parse(serializedBytes)
            val reparsedEvents = parseResult.events

            reparsedEvents.any { it is OutputEvent.Error } shouldBe false
            eventsEquivalent(events, reparsedEvents) shouldBe true
        }
    }
})
