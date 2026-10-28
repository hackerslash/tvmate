package app.tvmate

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * LAN management portal: a tiny HTTP server that listens only while the "Phone remote" toggle is on
 * and TVMate is in the foreground. Every API call needs the per-install key carried in the QR code URL.
 */
object Remote {
    const val PORT = 8787

    /** What the TV is doing; MainActivity publishes it and executes commands. */
    interface Host {
        val lineup: Lineup?
        val index: List<Clean.Searchable<Ch>>
        val playing: Ch?
        fun play(key: String)
        fun zap(d: Int)
        fun refreshNow()
        fun pinsChanged()
    }

    @Volatile var host: Host? = null
    private lateinit var app: Context
    private val prefs: SharedPreferences get() = app.getSharedPreferences("s", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private var server: ServerSocket? = null
    private var users = 0

    val enabled get() = prefs.getBoolean("remote_on", false)

    fun acquire(ctx: Context) {
        app = ctx.applicationContext
        users++
        sync()
    }

    fun release() {
        users--
        sync()
    }

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean("remote_on", on).apply()
        sync()
    }

    private fun sync() {
        val want = users > 0 && enabled
        if (want && server == null) start()
        if (!want && server != null) { runCatching { server?.close() }; server = null }
    }

    fun token(): String = prefs.getString("remote_key", null) ?: run {
        val abc = "abcdefghjkmnpqrstuvwxyz23456789"
        val r = SecureRandom()
        (1..10).map { abc[r.nextInt(abc.length)] }.joinToString("").also { prefs.edit().putString("remote_key", it).apply() }
    }

    fun url(): String? = lanIp()?.let { "http://$it:$PORT/?k=${token()}" }

    private fun lanIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    private fun start() {
        val ss = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) } }.getOrNull() ?: return
        server = ss
        val pool = Executors.newFixedThreadPool(4)
        Thread {
            while (!ss.isClosed) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                pool.execute { runCatching { handle(s) }; runCatching { s.close() } }
            }
            pool.shutdown()
        }.apply { isDaemon = true }.start()
    }

    private class Req(val method: String, val path: String, val query: Map<String, String>, val body: String)

    private fun read(s: Socket): Req? {
        s.soTimeout = 10_000
        val inp = BufferedInputStream(s.getInputStream())
        val head = ByteArrayOutputStream()
        var last = 0
        while (true) {
            val b = inp.read()
            if (b < 0) return null
            head.write(b)
            last = (last shl 8) or b
            if (last == 0x0D0A0D0A) break
            if (head.size() > 16_384) return null
        }
        val lines = head.toString("UTF-8").split("\r\n")
        val (method, target) = lines[0].split(" ").let { it.getOrNull(0).orEmpty() to it.getOrNull(1).orEmpty() }
        val len = lines.firstOrNull { it.startsWith("content-length:", true) }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        if (len > 1_000_000) return null
        val body = ByteArray(len).also { var o = 0; while (o < len) { val n = inp.read(it, o, len - o); if (n < 0) break; o += n } }
        val query = target.substringAfter('?', "").split('&').filter { '=' in it }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        return Req(method, target.substringBefore('?'), query, String(body, Charsets.UTF_8))
    }

    private fun send(s: Socket, code: Int, type: String, body: ByteArray) {
        val head = "HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        s.getOutputStream().apply { write(head.toByteArray()); write(body); flush() }
    }

    private fun json(s: Socket, o: Any, code: Int = 200) = send(s, code, "application/json; charset=utf-8", o.toString().toByteArray())

    private fun handle(s: Socket) {
        val r = read(s) ?: return
        when (r.path) {
            "/" -> return send(s, 200, "text/html; charset=utf-8", app.assets.open("remote.html").readBytes())
            "/logo.png" -> return send(s, 200, "image/png", app.resources.openRawResource(R.drawable.wordmark).readBytes())
        }
        if (r.query["k"] != token()) return json(s, JSONObject().put("error", "Scan the QR code on the TV again."), 403)
        val h = host
        when ("${r.method} ${r.path}") {
            "GET /api/state" -> json(s, state())
            "GET /api/search" -> json(s, JSONArray(Clean.search(r.query["q"].orEmpty(), h?.index.orEmpty(), 40).map { chJson(it) }))
            "POST /api/settings" -> { saveSettings(JSONObject(r.body)); json(s, state()) }
            "POST /api/pins" -> {
                prefs.edit().putString("pins", r.body).apply()
                main.post { host?.pinsChanged() }
                json(s, state())
            }
            "GET /api/cats" -> json(s, JSONArray(h?.lineup?.cats.orEmpty().mapIndexed { i, c ->
                JSONObject().put("i", i).put("name", c.name).put("group", c.group.ifEmpty { "Other" }).put("count", c.keys.size)
            }))
            "GET /api/cat" -> {
                val l = h?.lineup
                val cat = l?.cats?.getOrNull(r.query["i"]?.toIntOrNull() ?: -1)
                val from = r.query["from"]?.toIntOrNull() ?: 0
                val byKey = l?.channels?.associateBy { it.key }.orEmpty()
                val page = cat?.keys?.drop(from)?.take(60)?.mapNotNull { byKey[it] }.orEmpty()
                json(s, JSONObject().put("total", cat?.keys?.size ?: 0).put("channels", JSONArray(page.map { chJson(it) })))
            }
            "POST /api/zap" -> {
                main.post { host?.zap(r.query["d"]?.toIntOrNull() ?: 1) }
                json(s, JSONObject().put("ok", true))
            }
            "POST /api/play" -> { main.post { host?.play(r.query["key"].orEmpty()) }; json(s, JSONObject().put("ok", true)) }
            "POST /api/refresh" -> { main.post { host?.refreshNow() }; json(s, JSONObject().put("ok", true)) }
            "POST /api/update" -> {
                val rel = Update.available ?: Update.check()
                if (rel == null) json(s, JSONObject().put("ok", false).put("message", "TVMate is up to date."))
                else {
                    Thread { runCatching { Update.install(app, rel) } }.start()
                    json(s, JSONObject().put("ok", true).put("message", "Downloading v${rel.version}. Confirm the install on the TV."))
                }
            }
            else -> json(s, JSONObject().put("error", "not found"), 404)
        }
    }

    private fun names() = Repo.loadProviders(prefs).map { it.name }

    private fun chJson(c: Ch) = JSONObject().put("key", c.key).put("name", c.name).put("logo", c.logo ?: "")
        .put("source", Repo.source(c, names()))

    private fun state(): JSONObject {
        val h = host
        val l = h?.lineup
        val byKey = l?.channels?.associateBy { it.key }.orEmpty()
        val pins = JSONArray(Repo.loadPins(prefs).map { p ->
            JSONObject().put("name", p.name).put("channels", JSONArray(p.keys.mapNotNull { byKey[it] }.map { chJson(it) }))
        })
        val provs = JSONArray(Repo.loadProviders(prefs).map {
            JSONObject().put("name", it.name).put("server", it.server).put("user", it.user).put("pass", it.pass)
        })
        val counts = JSONObject()
        l?.cats?.groupBy { it.group }?.forEach { (g, cats) -> counts.put(g.ifEmpty { "Other" }, cats.flatMap { it.keys }.toSet().size) }
        return JSONObject()
            .put("version", BuildConfig.VERSION_NAME)
            .put("update", Update.available?.version ?: JSONObject.NULL)
            .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            .put("playing", h?.playing?.let { chJson(it) } ?: JSONObject.NULL)
            .put("channels", l?.channels?.size ?: 0)
            .put("counts", counts)
            .put("refreshedAt", l?.at ?: 0)
            .put("settings", JSONObject()
                .put("mi_on", prefs.getBoolean("mi_on", true))
                .put("extras", prefs.getBoolean("extras", false))
                .put("refresh_h", prefs.getInt("refresh_h", 6))
                .put("providers", provs))
            .put("pins", pins)
    }

    private fun saveSettings(o: JSONObject) {
        val provs = o.optJSONArray("providers")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                Provider(it.optString("name").ifBlank { "Provider" }, it.optString("server").trim(), it.optString("user").trim(), it.optString("pass"))
            }.filter { it.server.isNotEmpty() && it.user.isNotEmpty() }
        }
        val e = prefs.edit()
            .putBoolean("mi_on", o.optBoolean("mi_on", prefs.getBoolean("mi_on", true)))
            .putBoolean("extras", o.optBoolean("extras", prefs.getBoolean("extras", false)))
            .putInt("refresh_h", o.optInt("refresh_h", prefs.getInt("refresh_h", 6)))
            .putBoolean("dirty", true)
        provs?.let { Repo.saveProviders(e, it) }
        e.apply()
        main.post { host?.refreshNow() }
    }
}
