package com.astratv.player.core.player

object RetryPolicy {
    const val MAX_RETRIES = 3
    fun delayMs(attempt: Int): Long? = if(attempt in 0 until MAX_RETRIES) 1000L shl attempt else null
}
