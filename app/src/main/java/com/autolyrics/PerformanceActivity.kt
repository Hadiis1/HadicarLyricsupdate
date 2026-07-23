package com.autolyrics

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.autolyrics.media.MediaTracker
import com.autolyrics.model.LyricsState
import com.autolyrics.model.LyricsStatus
import com.autolyrics.ui.LyricsView
import kotlinx.coroutines.launch

class PerformanceActivity : AppCompatActivity() {

    private lateinit var mediaTracker: MediaTracker
    private lateinit var root: FrameLayout
    private lateinit var albumBg: ImageView
    private lateinit var albumArt: ImageView
    private lateinit var trackTitle: TextView
    private lateinit var trackArtist: TextView
    private lateinit var sourceLabel: TextView
    private lateinit var lyricsView: LyricsView
    private lateinit var exitHint: TextView

    private var lyricsIdentity = 0
    private var lastTrackKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_performance)

        enterImmersiveMode()

        mediaTracker = MediaTracker.getInstance(this)

        root = findViewById(R.id.perf_root)
        albumBg = findViewById(R.id.perf_album_bg)
        albumArt = findViewById(R.id.perf_album_art)
        trackTitle = findViewById(R.id.perf_track_title)
        trackArtist = findViewById(R.id.perf_track_artist)
        sourceLabel = findViewById(R.id.perf_source)
        lyricsView = findViewById(R.id.perf_lyrics_view)
        exitHint = findViewById(R.id.perf_exit_hint)

        root.setOnClickListener { finish() }

        // Full-screen showcase: large text, centred focal line, no hand-scroll
        // (so a tap anywhere exits).
        lyricsView.apply {
            userScrollEnabled = false
            focalYFraction = 0.46f
            setTextSizeSp(30f)
            activeScale = 1.28f
            inactiveScale = 0.66f
            inactiveAlpha = 0.28f
            positionProvider = { safePosition() }
        }

        exitHint.animate().alpha(0f).setStartDelay(3000).setDuration(1000).start()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mediaTracker.state.collect { state -> render(state) }
            }
        }
    }

    private fun render(state: LyricsState) {
        state.track?.let { track ->
            trackTitle.text = track.title
            trackArtist.text = track.artist.ifBlank { "" }
        }

        state.albumArt?.let { art ->
            albumBg.setImageBitmap(art)
            albumArt.setImageBitmap(art)
            albumArt.visibility = View.VISIBLE
        } ?: run {
            albumBg.setImageDrawable(null)
            albumArt.visibility = View.GONE
        }

        if (state.source.isNotBlank()) {
            sourceLabel.text = state.source
            sourceLabel.visibility = View.VISIBLE
        } else {
            sourceLabel.visibility = View.GONE
        }

        val colors = state.albumColors
        lyricsView.setColors(
            active = colors?.textPrimary ?: Color.WHITE,
            inactive = colors?.textDim ?: Color.parseColor("#66FFFFFF"),
            highlight = colors?.vibrant ?: DEFAULT_ACCENT
        )
        lyricsView.isPlaying = state.isPlaying

        when (state.status) {
            LyricsStatus.NO_MEDIA -> lyricsView.setMessage("Play a song")
            LyricsStatus.LOADING -> lyricsView.setMessage("Loading lyrics…")
            LyricsStatus.NOT_FOUND -> lyricsView.setMessage("No lyrics found")
            LyricsStatus.ERROR -> lyricsView.setMessage("Error loading lyrics")
            LyricsStatus.PLAIN_ONLY, LyricsStatus.FOUND -> {
                val plain = state.status == LyricsStatus.PLAIN_ONLY
                bumpIdentityIfTrackChanged(state)
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

    private fun bumpIdentityIfTrackChanged(state: LyricsState) {
        val key = "${state.track?.title}|${state.track?.artist}|${state.status}"
        if (key != lastTrackKey) {
            lastTrackKey = key
            lyricsIdentity++
        }
    }

    private fun safePosition(): Long = try {
        mediaTracker.getCurrentPositionMs()
    } catch (_: Exception) {
        0L
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).let { ctrl ->
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
            ctrl.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        private val DEFAULT_ACCENT = Color.parseColor("#FFD54F")
    }
}
