package com.astratv.player.core.security

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.astratv.player.BuildConfig
import java.util.concurrent.atomic.AtomicReference

/** Local diagnostics contain types/our code location only, never exception messages or source data. */
object CrashDiagnostics {
    enum class Stage { STARTUP, IDLE, PROVIDER_IMPORT }
    private val stage = AtomicReference(Stage.STARTUP)
    private const val PREFS = "astra.diagnostics"
    fun mark(value: Stage) { stage.set(value) }
    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("crash", summary(error, stage.get())).commit()
            } catch (_: Throwable) { /* Best effort only, especially when the heap is exhausted. */ }
            finally { if (previous != null) previous.uncaughtException(thread, error) else Runtime.getRuntime().halt(10) }
        }
    }
    internal fun summary(error: Throwable, stage: Stage): String {
        var cause = error
        repeat(8) { val next=cause.cause;if(next!=null && next!==cause) cause=next }
        val location = cause.stackTrace.firstOrNull { it.className.startsWith("com.astratv.player.") }
        val where = location?.let { " ${it.fileName}:${it.lineNumber}" }.orEmpty()
        return "${BuildConfig.VERSION_NAME} / ${stage.name} / ${cause.javaClass.simpleName}$where"
    }
    fun consume(context: Context): String? {
        val prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        val crash=prefs.getString("crash",null)
        if(crash!=null) prefs.edit().remove("crash").apply()
        var systemReason: String?=null
        if(Build.VERSION.SDK_INT>=30) runCatching {
            val exit=context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName,0,1).firstOrNull()
            if(exit!=null && exit.timestamp>prefs.getLong("exitSeen",0)) {
                prefs.edit().putLong("exitSeen",exit.timestamp).apply()
                systemReason=when(exit.reason) {
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "Android reported a low-memory shutdown."
                    ApplicationExitInfo.REASON_CRASH,ApplicationExitInfo.REASON_CRASH_NATIVE -> "Android reported an earlier app crash."
                    else -> null
                }
            }
        }
        return if(crash!=null) "Previous crash: $crash. Please share this code if it happens again." else systemReason
    }
}
