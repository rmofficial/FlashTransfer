package com.rahulmandal.flashtransfer

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Shared state between the foreground service and the UI (same process). */
object ServerState {
    @Volatile var running = false
    @Volatile var port = 8181
    @Volatile var error: String? = null
    val bytesSent = AtomicLong(0)
    val active = AtomicInteger(0)
}
