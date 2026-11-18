package app.melo.music.data

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

object Network {
    const val USER_AGENT = "Mozilla/5.0 (Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .build()

    fun text(url: String, userAgent: String = USER_AGENT): String {
        val request = okhttp3.Request.Builder().url(url).header("User-Agent", userAgent).build()
        return client.newCall(request).execute().use { response ->
            if (response.code == 429) throw RateLimitException(response.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 3600) ?: 60)
            if (!response.isSuccessful) throw IOException("Сервис ответил HTTP ${response.code}")
            response.body?.string() ?: throw IOException("Сервис вернул пустой ответ")
        }
    }
}

class RateLimitException(val retryAfterSeconds: Long) : IOException("Слишком много запросов. Повторите через $retryAfterSeconds с.")

class ExtractorDownloader : Downloader() {
    override fun execute(request: Request): Response {
        val builder = okhttp3.Request.Builder().url(request.url())
            .header("User-Agent", Network.USER_AGENT)
            .header("Cookie", "CONSENT=YES+cb; SOCS=CAI")
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        val body = request.dataToSend()?.toRequestBody(null)
        builder.method(request.httpMethod(), if (request.httpMethod() in listOf("POST", "PUT", "PATCH")) body ?: byteArrayOf().toRequestBody(null) else body)
        return Network.client.newCall(builder.build()).execute().use {
            Response(it.code, it.message, it.headers.toMultimap(), it.body?.string(), it.request.url.toString())
        }
    }
}
