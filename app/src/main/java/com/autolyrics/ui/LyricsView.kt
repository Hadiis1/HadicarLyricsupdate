package com.autolyrics.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import com.autolyrics.model.LyricLine
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Apple-Music-style lyrics surface.
 *
 * A single spring variable ([animIndex]) chases the target line; both the
 * whole-stack scroll offset and every line's "focus" (scale / brightness /
 * colour) are derived from it, so the active line blooms as the stack glides.
 * The active synced line is filled left-to-right in reading order following
 * the word timestamps, producing a continuous karaoke sweep rather than a
 * per-word snap.
 *
 * The view is self-driving: it pulls the playback position from
 * [positionProvider] on every frame, so the caller only needs to push the
 * lyric data, the active line index, and colours.
 */
class LyricsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ---- Public configuration --------------------------------------------

    /** Supplies the current playback position in ms (already offset-adjusted). */
    var positionProvider: (() -> Long)? = null

    /** True while playback is running — gates the karaoke sweep + plain drift. */
    var isPlaying: Boolean = false
        set(value) {
            val was = field
            field = value
            if (value && !was) resume()   // re-arm the loop when playback starts
        }

    /** Notifies the host when the user starts / stops browsing by hand. */
    var onUserScrollChanged: ((Boolean) -> Unit)? = null

    /** Vertical position of the focal (active) line, as a fraction of height. */
    var focalYFraction: Float = 0.42f

    /** Set false to let the parent handle touches (e.g. tap-to-exit screens). */
    var userScrollEnabled: Boolean = true

    // ---- Colours (sensible dark defaults; overridden via setColors) ------

    private var activeColor: Int = Color.WHITE
    private var inactiveColor: Int = Color.parseColor("#8AFFFFFF")
    private var highlightColor: Int = Color.parseColor("#FFD54F")
    private var translationColor: Int = Color.parseColor("#99FFFFFF")

    fun setColors(active: Int, inactive: Int, highlight: Int) {
        activeColor = active
        inactiveColor = inactive
        highlightColor = highlight
        translationColor = inactive
        invalidate()
    }

    // ---- Typography -------------------------------------------------------

    private val mainPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        textSize = spToPx(20f)
    }
    private val transPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        textSize = spToPx(16f)
    }

    /** Scale applied to the fully-active line vs. a fully-inactive one. */
    var activeScale: Float = 1.16f
    var inactiveScale: Float = 0.92f

    /** Alpha of a fully-inactive line (active is always 1.0). */
    var inactiveAlpha: Float = 0.42f

    fun setTextSizeSp(sp: Float) {
        mainPaint.textSize = spToPx(sp)
        transPaint.textSize = spToPx(sp * 0.8f)
        requestRebuild()
    }

    fun setTypeface(tf: android.graphics.Typeface?) {
        mainPaint.typeface = tf
        transPaint.typeface = tf
        requestRebuild()
    }

    // ---- Data model -------------------------------------------------------

    private var lines: List<LyricLine> = emptyList()
    private var translations: List<String>? = null
    private var plainMode: Boolean = false
    private var trackDurationMs: Long = 0L
    private var message: String? = null

    /** Integer target line for synced lyrics (ignored in plain mode). */
    private var targetLine: Int = 0

    private var lastLinesIdentity: Int = 0

    /**
     * Replace the lyric content. [linesId] lets the host signal a genuine
     * track/lyrics change so scroll + karaoke state reset cleanly (passing the
     * same id on a translation-only update avoids a jarring re-scroll).
     */
    fun setLyrics(
        newLines: List<LyricLine>,
        newTranslations: List<String>?,
        plain: Boolean,
        durationMs: Long,
        linesId: Int
    ) {
        val identityChanged = linesId != lastLinesIdentity
        // MediaTracker re-emits the SAME lines list on every word/line advance;
        // only rebuild the (expensive) StaticLayouts when content truly changes.
        val contentChanged = newLines !== lines || newTranslations !== translations ||
            plain != plainMode || message != null
        lines = newLines
        translations = newTranslations
        plainMode = plain
        trackDurationMs = durationMs
        message = null
        if (identityChanged) {
            lastLinesIdentity = linesId
            targetLine = 0
            animIndex = 0f
            animVelocity = 0f
            userFlingVel = 0f
            lastTouchIdleAtNanos = 0L
            // Fire the callback so a stuck "Jump to current" chip clears on
            // the auto-advance to a new track.
            if (userScrolling) {
                userScrolling = false
                onUserScrollChanged?.invoke(false)
            }
            resetKaraokeCache()
        }
        if (contentChanged) requestRebuild() else resume()
    }

    /** Move the active synced line; the stack springs toward it. */
    fun setActiveLine(index: Int) {
        if (index < 0) return
        targetLine = index
        // Large jumps (manual seek, or a repeat-one loop back to the top)
        // snap instead of a long spring scroll across the whole song.
        if (abs(index - animIndex) > SNAP_JUMP_LINES) {
            animIndex = index.toFloat()
            animVelocity = 0f
            resetKaraokeCache()
        }
        resume()
    }

    /** Show a single centred status line instead of lyrics. */
    fun setMessage(text: String?) {
        if (message == text && lines.isEmpty()) return
        message = text
        if (text != null) {
            lines = emptyList()
            translations = null
        }
        invalidate()
    }

    // ---- Spring / animation state ----------------------------------------

    private var animIndex: Float = 0f       // smoothed line position
    private var animVelocity: Float = 0f
    private var scrollY: Float = 0f          // current drawn content offset (px)

    private var userScrolling = false
    private var userScrollY: Float = 0f
    private var userFlingVel: Float = 0f
    private var lastTouchIdleAtNanos: Long = 0L

    // ---- Cached layouts ---------------------------------------------------

    private class LineLayout(
        val main: StaticLayout,
        val trans: StaticLayout?,
        val height: Int,
        val mainCenterY: Float,
        // Karaoke helpers (word char offsets into the main text).
        val wordStart: IntArray,
        val wordLen: IntArray
    )

    private var layouts: List<LineLayout> = emptyList()
    private var lineTop: FloatArray = FloatArray(0)
    private var lineCenter: FloatArray = FloatArray(0)
    private var contentHeight: Float = 0f
    private var needsRebuild = false
    private var builtForWidth = -1

    private fun requestRebuild() {
        needsRebuild = true
        resume()      // a rebuild may shift layout → let the loop redraw/settle
        invalidate()
    }

    private val interLineGapPx get() = spToPx(14f)

    private fun rebuildLayouts() {
        needsRebuild = false
        builtForWidth = width
        val avail = (width - paddingLeft - paddingRight).coerceAtLeast(1)

        val out = ArrayList<LineLayout>(lines.size)
        var running = 0f
        val tops = FloatArray(lines.size)
        val centers = FloatArray(lines.size)

        lines.forEachIndexed { i, line ->
            val display = displayText(line)
            val main = buildLayout(display, mainPaint, avail)

            val transText = translations?.getOrNull(i)?.takeIf { it.isNotBlank() }
            val trans = transText?.let { buildLayout(it, transPaint, avail) }

            val transGap = if (trans != null) spToPx(4f) else 0f
            val h = main.height + transGap + (trans?.height ?: 0)

            // Word char offsets (only meaningful for karaoke lines).
            val starts: IntArray
            val lens: IntArray
            if (line.words.isNotEmpty()) {
                starts = IntArray(line.words.size)
                lens = IntArray(line.words.size)
                var cursor = 0
                line.words.forEachIndexed { wi, w ->
                    starts[wi] = cursor
                    lens[wi] = w.text.length
                    cursor += w.text.length
                    if (wi < line.words.size - 1) cursor += 1 // the joining space
                }
            } else {
                starts = IntArray(0)
                lens = IntArray(0)
            }

            tops[i] = running
            centers[i] = running + main.height / 2f
            out.add(LineLayout(main, trans, h.toInt(), main.height / 2f, starts, lens))
            running += h + interLineGapPx
        }

        layouts = out
        lineTop = tops
        lineCenter = centers
        contentHeight = running
    }

    private fun displayText(line: LyricLine): String {
        return if (line.words.isNotEmpty()) {
            line.words.joinToString(" ") { it.text }
        } else {
            line.text.ifBlank { "♪" }
        }
    }

    private fun buildLayout(text: String, paint: TextPaint, width: Int): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.15f)
            .setIncludePad(false)
            .build()
    }

    // ---- Frame loop -------------------------------------------------------

    private var running = false
    private var lastFrameNanos = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val dt = if (lastFrameNanos == 0L) 0.016f
            else ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            lastFrameNanos = frameTimeNanos
            step(dt, frameTimeNanos)
            invalidate()
            // Keep pumping only while something is actually moving; otherwise
            // stop so a static screen doesn't redraw at 60fps forever.
            if (isBusy()) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                running = false
                lastFrameNanos = 0L
            }
        }
    }

    /** Whether any animation still needs frames. */
    private fun isBusy(): Boolean {
        if (layouts.isEmpty()) return false
        if (userScrolling || abs(userFlingVel) > 1f) return true
        val settled = abs(targetLineFloat - animIndex) < SETTLE_EPS &&
            abs(animVelocity) < SETTLE_EPS
        if (!settled) return true
        if (isPlaying) {
            if (plainMode) return true
            // Synced line with word timing keeps sweeping; a plain line-level
            // line is static until the next line change re-arms the loop.
            if (lines.getOrNull(targetLine)?.words?.isNotEmpty() == true) return true
        }
        return false
    }

    fun resume() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun pause() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isShown) resume()
    }

    override fun onDetachedFromWindow() {
        pause()
        velocityTracker?.recycle()
        velocityTracker = null
        super.onDetachedFromWindow()
    }

    // Stop the 60fps loop when the view isn't actually on screen (activity
    // backgrounded / window hidden) so it never burns battery in the dark.
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) resume() else pause()
    }

    private fun step(dt: Float, frameTimeNanos: Long) {
        if (needsRebuild || builtForWidth != width) {
            if (width > 0) rebuildLayouts()
        }
        if (layouts.isEmpty()) return

        val maxIndex = (layouts.size - 1).toFloat()

        // Plain lyrics: derive a fractional target from playback progress.
        if (plainMode && trackDurationMs > 0) {
            val pos = safePosition().coerceAtLeast(0)
            val frac = (pos.toFloat() / trackDurationMs).coerceIn(0f, 1f)
            targetLineFloat = frac * maxIndex
        } else {
            targetLineFloat = targetLine.toFloat().coerceIn(0f, maxIndex)
        }

        // Spring the smoothed index toward the target (critically-ish damped).
        val stiffness = 120f
        val damping = 2f * DAMPING_RATIO * sqrt(stiffness)
        val substeps = ceil(dt / 0.008f).toInt().coerceIn(1, 8)
        val h = dt / substeps
        repeat(substeps) {
            val a = stiffness * (targetLineFloat - animIndex) - damping * animVelocity
            animVelocity += a * h
            animIndex += animVelocity * h
        }
        animIndex = animIndex.coerceIn(0f, maxIndex)

        // Idle auto-return after browsing by hand.
        if (userScrolling) {
            // Fling decay.
            if (abs(userFlingVel) > 1f) {
                userScrollY += userFlingVel * dt
                userFlingVel *= FLING_FRICTION
                clampUserScroll()
            }
            if (lastTouchIdleAtNanos != 0L &&
                frameTimeNanos - lastTouchIdleAtNanos > IDLE_RETURN_NANOS
            ) {
                returnToCurrent()
            }
        }

        scrollY = if (userScrolling) userScrollY else centerOf(animIndex) - focalY()
    }

    private var targetLineFloat: Float = 0f

    private fun centerOf(indexF: Float): Float {
        if (layouts.isEmpty()) return 0f
        val f = indexF.coerceIn(0f, (layouts.size - 1).toFloat())
        val i0 = floor(f).toInt()
        val i1 = ceil(f).toInt().coerceAtMost(layouts.size - 1)
        if (i0 == i1) return lineCenter[i0]
        val frac = f - i0
        return lineCenter[i0] + (lineCenter[i1] - lineCenter[i0]) * frac
    }

    private fun focalY(): Float = height * focalYFraction

    private fun minScroll(): Float = if (layouts.isEmpty()) 0f else lineCenter[0] - focalY()
    private fun maxScroll(): Float =
        if (layouts.isEmpty()) 0f else lineCenter[layouts.size - 1] - focalY()

    private fun clampUserScroll() {
        val lo = minScroll()
        val hi = maxScroll()
        if (userScrollY < lo) { userScrollY = lo; userFlingVel = 0f }
        if (userScrollY > hi) { userScrollY = hi; userFlingVel = 0f }
    }

    fun returnToCurrent() {
        if (!userScrolling) return
        userScrolling = false
        userFlingVel = 0f
        lastTouchIdleAtNanos = 0L
        onUserScrollChanged?.invoke(false)
    }

    private fun safePosition(): Long = try {
        positionProvider?.invoke() ?: 0L
    } catch (_: Exception) {
        0L
    }

    // ---- Drawing ----------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) requestRebuild()
    }

    override fun onDraw(canvas: Canvas) {
        message?.let { msg ->
            drawCenteredMessage(canvas, msg)
            return
        }
        if (layouts.isEmpty()) {
            if (needsRebuild && width > 0) rebuildLayouts()
            if (layouts.isEmpty()) return
        }

        val top = scrollY
        val viewTop = -MARGIN_PX
        val viewBottom = height + MARGIN_PX
        val centerX = width / 2f

        for (i in layouts.indices) {
            val ll = layouts[i]
            val drawnTop = lineTop[i] - top
            val drawnBottom = drawnTop + ll.height
            if (drawnBottom < viewTop || drawnTop > viewBottom) continue

            val focus = (1f - abs(i - animIndex)).coerceIn(0f, 1f)
            val eased = smoothstep(focus)
            val scale = inactiveScale + (activeScale - inactiveScale) * eased

            val isKaraokeLine = !plainMode && i == targetLine && ll.wordStart.isNotEmpty()

            // The active synced line renders at full opacity/colour from its
            // very first frame — the spring (animIndex) still lags on the
            // previous line, so we can't let its focus dim the karaoke sweep.
            val alpha = if (isKaraokeLine) 1f else inactiveAlpha + (1f - inactiveAlpha) * eased
            val color = if (isKaraokeLine) activeColor else lerpColor(inactiveColor, activeColor, eased)

            canvas.save()
            canvas.translate(paddingLeft.toFloat(), drawnTop)
            canvas.scale(scale, scale, centerX - paddingLeft, ll.mainCenterY)

            if (isKaraokeLine) {
                drawKaraokeLine(canvas, ll, lines[i], color, alpha)
            } else {
                mainPaint.color = color
                mainPaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
                ll.main.draw(canvas)
            }

            ll.trans?.let { tl ->
                canvas.save()
                canvas.translate(0f, ll.main.height + spToPx(4f))
                transPaint.color = translationColor
                transPaint.alpha = (alpha * 0.9f * 255).toInt().coerceIn(0, 255)
                tl.draw(canvas)
                canvas.restore()
            }

            canvas.restore()
        }
    }

    private fun drawKaraokeLine(
        canvas: Canvas,
        ll: LineLayout,
        line: LyricLine,
        baseColor: Int,
        alpha: Float
    ) {
        // Base: whole line in the (bright) active colour.
        mainPaint.color = baseColor
        mainPaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        ll.main.draw(canvas)

        val revealChar = karaokeRevealChar(ll, line)
        if (revealChar <= 0) return

        // Overlay: the sung portion in the highlight colour, revealed in
        // reading order across wrapped visual lines.
        val layout = ll.main
        mainPaint.color = highlightColor
        mainPaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)

        val lineCount = layout.lineCount
        for (vln in 0 until lineCount) {
            val lineStart = layout.getLineStart(vln)
            val lineEnd = layout.getLineEnd(vln)
            if (revealChar <= lineStart) break

            val bandTop = layout.getLineTop(vln).toFloat()
            val bandBottom = layout.getLineBottom(vln).toFloat()

            canvas.save()
            if (revealChar >= lineEnd) {
                canvas.clipRect(0f, bandTop, width.toFloat(), bandBottom)
            } else {
                // Sub-character precision: interpolate the reveal x between the
                // two nearest glyph boundaries so the sweep glides continuously.
                // The upper bound is this visual line's right edge — never
                // getPrimaryHorizontal(lineEnd), which resolves to the NEXT
                // visual line's leading x on a mid-word wrap and would jump back.
                val cf = revealChar.coerceIn(lineStart.toFloat(), lineEnd.toFloat())
                val i0 = floor(cf).toInt().coerceIn(lineStart, maxOf(lineStart, lineEnd - 1))
                val x0 = layout.getPrimaryHorizontal(i0)
                val i1 = i0 + 1
                val x1 = if (i1 >= lineEnd) layout.getLineRight(vln) else layout.getPrimaryHorizontal(i1)
                val x = x0 + (x1 - x0) * (cf - i0)
                canvas.clipRect(0f, bandTop, x, bandBottom)
            }
            layout.draw(canvas)
            canvas.restore()
        }
    }

    // Cache to keep the reveal monotonic within a line (no backward flicker),
    // while still receding on a genuine backward seek.
    private var karaokeLineIdx = -1
    private var karaokeRevealCache = 0f
    private var karaokeLastPos = Long.MIN_VALUE

    private fun resetKaraokeCache() {
        karaokeLineIdx = -1
        karaokeRevealCache = 0f
        karaokeLastPos = Long.MIN_VALUE
    }

    private fun karaokeRevealChar(ll: LineLayout, line: LyricLine): Float {
        val words = line.words
        if (words.isEmpty()) return 0f
        val pos = safePosition()

        if (targetLine != karaokeLineIdx) {
            // Moved to a new line — start its sweep fresh.
            karaokeLineIdx = targetLine
            karaokeRevealCache = 0f
        } else if (pos < karaokeLastPos - BACKWARD_SEEK_MS) {
            // Scrubbed backward inside the current line — let the fill recede.
            karaokeRevealCache = 0f
        }
        karaokeLastPos = pos

        var cw = -1
        for (i in words.indices) {
            if (words[i].timeMs <= pos) cw = i else break
        }
        if (cw < 0) {
            karaokeRevealCache = 0f
            return 0f
        }

        val startMs = words[cw].timeMs
        val endMs = if (cw + 1 < words.size) words[cw + 1].timeMs else startMs + LAST_WORD_MS
        val span = (endMs - startMs).coerceAtLeast(1L)
        val p = ((pos - startMs).toFloat() / span).coerceIn(0f, 1f)

        // Monotonic forward within the line to smooth throttled updates.
        val reveal = maxOf(ll.wordStart[cw] + p * ll.wordLen[cw], karaokeRevealCache)
        karaokeRevealCache = reveal
        return reveal
    }

    private fun drawCenteredMessage(canvas: Canvas, msg: String) {
        val avail = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        mainPaint.color = inactiveColor
        mainPaint.alpha = 255
        val layout = buildLayout(msg, mainPaint, avail)
        val y = (height - layout.height) / 2f
        canvas.save()
        canvas.translate(paddingLeft.toFloat(), y)
        layout.draw(canvas)
        canvas.restore()
    }

    // ---- Touch (browse) ---------------------------------------------------

    private var velocityTracker: VelocityTracker? = null
    private var downY = 0f
    private var downScrollY = 0f
    private var draggingBeyondSlop = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!userScrollEnabled || layouts.isEmpty()) return false

        val tracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
        tracker.addMovement(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.y
                downScrollY = if (userScrolling) userScrollY else scrollY
                draggingBeyondSlop = false
                userFlingVel = 0f
                lastTouchIdleAtNanos = 0L
                resume()   // re-arm the loop so drag/fling animates
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - downY
                if (!draggingBeyondSlop && abs(dy) > touchSlop) {
                    draggingBeyondSlop = true
                    if (!userScrolling) {
                        userScrolling = true
                        userScrollY = downScrollY
                        onUserScrollChanged?.invoke(true)
                    }
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (draggingBeyondSlop) {
                    userScrollY = downScrollY - dy
                    clampUserScroll()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (draggingBeyondSlop) {
                    tracker.computeCurrentVelocity(1000)
                    userFlingVel = -tracker.yVelocity
                    lastTouchIdleAtNanos = System.nanoTime()
                }
                velocityTracker?.recycle()
                velocityTracker = null
                return true
            }
        }
        return true
    }

    // ---- Helpers ----------------------------------------------------------

    private fun spToPx(sp: Float): Float =
        sp * resources.displayMetrics.scaledDensity

    companion object {
        private const val DAMPING_RATIO = 0.82f
        private const val FLING_FRICTION = 0.92f
        private const val LAST_WORD_MS = 500L
        private const val IDLE_RETURN_NANOS = 8_000_000_000L
        private const val SETTLE_EPS = 0.0015f
        private const val BACKWARD_SEEK_MS = 400L
        private const val SNAP_JUMP_LINES = 6f
        private val MARGIN_PX = 120f

        private fun smoothstep(x: Float): Float {
            val t = x.coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        private fun lerpColor(from: Int, to: Int, t: Float): Int {
            val f = t.coerceIn(0f, 1f)
            val a = (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * f).toInt()
            val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * f).toInt()
            val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * f).toInt()
            val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f).toInt()
            return Color.argb(a, r, g, b)
        }
    }
}
