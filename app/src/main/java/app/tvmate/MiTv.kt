package app.tvmate

import okhttp3.Dns
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/** Xiaomi TV+ catalogue API, reconstructed from com.mitv.tvhome.mitvplus 3.9.9. */
object MiTv {
    private const val KEY = "581582928c881b42eedce96331bff5d3"
    private const val TOKEN = "0f9dfa001cba164d7bda671649c50abf"
    private const val HOST = "mitvplus.global.mi.com"

    // GeoDNS sends each country to one cluster, and each cluster only serves its own regions.
    private val CLUSTERS = mapOf(
        "pune" to "extranet-ipv4-pub-azpn.alb.xiaomi.com",
        "sgp" to "extranet-ipv4-pub-alisgp.alb.xiaomi.com",
        "ams" to "extranet-ipv4-pub-azams.alb.xiaomi.com",
    )

    // ponytail: minimal (cluster, region) set covering all 837 channels in a 2026-10 sweep; re-run tools/sweep.py if Xiaomi adds regions.
    val JOBS = listOf(
        "pune" to "in", "ams" to "de", "ams" to "es", "ams" to "fr", "sgp" to "ma", "ams" to "it",
        "ams" to "gb", "ams" to "ch", "sgp" to "mx", "sgp" to "br", "ams" to "pt", "sgp" to "my",
        "ams" to "pl", "pune" to "us", "ams" to "nl", "ams" to "no",
    )

    private val clients = CLUSTERS.mapValues { (_, alb) ->
        Net.http.newBuilder().dns(object : Dns {
            override fun lookup(hostname: String) = Dns.SYSTEM.lookup(if (hostname == HOST) alb else hostname)
        }).build()
    }

    var me: String = "0"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun sign(pathAndQuery: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(KEY.toByteArray(), "HmacSHA1"))
        return mac.doFinal("$pathAndQuery&token=$TOKEN".toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun api(cl: String, path: String, region: String, extra: Map<String, String> = emptyMap()): JSONObject {
        // Region must be lowercase: "IN" silently returns a 13-channel fallback list.
        val q = linkedMapOf(
            "me" to me, "app" to "4", "codever" to "58", "today" to (System.currentTimeMillis() / 1000).toString(),
            "ostype" to "1", "androidver" to "30", "region" to region, "dlg" to "en", "ptf" to "1234", "devicetype" to "0",
        ) + extra
        val pq = path + "?" + q.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
        val body = Net.get(clients.getValue(cl), "https://$HOST$pq&opaque=${sign(pq)}").use { it.body!!.string() }
        return JSONObject(body)
    }

    class Raw(val ch: Ch, val bucket: String)

    fun guide(cl: String, region: String): List<Raw> {
        val blocks = api(cl, "/mitv/guide", region).optJSONObject("data")?.optJSONArray("blocks") ?: return emptyList()
        val out = mutableListOf<Raw>()
        for (i in 0 until blocks.length()) {
            val b = blocks.getJSONObject(i)
            val bucket = bucket(b.optString("code"), b.optString("title")) ?: continue
            val chans = b.optJSONArray("channels") ?: continue
            for (j in 0 until chans.length()) {
                val c = chans.getJSONObject(j)
                val url = c.optString("playurl").ifEmpty { c.optString("intent") }
                if (!url.startsWith("http")) continue
                val img = c.optJSONObject("images")
                val logo = (img?.optJSONObject("icon") ?: img?.optJSONObject("poster"))?.optString("link")
                val title = c.optString("title").trim()
                out += Raw(
                    Ch(
                        key = norm(title), name = title, logo = logo, desc = c.optString("description"),
                        urls = listOf(url), mi = MiRef(c.getLong("id"), cl, region),
                    ),
                    bucket,
                )
            }
        }
        return out
    }

    fun norm(t: String) = t.lowercase().filter { it.isLetterOrDigit() }

    val ORDER = listOf(
        "Popular", "New", "News", "Local", "Movies", "Series", "Entertainment", "Sports", "Kids", "Music",
        "Comedy", "Crime", "Documentary", "Lifestyle", "Anime", "Devotion", "Halloween", "More",
    )

    // Order matters: "regional news" is Local, "news" must win over "new".
    private val RULES = listOf(
        "recent" to null, "favorite" to null,
        "popular" to "Popular", "best" to "Popular",
        "regional" to "Local", "local" to "Local", "french" to "Local", "portuguese" to "Local", "polski" to "Local",
        "news" to "News", "new" to "New", "novelty" to "New",
        "anime" to "Anime", "kid" to "Kids", "junior" to "Kids",
        "movie" to "Movies", "film" to "Movies", "serie" to "Series", "soap" to "Series",
        "sport" to "Sports", "music" to "Music", "comedy" to "Comedy", "crime" to "Crime",
        "science" to "Documentary", "docu" to "Documentary", "doku" to "Documentary",
        "lifestyle" to "Lifestyle", "travel" to "Lifestyle", "trips" to "Lifestyle", "cars" to "Lifestyle",
        "entertainment" to "Entertainment", "bbc" to "Entertainment",
        "devotion" to "Devotion", "halloween" to "Halloween",
    )

    // Block titles are localized per region; the codes are English-ish, so bucket on those.
    fun bucket(code: String, title: String): String? {
        val c = code.lowercase()
        for ((k, v) in RULES) if (k in c) return v
        return if (title.isEmpty()) null else "More"
    }

    fun programs(refs: List<MiRef>): Map<Long, List<Prog>> {
        val out = HashMap<Long, List<Prog>>()
        refs.groupBy { it.cl to it.region }.forEach { (k, list) ->
            list.chunked(40).forEach { chunk ->
                val chans = runCatching {
                    api(k.first, "/mitv/channel/program", k.second, mapOf(
                        "channel_ids" to chunk.joinToString(",") { it.id.toString() }, "call_from" to "guide",
                    )).optJSONObject("data")?.optJSONArray("channels")
                }.getOrNull() ?: return@forEach
                for (i in 0 until chans.length()) {
                    val c = chans.getJSONObject(i)
                    val ps = c.optJSONArray("programs") ?: continue
                    out[c.optString("channel_id").toLong()] = (0 until ps.length()).map {
                        val p = ps.getJSONObject(it)
                        Prog(p.optString("program_name"), p.optString("description"), p.optLong("start_time"), p.optLong("end_time"))
                    }
                }
            }
        }
        return out
    }

    /** Fill the ad-tracking placeholders the server leaves in play URLs; unfilled ones break some CDNs. */
    fun fill(url: String): String {
        if ('{' !in url && "%7B" !in url) return url
        return url
            .replace(Regex("\\{\\{[A-Z_]+\\}\\}")) { if ("CACHE" in it.value) Random.nextInt(1, 1_000_000_000).toString() else "" }
            .replace("{device_id}", UUID.randomUUID().toString())
            .replace("{us_privacy}", "1---")
            .replace("{app_name}", "xiaomitv")
            .replace(Regex("\\{[a-z_]+\\}"), "")
            .replace(Regex("%7B[A-Z_]+%7D"), "")
    }

    /** A channel is alive if its master playlist and first variant both answer with HLS. */
    fun alive(url: String): Boolean = runCatching {
        val r = Net.get(Net.http, fill(url))
        val base = r.request.url
        val body = Net.head(r)
        if ("#EXTM3U" !in body) return false
        val variant = body.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return "#EXTINF" in body
        val v = base.resolve(variant.trim()) ?: return false
        val vb = Net.head(Net.get(Net.http, v.toString()))
        "#EXTM3U" in vb || vb.startsWith("G")
    }.getOrDefault(false)
}
