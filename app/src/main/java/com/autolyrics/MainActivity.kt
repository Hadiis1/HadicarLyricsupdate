package com.autolyrics

import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.autolyrics.media.MediaListenerService
import com.autolyrics.media.MediaTracker
import com.autolyrics.model.AlbumColors
import com.autolyrics.model.LyricsState
import com.autolyrics.model.LyricsStatus
import com.autolyrics.ui.LyricsView
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var mediaTracker: MediaTracker
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var rootLayout: LinearLayout
    private lateinit var appBar: LinearLayout
    private lateinit var tvAppTitle: TextView
    private lateinit var tvAppSubtitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnPermission: Button
    private lateinit var ivAlbumArt: ImageView
    private lateinit var tvTrack: TextView
    private lateinit var tvSource: TextView
    private lateinit var lyricsView: LyricsView
    private lateinit var delayBar: LinearLayout
    private lateinit var tvDelay: TextView
    private lateinit var divider: View
    private lateinit var fontSettingsPanel: LinearLayout
    private lateinit var tvFontSize: TextView
    private lateinit var switchAaKaraoke: SwitchCompat
    private lateinit var switchTranslation: SwitchCompat
    private lateinit var tvAaDelay: TextView
    private lateinit var btnJumpToCurrent: Button
    private lateinit var prefs: SharedPreferences
    private var currentColors: AlbumColors? = null
    private var lyricsFontSizeSp = 16
    private var lyricsFontFamily = "sans-serif"
    private var aaOffsetMs = 0L

    private var lyricsIdentity = 0
    private var lastLyricsKey: String? = null

    private lateinit var btnTapSync: Button
    private lateinit var tvSyncStatus: TextView
    private var tapSyncActive = false
    private var tapSyncTargetLineIndex = -1
    private var tapSyncOffsets = mutableListOf<Long>()

    private val fontButtons = mutableMapOf<String, Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mediaTracker = MediaTracker.getInstance(this)
        prefs = getSharedPreferences("auto_lyrics_prefs", MODE_PRIVATE)

        rootLayout = findViewById(R.id.root_layout)
        appBar = findViewById(R.id.layout_app_bar)
        tvAppTitle = findViewById(R.id.tv_app_title)
        tvAppSubtitle = findViewById(R.id.tv_app_subtitle)
        tvStatus = findViewById(R.id.tv_status)
        btnPermission = findViewById(R.id.btn_permission)
        ivAlbumArt = findViewById(R.id.iv_album_art)
        tvTrack = findViewById(R.id.tv_track)
        tvSource = findViewById(R.id.tv_source)
        lyricsView = findViewById(R.id.lyrics_view)
        delayBar = findViewById(R.id.layout_delay)
        tvDelay = findViewById(R.id.tv_delay)
        divider = findViewById(R.id.divider)

        ivAlbumArt.clipToOutline = true
        btnJumpToCurrent = findViewById(R.id.btn_jump_to_current)

        setupLyricsView()

        btnPermission.setOnClickListener {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }

        findViewById<Button>(R.id.btn_delay_minus).setOnClickListener {
            mediaTracker.adjustOffset(-100)
        }
        findViewById<Button>(R.id.btn_delay_plus).setOnClickListener {
            mediaTracker.adjustOffset(100)
        }
        findViewById<Button>(R.id.btn_delay_reset).setOnClickListener {
            mediaTracker.resetOffset()
        }

        fontSettingsPanel = findViewById(R.id.layout_font_settings)
        tvFontSize = findViewById(R.id.tv_font_size)

        lyricsFontSizeSp = prefs.getInt("lyrics_font_size", 16)
        lyricsFontFamily = prefs.getString("lyrics_font_family", "sans-serif") ?: "sans-serif"

        applyFontSettings()

        findViewById<ImageButton>(R.id.btn_settings_toggle).setOnClickListener {
            fontSettingsPanel.visibility = if (fontSettingsPanel.visibility == View.VISIBLE)
                View.GONE else View.VISIBLE
        }

        findViewById<Button>(R.id.btn_performance_mode).setOnClickListener {
            startActivity(Intent(this, PerformanceActivity::class.java))
        }

        findViewById<Button>(R.id.btn_font_size_minus).setOnClickListener {
            if (lyricsFontSizeSp > 12) {
                lyricsFontSizeSp -= 2
                saveFontSettings()
                applyFontSettings()
            }
        }
        findViewById<Button>(R.id.btn_font_size_plus).setOnClickListener {
            if (lyricsFontSizeSp < 28) {
                lyricsFontSizeSp += 2
                saveFontSettings()
                applyFontSettings()
            }
        }

        val btnSans = findViewById<Button>(R.id.btn_font_sans)
        val btnSerif = findViewById<Button>(R.id.btn_font_serif)
        val btnMono = findViewById<Button>(R.id.btn_font_mono)
        val btnCursive = findViewById<Button>(R.id.btn_font_cursive)

        fontButtons["sans-serif"] = btnSans
        fontButtons["serif"] = btnSerif
        fontButtons["monospace"] = btnMono
        fontButtons["cursive"] = btnCursive

        btnSans.setOnClickListener { selectFont("sans-serif") }
        btnSerif.setOnClickListener { selectFont("serif") }
        btnMono.setOnClickListener { selectFont("monospace") }
        btnCursive.setOnClickListener { selectFont("cursive") }

        updateFontButtonHighlights()

        switchAaKaraoke = findViewById(R.id.switch_aa_karaoke)
        tvAaDelay = findViewById(R.id.tv_aa_delay)

        switchAaKaraoke.isChecked = prefs.getBoolean("aa_karaoke_enabled", true)
        aaOffsetMs = prefs.getLong("aa_offset_ms", 0L)
        updateAaDelayDisplay()

        switchAaKaraoke.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("aa_karaoke_enabled", isChecked).apply()
        }

        switchTranslation = findViewById(R.id.switch_translation)
        switchTranslation.isChecked = prefs.getBoolean("translation_enabled", true)
        switchTranslation.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("translation_enabled", isChecked).apply()
        }

        findViewById<Button>(R.id.btn_aa_delay_minus).setOnClickListener {
            aaOffsetMs -= 100
            prefs.edit().putLong("aa_offset_ms", aaOffsetMs).apply()
            updateAaDelayDisplay()
        }
        findViewById<Button>(R.id.btn_aa_delay_plus).setOnClickListener {
            aaOffsetMs += 100
            prefs.edit().putLong("aa_offset_ms", aaOffsetMs).apply()
            updateAaDelayDisplay()
        }
        findViewById<Button>(R.id.btn_aa_delay_reset).setOnClickListener {
            aaOffsetMs = 0L
            prefs.edit().putLong("aa_offset_ms", 0L).apply()
            updateAaDelayDisplay()
        }

        btnTapSync = findViewById(R.id.btn_tap_sync)
        tvSyncStatus = findViewById(R.id.tv_sync_status)

        btnTapSync.setOnClickListener { onTapSyncPressed() }

        findViewById<Button>(R.id.btn_quick_minus_1s).setOnClickListener {
            mediaTracker.adjustOffset(-1000)
            showSyncStatus("Offset: ${formatOffset(mediaTracker.state.value.offsetMs)}")
        }
        findViewById<Button>(R.id.btn_quick_minus_half).setOnClickListener {
            mediaTracker.adjustOffset(-500)
            showSyncStatus("Offset: ${formatOffset(mediaTracker.state.value.offsetMs)}")
        }
        findViewById<Button>(R.id.btn_quick_plus_half).setOnClickListener {
            mediaTracker.adjustOffset(500)
            showSyncStatus("Offset: ${formatOffset(mediaTracker.state.value.offsetMs)}")
        }
        findViewById<Button>(R.id.btn_quick_plus_1s).setOnClickListener {
            mediaTracker.adjustOffset(1000)
            showSyncStatus("Offset: ${formatOffset(mediaTracker.state.value.offsetMs)}")
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mediaTracker.state.collect { state ->
                    updatePermissionUi()
                    updateDelayDisplay(state.offsetMs)
                    updateAlbumArt(state)
                    applyThemeColors(state.albumColors)

                    if (state.track != null) {
                        val artistText = if (state.track.artist.isNotBlank())
                            state.track.artist else "Unknown Artist"
                        tvTrack.text = "${state.track.title}\n$artistText"
                        tvTrack.visibility = View.VISIBLE
                    } else {
                        tvTrack.text = ""
                        tvTrack.visibility = View.GONE
                    }

                    if (state.source.isNotBlank()) {
                        val srcText = if (state.detectedLanguage != null) {
                            "${state.source} · ${state.detectedLanguage}→en"
                        } else state.source
                        tvSource.text = srcText
                        tvSource.visibility = View.VISIBLE
                    } else {
                        tvSource.visibility = View.GONE
                    }

                    renderLyrics(state)
                }
            }
        }
    }

    private fun setupLyricsView() {
        lyricsView.apply {
            userScrollEnabled = true
            focalYFraction = 0.4f
            activeScale = 1.14f
            inactiveScale = 0.9f
            inactiveAlpha = 0.45f
            positionProvider = { safePosition() }
            onUserScrollChanged = { scrolling ->
                btnJumpToCurrent.visibility = if (scrolling) View.VISIBLE else View.GONE
            }
        }
        btnJumpToCurrent.setOnClickListener {
            btnJumpToCurrent.visibility = View.GONE
            lyricsView.returnToCurrent()
        }
    }

    private fun renderLyrics(state: LyricsState) {
        lyricsView.isPlaying = state.isPlaying
        when (state.status) {
            LyricsStatus.NO_MEDIA -> lyricsView.setMessage(
                "Play a song to see lyrics here.\n\nLyrics will also appear on Android Auto."
            )
            LyricsStatus.LOADING -> lyricsView.setMessage("Loading lyrics…")
            LyricsStatus.NOT_FOUND -> lyricsView.setMessage("No lyrics found for this track.")
            LyricsStatus.ERROR -> lyricsView.setMessage(
                "Error loading lyrics.\nCheck your internet connection."
            )
            LyricsStatus.FOUND, LyricsStatus.PLAIN_ONLY -> {
                val plain = state.status == LyricsStatus.PLAIN_ONLY
                bumpIdentityIfChanged(state)
                lyricsView.setLyrics(
                    newLines = state.lines,
                    newTranslations = state.translatedLines,
                    plain = plain,
                    durationMs = state.track?.durationMs ?: 0L,
                    linesId = lyricsIdentity
                )
                if (!plain && state.currentIndex >= 0) {
                    lyricsView.setActiveLine(state.currentIndex)
                }
            }
        }
    }

    private fun bumpIdentityIfChanged(state: LyricsState) {
        val key = "${state.track?.title}|${state.track?.artist}|${state.status}"
        if (key != lastLyricsKey) {
            lastLyricsKey = key
            lyricsIdentity++
        }
    }

    private fun safePosition(): Long = try {
        mediaTracker.getCurrentPositionMs()
    } catch (_: Exception) {
        0L
    }

    private fun updateAlbumArt(state: LyricsState) {
        val art = state.albumArt
        if (art != null) {
            ivAlbumArt.setImageBitmap(art)
            ivAlbumArt.visibility = View.VISIBLE
        } else {
            ivAlbumArt.setImageDrawable(null)
            ivAlbumArt.visibility = View.GONE
        }
    }

    private fun applyThemeColors(colors: AlbumColors?) {
        if (colors == currentColors) return
        currentColors = colors

        if (colors != null) {
            val gradient = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(colors.dominant, colors.dominantDark)
            )
            rootLayout.background = gradient

            appBar.setBackgroundColor(lighten(colors.dominant, 1.3f))
            delayBar.setBackgroundColor(lighten(colors.dominantDark, 1.2f))
            divider.setBackgroundColor(lighten(colors.dominant, 1.8f))

            tvAppTitle.setTextColor(colors.textPrimary)
            tvAppSubtitle.setTextColor(colors.textDim)
            tvTrack.setTextColor(colors.vibrant)
            lyricsView.setColors(colors.textPrimary, colors.textDim, colors.vibrant)
        } else {
            rootLayout.setBackgroundColor(DEFAULT_BG)
            appBar.setBackgroundColor(DEFAULT_APP_BAR)
            delayBar.setBackgroundColor(DEFAULT_DELAY_BAR)
            divider.setBackgroundColor(DEFAULT_DIVIDER)

            tvAppTitle.setTextColor(Color.parseColor("#E0E0FF"))
            tvAppSubtitle.setTextColor(Color.parseColor("#8888AA"))
            tvTrack.setTextColor(Color.parseColor("#BB86FC"))
            lyricsView.setColors(
                Color.parseColor("#F5F5FF"),
                Color.parseColor("#7A7A96"),
                DEFAULT_HIGHLIGHT
            )
        }
    }

    private fun lighten(color: Int, factor: Float): Int {
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun updateDelayDisplay(offsetMs: Long) {
        val sign = when {
            offsetMs > 0 -> "+"
            else -> ""
        }
        tvDelay.text = "Phone Sync: ${sign}${offsetMs}ms"
    }

    override fun onResume() {
        super.onResume()
        updatePermissionUi()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun updatePermissionUi() {
        val enabled = isNotificationListenerEnabled()
        if (enabled) {
            tvStatus.visibility = View.GONE
            btnPermission.visibility = View.GONE
        } else {
            tvStatus.text = "⚠  Notification access required"
            tvStatus.setBackgroundResource(R.drawable.bg_status_warn)
            tvStatus.visibility = View.VISIBLE
            btnPermission.visibility = View.VISIBLE
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        )
        val component = ComponentName(this, MediaListenerService::class.java)
        return flat?.contains(component.flattenToString()) == true
    }

    private fun updateAaDelayDisplay() {
        val sign = if (aaOffsetMs > 0) "+" else ""
        tvAaDelay.text = "${sign}${aaOffsetMs}ms"
    }

    private fun selectFont(family: String) {
        lyricsFontFamily = family
        saveFontSettings()
        applyFontSettings()
        updateFontButtonHighlights()
    }

    private fun saveFontSettings() {
        prefs.edit()
            .putInt("lyrics_font_size", lyricsFontSizeSp)
            .putString("lyrics_font_family", lyricsFontFamily)
            .apply()
    }

    private fun applyFontSettings() {
        lyricsView.setTextSizeSp(lyricsFontSizeSp.toFloat())
        lyricsView.setTypeface(Typeface.create(lyricsFontFamily, Typeface.NORMAL))
        tvFontSize.text = "${lyricsFontSizeSp}sp"
    }

    private fun updateFontButtonHighlights() {
        val selectedTint = Color.parseColor("#3A3A5E")
        val normalTint = Color.parseColor("#2A2A3E")
        fontButtons.forEach { (family, button) ->
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (family == lyricsFontFamily) selectedTint else normalTint
            )
        }
    }

    private fun onTapSyncPressed() {
        val state = mediaTracker.state.value
        if (state.status != LyricsStatus.FOUND || !state.isPlaying) {
            showSyncStatus("Play a synced song first")
            return
        }

        if (!tapSyncActive) {
            tapSyncActive = true
            tapSyncOffsets.clear()
            val nextIdx = (state.currentIndex + 1).coerceAtMost(state.lines.size - 1)
            tapSyncTargetLineIndex = nextIdx
            setTapSyncActiveStyle(true)
            btnTapSync.text = "⏎  TAP when you hear it (1/3)"
            val lineText = state.lines.getOrNull(nextIdx)?.text?.take(60) ?: "…"
            showSyncStatus("Listen for:\n\"$lineText\"")
        } else {
            val targetLine = state.lines.getOrNull(tapSyncTargetLineIndex)
            if (targetLine != null && targetLine.timeMs > 0) {
                val rawPos = getCurrentRawPositionMs()
                val offset = rawPos - targetLine.timeMs
                tapSyncOffsets.add(offset)
            }

            if (tapSyncOffsets.size >= 3) {
                val avgOffset = tapSyncOffsets.average().toLong()
                mediaTracker.setOffset(avgOffset)
                showSyncStatus("Synced! Offset: ${formatOffset(avgOffset)}")
                tapSyncActive = false
                setTapSyncActiveStyle(false)
                btnTapSync.text = "Tap to Sync"
            } else {
                tapSyncTargetLineIndex = (tapSyncTargetLineIndex + 1).coerceAtMost(state.lines.size - 1)
                btnTapSync.text = "⏎  TAP when you hear it (${tapSyncOffsets.size + 1}/3)"
                val lineText = state.lines.getOrNull(tapSyncTargetLineIndex)?.text?.take(60) ?: "…"
                showSyncStatus("Listen for:\n\"$lineText\"")
            }
        }
    }

    private fun setTapSyncActiveStyle(active: Boolean) {
        if (active) {
            btnTapSync.backgroundTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor("#4A2A6E")
            )
            btnTapSync.setTextColor(Color.parseColor("#EEDDFF"))
        } else {
            btnTapSync.backgroundTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor("#2A2A3E")
            )
            btnTapSync.setTextColor(Color.parseColor("#CCCCDD"))
        }
    }

    private fun getCurrentRawPositionMs(): Long {
        return try {
            mediaTracker.getCurrentPositionMs() - mediaTracker.state.value.offsetMs
        } catch (_: Exception) { 0L }
    }

    private val hideSyncStatusRunnable = Runnable {
        if (!tapSyncActive) {
            tvSyncStatus.visibility = View.GONE
        }
    }

    private fun showSyncStatus(text: String) {
        tvSyncStatus.text = text
        tvSyncStatus.visibility = View.VISIBLE
        handler.removeCallbacks(hideSyncStatusRunnable)
        handler.postDelayed(hideSyncStatusRunnable, 8000)
    }

    private fun formatOffset(ms: Long): String {
        val sign = if (ms >= 0) "+" else ""
        return "${sign}${ms}ms"
    }

    companion object {
        private val DEFAULT_BG = Color.parseColor("#121212")
        private val DEFAULT_APP_BAR = Color.parseColor("#1E1E2E")
        private val DEFAULT_DELAY_BAR = Color.parseColor("#1A1A2A")
        private val DEFAULT_DIVIDER = Color.parseColor("#2A2A3A")
        private val DEFAULT_HIGHLIGHT = Color.parseColor("#FFD54F")
    }
}
