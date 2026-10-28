package app.tvmate

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

/** Checks this repo's GitHub releases; needs the repo to be public. */
object Update {
    private const val REPO = "hackerslash/tvmate"

    class Release(val version: String, val apk: String)

    @Volatile var available: Release? = null

    fun check(): Release? = runCatching {
        val j = JSONObject(Net.get(Net.http, "https://api.github.com/repos/$REPO/releases/latest").use { it.body!!.string() })
        val version = j.getString("tag_name").removePrefix("v")
        val assets = j.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .first { it.getString("name").endsWith(".apk") }.getString("browser_download_url")
        Release(version, apk).takeIf { newer(version, BuildConfig.VERSION_NAME) }
    }.getOrNull().also { available = it }

    fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** Blocking download, then hands the APK to the system installer. */
    fun install(ctx: Context, r: Release, progress: (Int) -> Unit = {}) {
        val f = File(ctx.cacheDir, "update.apk")
        Net.get(Net.http, r.apk).use { resp ->
            check(resp.isSuccessful) { "Download failed: HTTP ${resp.code}" }
            val total = resp.body!!.contentLength()
            f.outputStream().use { out ->
                val inp = resp.body!!.byteStream()
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) progress((done * 100 / total).toInt())
                }
            }
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
