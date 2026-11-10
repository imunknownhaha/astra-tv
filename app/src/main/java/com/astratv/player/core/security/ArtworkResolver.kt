package com.astratv.player.core.security

import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Bounded in-memory URL reuse avoids repeating Keystore operations while scrolling back. */
class ArtworkResolver(private val secrets: SecretCodec, capacityBytes: Int = 1024 * 1024) {
    private val permits=Semaphore(2)
    private val cache=object: LruCache<String,String>(capacityBytes) {
        override fun sizeOf(key: String,value: String) = (key.length+value.length)*2
    }
    suspend fun resolve(cipher: String): String {
        if(cipher.isEmpty()) return ""
        cache.get(cipher)?.let { return it }
        return permits.withPermit {
            cache.get(cipher) ?: withContext(Dispatchers.IO) {
                secrets.open(cipher).also { cache.put(cipher,it) }
            }
        }
    }
}
