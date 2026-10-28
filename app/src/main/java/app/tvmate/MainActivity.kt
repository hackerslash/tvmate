package app.tvmate

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TOUR_VERSION = 1

@OptIn(UnstableApi::class)
class MainActivity : Activity(), Remote.Host {
    private val scope = MainScope()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var repo: Repo
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var banner: View
    private lateinit var hint: View
    private lateinit var guide: View
    private lateinit var status: TextView
    private lateinit var rail: RecyclerView
    private lateinit var rows: RecyclerView

    /** Lineup as loaded, before pinned sections are prepended. */
    private var base: Lineup? = null
    override var lineup: Lineup? = null
    override val index get() = searchIndex
    override val playing get() = cur
    private var numbers = emptyMap<String, Int>()
    private var byKey = emptyMap<String, Ch>()
    private var searchIndex = emptyList<Clean.Searchable<Ch>>()
    private var providerNames = emptyList<String>()
    private var searching = false
    private lateinit var search: EditText
    private var zapList = emptyList<Ch>()
    private var cur: Ch? = null
    private var urlIdx = 0
    private var catIdx = 0
    private var refreshing = false
    private var lastBack = 0L
    private var cacheLoaded = false
    private val rowsAdapter = RowsAdapter()
    private val railAdapter = RailAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        repo = Repo(this)
        playerView = findViewById(R.id.player)
        banner = findViewById(R.id.banner)
        hint = findViewById(R.id.hint)
        guide = findViewById(R.id.guide)
        status = findViewById(R.id.status)
        rail = findViewById(R.id.rail)
        rows = findViewById(R.id.rows)
        rail.layoutManager = LinearLayoutManager(this)
        rail.adapter = railAdapter
        rail.itemAnimator = null
        rows.layoutManager = LinearLayoutManager(this)
        rows.adapter = rowsAdapter
        rows.itemAnimator = null
        search = findViewById(R.id.gSearch)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable) {
                ui.removeCallbacks(searchRun)
                ui.postDelayed(searchRun, 150)
            }
        })
        search.setOnEditorActionListener { _, a, _ ->
            if (a == EditorInfo.IME_ACTION_SEARCH || a == EditorInfo.IME_ACTION_DONE) { hideKeyboard(); rows.requestFocus(); true } else false
        }

        // Start on a small buffer: live zapping wants the first frame fast, not a deep buffer.
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(10_000, 30_000, 500, 1_500).build()
        player = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setLoadControl(load)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(Net.http).setUserAgent(Net.UA)))
            .build()
        playerView.player = player
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition(); player.prepare(); return
                }
                val c = cur ?: return
                if (++urlIdx < c.urls.size) startUrl() else {
                    showBanner()
                    findViewById<TextView>(R.id.bDesc).text = "Channel unavailable right now."
                }
            }
        })

        // The cached lineup is several MB of JSON; decoding it on the main thread froze startup for seconds.
        setStatus("Loading channels…")
        scope.launch {
            val cached = withContext(Dispatchers.IO) { repo.cached() }
            cacheLoaded = true
            setStatus(null)
            cached?.let { apply(it) }
            maybeRefresh()
        }
        if (!repo.configured) openSettings()
        scope.launch {
            update = withContext(Dispatchers.IO) { Update.check() }
            maybeShowUpdate()
        }
        if (repo.prefs.getInt("tour_v", 0) < TOUR_VERSION) showTour(0)
    }

    // ---- update prompt: OK installs, BACK dismisses ----

    private var update: Update.Release? = null
    private var installing = false
    private val updateCard get() = findViewById<View>(R.id.updateCard)

    private fun maybeShowUpdate() {
        val r = update ?: return
        if (tour.visibility == View.VISIBLE || installing) return
        findViewById<TextView>(R.id.upTitle).text = "TVMate ${r.version} is ready"
        findViewById<TextView>(R.id.upSub).text = "Press OK to install  ·  BACK to dismiss"
        findViewById<View>(R.id.upProg).visibility = View.GONE
        updateCard.visibility = View.VISIBLE
        ui.removeCallbacks(hideUpdate)
        ui.postDelayed(hideUpdate, 30_000)
    }

    private val hideUpdate = Runnable { if (!installing) updateCard.visibility = View.GONE }

    private fun installUpdate() {
        val r = update ?: return
        installing = true
        ui.removeCallbacks(hideUpdate)
        val sub = findViewById<TextView>(R.id.upSub)
        val bar = findViewById<ProgressBar>(R.id.upProg)
        sub.text = "Downloading…"
        bar.visibility = View.VISIBLE
        bar.progress = 0
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching { Update.install(this@MainActivity, r) { p -> runOnUiThread { bar.progress = p; sub.text = "Downloading…  $p%" } } }.exceptionOrNull()
            }
            installing = false
            if (err == null) updateCard.visibility = View.GONE
            else { sub.text = "Download failed. Press OK to retry  ·  BACK to dismiss"; bar.visibility = View.GONE }
        }
    }

    // ---- guided tour, shown once per TOUR_VERSION (so existing installs see it after updating) ----

    private class Step(val title: String, val body: String, val keys: Set<String> = emptySet(), val keycap: String? = null)

    private val steps = listOf(
        Step("Welcome to TVMate", "Live TV without the clutter. Here's how to use it with your remote."),
        Step("Change channel", "Press UP for the next channel and DOWN for the previous one.", setOf("up", "down")),
        Step("Open the guide", "Press LEFT or OK to browse categories, see what's on now and next, and search.", setOf("left", "ok")),
        Step("What's playing", "Press RIGHT to see the current programme, its time and description.", setOf("right")),
        Step("Settings", "Press MENU to add Xtream providers, switch sources or turn on the phone remote.", keycap = "≡  MENU"),
        Step("Going back", "BACK closes the guide. Press BACK twice to leave TVMate.", keycap = "BACK"),
    )
    private var step = 0
    private val tour get() = findViewById<View>(R.id.tour)

    private fun showTour(i: Int) {
        step = i.coerceIn(0, steps.size - 1)
        val s = steps[step]
        updateCard.visibility = View.GONE
        tour.visibility = View.VISIBLE
        findViewById<TextView>(R.id.tourStep).text = "Step ${step + 1} of ${steps.size}"
        findViewById<TextView>(R.id.tourTitle).text = s.title
        findViewById<TextView>(R.id.tourBody).text = s.body
        findViewById<View>(R.id.tourLogo).visibility = if (step == 0) View.VISIBLE else View.GONE
        findViewById<View>(R.id.dpad).visibility = if (step > 0 && s.keycap == null) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.keycap).apply { visibility = if (s.keycap != null) View.VISIBLE else View.GONE; text = s.keycap }
        mapOf(R.id.dUp to "up", R.id.dDown to "down", R.id.dLeft to "left", R.id.dRight to "right").forEach { (id, k) ->
            findViewById<ImageView>(id).apply {
                val on = k in s.keys
                alpha = if (on) 1f else 0.25f
                setColorFilter(if (on) 0xFF1E88FF.toInt() else 0xFFFFFFFF.toInt())
                scaleX = if (on) 1.3f else 1f
                scaleY = scaleX
            }
        }
        findViewById<View>(R.id.dOk).alpha = if ("ok" in s.keys) 1f else 0.25f
        findViewById<LinearLayout>(R.id.tourDots).apply {
            removeAllViews()
            steps.indices.forEach { d ->
                addView(View(context).apply {
                    setBackgroundResource(R.drawable.dot)
                    alpha = if (d == step) 1f else 0.25f
                    layoutParams = LinearLayout.LayoutParams(dp(if (d == step) 22 else 8), dp(8)).apply { marginStart = dp(4); marginEnd = dp(4) }
                })
            }
        }
        findViewById<TextView>(R.id.tourHint).text =
            if (step == steps.size - 1) "OK  Start watching" else "OK  Next     ◀  Back     BACK  Skip"
    }

    private fun endTour() {
        tour.visibility = View.GONE
        repo.prefs.edit().putInt("tour_v", TOUR_VERSION).apply()
        maybeShowUpdate()
    }

    private fun tourKey(code: Int) {
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_RIGHT ->
                if (step == steps.size - 1) endTour() else showTour(step + 1)
            KeyEvent.KEYCODE_DPAD_LEFT -> showTour(step - 1)
        }
    }

    override fun onResume() {
        super.onResume()
        maybeRefresh()
        player.play()
    }

    private fun maybeRefresh() {
        if (!cacheLoaded) return
        val l = lineup
        val dirty = repo.prefs.getBoolean("dirty", false)
        val hours = repo.prefs.getInt("refresh_h", 6)
        val stale = l != null && hours > 0 && System.currentTimeMillis() - l.at > hours * 3600_000L
        if (repo.configured && (l == null || dirty || stale)) refresh()
    }

    override fun onStart() {
        super.onStart()
        Remote.host = this
        Remote.acquire(this)
    }

    override fun onStop() {
        Remote.release()
        super.onStop()
        player.pause()
    }

    override fun onDestroy() {
        if (Remote.host === this) Remote.host = null
        scope.cancel()
        player.release()
        super.onDestroy()
    }

    private fun refresh() {
        if (refreshing) return
        refreshing = true
        repo.prefs.edit().putBoolean("dirty", false).apply()
        if (lineup == null) setStatus("Loading channels…")
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { repo.refresh { msg -> runOnUiThread { if (lineup == null) setStatus(msg) } } }
            }
            refreshing = false
            r.onSuccess { setStatus(null); apply(it) }
                .onFailure { if (lineup == null) setStatus("Couldn't load channels: ${it.message}") else toast("Refresh failed: ${it.message}") }
        }
    }

    private fun apply(loaded: Lineup) {
        base = loaded
        val pinned = Repo.loadPins(repo.prefs).map { Cat(it.name, it.keys, Repo.PINNED) }
        val l = loaded.copy(cats = pinned.filter { p -> p.keys.isNotEmpty() } + loaded.cats)
        lineup = l
        numbers = l.channels.withIndex().associate { it.value.key to it.index + 1 }
        byKey = l.channels.associateBy { it.key }
        scope.launch {
            val idx = withContext(Dispatchers.Default) { l.channels.map { Clean.searchable(it.name, it) } }
            if (lineup === l) searchIndex = idx
        }
        providerNames = repo.providers.map { it.name }
        catIdx = catIdx.coerceAtMost((l.cats.size - 1).coerceAtLeast(0))
        railAdapter.rebuild()
        if (l.channels.isEmpty()) {
            setStatus("No channels. Open Settings to add an Xtream account or enable Xiaomi TV+.")
            return
        }
        val playing = cur?.let { byKey[it.key] }
        zapList = zapList.mapNotNull { byKey[it.key] }.ifEmpty { l.channels }
        if (playing != null) {
            cur = playing
        } else {
            play(byKey[repo.prefs.getString("last", null)] ?: l.channels[0], zapList)
            hint.visibility = View.VISIBLE
            ui.postDelayed({ hint.visibility = View.GONE }, 6000)
        }
        if (guide.visibility == View.VISIBLE && !searching) showCat(catIdx, focus = false)
    }

    private fun play(c: Ch, list: List<Ch>) {
        cur = c
        zapList = list
        urlIdx = 0
        startUrl()
        repo.prefs.edit().putString("last", c.key).apply()
        showBanner()
        warmNeighbours()
    }

    private fun startUrl() {
        val c = cur ?: return
        val raw = c.urls[urlIdx]
        val item = MediaItem.Builder().setUri(if (c.mi != null) MiTv.fill(raw) else raw)
        if (c.mi != null) item.setMimeType(MimeTypes.APPLICATION_M3U8)
        // A reused decoder kept the previous channel's buffer size: a 720p channel after a 1080p one
        // drew in the corner over the old frame. stop() releases it so each channel starts clean.
        player.stop()
        player.setMediaItem(item.build())
        player.prepare()
        player.play()
    }

    // Pre-open TLS connections to the next/previous Mi channel so a zap skips the handshake.
    // Xtream is skipped: a speculative request would eat one of the account's few connections.
    private fun warmNeighbours() {
        val c = cur ?: return
        val i = zapList.indexOf(c).takeIf { it >= 0 } ?: return
        val n = listOf(zapList[(i + 1) % zapList.size], zapList[(i - 1 + zapList.size) % zapList.size]).filter { it.mi != null }
        scope.launch(Dispatchers.IO) {
            n.forEach { runCatching { Net.head(Net.get(Net.http, MiTv.fill(it.urls[0]))) } }
        }
    }

    override fun zap(d: Int) {
        val list = zapList.ifEmpty { lineup?.channels.orEmpty() }
        if (list.isEmpty()) return
        val i = list.indexOf(cur).coerceAtLeast(0)
        play(list[(i + d + list.size) % list.size], list)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        // Android acts on BACK's key-up; handling only key-down let the release finish the activity.
        if (e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP) back()
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)
        if (tour.visibility == View.VISIBLE) { tourKey(e.keyCode); return true }
        if (e.keyCode == KeyEvent.KEYCODE_MENU || e.keyCode == KeyEvent.KEYCODE_SETTINGS) { openSettings(); return true }
        if (updateCard.visibility == View.VISIBLE && guide.visibility != View.VISIBLE && !installing &&
            (e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER)) { installUpdate(); return true }
        if (guide.visibility == View.VISIBLE) {
            if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && rows.hasFocus()) { focusRail(); return true }
            if (e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                rail.focusedChild?.let { rail.getChildAdapterPosition(it) } == railAdapter.itemCount - 1) return true
            return super.dispatchKeyEvent(e)
        }
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> zap(+1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> zap(-1)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_GUIDE -> openGuide()
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> showBanner()
            else -> return super.dispatchKeyEvent(e)
        }
        return true
    }

    private fun back() = when {
        tour.visibility == View.VISIBLE -> endTour()
        updateCard.visibility == View.VISIBLE && !installing -> updateCard.visibility = View.GONE
        guide.visibility == View.VISIBLE -> closeGuide()
        banner.visibility == View.VISIBLE -> banner.visibility = View.GONE
        System.currentTimeMillis() - lastBack < 2000 -> finish()
        else -> { lastBack = System.currentTimeMillis(); toast("Press BACK again to exit") }
    }

    private val hideBanner = Runnable { banner.visibility = View.GONE }

    private fun showBanner() {
        val c = cur ?: return
        banner.visibility = View.VISIBLE
        ui.removeCallbacks(hideBanner)
        ui.postDelayed(hideBanner, 4500)
        logo(findViewById(R.id.bLogo), findViewById(R.id.bLogoText), c)
        findViewById<TextView>(R.id.bNum).text = numbers[c.key]?.toString().orEmpty()
        findViewById<TextView>(R.id.bName).text = c.name
        val epg = repo.cachedEpg(c)
        val p = epg?.let { now(it) }
        findViewById<TextView>(R.id.bTitle).text = p?.title ?: c.name
        findViewById<TextView>(R.id.bTime).text = p?.let { span(it) }.orEmpty()
        findViewById<ProgressBar>(R.id.bProg).apply {
            visibility = if (p != null) View.VISIBLE else View.GONE
            progress = p?.let { pct(it) } ?: 0
        }
        findViewById<TextView>(R.id.bDesc).text = p?.desc?.ifBlank { null } ?: c.desc
        if (epg == null) scope.launch {
            withContext(Dispatchers.IO) { repo.loadEpg(listOf(c)) }
            if (cur == c && banner.visibility == View.VISIBLE) showBanner()
        }
    }

    private fun openGuide() {
        val l = lineup ?: return
        if (l.cats.isEmpty()) return
        banner.visibility = View.GONE
        hint.visibility = View.GONE
        // Scale instead of resizing: a resized video surface kept its old buffer size and drew off-fit.
        playerView.pivotX = playerView.width.toFloat()
        playerView.pivotY = 0f
        val k = dp(330).toFloat() / playerView.width
        playerView.scaleX = k
        playerView.scaleY = k
        guide.visibility = View.VISIBLE
        val c = cur
        catIdx = l.cats.indexOfFirst { cat -> c != null && cat.keys.contains(c.key) && zapList.size == cat.keys.size }
            .takeIf { it >= 0 } ?: l.cats.indexOfFirst { c != null && it.keys.contains(c.key) }.coerceAtLeast(0)
        showCat(catIdx, focus = true)
    }

    private fun closeGuide() {
        guide.visibility = View.GONE
        playerView.scaleX = 1f
        playerView.scaleY = 1f
    }

    private fun catChannels(i: Int): List<Ch> =
        lineup?.cats?.getOrNull(i)?.keys?.mapNotNull { byKey[it] }.orEmpty()

    private fun showCat(i: Int, focus: Boolean) {
        val l = lineup ?: return
        catIdx = i
        setSearching(false)
        rail.post { railAdapter.notifyItemRangeChanged(0, railAdapter.itemCount) }
        val cat = l.cats.getOrNull(i)
        findViewById<TextView>(R.id.gCat).text = cat?.let { if (it.group.isEmpty()) it.name else "${it.group} · ${it.name}" }.orEmpty()
        rowsAdapter.items = catChannels(i)
        rowsAdapter.notifyDataSetChanged()
        val pos = rowsAdapter.items.indexOf(cur).coerceAtLeast(0)
        rows.scrollToPosition(pos)
        rowsAdapter.items.getOrNull(pos)?.let { info(it) }
        if (focus) rows.post { rows.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() ?: rows.requestFocus() }
    }

    private fun info(c: Ch) {
        val p = repo.cachedEpg(c)?.let { now(it) }
        logo(findViewById(R.id.gLogo), findViewById(R.id.gLogoText), c)
        findViewById<TextView>(R.id.gTitle).text = p?.title ?: c.name
        findViewById<TextView>(R.id.gTime).text = p?.let { span(it) } ?: numbers[c.key]?.let { "CH $it" }.orEmpty()
        findViewById<TextView>(R.id.gDesc).text = p?.desc?.ifBlank { null } ?: c.desc
    }

    // EPG for rows is fetched in small debounced batches as rows scroll into view.
    private var railFocus = Runnable {}
    private val pendingEpg = LinkedHashSet<Ch>()
    private val epgRun = Runnable {
        val batch = pendingEpg.toList()
        pendingEpg.clear()
        scope.launch {
            withContext(Dispatchers.IO) { repo.loadEpg(batch) }
            rowsAdapter.notifyItemRangeChanged(0, rowsAdapter.itemCount, "epg")
            (rows.focusedChild?.let { rows.getChildAdapterPosition(it) })?.let { rowsAdapter.items.getOrNull(it) }?.let { info(it) }
        }
    }

    private fun requestEpg(c: Ch) {
        pendingEpg += c
        ui.removeCallbacks(epgRun)
        ui.postDelayed(epgRun, 250)
    }

    private fun focusRail() {
        val pos = railAdapter.positionOf(if (searching) -1 else catIdx)
        rail.scrollToPosition(pos)
        rail.post { (rail.findViewHolderForAdapterPosition(pos)?.itemView ?: rail).requestFocus() }
    }

    private fun setSearching(on: Boolean) {
        searching = on
        findViewById<View>(R.id.searchBar).visibility = if (on) View.VISIBLE else View.GONE
        findViewById<View>(R.id.gCat).visibility = if (on) View.GONE else View.VISIBLE
        if (!on) findViewById<View>(R.id.gEmpty).visibility = View.GONE
    }

    private fun showSearch() {
        if (!searching) {
            setSearching(true)
            rail.post { railAdapter.notifyItemRangeChanged(0, railAdapter.itemCount) }
            runSearch(search.text.toString())
        }
    }

    private val searchRun = Runnable { runSearch(search.text.toString()) }

    // ponytail: linear scan over pre-normalized names; ~25k channels scan in a few ms, add a trigram index if lineups grow 10x.
    private fun runSearch(q: String) {
        val res = Clean.search(q, searchIndex)
        rowsAdapter.items = res
        rowsAdapter.notifyDataSetChanged()
        rows.scrollToPosition(0)
        res.firstOrNull()?.let { info(it) }
        val blank = q.isBlank()
        findViewById<TextView>(R.id.gSearchCount).text = when {
            blank -> ""
            res.size >= 300 -> "300+ channels"
            else -> "${res.size} channel${if (res.size == 1) "" else "s"}"
        }
        findViewById<TextView>(R.id.gEmpty).apply {
            text = if (blank) "Press OK to type a channel name. Words can be in any order." else "No channels match “${q.trim()}”."
            visibility = if (res.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun source(c: Ch) = Repo.source(c, providerNames)

    override fun play(key: String) {
        val c = byKey[key] ?: return
        closeGuide()
        play(c, lineup?.cats?.firstOrNull { key in it.keys }?.keys?.mapNotNull { byKey[it] } ?: lineup?.channels.orEmpty())
    }

    override fun refreshNow() {
        repo.prefs.edit().putBoolean("dirty", true).apply()
        maybeRefresh()
    }

    override fun pinsChanged() {
        base?.let { apply(it) }
    }

    private fun hideKeyboard() =
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun setStatus(s: String?) {
        status.text = s
        status.visibility = if (s == null) View.GONE else View.VISIBLE
    }

    /** Channel name tile stays visible until (unless) the logo actually loads. */
    private fun logo(iv: ImageView, tv: TextView, c: Ch) {
        tv.text = c.name
        tv.visibility = View.VISIBLE
        iv.load(c.logo) { listener(onSuccess = { _, _ -> tv.visibility = View.GONE }) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
    private fun nowSec() = System.currentTimeMillis() / 1000
    private fun now(ps: List<Prog>) = ps.firstOrNull { nowSec() in it.start until it.end }
    private fun span(p: Prog) = hm.format(Date(p.start * 1000)) + "-" + hm.format(Date(p.end * 1000))
    private fun pct(p: Prog) = ((nowSec() - p.start) * 1000 / (p.end - p.start).coerceAtLeast(1)).toInt()

    private sealed class Entry {
        object Search : Entry()
        class Header(val name: String) : Entry()
        class C(val idx: Int) : Entry()
        object Settings : Entry()
    }

    inner class RailAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var entries = listOf<Entry>(Entry.Search, Entry.Settings)

        fun rebuild() {
            val cats = lineup?.cats.orEmpty()
            val out = mutableListOf<Entry>(Entry.Search)
            var group: String? = null
            cats.forEachIndexed { i, c ->
                if (c.group != group) { group = c.group; out += Entry.Header(c.group) }
                out += Entry.C(i)
            }
            out += Entry.Settings
            entries = out
            notifyDataSetChanged()
        }

        /** Rail position of category [idx], or of Search when idx is -1. */
        fun positionOf(idx: Int) = entries.indexOfFirst { if (idx < 0) it is Entry.Search else it is Entry.C && it.idx == idx }.coerceAtLeast(0)

        override fun getItemCount() = entries.size
        override fun getItemViewType(pos: Int) = if (entries[pos] is Entry.Header) 1 else 0

        override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
            val v = if (t == 1) TextView(p.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40))
                gravity = Gravity.BOTTOM
                setPadding(dp(20), 0, 0, dp(6))
                textSize = 12f
                setTextColor(0x80FFFFFF.toInt())
                isAllCaps = true
                letterSpacing = 0.08f
            } else LayoutInflater.from(p.context).inflate(R.layout.item_cat, p, false)
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            val tv = h.itemView as TextView
            val e = entries[pos]
            val cats = lineup?.cats.orEmpty()
            tv.text = when (e) {
                is Entry.Search -> "⌕  Search"
                is Entry.Header -> e.name
                is Entry.C -> cats[e.idx].name
                is Entry.Settings -> "⚙  Settings"
            }
            if (e is Entry.Header) return
            val sel = (e is Entry.C && e.idx == catIdx && !searching) || (e is Entry.Search && searching)
            tv.setTextColor(if (sel) 0xFFFFFFFF.toInt() else 0x99FFFFFF.toInt())
            tv.paint.isFakeBoldText = sel
            tv.invalidate()
            // Deferred: focus can land here mid-layout of either list, and scrolling the rail shouldn't load every category.
            tv.setOnFocusChangeListener { _, f ->
                ui.removeCallbacks(railFocus)
                if (!f) return@setOnFocusChangeListener
                railFocus = Runnable {
                    when (e) {
                        is Entry.C -> if (e.idx != catIdx || searching) showCat(e.idx, focus = false)
                        is Entry.Search -> showSearch()
                        else -> {}
                    }
                }
                ui.postDelayed(railFocus, 120)
            }
            tv.setOnClickListener {
                when (e) {
                    is Entry.Settings -> openSettings()
                    is Entry.Search -> {
                        search.requestFocus()
                        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(search, 0)
                    }
                    else -> rows.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: rows.requestFocus()
                }
            }
        }
    }

    inner class RowsAdapter : RecyclerView.Adapter<RowsAdapter.H>() {
        var items = emptyList<Ch>()
        override fun getItemCount() = items.size

        inner class H(v: View) : RecyclerView.ViewHolder(v) {
            val logo: ImageView = v.findViewById(R.id.logo)
            val logoText: TextView = v.findViewById(R.id.logoText)
            val nowTitle: TextView = v.findViewById(R.id.nowTitle)
            val nowLeft: TextView = v.findViewById(R.id.nowLeft)
            val nowProg: ProgressBar = v.findViewById(R.id.nowProg)
            val next = listOf(
                Triple(v.findViewById<View>(R.id.next1), v.findViewById<TextView>(R.id.next1Title), v.findViewById<TextView>(R.id.next1Time)),
                Triple(v.findViewById<View>(R.id.next2), v.findViewById<TextView>(R.id.next2Title), v.findViewById<TextView>(R.id.next2Time)),
            )
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) = H(LayoutInflater.from(p.context).inflate(R.layout.row_channel, p, false))

        override fun onBindViewHolder(h: H, pos: Int, payloads: MutableList<Any>) {
            if (payloads.isEmpty()) {
                val c = items[pos]
                logo(h.logo, h.logoText, c)
                h.itemView.setOnClickListener {
                    if (c != cur) play(c, items)
                    closeGuide()
                }
                h.itemView.setOnFocusChangeListener { _, f -> if (f) info(c) }
            }
            bindEpg(h, items[pos])
        }

        override fun onBindViewHolder(h: H, pos: Int) = onBindViewHolder(h, pos, mutableListOf())

        private fun bindEpg(h: H, c: Ch) {
            val ps = repo.cachedEpg(c)
            if (ps == null) requestEpg(c)
            val t = nowSec()
            val p = ps?.let { now(it) }
            // In search the channel itself is the answer: lead with its name and source, programmes move right.
            h.nowTitle.text = if (searching) c.name else p?.title ?: c.name
            h.nowLeft.text = when {
                searching -> source(c)
                p != null -> "${((p.end - t) / 60).coerceAtLeast(1)}m left"
                else -> numbers[c.key]?.let { "CH $it" }.orEmpty()
            }
            h.nowProg.visibility = if (p != null && !searching) View.VISIBLE else View.GONE
            h.nowProg.progress = p?.let { pct(it) } ?: 0
            val upcoming = if (searching) listOfNotNull(p) + ps?.filter { it.start >= (p?.end ?: t) }.orEmpty()
                else ps?.filter { it.start >= (p?.end ?: t) }.orEmpty()
            h.next.forEachIndexed { i, (box, title, time) ->
                val n = upcoming.getOrNull(i)
                box.visibility = if (n != null) View.VISIBLE else View.INVISIBLE
                title.text = n?.title.orEmpty()
                time.text = n?.let { span(it) }.orEmpty()
            }
        }
    }
}
