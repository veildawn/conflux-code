package com.claudemobile.core.domain.model

/**
 * The result of parsing a byte buffer, containing the produced events,
 * the number of bytes consumed, and the remaining unconsumed buffer bytes.
 */
public data class ParseResult(
    val events: List<OutputEvent>,
    val remainingBuffer: ByteArray = byteArrayOf(),
    val consumedBytes: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParseResult) return false
        return events == other.events &&
            remainingBuffer.contentEquals(other.remainingBuffer) &&
            consumedBytes == other.consumedBytes
    }

    override fun hashCode(): Int {
        var result = events.hashCode()
        result = 31 * result + remainingBuffer.contentHashCode()
        result = 31 * result + consumedBytes
        return result
    }
}
