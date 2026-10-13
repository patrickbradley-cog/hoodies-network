package com.gap.hoodies_network.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Coroutine dispatchers used by [HoodiesNetworkClientNonInlined].
 *
 * @param io runs request preparation and cache lookups before a request is enqueued.
 */
internal class HoodiesDispatchers(
    val io: CoroutineDispatcher = Dispatchers.IO
)
