package com.astratv.player.feature.epg

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.astratv.player.AstraApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

class GuideRefreshWorker(context: Context,parameters: WorkerParameters): CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result {
        val app=applicationContext as AstraApplication
        val settings=app.settings.flow.first()
        if(!settings.guideRefresh) return Result.success()
        for(source in app.library.dao.allSources()) {
            try {
                val url=app.library.secrets.open(source.guideCipher)
                if(url.isNotEmpty()) app.library.refreshGuide(source,url,settings.allowHttp)
            } catch(e: CancellationException) { throw e } catch(_: Exception) { /* Existing guide remains intact. Next periodic run retries. */ }
        }
        app.library.dao.pruneGuide(System.currentTimeMillis()-7*86400000L)
        return Result.success()
    }
}
