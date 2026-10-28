package app.tvmate

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

class Xtream(private val p: Provider) {
    private val base = p.server.trim().trimEnd('/').let { if ("://" in it) it else "http://$it" }

    private fun api(action: String = "", extra: String = ""): String {
        val u = "$base/player_api.php?username=${enc(p.user)}&password=${enc(p.pass)}" +
            (if (action.isNotEmpty()) "&action=$action" else "") + extra
        return Net.get(Net.http, u).use { r ->
            check(r.isSuccessful) { "${p.name}: HTTP ${r.code}" }
            r.body!!.string()
        }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    private class Variant(val rank: Int, val id: Int, val url: String, val logo: String?)

    /** Raw channels for provider [idx]: VOD-like entries dropped, quality variants merged, re-categorized by genre and country. */
    fun load(idx: Int, extras: Boolean): Pair<List<Ch>, List<Cat>> {
        val info = JSONObject(api()).optJSONObject("user_info")
        check(info != null && info.optInt("auth", 1) == 1) { "${p.name}: login failed" }
        val formats = info.optJSONArray("allowed_output_formats")?.toString().orEmpty()
        // Raw TS starts fastest; fall back to HLS only when the panel forbids TS.
        val ext = if (formats.isNotEmpty() && "ts" !in formats && "m3u8" in formats) "m3u8" else "ts"

        val catNames = HashMap<String, String>()
        JSONArray(api("get_live_categories")).forEachObj { catNames[it.optString("category_id")] = it.optString("category_name") }

        class Group(val name: String, val country: String, val genre: String, val variants: MutableList<Variant>)
        val groups = LinkedHashMap<String, Group>()
        JSONArray(api("get_live_streams")).forEachObj {
            val name = it.optString("name")
            val cat = catNames[it.optString("category_id")].orEmpty()
            if (!extras && (Clean.junk(name, cat) || it.optString("is_adult") == "1")) return@forEachObj
            val clean = Clean.base(name)
            val k = Clean.key(clean).ifEmpty { clean.lowercase().replace(" ", "") }
            if (k.length < 2) return@forEachObj
            val country = Clean.country(cat)
            val id = it.optInt("stream_id")
            val v = Variant(Clean.rank(name), id, "$base/live/${enc(p.user)}/${enc(p.pass)}/$id.$ext", it.optString("stream_icon").ifBlank { null })
            groups.getOrPut("x$idx:$country:$k") { Group(clean, country, Clean.genre("$clean $cat"), mutableListOf()) }.variants += v
        }

        // A logo shared by many different channels is the sub-feed's brand (e.g. AT&T), not the channel's.
        val uses = HashMap<String, Int>()
        groups.values.forEach { g -> g.variants.mapNotNull { it.logo }.distinct().forEach { uses.merge(it, 1, Int::plus) } }
        val chans = groups.map { (key, g) ->
            val vs = g.variants.sortedBy { it.rank }
            Ch(key = key, name = g.name, logo = Clean.bestLogo(vs.mapNotNull { it.logo }.filter { uses.getValue(it) < 8 }), desc = "${g.country} · ${g.genre}",
                urls = vs.map { it.url }.distinct(), xt = "$idx:${vs[0].id}")
        }
        val genreOf = groups.mapValues { it.value.genre }
        val order = Clean.GENRES.withIndex().associate { it.value to it.index }
        val cats = mutableListOf<Cat>()
        Clean.GENRES.filter { it != "Entertainment" }.forEach { g ->
            val keys = chans.filter { genreOf[it.key] == g }.map { it.key }
            if (keys.isNotEmpty()) cats += Cat(g, keys, p.name)
        }
        chans.groupBy { groups.getValue(it.key).country }.entries.sortedByDescending { it.value.size }.forEach { (country, list) ->
            cats += Cat(country, list.sortedBy { order[genreOf[it.key]] }.map { it.key }, p.name)
        }
        return chans to cats
    }

    fun epg(streamId: Int): List<Prog> {
        val arr = JSONObject(api("get_short_epg", "&stream_id=$streamId&limit=6")).optJSONArray("epg_listings") ?: return emptyList()
        val out = mutableListOf<Prog>()
        arr.forEachObj {
            out += Prog(b64(it.optString("title")), b64(it.optString("description")),
                it.optString("start_timestamp").toLongOrNull() ?: 0, it.optString("stop_timestamp").toLongOrNull() ?: 0)
        }
        return out
    }

    private fun b64(s: String) = runCatching { String(Base64.decode(s, Base64.DEFAULT)) }.getOrDefault(s)
}

inline fun JSONArray.forEachObj(f: (JSONObject) -> Unit) {
    for (i in 0 until length()) optJSONObject(i)?.let(f)
}
