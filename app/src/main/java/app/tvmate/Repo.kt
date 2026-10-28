package app.tvmate

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class Repo(ctx: Context) {
    val prefs = ctx.getSharedPreferences("s", Context.MODE_PRIVATE)
    private val file = File(ctx.filesDir, "lineup.json")
    private val epg = ConcurrentHashMap<String, Pair<Long, List<Prog>>>()

    init {
        MiTv.me = prefs.getString("me", null) ?: UUID.randomUUID().toString().replace("-", "").also {
            prefs.edit().putString("me", it).apply()
        }
    }

    val miOn get() = prefs.getBoolean("mi_on", true)
    private val extras get() = prefs.getBoolean("extras", false)
    val providers: List<Provider> get() = loadProviders(prefs)
    val configured get() = miOn || providers.isNotEmpty()

    fun cached(): Lineup? = runCatching { json.decodeFromString<Lineup>(file.readText()) }.getOrNull()

    fun refresh(progress: (String) -> Unit): Lineup {
        val pool = Executors.newFixedThreadPool(32)
        try {
            val provs = providers
            // Providers download while the free catalogue is being liveness-checked.
            val xtJobs = provs.mapIndexed { i, p -> pool.submit(Callable { runCatching { Xtream(p).load(i, extras) } }) }
            val cats = mutableListOf<Cat>()
            val chans = HashMap<String, Ch>()
            if (miOn) loadMi(pool, progress).let { (c, k) -> chans.putAll(c.associateBy { it.key }); cats += k }
            if (provs.isNotEmpty()) progress("Loading ${provs.joinToString { it.name }}…")
            val errors = mutableListOf<String>()
            xtJobs.forEach { f ->
                f.get().onSuccess { (c, k) -> c.forEach { chans[it.key] = it }; cats += k }
                    .onFailure { errors += it.message ?: it.toString() }
            }
            // Borrow a logo from any same-named channel (other country, other provider, free catalogue).
            val logos = HashMap<String, String>()
            chans.values.forEach { c -> if (!Clean.weakLogo(c.logo)) logos.putIfAbsent(Clean.key(c.name), c.logo!!) }
            chans.replaceAll { _, c -> if (Clean.weakLogo(c.logo)) logos[Clean.key(c.name)]?.let { c.copy(logo = it) } ?: c else c }
            // Number channels in guide order so UP/DOWN and the banner number agree.
            val ordered = LinkedHashMap<String, Ch>()
            cats.forEach { cat -> cat.keys.forEach { k -> chans[k]?.let { ordered.putIfAbsent(k, it) } } }
            val lineup = Lineup(ordered.values.toList(), cats, System.currentTimeMillis())
            if (lineup.channels.isEmpty() && errors.isNotEmpty()) error(errors.joinToString("; "))
            file.writeText(json.encodeToString(Lineup.serializer(), lineup))
            return lineup
        } finally {
            pool.shutdownNow()
        }
    }

    private fun loadMi(pool: java.util.concurrent.ExecutorService, progress: (String) -> Unit): Pair<List<Ch>, List<Cat>> {
        progress("Loading free channels…")
        val raws = pool.invokeAll(MiTv.JOBS.map { (cl, r) ->
            Callable { runCatching { MiTv.guide(cl, r) }.getOrDefault(emptyList()) }
        }).flatMap { it.get() }
        // Same channel shows up in many regions under different ids: merge by title, keep every URL as a fallback.
        val merged = LinkedHashMap<String, Ch>()
        val buckets = LinkedHashMap<String, LinkedHashSet<String>>()
        for (raw in raws) {
            val c = raw.ch
            val prev = merged[c.key]
            merged[c.key] = if (prev == null) c else prev.copy(urls = (prev.urls + c.urls).distinct())
            buckets.getOrPut(raw.bucket) { LinkedHashSet() } += c.key
        }
        progress("Checking ${merged.size} free channels…")
        val live = pool.invokeAll(merged.values.map { c ->
            Callable { c.copy(urls = c.urls.filter(MiTv::alive)) }
        }).map { it.get() }.filter { it.urls.isNotEmpty() }
        val alive = live.associateBy { it.key }
        val cats = MiTv.ORDER.mapNotNull { name ->
            buckets[name]?.filter { it in alive }?.takeIf { it.isNotEmpty() }?.let { Cat(name, it, FREE) }
        }
        return live to cats
    }

    fun cachedEpg(c: Ch): List<Prog>? = epg[c.key]?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000 }?.second

    /** Fetches now/next for channels missing from the cache; free channels are batched per cluster. */
    fun loadEpg(list: List<Ch>) {
        val todo = list.filter { cachedEpg(it) == null }
        if (todo.isEmpty()) return
        val now = System.currentTimeMillis()
        val mi = todo.filter { it.mi != null }
        if (mi.isNotEmpty()) {
            val got = MiTv.programs(mi.map { it.mi!! })
            mi.forEach { epg[it.key] = now to got[it.mi!!.id].orEmpty() }
        }
        val provs = providers
        todo.forEach { c ->
            val (p, id) = c.xt?.split(':')?.takeIf { it.size == 2 } ?: return@forEach
            val prov = provs.getOrNull(p.toInt()) ?: return@forEach
            epg[c.key] = now to runCatching { Xtream(prov).epg(id.toInt()) }.getOrDefault(emptyList())
        }
    }

    companion object {
        const val FREE = "Free TV"
        val json = Json { ignoreUnknownKeys = true }

        fun loadProviders(prefs: android.content.SharedPreferences): List<Provider> = runCatching {
            json.decodeFromString(ListSerializer(Provider.serializer()), prefs.getString("providers", "[]")!!)
        }.getOrDefault(emptyList())

        const val PINNED = "Pinned"

        fun loadPins(prefs: android.content.SharedPreferences): List<Pin> = runCatching {
            json.decodeFromString(ListSerializer(Pin.serializer()), prefs.getString("pins", "[]")!!)
        }.getOrDefault(emptyList())

        /** "Free TV" or the provider name, plus country and genre for provider channels. */
        fun source(c: Ch, providerNames: List<String>): String {
            if (c.mi != null) return FREE
            val p = c.xt?.substringBefore(':')?.toIntOrNull()?.let { providerNames.getOrNull(it) } ?: "Provider"
            return if (c.desc.isBlank()) p else "$p · ${c.desc}"
        }

        fun saveProviders(prefs: android.content.SharedPreferences.Editor, list: List<Provider>) =
            prefs.putString("providers", json.encodeToString(ListSerializer(Provider.serializer()), list))
    }
}
