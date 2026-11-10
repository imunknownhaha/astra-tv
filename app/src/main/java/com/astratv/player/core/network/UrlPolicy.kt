package com.astratv.player.core.network

import com.astratv.player.core.model.ImportFailure
import java.net.URI

object UrlPolicy {
    fun validate(raw: String, allowHttp: Boolean = false): String {
        val text = raw.trim()
        if (text.length > 16384 || text.any { it.code < 32 }) throw ImportFailure("The address contains invalid characters.")
        val uri = try { URI(text) } catch (_: Exception) { throw ImportFailure("Enter a valid stream or playlist address.") }
        if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null)
            throw ImportFailure("Use an HTTPS address with a valid host. Embedded user information is not supported.")
        if (uri.scheme == "http" && !allowHttp) throw ImportFailure("This source uses HTTP. Enable HTTP sources in Settings if your provider requires it.")
        return text
    }
    fun resolve(base: String?, path: String, allowHttp: Boolean): String =
        validate(if (base != null) URI(base).resolve(path.trim()).toString() else path, allowHttp)
}
