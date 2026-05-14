package com.claudemobile.core.common

import java.util.UUID

/**
 * Abstraction for UUID generation to enable deterministic testing.
 */
public interface UuidGenerator {
    /** Generates a new unique identifier string. */
    public fun generate(): String
}

/**
 * Default implementation using [java.util.UUID.randomUUID].
 */
public class DefaultUuidGenerator : UuidGenerator {
    override fun generate(): String = UUID.randomUUID().toString()
}
