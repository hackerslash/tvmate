package app.tvmate

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.ImageView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

class SettingsActivity : Activity() {
    private lateinit var list: LinearLayout
    private var saving = false

    // The phone portal writes the same prefs; reload so this screen never shows (or re-saves) stale values.
    private val remoteEdits = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (!saving && !isFinishing && key in setOf("providers", "mi_on", "extras", "refresh_h")) recreate()
    }

    override fun onStart() {
        super.onStart()
        Remote.acquire(this)
        getSharedPreferences("s", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(remoteEdits)
        val toggle = findViewById<Switch>(R.id.remote)
        toggle.isChecked = Remote.enabled
        showRemote()
        findViewById<View>(R.id.remoteRow).setOnClickListener {
            toggle.toggle()
            Remote.setEnabled(toggle.isChecked)
            showRemote()
        }
    }

    private fun showRemote() {
        findViewById<View>(R.id.remoteCard).visibility = if (Remote.enabled) View.VISIBLE else View.GONE
        if (!Remote.enabled) return
        val url = Remote.url()
        findViewById<TextView>(R.id.remoteUrl).text = url ?: "Connect the TV to Wi-Fi or Ethernet to use the phone remote."
        findViewById<ImageView>(R.id.qr).apply {
            visibility = if (url != null) View.VISIBLE else View.GONE
            url?.let { setImageBitmap(qr(it)) }
        }
    }

    override fun onStop() {
        getSharedPreferences("s", Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(remoteEdits)
        Remote.release()
        super.onStop()
    }

    private fun qr(text: String): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0)
        val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.RGB_565)
        for (x in 0 until m.width) for (y in 0 until m.height) bmp.setPixel(x, y, if (m[x, y]) Color.BLACK else Color.WHITE)
        return Bitmap.createScaledBitmap(bmp, m.width * 8, m.height * 8, false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val p = getSharedPreferences("s", Context.MODE_PRIVATE)
        val mi = findViewById<Switch>(R.id.mi)
        val extras = findViewById<Switch>(R.id.extras)
        list = findViewById(R.id.providers)
        mi.isChecked = p.getBoolean("mi_on", true)
        extras.isChecked = p.getBoolean("extras", false)
        findViewById<View>(R.id.miRow).setOnClickListener { mi.toggle() }
        findViewById<View>(R.id.extrasRow).setOnClickListener { extras.toggle() }
        findViewById<TextView>(R.id.version).text = "Version ${BuildConfig.VERSION_NAME}"
        val refreshLabel = findViewById<TextView>(R.id.refresh)
        var hours = p.getInt("refresh_h", 6)
        fun label() { refreshLabel.text = REFRESH.firstOrNull { it.first == hours }?.second ?: "Every $hours hours" }
        label()
        findViewById<View>(R.id.refreshRow).setOnClickListener {
            hours = REFRESH[(REFRESH.indexOfFirst { it.first == hours } + 1) % REFRESH.size].first
            label()
        }
        Repo.loadProviders(p).forEach(::addRow)
        syncEmpty()
        findViewById<View>(R.id.remoteRow).requestFocus()

        Update.available?.let { r ->
            findViewById<TextView>(R.id.updateStatus).text = "Version ${r.version} is available."
            findViewById<Button>(R.id.update).apply {
                visibility = View.VISIBLE
                text = "Install update"
                requestFocus()
                setOnClickListener {
                    text = "Downloading…"
                    Thread {
                        val err = runCatching { Update.install(this@SettingsActivity, r) }.exceptionOrNull()
                        runOnUiThread { text = if (err == null) "Install update" else "Retry update" }
                        err?.let { runOnUiThread { Toast.makeText(this@SettingsActivity, "Update failed: ${it.message}", Toast.LENGTH_LONG).show() } }
                    }.start()
                }
            }
        }

        findViewById<View>(R.id.add).setOnClickListener { addRow(null).findViewById<View>(R.id.name).requestFocus() }
        findViewById<View>(R.id.save).setOnClickListener {
            val provs = (0 until list.childCount).map { list.getChildAt(it) }.map { v ->
                fun t(id: Int) = v.findViewById<EditText>(id).text.toString().trim()
                Provider(t(R.id.name).ifEmpty { "Provider" }, t(R.id.server), t(R.id.user), v.findViewById<EditText>(R.id.pass).text.toString())
            }.filter { it.server.isNotEmpty() && it.user.isNotEmpty() }
            saving = true
            val e = p.edit().putBoolean("mi_on", mi.isChecked).putBoolean("extras", extras.isChecked)
                .putInt("refresh_h", hours).putBoolean("dirty", true)
            Repo.saveProviders(e, provs).apply()
            Toast.makeText(this, "Saved. Reloading channels…", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private companion object {
        val REFRESH = listOf(1 to "Every hour", 6 to "Every 6 hours", 12 to "Every 12 hours", 24 to "Daily", 0 to "Only when saved here")
    }

    private fun syncEmpty() {
        findViewById<View>(R.id.empty).visibility = if (list.childCount == 0) View.VISIBLE else View.GONE
    }

    /** Saved providers start collapsed to one summary row; OK on it expands the fields. */
    private fun addRow(prov: Provider?): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_provider, list, false)
        val title = v.findViewById<TextView>(R.id.title)
        val summary = v.findViewById<TextView>(R.id.summary)
        val chevron = v.findViewById<TextView>(R.id.chevron)
        val fields = v.findViewById<View>(R.id.fields)
        val name = v.findViewById<EditText>(R.id.name)
        val server = v.findViewById<EditText>(R.id.server)
        val user = v.findViewById<EditText>(R.id.user)
        fun sync() {
            title.text = name.text.toString().ifBlank { "New provider" }
            summary.text = listOf(server.text.toString().substringAfter("://"), user.text.toString())
                .filter { it.isNotBlank() }.joinToString("  ·  ").ifEmpty { "Enter server and login" }
            chevron.text = if (fields.visibility == View.VISIBLE) "Hide" else "Edit"
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable) = sync()
        }
        prov?.let {
            name.setText(it.name)
            server.setText(it.server)
            user.setText(it.user)
            v.findViewById<EditText>(R.id.pass).setText(it.pass)
            fields.visibility = View.GONE
        }
        listOf(name, server, user).forEach { it.addTextChangedListener(watcher) }
        v.findViewById<View>(R.id.head).setOnClickListener {
            fields.visibility = if (fields.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            sync()
            if (fields.visibility == View.VISIBLE) name.requestFocus()
        }
        v.findViewById<View>(R.id.remove).setOnClickListener {
            list.removeView(v)
            syncEmpty()
            findViewById<View>(R.id.add).requestFocus()
        }
        sync()
        list.addView(v)
        syncEmpty()
        return v
    }
}
