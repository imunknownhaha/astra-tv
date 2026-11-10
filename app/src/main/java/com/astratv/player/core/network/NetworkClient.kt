package com.astratv.player.core.network

import com.astratv.player.core.model.ImportFailure
import okhttp3.*
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

class NetworkClient {
    fun client(allowHttp: Boolean): OkHttpClient = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS)
        .readTimeout(30,TimeUnit.SECONDS).callTimeout(120,TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            try { UrlPolicy.validate(chain.request().url.toString(),allowHttp) } catch (_: ImportFailure) { throw java.io.IOException("Insecure or invalid redirect blocked.") }
            chain.proceed(chain.request())
        }.build()
    private val secure by lazy { client(false) };private val legacy by lazy { client(true) }
    private val secureStream by lazy { secure.newBuilder().callTimeout(0,TimeUnit.MILLISECONDS).build() }
    private val legacyStream by lazy { legacy.newBuilder().callTimeout(0,TimeUnit.MILLISECONDS).build() }
    fun streamHttp(allowHttp: Boolean) = if(allowHttp) legacyStream else secureStream
    fun http(allowHttp: Boolean) = if(allowHttp) legacy else secure
    fun <T> read(url: String,allowHttp: Boolean,limit: Long=64L*1024*1024,block: (InputStream)->T): T {
        UrlPolicy.validate(url,allowHttp)
        http(allowHttp).newCall(Request.Builder().url(url).header("User-Agent","AstraTV/0.1").build()).execute().use { response ->
            if(!response.isSuccessful) throw ImportFailure("The provider returned HTTP ${response.code}. Check the source configuration.")
            val body=response.body ?: throw ImportFailure("The provider returned an empty response.")
            if(body.contentLength()>limit) throw ImportFailure("The provider response exceeds the size limit.")
            val raw=body.byteStream().buffered();raw.mark(2);val gzip=raw.read()==0x1f && raw.read()==0x8b;raw.reset()
            val input=if(gzip) GZIPInputStream(raw) else raw
            return LimitedInput(input,limit).use(block)
        }
    }
    fun text(url: String,allowHttp: Boolean): String = read(url,allowHttp) { it.bufferedReader().readText() }
}
private class LimitedInput(input: InputStream,private val limit: Long): FilterInputStream(input) {
    private var count=0L
    private fun check(n: Int): Int { if(n>0) count+=n; if(count>limit) throw ImportFailure("Decompressed response exceeds the size limit.");return n }
    override fun read(): Int { val n=super.read();if(n>=0) check(1);return n }
    override fun read(b: ByteArray,off: Int,len: Int): Int = check(`in`.read(b,off,len))
}
