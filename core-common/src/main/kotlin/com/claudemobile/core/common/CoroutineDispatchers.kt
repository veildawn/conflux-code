package com.claudemobile.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Abstraction over coroutine dispatchers to enable DI and testing.
 * Inject this interface instead of using [Dispatchers] directly.
 */
public interface CoroutineDispatchers {
    /** Dispatcher for CPU-intensive work. */
    public val default: CoroutineDispatcher

    /** Dispatcher for IO-bound work (disk, network). */
    public val io: CoroutineDispatcher

    /** Dispatcher for UI/main-thread work. */
    public val main: CoroutineDispatcher

    /** Immediate dispatcher for main-thread work without redispatch. */
    public val mainImmediate: CoroutineDispatcher

    /** Dispatcher constrained to a single thread, useful for shared mutable state. */
    public val unconfined: CoroutineDispatcher
}

/**
 * Default implementation backed by the standard [Dispatchers].
 */
public class DefaultCoroutineDispatchers : CoroutineDispatchers {
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val main: CoroutineDispatcher = Dispatchers.Main
    override val mainImmediate: CoroutineDispatcher = Dispatchers.Main.immediate
    override val unconfined: CoroutineDispatcher = Dispatchers.Unconfined
}
