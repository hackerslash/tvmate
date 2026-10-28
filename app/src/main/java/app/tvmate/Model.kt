package app.tvmate

import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

@Serializable
data class MiRef(val id: Long, val cl: String, val region: String)

@Serializable
data class Ch(
    val key: String,
    val name: String,
    val logo: String? = null,
    val desc: String = "",
    val urls: List<String>,
    val mi: MiRef? = null,
    /** "providerIndex:streamId" of the preferred variant, for EPG. */
    val xt: String? = null,
)

@Serializable
data class Cat(val name: String, val keys: List<String>, val group: String = "")

@Serializable
data class Provider(val name: String, val server: String, val user: String, val pass: String)

@Serializable
data class Pin(val name: String, val keys: List<String>)

@Serializable
data class Lineup(val channels: List<Ch>, val cats: List<Cat>, val at: Long)

data class Prog(val title: String, val desc: String, val start: Long, val end: Long)

object Net {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun get(client: OkHttpClient, url: String): Response =
        client.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute()

    // Streams can be endless; never read more than the first 64 KB.
    fun head(r: Response): String = r.use {
        if (!it.isSuccessful) return ""
        val src = it.body!!.source()
        src.request(65536)
        src.buffer.readUtf8(minOf(src.buffer.size, 65536))
    }

    const val UA = "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"
}
