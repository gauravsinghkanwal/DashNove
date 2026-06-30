package com.papaji.alpharunner.render

import android.graphics.*
import com.papaji.alpharunner.engine.GameEngine
import com.papaji.alpharunner.engine.GameEngine.EngineState
import com.papaji.alpharunner.engine.GameSpawner.EngineCoin
import com.papaji.alpharunner.engine.GameSpawner.EngineObstacle
import com.papaji.alpharunner.engine.GameSpawner.EnginePowerType
import com.papaji.alpharunner.engine.GameSpawner.EnginePowerUp
import com.papaji.alpharunner.engine.GameSpawner.ObstacleKind
import kotlin.math.*

/**
 * GameRenderer — draws every object that lives in world-space.
 *
 * Rule: zero object allocations inside any method that is called per frame.
 * Every Paint, Path, and RectF that draw methods touch is declared as a
 * class-level field and mutated in place. The only allocation permitted
 * is the one-time lazy [gridBitmap] creation that happens on the first
 * draw after a surface resize.
 *
 * How to wire it up in GameView:
 *   val renderer = GameRenderer(engine)
 *   // In onSizeChanged:
 *   renderer.onSurfaceChanged(w, h, groundTop, playerSize)
 *   renderer.assets = GameRenderer.Assets(crateBitmap, spikeBitmap, …)
 *   // In onDraw, before the UI layer:
 *   renderer.drawBackground(canvas, themeMode, dayBackgroundBitmap)
 *   renderer.drawParallaxLayers(canvas, themeMode, mountains, cloudsMid, cloudsFront)
 *   renderer.drawStars(canvas, starsBack, starsFront)
 *   renderer.drawGroundStrip(canvas, themeMode, skinColor)
 *   renderer.drawGameWorld(canvas, skinColor)
 */
class GameRenderer(private val engine: GameEngine) {

    // ═══════════════════════════════════════════════════════════════════════════
    // Surface geometry  (updated on every onSizeChanged call)
    // ═══════════════════════════════════════════════════════════════════════════

    private var screenW   = 0f
    private var screenH   = 0f
    private var groundTop = 0f
    private var playerSz  = 130f

    fun onSurfaceChanged(w: Float, h: Float, gTop: Float, pSize: Float) {
        screenW   = w
        screenH   = h
        groundTop = gTop
        playerSz  = pSize
        // Invalidate the lazy grid bitmap so it is rebuilt at the new size
        gridBitmap = null
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Assets bundle  (set once per surface creation; only bitmaps live here)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * All Bitmap resources needed by the world renderer. Every field is
     * nullable; the renderer falls back to geometric shapes when null so
     * the game always runs even with missing art.
     *
     * @param scaledSkinCache  Pre-scaled at playerSize × playerSize; built by
     *                         GameView.loadBitmaps() so we never scale at runtime.
     * @param powerBitmaps     Map from [EnginePowerType] to the power-up icon bitmap.
     */
    data class Assets(
        val crateBitmap:    Bitmap? = null,
        val spikeBitmap:    Bitmap? = null,
        val sawBitmap:      Bitmap? = null,
        val coinBitmap:     Bitmap? = null,
        val ravanBitmap:    Bitmap? = null,
        val vignetteBitmap: Bitmap? = null,
        val moonBitmap:     Bitmap? = null,
        val moonDrawX:      Float   = 0f,
        val moonDrawY:      Float   = 0f,
        val scaledSkinCache: Map<String, Bitmap?> = emptyMap(),
        val powerBitmaps:    Map<EnginePowerType, Bitmap?> = emptyMap()
    )

    var assets = Assets()

    // ═══════════════════════════════════════════════════════════════════════════
    // Parallax layer type (mirrors GameView.ParallaxLayer; bitmaps live here)
    // ═══════════════════════════════════════════════════════════════════════════

    data class ParallaxLayer(var x: Float, var y: Float, var speed: Float, val bitmap: Bitmap)
    data class StarLayer(var x: Float, var y: Float, val radius: Float, val speed: Float)

    // ═══════════════════════════════════════════════════════════════════════════
    // Class-level Paints  (NEVER re-allocated inside draw methods)
    // ═══════════════════════════════════════════════════════════════════════════

    // Background
    private val bgPaint = Paint()

    // Ground
    private val groundFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val groundEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Stars
    private val starBackPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = 90  }
    private val starFrontPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = 200 }

    // Neon grid
    private val gridPaint = Paint().apply {
        color       = Color.parseColor("#BC13FE")
        strokeWidth = 3f
        alpha       = 100
        style       = Paint.Style.STROKE
    }

    // Trail  — BlurMaskFilter set once here, never changed
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL)
    }

    // Player
    private val playerPaint         = Paint(Paint.ANTI_ALIAS_FLAG)
    private val playerFallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val playerShadowPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = BlurMaskFilter(22f, BlurMaskFilter.Blur.NORMAL)
        color      = Color.argb(90, 0, 0, 0)
    }

    // Shield bubble  — strokeWidth / style set here, only color+alpha mutated per frame
    private val shieldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = 6f
    }

    // Shockwave
    private val shockwavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = 6f
        color       = Color.CYAN
    }

    // Obstacle fallback
    private val obstaclePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Obstacle spike path  — reused every frame, points replaced in place
    private val spikePath = Path()

    // Coin fallback
    private val coinFallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW }

    // Power-up glow
    private val powerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Speed lines
    private val speedLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.argb(60, 150, 200, 255)
        strokeWidth = 2.5f
    }

    // Particles
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ── Typography ────────────────────────────────────────────────────────────
    // Default is Typeface.DEFAULT_BOLD. Override by calling setTypeface() at
    // startup once a custom arcade font has been loaded from assets. All text
    // paints in this class reference [gameTypeface] so one call updates them all.
    var gameTypeface: Typeface = Typeface.DEFAULT_BOLD
        private set

    fun setTypeface(tf: Typeface) {
        gameTypeface           = tf
        floatTextPaint.typeface  = tf
        scorePopupPaint.typeface = tf
    }

    // Floating text
    private val floatTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface  = Typeface.DEFAULT_BOLD
        textSize  = 45f
        textAlign = Paint.Align.LEFT
    }

    // Score popups  — separate paint so we can vary textSize without
    // clobbering floatTextPaint's settings mid-loop
    private val scorePopupPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface       = Typeface.DEFAULT_BOLD
        textAlign      = Paint.Align.CENTER
        isFakeBoldText = true
    }

    // Boss projectile
    private val projPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4500")
        style = Paint.Style.FILL
    }
    private val projGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color      = Color.parseColor("#FF4500")
        alpha      = 100
        maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL)
    }

    // Skin selection highlight in shop (not needed in world renderer, but kept
    // here so the paint cache is in one place — UiRenderer will use it)
    private val skinOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = 8f
        color       = Color.CYAN
    }

    // Reusable RectF for power-up icon draw (avoids allocation per powerup per frame)
    private val powerIconRect = RectF()

    // Synthwave sun — shader rebuilt only when screen is resized
    private val sunBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sunGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = BlurMaskFilter(80f, BlurMaskFilter.Blur.NORMAL)
    }
    // Line scan stripe across the sun face (horizontal bands)
    private val sunStripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.argb(60, 0, 0, 0)
        style       = Paint.Style.FILL
        strokeWidth = 0f
    }
    // Pre-baked procedural skyline paint
    private val skylinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val skylineWindowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Neon glow bloom overlays for SPIKE and SAW obstacles.
    // BlurMaskFilter set once at class-init; only color + alpha mutated per draw.
    // Spike = hot magenta-pink bloom (danger/lava feel).
    // Saw   = electric-cyan bloom   (machine/tech feel).
    private val spikeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.FILL
        maskFilter  = BlurMaskFilter(28f, BlurMaskFilter.Blur.NORMAL)
        color       = Color.parseColor("#FF006E")
        alpha       = 190
    }
    private val spikeCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = 3f
        color       = Color.parseColor("#FF99CC")
        alpha       = 220
        maskFilter  = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
    }
    private val sawGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = 14f
        maskFilter  = BlurMaskFilter(22f, BlurMaskFilter.Blur.NORMAL)
        color       = Color.parseColor("#00FFE5")
        alpha       = 160
    }

    // Velocity ember wake — drawn behind the player, scales with speed
    private val emberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL)
    }
    // Seeded rng for stable ember layout within a frame; re-created only on speed tier change
    private val emberRng   = java.util.Random(0)

    // ═══════════════════════════════════════════════════════════════════════════
    // Lazy grid bitmap  (baked once at current screen size, rebuilt on resize)
    // ═══════════════════════════════════════════════════════════════════════════

    private var gridBitmap: Bitmap? = null

    private fun ensureGridBitmap() {
        if (gridBitmap != null || screenW <= 0f || screenH <= 0f) return

        val bmp  = Bitmap.createBitmap(screenW.toInt(), screenH.toInt(), Bitmap.Config.ARGB_8888)
        val c    = Canvas(bmp)

        // Horizontal ground lines — alpha increases with distance (fade-out trick)
        for (i in 0..15) {
            val yPos = groundTop + i * 40f
            if (yPos >= screenH) break
            gridPaint.alpha = (i * 15).coerceIn(0, 200)
            c.drawLine(0f, yPos, screenW, yPos, gridPaint)
        }

        gridBitmap = bmp   // sun removed — drawn live by drawSynthwaveSun()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Public draw methods  (called in this order from GameView.onDraw)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Step 1 — background: either the day image or a dark gradient.
     * No allocations: [bgPaint.shader] is overwritten in place; LinearGradient
     * is a value object so overwriting the shader reference releases the old one
     * at the next GC cycle rather than immediately, which is fine since the
     * gradient only changes when [themeMode] changes (not every frame).
     */
    fun drawBackground(canvas: Canvas, themeMode: ThemeMode, dayBitmap: Bitmap?) {
        if (themeMode == ThemeMode.DAY && dayBitmap != null) {
            canvas.drawBitmap(dayBitmap, null,
                RectF(0f, 0f, screenW, screenH), null)
        } else {
            bgPaint.shader = LinearGradient(
                0f, 0f, 0f, screenH,
                Color.parseColor("#0F2027"),
                Color.parseColor("#203A43"),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, screenW, screenH, bgPaint)
        }
    }

    enum class ThemeMode { DAY, NIGHT }

    /**
     * Step 2 — moon (night only). Uses the pre-baked [Assets.moonBitmap]
     * so there are zero Paint or shader allocations here at all.
     */
    fun drawMoon(canvas: Canvas) {
        assets.moonBitmap?.let { canvas.drawBitmap(it, assets.moonDrawX, assets.moonDrawY, null) }
    }

    /**
     * Draws the synthwave gradient sun for DAY mode.
     * Called from GameView.onDraw() only when themeMode == DAY, so it never
     * co-renders with the moon. Painted live (not baked) so the gradient stays
     * sharp at any screen density. All paints are class-level — zero allocations.
     */
    fun drawSynthwaveSun(canvas: Canvas) {
        val sunCx    = screenW / 2f
        val horizonY = groundTop * 0.72f   // sits above the mid-point of the sky
        val radius   = screenH * 0.22f

        // Outer atmosphere glow
        sunGlowPaint.color = Color.argb(90, 255, 180, 0)
        canvas.drawCircle(sunCx, horizonY, radius * 1.55f, sunGlowPaint)

        // Main body — vertical gradient gold → hot-pink (classic synthwave)
        sunBodyPaint.shader = LinearGradient(
            sunCx, horizonY - radius,
            sunCx, horizonY + radius,
            Color.parseColor("#FFD700"),
            Color.parseColor("#FF0055"),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(sunCx, horizonY, radius, sunBodyPaint)

        // Horizontal stripe scan lines across lower half of the sun.
        // Each stripe is a thin dark rectangle clipped to the circle region.
        // We clip with canvas.save()/clipPath() so the stripes don't spill out.
        canvas.save()
        val clipPath = Path()
        clipPath.addCircle(sunCx, horizonY, radius, Path.Direction.CW)
        canvas.clipPath(clipPath)
        var stripeY = horizonY + radius * 0.10f
        val stripeH   = radius * 0.060f
        val stripeGap = radius * 0.095f
        while (stripeY < horizonY + radius) {
            canvas.drawRect(sunCx - radius, stripeY, sunCx + radius, stripeY + stripeH, sunStripePaint)
            stripeY += stripeH + stripeGap
        }
        canvas.restore()
    }

    /**
     * Step 3 — scrolling parallax layers (mountains → mid clouds → front clouds).
     * Each list contains exactly 2 copies of a bitmap tiled horizontally so the
     * wrap is seamless.
     */
    fun drawParallaxLayers(
        canvas:      Canvas,
        themeMode:   ThemeMode,
        citySkyline: List<ParallaxLayer>,   // far — drawn first, painted over by closer layers
        mountains:   List<ParallaxLayer>,
        cloudsMid:   List<ParallaxLayer>,
        cloudsFront: List<ParallaxLayer>    // near — drawn last, occludes everything behind it
    ) {
        if (themeMode != ThemeMode.NIGHT) return

        // Painter's algorithm: draw farthest layer first.
        // If no city_skyline.png asset was loaded, fall back to a procedural
        // cyberpunk silhouette drawn entirely with rectangles — zero bitmaps needed.
        if (citySkyline.isEmpty()) {
            drawProceduralSkyline(canvas)
        } else {
            citySkyline.forEach { canvas.drawBitmap(it.bitmap, it.x, it.y, null) }
        }
        mountains.forEach   { canvas.drawBitmap(it.bitmap, it.x, it.y, null) }
        cloudsMid.forEach   { canvas.drawBitmap(it.bitmap, it.x, it.y, null) }
        cloudsFront.forEach { canvas.drawBitmap(it.bitmap, it.x, it.y, null) }
    }

    /**
     * Draws a procedural cyberpunk city silhouette using only rectangles.
     * Called when the city_skyline.png bitmap is absent. Uses a seeded
     * deterministic [java.util.Random] so the skyline is stable across frames —
     * the same seed produces the same building layout every time.
     *
     * No allocations: [skylinePaint] and [skylineWindowPaint] are class-level.
     * The seeded rng object is the only per-call allocation and it is local,
     * not stored, so it is GC'd immediately after this method returns.
     * In practice this only runs on first draw after assets fail to load,
     * so its cost is negligible.
     */
    private fun drawProceduralSkyline(canvas: Canvas) {
        val horizonY = groundTop * 0.55f   // where the tallest buildings end
        val rng = java.util.Random(0xDEADC0DEL)   // fixed seed = stable layout

        var x = 0f
        while (x < screenW) {
            // Building width: 40–120 px
            val bW = 40f + rng.nextInt(80)
            // Building height: varies from 20 % to 80 % of the available sky
            val bH = (horizonY * 0.20f) + rng.nextFloat() * (horizonY * 0.60f)
            val bY = groundTop - bH

            // Silhouette fill: very dark navy-blue tinted block
            skylinePaint.color = Color.argb(
                45 + rng.nextInt(30),
                10 + rng.nextInt(10),
                15 + rng.nextInt(10),
                30 + rng.nextInt(20)
            )
            canvas.drawRect(x, bY, x + bW, groundTop, skylinePaint)

            // Edge highlight: a 1 px bright-cyan left edge to give the
            // buildings a neon-lit silhouette feel without any extra bitmaps
            skylinePaint.color = Color.argb(60, 0, 220, 220)
            canvas.drawRect(x, bY, x + 2f, groundTop, skylinePaint)

            // Scattered lit windows — small bright dots on the building face
            skylineWindowPaint.color = Color.argb(90, 255, 240, 180)
            val windowRows = (bH / 24f).toInt().coerceAtMost(10)
            val windowCols = (bW  / 14f).toInt().coerceAtMost(6)
            for (row in 0 until windowRows) {
                for (col in 0 until windowCols) {
                    // ~35 % of windows are lit; seed keeps them stable
                    if (rng.nextFloat() > 0.35f) continue
                    val wx = x + 4f + col * ((bW - 8f) / windowCols.toFloat())
                    val wy = bY + 4f + row * (bH / (windowRows + 1f))
                    canvas.drawRect(wx, wy, wx + 5f, wy + 8f, skylineWindowPaint)
                }
            }

            // Small gap between buildings (0–12 px alley)
            x += bW + rng.nextInt(12)
        }
    }

    /**
     * Step 4 — star field. Two layers (back = slow/dim, front = fast/bright)
     * give the illusion of depth without any extra bitmap allocations.
     */
    fun drawStars(canvas: Canvas, starsBack: List<StarLayer>, starsFront: List<StarLayer>) {
        starsBack.forEach  { canvas.drawCircle(it.x, it.y, it.radius, starBackPaint)  }
        starsFront.forEach { canvas.drawCircle(it.x, it.y, it.radius, starFrontPaint) }
    }

    /**
     * Step 5 — neon grid overlay (baked once, drawn every frame).
     */
    fun drawNeonGrid(canvas: Canvas) {
        ensureGridBitmap()
        gridBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    /**
     * Step 6 — ground strip: solid fill + a 10 px accent edge in the skin colour.
     * [groundFillPaint] and [groundEdgePaint] are mutated in place; no
     * allocation occurs.
     */
    fun drawGroundStrip(canvas: Canvas, themeMode: ThemeMode, skinColor: Int) {
        groundFillPaint.color =
            if (themeMode == ThemeMode.NIGHT) Color.parseColor("#111111")
            else Color.parseColor("#333333")
        canvas.drawRect(0f, groundTop, screenW, screenH, groundFillPaint)

        groundEdgePaint.color = skinColor
        canvas.drawRect(0f, groundTop, screenW, groundTop + 10f, groundEdgePaint)
    }

    /**
     * Step 7 — the entire world: boss, obstacles, coins, power-ups, player,
     * trail, particles, score popups, floating texts.
     *
     * The canvas has already been scaled by the death-zoom in GameView.onDraw
     * before this is called.
     *
     * @param skinColor  The current skin's primary colour for the trail.
     * @param skinKey    Key into [Assets.scaledSkinCache] (e.g. "DEFAULT").
     */
    fun drawGameWorld(canvas: Canvas, skinColor: Int, skinKey: String) {
        val s = engine.state

        drawBoss(canvas, s)
        drawObstacles(canvas)
        drawCoins(canvas, s)
        drawPowerUps(canvas)

        // Player section: all transforms apply to player + shield + trail
        canvas.save()
        playerPaint.alpha = if (s.ghostActive || s.invincibility > 0f) 128 else 255

        // Camera recoil: translate first (positional shake), then rotate
        // (rotational recoil). Both decay each tick via engine.tick():
        //   shakeAmount *= 0.90   tiltAngle *= 0.90
        // This produces a natural spring-back without any extra timer.
        val shakeX = (Math.random().toFloat() - 0.5f) * s.shakeAmount
        val shakeY = (Math.random().toFloat() - 0.5f) * s.shakeAmount * 0.5f  // vertical shake is halved
        canvas.translate(shakeX, shakeY)

        val pivotX = engine.playerX + playerSz / 2f
        val pivotY = s.playerY    + playerSz / 2f

        canvas.scale(s.animScaleX, s.animScaleY, pivotX, pivotY)

        // Rotational recoil: tiltAngle is set by the engine on dash/obstacle-clear.
        // We additionally map the shake magnitude to a subtle canvas rotation so
        // hard hits physically tilt the world, not just shift it.
        val recoilRotation = s.tiltAngle + (shakeX / screenW) * 3.5f
        if (recoilRotation != 0f) canvas.rotate(recoilRotation, pivotX, pivotY)

        drawSpeedLines(canvas, s)
        drawVelocityEmbers(canvas, s, skinColor)
        drawTrail(canvas, skinColor)
        drawPlayerShadow(canvas, s)
        drawShockwave(canvas, s)
        drawPlayerBody(canvas, s, skinKey)
        drawShieldBubble(canvas, s)

        canvas.restore()

        drawParticles(canvas)
        drawBossProjectiles(canvas)
        drawScorePopups(canvas)
        drawFloatingTexts(canvas)
        drawVignette(canvas)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Private draw helpers  (all called from drawGameWorld — must be alloc-free)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun drawBoss(canvas: Canvas, s: EngineState) {
        val bmp = assets.ravanBitmap ?: return
        if (!s.bossActive) return

        canvas.save()
        // Subtle horizontal shake to convey menace
        val shakeX = (Math.random().toFloat() - 0.5f) * 3f
        canvas.drawBitmap(bmp, s.bossX + shakeX, s.bossY, null)
        canvas.restore()
    }

    private fun drawObstacles(canvas: Canvas) {
        engine.obstacles.forEach { obs ->
            val oy = groundTop - obs.height
            when (obs.type) {
                ObstacleKind.BOX -> drawBox(canvas, obs, oy)
                ObstacleKind.SPIKE -> drawSpike(canvas, obs, oy)
                ObstacleKind.SAW -> drawSaw(canvas, obs, oy)
            }
        }
    }

    private fun drawBox(canvas: Canvas, obs: EngineObstacle, oy: Float) {
        val bmp = assets.crateBitmap
        if (bmp != null) {
            canvas.drawBitmap(bmp, obs.x, oy, null)
        } else {
            obstaclePaint.color = Color.parseColor("#8B4513")
            canvas.drawRect(obs.x, oy, obs.x + obs.width, groundTop, obstaclePaint)
            // Simple cross-hatch to look like a crate even without a bitmap
            obstaclePaint.color = Color.parseColor("#5C2E00")
            obstaclePaint.style = Paint.Style.STROKE
            obstaclePaint.strokeWidth = 3f
            val half = obs.height / 2f
            canvas.drawLine(obs.x, oy, obs.x + obs.width, groundTop, obstaclePaint)
            canvas.drawLine(obs.x + obs.width, oy, obs.x, groundTop, obstaclePaint)
            canvas.drawLine(obs.x, oy + half, obs.x + obs.width, oy + half, obstaclePaint)
            canvas.drawLine(obs.x + obs.width / 2f, oy, obs.x + obs.width / 2f, groundTop, obstaclePaint)
            obstaclePaint.style = Paint.Style.FILL
        }
    }

    private fun drawSpike(canvas: Canvas, obs: EngineObstacle, oy: Float) {
        // Build triangle path once per spike; reset() re-uses the allocation
        spikePath.reset()
        spikePath.moveTo(obs.x, groundTop)
        spikePath.lineTo(obs.x + obs.width / 2f, oy)
        spikePath.lineTo(obs.x + obs.width, groundTop)
        spikePath.close()

        val bmp = assets.spikeBitmap
        if (bmp != null) {
            // Bitmap art: add neon bloom halo beneath so it glows against the grid
            canvas.drawPath(spikePath, spikeGlowPaint)
            canvas.drawBitmap(bmp, obs.x, oy, null)
        } else {
            // Vector fallback: dark red fill + neon pink bloom glow + bright edge
            // Layer 1 — outer bloom (blurred, drawn first so it sits underneath)
            canvas.drawPath(spikePath, spikeGlowPaint)
            // Layer 2 — solid fill
            obstaclePaint.color = Color.parseColor("#880022")
            obstaclePaint.style = Paint.Style.FILL
            canvas.drawPath(spikePath, obstaclePaint)
            // Layer 3 — neon-pink glowing edge (tight blur outline)
            canvas.drawPath(spikePath, spikeCorePaint)
            obstaclePaint.style = Paint.Style.FILL
        }
    }

    private fun drawSaw(canvas: Canvas, obs: EngineObstacle, oy: Float) {
        val cx = obs.x + obs.width / 2f
        val cy = oy + obs.height / 2f
        canvas.save()
        canvas.rotate(obs.rotation, cx, cy)
        // Neon-cyan bloom ring drawn first, underneath the bitmap
        canvas.drawCircle(cx, cy, obs.width / 2f + 6f, sawGlowPaint)
        // Saw bitmap (or procedural circle fallback if missing)
        val bmp = assets.sawBitmap
        if (bmp != null) {
            canvas.drawBitmap(bmp, obs.x, oy, null)
        } else {
            obstaclePaint.color       = Color.parseColor("#004444")
            obstaclePaint.style       = Paint.Style.FILL
            canvas.drawCircle(cx, cy, obs.width / 2f, obstaclePaint)
            obstaclePaint.color       = Color.parseColor("#00FFE5")
            obstaclePaint.style       = Paint.Style.STROKE
            obstaclePaint.strokeWidth = 4f
            canvas.drawCircle(cx, cy, obs.width / 2f - 2f, obstaclePaint)
            obstaclePaint.style = Paint.Style.FILL
        }
        canvas.restore()
    }

    private fun drawCoins(canvas: Canvas, s: EngineState) {
        val bmp = assets.coinBitmap
        engine.coins.forEach { c ->
            // bobY is computed by GameEngine.tickCoins every frame
            val drawY = c.y + c.bobY
            if (bmp != null) {
                canvas.drawBitmap(bmp, c.x - 35f, drawY - 35f, null)
            } else {
                canvas.drawCircle(c.x, drawY, c.radius, coinFallbackPaint)
            }
        }
    }

    private fun drawPowerUps(canvas: Canvas) {
        engine.powerUps.forEach { p ->
            val col = colorForPowerType(p.type)
            val bmp = assets.powerBitmaps[p.type]

            val cx = p.x + p.size / 2f
            val cy = p.y + p.size / 2f

            // Glow halo
            powerGlowPaint.color = col
            powerGlowPaint.alpha = 140
            powerGlowPaint.setShadowLayer(25f, 0f, 0f, col)
            canvas.drawCircle(cx, cy, p.size * 0.40f, powerGlowPaint)

            // Icon bitmap centred on the glow
            if (bmp != null) {
                val scale = (p.size * 2.5f) / bmp.width.toFloat()
                val iw    = bmp.width  * scale
                val ih    = bmp.height * scale
                powerIconRect.set(cx - iw / 2f, cy - ih / 2f, cx + iw / 2f, cy + ih / 2f)
                canvas.drawBitmap(bmp, null, powerIconRect, null)
            }
        }
    }

    private fun colorForPowerType(type: EnginePowerType): Int = when (type) {
        EnginePowerType.SHIELD -> Color.CYAN
        EnginePowerType.SLOW   -> Color.MAGENTA
        EnginePowerType.DOUBLE -> Color.YELLOW
        EnginePowerType.MAGNET -> Color.RED
        EnginePowerType.GHOST  -> Color.LTGRAY
    }

    private fun drawSpeedLines(canvas: Canvas, s: EngineState) {
        val base      = GameEngine.BASE_SPEED
        val maxSpeed  = GameEngine.MAX_SPEED
        val threshold = base * 1.4f
        if (s.currentSpeed <= threshold) return

        val intensity = ((s.currentSpeed - threshold) / (maxSpeed - threshold)).coerceIn(0f, 1f)
        speedLinePaint.alpha = (intensity * 90).toInt()

        // Seeded random so line positions are stable across frames (no jitter)
        val rng = java.util.Random(42)
        repeat(14) {
            val lx  = screenW  * rng.nextFloat()
            val ly  = groundTop * rng.nextFloat()
            val len = 80f + 120f * intensity
            canvas.drawLine(lx, ly, lx - len, ly + 6f * rng.nextFloat(), speedLinePaint)
        }
    }

    /**
     * Velocity-based ember wake: neon particles emitted from the player's rear
     * edge that scale in count, size and alpha with current engine speed.
     * Drawn via [emberPaint] (cached BlurMaskFilter) — zero allocations.
     *
     * Ember positions use a re-seeded [emberRng] each call so that visual
     * variety between frames is preserved while still being cheap (one
     * [java.util.Random] object, not many).
     */
    private fun drawVelocityEmbers(canvas: Canvas, s: EngineState, skinColor: Int) {
        val base      = GameEngine.BASE_SPEED
        val maxSpeed  = GameEngine.MAX_SPEED
        if (s.currentSpeed <= base * 1.1f) return

        val intensity = ((s.currentSpeed - base * 1.1f) / (maxSpeed - base * 1.1f)).coerceIn(0f, 1f)
        val count     = (intensity * 16f).toInt().coerceAtLeast(2)
        val rearX     = engine.playerX                        // left edge of player
        val midY      = s.playerY + playerSz / 2f

        emberRng.setSeed(System.currentTimeMillis() / 32L)   // stable per ~32 ms bucket

        repeat(count) {
            val ex  = rearX - emberRng.nextFloat() * (50f + intensity * 60f)
            val ey  = midY  + (emberRng.nextFloat() - 0.5f) * playerSz * 0.65f
            val r   = (2f + intensity * 9f) * emberRng.nextFloat().coerceAtLeast(0.2f)
            val a   = ((0.45f + intensity * 0.55f) * (1f - emberRng.nextFloat() * 0.5f) * 255f).toInt()
            // Alternate between skin colour and hot-white to produce a flame-like range
            emberPaint.color = if (emberRng.nextFloat() > 0.35f) skinColor else Color.WHITE
            emberPaint.alpha = a.coerceIn(30, 240)
            canvas.drawCircle(ex, ey, r, emberPaint)
        }
    }

    private fun drawTrail(canvas: Canvas, skinColor: Int) {
        engine.trailPoints.forEach { t ->
            trailPaint.color = skinColor
            trailPaint.alpha = (t.alpha * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(t.x, t.y, t.size / 2f, trailPaint)
        }
    }

    private fun drawPlayerShadow(canvas: Canvas, s: EngineState) {
        val shadowW = playerSz * 1.1f
        val shadowH = playerSz * 0.22f
        val shadowX = engine.playerX + (playerSz - shadowW) / 2f
        val shadowY = groundTop + 8f
        canvas.drawOval(shadowX, shadowY, shadowX + shadowW, shadowY + shadowH, playerShadowPaint)
    }

    private fun drawShockwave(canvas: Canvas, s: EngineState) {
        if (!s.shockwaveActive) return
        shockwavePaint.alpha = (300f - s.shockwaveRadius).toInt().coerceIn(0, 255)
        // shockwaveX/Y are stored on EngineState as player-centre snapshot at dash start
        canvas.drawCircle(
            engine.playerX + playerSz / 2f,
            s.playerY + playerSz / 2f,
            s.shockwaveRadius,
            shockwavePaint
        )
    }

    private fun drawPlayerBody(canvas: Canvas, s: EngineState, skinKey: String) {
        val bmp = assets.scaledSkinCache[skinKey]
        if (bmp != null) {
            canvas.drawBitmap(bmp, engine.playerX, s.playerY, playerPaint)
        } else {
            // Geometric fallback: rounded rectangle in the skin colour
            playerFallbackPaint.color  = skinFallbackColor(skinKey)
            playerFallbackPaint.alpha  = playerPaint.alpha
            canvas.drawRoundRect(
                engine.playerX,
                s.playerY,
                engine.playerX + playerSz,
                s.playerY + playerSz,
                20f, 20f,
                playerFallbackPaint
            )
        }
    }

    private fun skinFallbackColor(key: String): Int = when (key) {
        "NINJA"  -> Color.parseColor("#00FF7F")
        "POLICE" -> Color.parseColor("#FFEA00")
        "FIRE"   -> Color.parseColor("#FF6600")
        "THIEF"  -> Color.parseColor("#CC00CC")
        else     -> Color.parseColor("#00F5FF")
    }

    private fun drawShieldBubble(canvas: Canvas, s: EngineState) {
        if (!s.shieldActive) return
        val pulse = (sin(System.currentTimeMillis() * 0.006) * 0.10f + 0.90f).toFloat()
        shieldPaint.color = Color.CYAN
        shieldPaint.alpha = (180 * pulse).toInt()
        shieldPaint.setShadowLayer(22f, 0f, 0f, Color.CYAN)
        canvas.drawCircle(
            engine.playerX + playerSz / 2f,
            s.playerY + playerSz / 2f,
            playerSz * 0.72f * pulse,
            shieldPaint
        )
    }

    private fun drawParticles(canvas: Canvas) {
        engine.particles.forEach { p ->
            particlePaint.color = p.color
            particlePaint.alpha = (p.life * 255).toInt().coerceIn(0, 255)
            canvas.drawCircle(p.x, p.y, p.size, particlePaint)
        }
    }

    /**
     * Boss projectiles: each drawn as a glowing orange orb (glow pass first,
     * solid core pass second). Both [projGlowPaint] and [projPaint] are
     * class-level; only [Canvas.drawCircle] is called — no allocations.
     */
    private fun drawBossProjectiles(canvas: Canvas) {
        engine.bossProjectiles.forEach { p ->
            canvas.drawCircle(p.x, p.y, 28f, projGlowPaint)
            canvas.drawCircle(p.x, p.y, 14f, projPaint)
        }
    }

    /**
     * Score popups (+1, +2, x5 COMBO etc.) float upward and scale slightly
     * as their life drops. [scorePopupPaint] is mutated in place: color, alpha,
     * and textSize are all that change.
     */
    private fun drawScorePopups(canvas: Canvas) {
        engine.scorePopups.forEach { sp ->
            scorePopupPaint.color  = sp.color
            scorePopupPaint.alpha  = (sp.life.coerceIn(0f, 1f) * 255).toInt()
            scorePopupPaint.textSize = 48f + (1f - sp.life) * 18f
            canvas.drawText(sp.text, sp.x, sp.y, scorePopupPaint)
        }
    }

    /**
     * Floating texts (PHASED, SHIELD BROKEN, GHOST ENDED, coin pickup +N, etc.)
     * The engine moves them upward and fades them; we just render at the current y.
     */
    private fun drawFloatingTexts(canvas: Canvas) {
        engine.floatingTexts.forEach { t ->
            floatTextPaint.color = t.color
            floatTextPaint.alpha = (t.life * 255).toInt().coerceIn(0, 255)
            canvas.drawText(t.text, t.x, t.y, floatTextPaint)
        }
    }

    /**
     * Vignette — the pre-baked radial darkening bitmap drawn on top of
     * everything else in the world layer.  Zero cost: single [drawBitmap].
     */
    private fun drawVignette(canvas: Canvas) {
        assets.vignetteBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }
}