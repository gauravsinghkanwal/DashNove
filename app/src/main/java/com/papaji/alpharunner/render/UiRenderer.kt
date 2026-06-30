package com.papaji.alpharunner.render

import android.graphics.*
import com.papaji.alpharunner.engine.GameEngine
import com.papaji.alpharunner.engine.GameEngine.EngineState
import com.papaji.alpharunner.engine.GameSpawner.EnginePowerType
import kotlin.math.min
import kotlin.math.max
import kotlin.math.sin

/**
 * UiRenderer — draws every screen-space overlay on top of the world layer.
 *
 * Allocation rules (same as [GameRenderer]):
 *   • All Paints are class-level val fields, mutated in place.
 *   • All RectF objects are class-level val fields, set/mutated in place.
 *   • The only allocation that may happen inside draw methods is the *first*
 *     call to [drawButton] for a given (color × size) combination, which
 *     creates and caches a Bitmap. Subsequent calls are a single drawBitmap.
 *   • The menu glow Paint uses [BlurMaskFilter]; a new filter is created only
 *     when [menuPulse] crosses a blur-radius tier (not every frame).
 *
 * Touch hit-test integration:
 *   All button [RectF] instances are public `val` fields. GameView reads them
 *   in [onTouchEvent] without needing to call back into UiRenderer at all.
 *
 * How to wire up in GameView:
 *   val ui = UiRenderer(engine)
 *   ui.onSurfaceChanged(w, h)
 *   // onDraw per-state:
 *   ui.drawHUD(canvas, musicEnabled, sfxEnabled, themeMode, skinColor, selectedSkinKey, ownedSkins, showTutorial, tutorialAlpha)
 *   ui.drawMenu(canvas, highScore, menuPulse)
 *   ui.drawShop(canvas, scaledSkinCache, skinBitmapKeys, ownedSkins, selectedSkinKey, totalCoins)
 *   ui.drawGameOver(canvas)
 *   ui.drawSettings(canvas, musicEnabled, sfxEnabled, themeMode)
 *   ui.drawPaused(canvas)
 */
class UiRenderer(private val engine: GameEngine) {

    // ═══════════════════════════════════════════════════════════════════════════
    // Surface geometry
    // ═══════════════════════════════════════════════════════════════════════════

    private var screenW = 0f
    private var screenH = 0f

    fun onSurfaceChanged(w: Float, h: Float) {
        screenW = w
        screenH = h
        btnBitmapCache.clear()   // button backgrounds must be re-baked at new size
        setupButtonRects()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Button bitmap cache
    //   Key = btnColor XOR (width SHL 8) XOR height
    //   Value = a Bitmap with the gradient + shine strip + border baked in.
    //   Cache is cleared in onSurfaceChanged so sizes stay consistent.
    // ═══════════════════════════════════════════════════════════════════════════

    private val btnBitmapCache = HashMap<Int, Bitmap>(16)

    // ═══════════════════════════════════════════════════════════════════════════
    // Public RectF hit regions  (read by GameView.onTouchEvent)
    // ═══════════════════════════════════════════════════════════════════════════

    // Menu
    val playBtn      = RectF()
    val shopBtn      = RectF()
    val settingsBtn  = RectF()
    val exitBtn      = RectF()

    // Gameplay
    val pauseBtn     = RectF()

    // Game Over
    val restartBtn   = RectF()
    val menuBtn      = RectF()
    val reviveRect   = RectF()

    // Shop
    val backBtn      = RectF()
    val skinBoxRects = LinkedHashMap<String, RectF>()   // key = SkinType.name

    // Shop — upgrade tiles (four in a horizontal row below the skin grid)
    val upMagnetRect = RectF()
    val upShieldRect = RectF()
    val upSlowRect   = RectF()
    val upScoreRect  = RectF()

    // Settings
    val settingsBackRect  = RectF()
    val musicToggleRect   = RectF()
    val sfxToggleRect     = RectF()
    val themeDayRect      = RectF()
    val themeNightRect    = RectF()

    // Pause
    val resumeBtn        = RectF()
    val pausedMenuBtn    = RectF()

    // ═══════════════════════════════════════════════════════════════════════════
    // Class-level Paints  — never re-allocated inside draw methods
    // ═══════════════════════════════════════════════════════════════════════════

    // ── Typography ────────────────────────────────────────────────────────────
    // Override [gameTypeface] via [setTypeface()] at startup once a custom
    // arcade/pixel font is loaded. All text in UiRenderer flows through [tp]
    // which reads this property, so one call updates the entire UI layer.
    var gameTypeface: Typeface = Typeface.DEFAULT_BOLD
        private set

    fun setTypeface(tf: Typeface) {
        gameTypeface           = tf
        tp.typeface            = tf
        menuGlowPaint.typeface = tf
    }

    // General-purpose text paint: only color, size, align, bold mutated per call
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        color    = Color.WHITE
    }

    // Semi-transparent fills used across multiple screens
    private val chipBgPaint   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chipBorderP   = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val cardBgPaint   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardBorderP   = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val overlayPaint  = Paint()
    private val divPaint      = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(100, 255, 255, 255) }
    private val barPaint      = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(130, 0, 0, 0) }

    // HUD pill
    private val pillBgPaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 0, 0, 0) }
    private val pillBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val pillRect        = RectF()

    // Menu glow text: [BlurMaskFilter] is recreated only when blur radius changes tier
    private val menuGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lastGlowRadius = -1f

    // Skin outline in shop
    private val skinOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = 8f
        color       = Color.CYAN
    }

    // Tutorial background
    private val tutorialBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ═══════════════════════════════════════════════════════════════════════════
    // Button rects pre-setup (called from onSurfaceChanged)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun setupButtonRects() {
        val cx  = screenW / 2f
        val cy  = screenH / 2f
        val bW  = 520f
        val bH  = 112f

        // Menu layout:
        //   ┌──────────────────────────────────────────┐
        //   │          ▶  PLAY   (large dominant)       │
        //   │  🎽 SKINS  │  ⚙ SETTINGS  (compact pair) │
        //   │             ✕ EXIT (slim dismissal)        │
        //   └──────────────────────────────────────────┘
        // This clears >60 % of the background for the parallax city to breathe.

        val playH = 128f
        val playY = cy - 90f
        playBtn.set(cx - bW / 2f, playY, cx + bW / 2f, playY + playH)

        val subH  = 96f; val subW = 240f; val subGap = 20f
        val subY  = playY + playH + 18f
        shopBtn.set(cx - subW - subGap / 2f, subY, cx - subGap / 2f, subY + subH)
        settingsBtn.set(cx + subGap / 2f, subY, cx + subGap / 2f + subW, subY + subH)

        val exitH = 80f; val exitW = 300f
        val exitY = subY + subH + 18f
        exitBtn.set(cx - exitW / 2f, exitY, cx + exitW / 2f, exitY + exitH)

        // Pause button: top-right corner inside the HUD bar
        pauseBtn.set(screenW - 145f, 14f, screenW - 16f, 90f)

        // Game Over buttons
        val goW  = 320f; val goH = 100f; val goGap = 30f
        val totalW = goW * 2 + goGap
        val goLeft = cx - totalW / 2f
        restartBtn.set(goLeft,            cy + 60f, goLeft + goW,    cy + 60f + goH)
        menuBtn.set(goLeft + goW + goGap, cy + 60f, goLeft + totalW, cy + 60f + goH)
        reviveRect.set(cx - 340f,  cy + 188f,  cx + 340f,  cy + 288f)

        // Back button (shop / settings)
        backBtn.set(26f, 26f, 230f, 100f)

        // Settings back button: inside top-left of settings card
        settingsBackRect.set(cx - 600f + 20f,  cy - 270f + 14f,
            cx - 600f + 220f, cy - 270f + 84f)

        // Settings toggles
        val tW = 500f; val tH = 96f; val tGap = 24f
        val tY = cy - 130f
        musicToggleRect.set(cx - tW - tGap / 2f, tY, cx - tGap / 2f, tY + tH)
        sfxToggleRect.set(  cx + tGap / 2f,       tY, cx + tW + tGap / 2f, tY + tH)
        val thY = tY + tH + tGap
        val thW = tW - 10f
        themeDayRect.set(  cx - thW - tGap / 2f, thY, cx - tGap / 2f,             thY + tH)
        themeNightRect.set(cx + tGap / 2f,        thY, cx + thW + tGap / 2f,       thY + tH)

        // Pause buttons
        val pW = 300f; val pH = 100f
        resumeBtn.set(   cx - pW - 20f, cy + 10f, cx - 20f,      cy + 10f + pH)
        pausedMenuBtn.set(cx + 20f,      cy + 10f, cx + pW + 20f, cy + 10f + pH)

        // Upgrade tiles — four equal-width cells in a horizontal row.
        // These sit below the skin grid in the Shop screen.
        // Y origin (upBaseY) is computed dynamically to allow for variable
        // skin-grid height; the shop always has 2 skin rows = 370 + 220 + 140 + 220 = 950.
        // We anchor at a safe fixed offset so the tiles are always visible.
        val upTileW   = (screenW - 100f) / 4f - 10f  // 4 tiles with 10 px gaps
        val upTileH   = 90f
        val upBaseY   = 980f                          // below two rows of skins
        val upStartX  = 50f
        val upGap     = 10f
        upMagnetRect.set(upStartX,                          upBaseY, upStartX + upTileW,                          upBaseY + upTileH)
        upShieldRect.set(upStartX + (upTileW + upGap),      upBaseY, upStartX + (upTileW + upGap) + upTileW,      upBaseY + upTileH)
        upSlowRect.set(  upStartX + (upTileW + upGap) * 2f, upBaseY, upStartX + (upTileW + upGap) * 2f + upTileW, upBaseY + upTileH)
        upScoreRect.set( upStartX + (upTileW + upGap) * 3f, upBaseY, upStartX + (upTileW + upGap) * 3f + upTileW, upBaseY + upTileH)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HUD  (drawn while PLAYING)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Draws the complete gameplay HUD:
     *   • Semi-transparent top bar
     *   • Score chip (centre)
     *   • Coin chip (left)
     *   • Combo chip (right, only when comboCount ≥ 3)
     *   • Power-up pills (below bar, left-anchored)
     *   • Pause button (top-right, inside bar)
     *   • Tutorial overlay (bottom, first run only)
     */
    fun drawHUD(
        canvas:         Canvas,
        skinColor:      Int,
        showTutorial:   Boolean,
        tutorialAlpha:  Float
    ) {
        val s  = engine.state
        val cx = screenW / 2f
        val w  = screenW

        // ── Top bar ──────────────────────────────────────────────────────────
        canvas.drawRect(0f, 0f, w, 110f, barPaint)

        // ── Score chip (centred) ─────────────────────────────────────────────
        tp.textSize  = 50f
        tp.color     = Color.WHITE
        tp.isFakeBoldText = true
        tp.textAlign = Paint.Align.CENTER
        val scoreTw  = tp.measureText("SCORE: ${s.score}") + 48f

        chipBgPaint.color = Color.argb(200, 20, 20, 50)
        canvas.drawRoundRect(cx - scoreTw / 2f, 10f, cx + scoreTw / 2f, 95f, 22f, 22f, chipBgPaint)

        chipBorderP.strokeWidth = 2.5f
        chipBorderP.color = Color.argb(200, 80, 180, 255)
        canvas.drawRoundRect(cx - scoreTw / 2f, 10f, cx + scoreTw / 2f, 95f, 22f, 22f, chipBorderP)

        canvas.drawText("SCORE: ${s.score}", cx, 72f, tp)

        // ── Coin chip (left) ─────────────────────────────────────────────────
        chipBgPaint.color = Color.argb(180, 30, 20, 0)
        canvas.drawRoundRect(14f, 12f, 240f, 90f, 20f, 20f, chipBgPaint)
        chipBorderP.strokeWidth = 2f
        chipBorderP.color = Color.argb(200, 255, 210, 0)
        canvas.drawRoundRect(14f, 12f, 240f, 90f, 20f, 20f, chipBorderP)

        tp.textAlign = Paint.Align.LEFT
        tp.textSize  = 42f
        tp.color     = Color.parseColor("#FFD600")
        tp.isFakeBoldText = true
        canvas.drawText("🪙 ${s.totalCoins}", 26f, 68f, tp)

        // ── Combo chip (right, conditional) ──────────────────────────────────
        if (s.comboCount >= 3) {
            val comboColor = when {
                s.comboCount >= 10 -> Color.parseColor("#FF0055")
                s.comboCount >= 5  -> Color.parseColor("#FF6D00")
                else               -> Color.parseColor("#FFD600")
            }
            chipBgPaint.color = Color.argb(200, 20, 0, 0)
            canvas.drawRoundRect(w - 300f, 12f, w - 160f, 90f, 20f, 20f, chipBgPaint)
            chipBorderP.strokeWidth = 2f
            chipBorderP.color = comboColor
            chipBorderP.alpha = 220
            canvas.drawRoundRect(w - 300f, 12f, w - 160f, 90f, 20f, 20f, chipBorderP)
            chipBorderP.alpha = 255   // restore

            tp.textAlign = Paint.Align.CENTER
            tp.textSize  = 40f
            tp.color     = comboColor
            tp.isFakeBoldText = true
            canvas.drawText("x${s.comboCount} 🔥", w - 230f, 66f, tp)
        }

        tp.isFakeBoldText = false

        // ── Power-up pills ────────────────────────────────────────────────────
        var hx = 50f
        val hy = 150f
        if (s.shieldActive) { drawHudPill(canvas, hx, hy, "🛡", s.shieldTimer,  Color.parseColor("#FFC107")); hx += 170f }
        if (s.slowActive)   { drawHudPill(canvas, hx, hy, "⏳", s.slowTimer,    Color.MAGENTA);               hx += 170f }
        if (s.doubleActive) { drawHudPill(canvas, hx, hy, "✨", s.doubleTimer,  Color.YELLOW);                hx += 170f }
        if (s.magnetActive) { drawHudPill(canvas, hx, hy, "🧲", s.magnetTimer,  Color.RED);                   hx += 170f }
        if (s.ghostActive)  { drawHudPill(canvas, hx, hy, "👻", s.ghostTimer,   Color.LTGRAY);                hx += 170f }

        // ── Pause button ──────────────────────────────────────────────────────
        // RectF already set in setupButtonRects; drawButton reads rect dimensions
        drawButton(canvas, pauseBtn, "⏸", Color.DKGRAY)

        // ── First-run tutorial overlay ────────────────────────────────────────
        if (showTutorial) {
            val a = (tutorialAlpha * 220).toInt()
            tutorialBgPaint.color = Color.argb(a / 2, 0, 0, 0)
            val tipY = screenH - 260f
            canvas.drawRoundRect(
                screenW / 2f - 360f, tipY - 10f,
                screenW / 2f + 360f, tipY + 110f,
                24f, 24f, tutorialBgPaint
            )
            tp.textAlign = Paint.Align.CENTER
            tp.color     = Color.WHITE
            tp.alpha     = a
            tp.textSize  = 40f
            canvas.drawText("TAP  —  Jump  |  TAP TWICE  —  Double Jump", screenW / 2f, tipY + 36f, tp)
            canvas.drawText("SWIPE RIGHT  —  Dash through obstacles",       screenW / 2f, tipY + 82f, tp)
            tp.alpha = 255
        }
    }

    /**
     * A single power-up pill: dark pill background + tinted border + icon + seconds.
     * All six fields of [pillRect] are set here; no new RectF is created.
     */
    private fun drawHudPill(
        canvas:      Canvas,
        x:           Float,
        y:           Float,
        icon:        String,
        secondsLeft: Float,
        tint:        Int
    ) {
        pillRect.set(x, y, x + 160f, y + 70f)
        canvas.drawRoundRect(pillRect, 24f, 24f, pillBgPaint)

        pillBorderPaint.color = tint
        canvas.drawRoundRect(pillRect, 24f, 24f, pillBorderPaint)

        tp.textSize  = 38f
        tp.color     = Color.WHITE
        val tcy      = pillRect.centerY() - (tp.fontMetrics.ascent + tp.fontMetrics.descent) / 2f

        tp.textAlign = Paint.Align.LEFT
        canvas.drawText(icon, pillRect.left + 14f, tcy, tp)

        tp.textAlign = Paint.Align.RIGHT
        canvas.drawText("${secondsLeft.toInt() + 1}s", pillRect.right - 12f, tcy, tp)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Main Menu
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * @param menuPulse  Monotonically increasing angle in radians — drives the
     *                   title glow animation. Provided by GameView's doFrame tick.
     */
    fun drawMenu(canvas: Canvas, highScore: Int, menuPulse: Float) {
        canvas.save()
        canvas.translate(0f, engine.state.menuCamOffset)
        drawDarkOverlay(canvas)

        val cx = screenW / 2f
        val cy = screenH / 2f

        // ── Glowing title ─────────────────────────────────────────────────────
        val pulse = (sin(menuPulse.toDouble()) * 0.18f + 0.82f).toFloat()
        val blurR = 40f * pulse

        // Recreate the BlurMaskFilter only when the blur radius changes tier
        // (± 2 px tolerance) so we avoid per-frame object creation in steady state
        if (kotlin.math.abs(blurR - lastGlowRadius) > 2f) {
            menuGlowPaint.maskFilter = BlurMaskFilter(blurR, BlurMaskFilter.Blur.NORMAL)
            lastGlowRadius           = blurR
        }
        menuGlowPaint.color = Color.argb((160 * pulse).toInt(), 0, 220, 255)

        tp.textAlign      = Paint.Align.CENTER
        tp.textSize       = 108f
        canvas.drawText("ALPHA RUNNER", cx, cy - 220f, menuGlowPaint)

        tp.color          = Color.WHITE
        tp.isFakeBoldText = true
        canvas.drawText("ALPHA RUNNER", cx, cy - 220f, tp)
        tp.isFakeBoldText = false

        // Sub-line
        tp.textSize = 36f
        tp.color    = Color.parseColor("#90CAF9")
        canvas.drawText("HOW FAR CAN YOU RUN?", cx, cy - 168f, tp)

        // Best score badge (hidden when highScore == 0 to avoid clutter on first run)
        if (highScore > 0) {
            chipBgPaint.color = Color.argb(180, 20, 20, 60)
            canvas.drawRoundRect(cx - 180f, cy - 148f, cx + 180f, cy - 94f, 26f, 26f, chipBgPaint)
            chipBorderP.strokeWidth = 2f
            chipBorderP.color       = Color.parseColor("#FFD600")
            canvas.drawRoundRect(cx - 180f, cy - 148f, cx + 180f, cy - 94f, 26f, 26f, chipBorderP)
            tp.textSize = 38f
            tp.color    = Color.parseColor("#FFD600")
            canvas.drawText("🏆  BEST: $highScore", cx, cy - 108f, tp)
        }

        // ── Main action: large PLAY pill ─────────────────────────────────────
        drawButton(canvas, playBtn, "▶  PLAY", Color.parseColor("#00C853"))

        // ── Compact sub-row: SKINS + SETTINGS side by side ───────────────────
        // Smaller buttons let the parallax background show through clearly.
        drawButton(canvas, shopBtn,     "🎽  SKINS",    Color.parseColor("#FF6D00"))
        drawButton(canvas, settingsBtn, "⚙  SETTINGS", Color.parseColor("#2962FF"))

        // ── Slim exit affordance ──────────────────────────────────────────────
        drawButton(canvas, exitBtn, "✕  EXIT", Color.parseColor("#B71C1C"))

        canvas.restore()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Shop / Armory
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * @param scaledSkinCache  Pre-scaled bitmaps keyed by SkinType.name.
     * @param skinKeys         Ordered list of SkinType.name strings to display.
     * @param ownedSkins       Set of SkinType.name strings the player owns.
     * @param selectedSkinKey  Currently equipped skin.
     * @param totalCoins       Player's coin balance.
     */
    fun drawShop(
        canvas:          Canvas,
        scaledSkinCache: Map<String, Bitmap?>,
        skinKeys:        List<String>,
        ownedSkins:      Set<String>,
        selectedSkinKey: String,
        totalCoins:      Int
    ) {
        drawDarkOverlay(canvas)
        val cx = screenW / 2f

        // ── Header ────────────────────────────────────────────────────────────
        tp.textAlign      = Paint.Align.CENTER
        tp.textSize       = 80f
        tp.color          = Color.parseColor("#FFD600")
        tp.isFakeBoldText = true
        canvas.drawText("🎽  ARMORY", cx, 110f, tp)
        tp.isFakeBoldText = false

        // Coin balance badge
        chipBgPaint.color = Color.argb(180, 30, 20, 0)
        canvas.drawRoundRect(cx - 180f, 118f, cx + 180f, 178f, 20f, 20f, chipBgPaint)
        chipBorderP.strokeWidth = 2f
        chipBorderP.color       = Color.parseColor("#FFD600")
        canvas.drawRoundRect(cx - 180f, 118f, cx + 180f, 178f, 20f, 20f, chipBorderP)
        tp.textSize = 44f
        tp.color    = Color.parseColor("#FFD600")
        canvas.drawText("🪙  $totalCoins  coins", cx, 162f, tp)

        // Back button
        drawButton(canvas, backBtn, "< BACK", Color.DKGRAY)

        // ── Row 1 header: SKINS ──────────────────────────────────────────────
        tp.textSize = 52f
        tp.color    = Color.CYAN
        canvas.drawText("CHARACTER SKINS", cx, 320f, tp)

        // Horizontal rule beneath label
        canvas.drawRect(cx - 480f, 330f, cx + 480f, 332f, divPaint)

        // ── Skin grid: 3 columns centred ──────────────────────────────────────
        val skinSize = 200f
        val spacing  = 50f
        val cols     = 3
        val startX   = (screenW - (cols * skinSize + (cols - 1) * spacing)) / 2f

        skinBoxRects.clear()

        var xPos = startX
        var yPos = 348f

        skinKeys.forEachIndexed { i, key ->
            val rect = skinBoxRects.getOrPut(key) { RectF() }
            rect.set(xPos, yPos, xPos + skinSize, yPos + skinSize)

            // Draw bitmap or colour block fallback
            val bmp = scaledSkinCache[key]
            if (bmp != null) {
                canvas.drawBitmap(bmp, null, rect, null)
            } else {
                chipBgPaint.color = skinColorForKey(key)
                canvas.drawRoundRect(rect, 18f, 18f, chipBgPaint)
            }

            // Owned / cost label
            tp.textSize  = 42f
            tp.textAlign = Paint.Align.CENTER
            val owned = ownedSkins.contains(key)
            tp.color = if (owned) Color.parseColor("#00E676") else Color.WHITE
            canvas.drawText(if (owned) "Owned" else "50 🪙", rect.centerX(), rect.bottom + 48f, tp)

            // Selected skin highlight ring
            if (key == selectedSkinKey) {
                canvas.drawRoundRect(rect, 25f, 25f, skinOutlinePaint)
            }

            // Advance grid position
            xPos += skinSize + spacing
            if ((i + 1) % cols == 0) {
                xPos  = startX
                yPos += skinSize + 140f
            }
        }
    }

    /**
     * Draws the four upgrade tiles inside [drawShop].
     *
     * Layout: Row 2 — four equal-width cards in a full-width horizontal strip
     * beneath the skin grid, with a labelled section divider separating the rows.
     * Each card shows: icon + label, level-pip bar, and cost / MAX text.
     *
     * @param upgradeLevels  Ordered list [magnetLevel, shieldLevel, slowLevel, scoreLevel]
     * @param maxLevel       Maximum purchasable level (e.g. 5)
     * @param costs          Cost of each next level purchase in coins
     * @param totalCoins     Player's current coin balance
     */
    fun drawUpgrades(
        canvas:        Canvas,
        upgradeLevels: List<Int>,
        maxLevel:      Int,
        costs:         List<Int>,
        totalCoins:    Int
    ) {
        val cx = screenW / 2f

        // Row 2 section divider
        val divY = upMagnetRect.top - 52f
        canvas.drawRect(cx - 480f, divY, cx + 480f, divY + 2f, divPaint)
        tp.textAlign = Paint.Align.CENTER
        tp.textSize  = 48f
        tp.color     = Color.parseColor("#FFD600")
        tp.isFakeBoldText = true
        canvas.drawText("UPGRADES", cx, divY - 8f, tp)
        tp.isFakeBoldText = false

        val rects  = listOf(upMagnetRect, upShieldRect, upSlowRect, upScoreRect)
        val icons  = listOf("🧲", "🛡", "⏳", "✨")
        val labels = listOf("MAGNET", "SHIELD", "SLOW", "SCORE")

        rects.forEachIndexed { i, rect ->
            val level = upgradeLevels.getOrElse(i) { 0 }
            val maxed = level >= maxLevel

            // Background chip
            chipBgPaint.color = if (maxed) Color.argb(180, 0, 60, 0) else Color.argb(160, 20, 20, 50)
            canvas.drawRoundRect(rect, 16f, 16f, chipBgPaint)
            chipBorderP.strokeWidth = 2f
            chipBorderP.color = if (maxed) Color.parseColor("#00E676") else Color.parseColor("#5C6BC0")
            canvas.drawRoundRect(rect, 16f, 16f, chipBorderP)

            // Icon + label
            tp.textAlign = Paint.Align.CENTER
            tp.textSize  = 32f
            tp.color     = Color.WHITE
            canvas.drawText("${icons[i]} ${labels[i]}", rect.centerX(), rect.top + 32f, tp)

            // Level pips
            val pipW   = (rect.width() - 20f) / maxLevel
            val pipY   = rect.top + 46f
            for (pip in 0 until maxLevel) {
                val px = rect.left + 10f + pip * pipW
                chipBgPaint.color = if (pip < level) Color.parseColor("#FFD600") else Color.argb(80, 255, 255, 255)
                canvas.drawRoundRect(px, pipY, px + pipW - 3f, pipY + 12f, 4f, 4f, chipBgPaint)
            }

            // Cost / MAX label
            tp.textSize = 28f
            tp.color = when {
                maxed -> Color.parseColor("#00E676")
                totalCoins >= costs.getOrElse(i) { 999 } -> Color.WHITE
                else  -> Color.parseColor("#EF5350")
            }
            val costTxt = if (maxed) "MAX" else "${costs.getOrElse(i) { 999 }} 🪙"
            canvas.drawText(costTxt, rect.centerX(), rect.bottom - 10f, tp)
        }
    }

    private fun skinColorForKey(key: String): Int = when (key) {
        "NINJA"  -> Color.parseColor("#00FF7F")
        "POLICE" -> Color.parseColor("#FFEA00")
        "FIRE"   -> Color.parseColor("#FF6600")
        "THIEF"  -> Color.parseColor("#CC00CC")
        else     -> Color.parseColor("#00F5FF")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Game Over card
    // ═══════════════════════════════════════════════════════════════════════════

    fun drawGameOver(canvas: Canvas) {
        val s  = engine.state
        val cx = screenW / 2f
        val cy = screenH / 2f

        drawDarkOverlay(canvas)

        // ── Card ─────────────────────────────────────────────────────────────
        cardBgPaint.color = Color.argb(210, 10, 10, 30)
        canvas.drawRoundRect(cx - 520f, cy - 280f, cx + 520f, cy + 320f, 40f, 40f, cardBgPaint)
        cardBorderP.strokeWidth = 3f
        cardBorderP.color       = Color.parseColor("#EF5350")
        canvas.drawRoundRect(cx - 520f, cy - 280f, cx + 520f, cy + 320f, 40f, 40f, cardBorderP)

        // ── Title row ────────────────────────────────────────────────────────
        tp.textAlign      = Paint.Align.CENTER
        tp.isFakeBoldText = true
        if (s.isNewHighScore) {
            tp.textSize = 52f
            tp.color    = Color.parseColor("#FFD600")
            canvas.drawText("🏆  NEW BEST!", cx, cy - 230f, tp)
        }
        tp.textSize = 90f
        tp.color    = Color.parseColor("#EF5350")
        canvas.drawText("GAME OVER", cx, cy - 150f, tp)

        // ── Horizontal divider ───────────────────────────────────────────────
        canvas.drawRect(cx - 400f, cy - 108f, cx + 400f, cy - 105f, divPaint)

        // ── Stats row: SCORE | BEST | COINS ──────────────────────────────────
        tp.isFakeBoldText = false
        tp.textSize       = 52f
        tp.color          = Color.WHITE
        canvas.drawText("${s.score}",      cx - 260f, cy - 40f, tp)
        canvas.drawText("${s.highScore}",  cx,        cy - 40f, tp)
        canvas.drawText("${s.runCoins}",   cx + 260f, cy - 40f, tp)

        tp.textSize = 34f
        tp.color    = Color.LTGRAY
        canvas.drawText("SCORE", cx - 260f, cy + 4f, tp)
        canvas.drawText("BEST",  cx,        cy + 4f, tp)
        canvas.drawText("COINS", cx + 260f, cy + 4f, tp)

        // Vertical dividers between stats
        canvas.drawRect(cx - 130f, cy - 68f, cx - 127f, cy + 12f, divPaint)
        canvas.drawRect(cx + 127f, cy - 68f, cx + 130f, cy + 12f, divPaint)

        // ── Action buttons ────────────────────────────────────────────────────
        drawButton(canvas, restartBtn, "▶ RETRY", Color.parseColor("#00C853"))
        drawButton(canvas, menuBtn,    "⌂ MENU",  Color.parseColor("#37474F"))

        // Revive (full-width, below the two main buttons)
        if (s.reviveAvailable) {
            drawButton(canvas, reviveRect,
                "💎 REVIVE  –  ${GameEngine.REVIVE_COST} 🪙",
                Color.parseColor("#7B1FA2"))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings card
    // ═══════════════════════════════════════════════════════════════════════════

    fun drawSettings(
        canvas:       Canvas,
        musicEnabled: Boolean,
        sfxEnabled:   Boolean,
        themeMode:    GameRenderer.ThemeMode
    ) {
        drawDarkOverlay(canvas)
        val cx = screenW / 2f
        val cy = screenH / 2f

        // ── Card ─────────────────────────────────────────────────────────────
        cardBgPaint.color = Color.argb(210, 8, 8, 26)
        canvas.drawRoundRect(cx - 600f, cy - 270f, cx + 600f, cy + 290f, 40f, 40f, cardBgPaint)
        cardBorderP.strokeWidth = 2f
        cardBorderP.color       = Color.argb(160, 0, 200, 255)
        canvas.drawRoundRect(cx - 600f, cy - 270f, cx + 600f, cy + 290f, 40f, 40f, cardBorderP)

        // ── Title ─────────────────────────────────────────────────────────────
        tp.textAlign      = Paint.Align.CENTER
        tp.textSize       = 80f
        tp.color          = Color.CYAN
        tp.isFakeBoldText = true
        canvas.drawText("⚙  SETTINGS", cx, cy - 200f, tp)
        tp.isFakeBoldText = false

        // ── Back button ───────────────────────────────────────────────────────
        drawButton(canvas, settingsBackRect, "< BACK", Color.DKGRAY)

        // ── Music + SFX (side by side) ────────────────────────────────────────
        drawButton(canvas, musicToggleRect,
            if (musicEnabled) "🎵  MUSIC: ON"  else "🔇  MUSIC: OFF",
            if (musicEnabled) Color.parseColor("#1565C0") else Color.DKGRAY)

        drawButton(canvas, sfxToggleRect,
            if (sfxEnabled)   "🔊  SFX: ON"    else "🔇  SFX: OFF",
            if (sfxEnabled)   Color.parseColor("#1565C0") else Color.DKGRAY)

        // ── Theme (side by side) ──────────────────────────────────────────────
        val isDay   = themeMode == GameRenderer.ThemeMode.DAY
        val isNight = themeMode == GameRenderer.ThemeMode.NIGHT
        drawButton(canvas, themeDayRect,
            "☀  DAY",    if (isDay)   Color.parseColor("#F9A825") else Color.DKGRAY)
        drawButton(canvas, themeNightRect,
            "🌙  NIGHT", if (isNight) Color.parseColor("#283593") else Color.DKGRAY)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Pause card
    // ═══════════════════════════════════════════════════════════════════════════

    fun drawPaused(canvas: Canvas) {
        val s  = engine.state
        val cx = screenW / 2f
        val cy = screenH / 2f

        drawDarkOverlay(canvas)

        // ── Card ─────────────────────────────────────────────────────────────
        cardBgPaint.color = Color.argb(210, 5, 5, 20)
        canvas.drawRoundRect(cx - 400f, cy - 200f, cx + 400f, cy + 220f, 40f, 40f, cardBgPaint)
        cardBorderP.strokeWidth = 2f
        cardBorderP.color       = Color.argb(160, 80, 180, 255)
        canvas.drawRoundRect(cx - 400f, cy - 200f, cx + 400f, cy + 220f, 40f, 40f, cardBorderP)

        // ── Title + sub-score ─────────────────────────────────────────────────
        tp.textAlign      = Paint.Align.CENTER
        tp.textSize       = 84f
        tp.color          = Color.WHITE
        tp.isFakeBoldText = true
        canvas.drawText("⏸  PAUSED", cx, cy - 110f, tp)
        tp.isFakeBoldText = false

        tp.textSize = 36f
        tp.color    = Color.LTGRAY
        canvas.drawText("SCORE: ${s.score}", cx, cy - 58f, tp)

        // ── Buttons ───────────────────────────────────────────────────────────
        drawButton(canvas, resumeBtn,     "▶ RESUME", Color.parseColor("#00C853"))
        drawButton(canvas, pausedMenuBtn, "⌂ MENU",   Color.parseColor("#37474F"))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Shared utility: dark scene overlay
    // ═══════════════════════════════════════════════════════════════════════════

    fun drawDarkOverlay(canvas: Canvas) {
        canvas.drawColor(Color.argb(220, 10, 10, 20))
    }

    // Flash overlay (red hit flash — drawn AFTER all UI layers)
    fun drawFlashOverlay(canvas: Canvas) {
        val s = engine.state
        if (s.flashTimer <= 0f) return
        val alpha = (s.flashTimer * 800f).toInt().coerceIn(0, 180)
        canvas.drawColor(Color.argb(alpha, 255, 0, 0))
    }

    // Death darkness overlay (drawn over everything when GAME_OVER)
    fun drawDeathOverlay(canvas: Canvas) {
        val s = engine.state
        if (s.deathDarkness <= 0f) return
        canvas.drawColor(Color.argb((s.deathDarkness * 255).toInt(), 0, 0, 0))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // drawButton — the only method that may allocate, and only on cache miss
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Draws a rounded-rectangle button using a cached [Bitmap] background.
     *
     * Cache key = [btnColor] XOR ([width] SHL 8) XOR [height].
     * On a cache miss a new [Bitmap] is created and painted once (gradient fill,
     * shine strip, white border). All subsequent calls for the same key are a
     * single [Canvas.drawBitmap] — no shader objects are created at runtime.
     *
     * The label is drawn on top with [tp] (shared text paint).
     */
    fun drawButton(canvas: Canvas, rect: RectF, label: String, btnColor: Int) {
        val radius = 28f
        val w  = rect.width().toInt().coerceAtLeast(1)
        val h  = rect.height().toInt().coerceAtLeast(1)
        val key = btnColor xor (w shl 8) xor h

        val bg = btnBitmapCache.getOrPut(key) {
            // ── One-time bake ────────────────────────────────────────────────
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
                val c  = Canvas(bmp)
                val rF = RectF(0f, 0f, w.toFloat(), h.toFloat())

                // Base: vertical gradient from lightened top to darkened bottom
                val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        0f, 0f, 0f, h.toFloat(),
                        lighten(btnColor, 0.40f),
                        darken(btnColor,  0.30f),
                        Shader.TileMode.CLAMP
                    )
                }
                c.drawRoundRect(rF, radius, radius, basePaint)

                // Shine strip: semi-transparent white, covers top 45 %
                val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        0f, 0f, 0f, h * 0.45f,
                        Color.argb(140, 255, 255, 255),
                        Color.argb(0,   255, 255, 255),
                        Shader.TileMode.CLAMP
                    )
                }
                c.drawRoundRect(RectF(4f, 4f, w - 4f, h * 0.5f), radius, radius, shinePaint)

                // Border: translucent white stroke
                val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style       = Paint.Style.STROKE
                    strokeWidth = 2.5f
                    color       = Color.WHITE
                    alpha       = 110
                }
                c.drawRoundRect(rF, radius, radius, borderPaint)
            }
        }

        // Draw cached background scaled to the rect
        canvas.drawBitmap(bg, null, rect, null)

        // Label centred on the button with optical vertical centring
        tp.textAlign      = Paint.Align.CENTER
        tp.textSize       = 48f
        tp.color          = Color.WHITE
        tp.isFakeBoldText = true
        val tcy = rect.centerY() - (tp.fontMetrics.ascent + tp.fontMetrics.descent) / 2f
        canvas.drawText(label, rect.centerX(), tcy, tp)
        tp.isFakeBoldText = false
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Colour math helpers  (pure functions — no allocations)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun lighten(color: Int, factor: Float): Int {
        val r = min(255, ((Color.red(color)   * (1f - factor)) + 255f * factor).toInt())
        val g = min(255, ((Color.green(color) * (1f - factor)) + 255f * factor).toInt())
        val b = min(255, ((Color.blue(color)  * (1f - factor)) + 255f * factor).toInt())
        return Color.rgb(r, g, b)
    }

    private fun darken(color: Int, factor: Float): Int {
        val r = max(0, (Color.red(color)   * (1f - factor)).toInt())
        val g = max(0, (Color.green(color) * (1f - factor)).toInt())
        val b = max(0, (Color.blue(color)  * (1f - factor)).toInt())
        return Color.rgb(r, g, b)
    }
}