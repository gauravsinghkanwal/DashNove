package com.papaji.alpharunner.engine

import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.RectF
import com.papaji.alpharunner.engine.GameSpawner.*
import com.papaji.alpharunner.sound.SoundManager
import kotlin.math.*
import kotlin.random.Random

/**
 * GameEngine — the single source of truth for all mutable simulation state.
 *
 * The engine is deliberately Android-free: it holds no View references, draws
 * nothing, and touches no Bitmaps. It communicates outward exclusively through
 * [GameEngineListener]. The renderer (GameView) reads the public [state] and
 * entity lists on every draw call.
 *
 * Threading: [tick] is called from the Choreographer callback, which runs on
 * the main thread. No additional synchronisation is needed.
 *
 * Usage:
 *   val engine = GameEngine(sound, listener, prefs)
 *   engine.onSurfaceChanged(w, h, groundTop, playerSize)
 *   engine.resetGame()
 *   // each Choreographer frame:
 *   engine.tick()
 */
class GameEngine(
    private val sound:    SoundManager,
    private val listener: GameEngineListener,
    private val prefs:    SharedPreferences
) {

    // ═══════════════════════════════════════════════════════════════════════════
    // Listener interface — GameView (or a test double) implements this
    // ═══════════════════════════════════════════════════════════════════════════

    interface GameEngineListener {
        /** Called when the game transitions to GAME_OVER. */
        fun onGameOver(isNewBest: Boolean, finalScore: Int)
        /** Called when a revive becomes affordable. */
        fun onReviveAvailable()
        /** Called when the boss spawns — View should switch background music. */
        fun onBossSpawned()
        /** Called when the boss is defeated — View should restore game music. */
        fun onBossDefeated()
        /** Trigger device vibration: "CRASH" = long, "BUMP" = short. */
        fun onShake(type: String)
        /** The score popup list was mutated — View re-reads [scorePopups]. */
        fun onNewScorePopup()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Surface / layout config  (set by GameView in onSizeChanged)
    // ═══════════════════════════════════════════════════════════════════════════

    var screenWidth:   Float = 0f; private set
    var screenHeight:  Float = 0f; private set
    var groundTop:     Float = 0f; private set
    var playerSize:    Float = 130f; private set
    val playerX:       Float = 150f   // horizontal position is fixed in a runner

    fun onSurfaceChanged(w: Float, h: Float, gTop: Float, pSize: Float) {
        screenWidth  = w
        screenHeight = h
        groundTop    = gTop
        playerSize   = pSize
        spawner.screenRef.width     = w
        spawner.screenRef.groundTop = gTop
    }

    /**
     * Called by GameView immediately after the ravan_boss Bitmap is scaled.
     * Keeps the physics hitbox in sync with the actual rendered size so the
     * collision rectangle the player fights matches what they see on screen.
     *
     * Guarded with coerceAtLeast so a corrupt/missing bitmap never produces
     * a zero-size hitbox that makes the boss unkillable.
     */
    fun onBossBitmapLoaded(w: Float, h: Float) {
        bossW = w.coerceAtLeast(40f)
        bossH = h.coerceAtLeast(40f)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Canonical game state  (read by renderer every frame)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Which animation loop the player character is currently in.
     * GameRenderer maps each state to a strip of bitmap frames.
     * When sprite sheets are not yet loaded, the renderer falls back
     * to the single static skin bitmap — so adding this enum is safe
     * before any art exists.
     */
    enum class SpriteState { RUN, JUMP, FALL, DASH, LAND }

    /** All primitive scalars are kept in one place so the renderer can pass a
     *  single reference to its draw methods rather than a dozen parameters. */
    data class EngineState(
        // ── Player ──────────────────────────────────────────────────────────
        var playerY:          Float   = 0f,
        var playerVelocityY:  Float   = 0f,
        var isJumping:        Boolean = false,
        var canDoubleJump:    Boolean = false,
        var jumpHoldTime:     Float   = 0f,
        var coyoteTime:       Float   = 0f,
        var isDashing:        Boolean = false,
        var dashTimer:        Float   = 0f,
        var invincibility:    Float   = 0f,
        var flashTimer:       Float   = 0f,
        var lastFallSpeed:    Float   = 0f,
        // squash-and-stretch
        var animScaleX:       Float   = 1f,
        var animScaleY:       Float   = 1f,
        var targetScaleX:     Float   = 1f,
        var targetScaleY:     Float   = 1f,
        var tiltAngle:        Float       = 0f,
        var shakeAmount:      Float       = 0f,

        // ── Sprite animation (read by GameRenderer to pick the correct frame) ──
        var spriteState:      SpriteState = SpriteState.RUN,
        var spriteFrameIdx:   Int         = 0,
        var spriteFrameTimer: Float       = 0f,

        // ── Scoring ──────────────────────────────────────────────────────────
        var score:            Int     = 0,
        var totalCoins:       Int     = 0,
        var runCoins:         Int     = 0,
        var highScore:        Int     = 0,
        var currentSpeed:     Float   = BASE_SPEED,
        var comboCount:       Int     = 0,
        var comboTimer:       Float   = 0f,
        var runDistanceM:     Int     = 0,
        var isNewHighScore:   Boolean = false,

        // ── Power-up active flags + timers ───────────────────────────────────
        var shieldActive:     Boolean = false,
        var shieldTimer:      Float   = 0f,
        var slowActive:       Boolean = false,
        var slowTimer:        Float   = 0f,
        var doubleActive:     Boolean = false,
        var doubleTimer:      Float   = 0f,
        var magnetActive:     Boolean = false,
        var magnetTimer:      Float   = 0f,
        var ghostActive:      Boolean = false,
        var ghostTimer:       Float   = 0f,

        // ── Boss ─────────────────────────────────────────────────────────────
        var bossActive:        Boolean = false,
        var bossX:             Float   = 0f,
        var bossY:             Float   = 0f,
        var bossHP:            Int     = BOSS_MAX_HP,
        var bossShootTimer:    Float   = 0f,
        var projSkipFrame:     Boolean = false,
        var nextBossCoinThr:   Int     = 33,

        // ── Cinematic ────────────────────────────────────────────────────────
        var deathZoom:         Float   = 1f,
        var targetDeathZoom:   Float   = 1f,
        var deathDarkness:     Float   = 0f,
        var deathSlowMoTimer:  Float   = 0f,
        var menuCamOffset:     Float   = 0f,
        var shockwaveActive:   Boolean = false,
        var shockwaveRadius:   Float   = 0f,

        // ── Revive ───────────────────────────────────────────────────────────
        var reviveAvailable:   Boolean = false
    )

    val state = EngineState().also { s ->
        s.highScore   = prefs.getInt("high_score",   0)
        s.totalCoins  = prefs.getInt("total_coins",  0)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Entity lists  (renderer reads these; only engine + spawner write to them)
    // ═══════════════════════════════════════════════════════════════════════════

    val obstacles:       MutableList<EngineObstacle>  = mutableListOf()
    val coins:           MutableList<EngineCoin>      = mutableListOf()
    val powerUps:        MutableList<EnginePowerUp>   = mutableListOf()
    val particles:       MutableList<EngineParticle>  = mutableListOf()
    val trailPoints:     MutableList<EngineTrail>     = mutableListOf()
    val floatingTexts:   MutableList<EngineFloatText> = mutableListOf()
    val bossProjectiles: MutableList<BossProjectile>  = mutableListOf()
    val scorePopups:     MutableList<ScorePopup>      = mutableListOf()

    // ═══════════════════════════════════════════════════════════════════════════
    // Additional entity types (defined here, not in GameSpawner, so the
    // renderer only imports from one package)
    // ═══════════════════════════════════════════════════════════════════════════

    data class EngineParticle(
        var x: Float, var y: Float,
        var vx: Float, var vy: Float,
        var size: Float, var life: Float,
        val color: Int
    )

    data class EngineTrail(
        var x: Float, var y: Float,
        var size: Float, var alpha: Float
    )

    data class BossProjectile(
        var x: Float, var y: Float,
        val vx: Float, val vy: Float = 0f
    )

    data class ScorePopup(
        var x: Float, var y: Float,
        val text: String, val color: Int,
        var life: Float = 1.2f
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // Sub-systems
    // ═══════════════════════════════════════════════════════════════════════════

    private val spawner = GameSpawner(
        lists     = GameSpawner.EntityLists(obstacles, coins, powerUps, floatingTexts),
        screenRef = GameSpawner.ScreenRef()
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // Constants
    // ═══════════════════════════════════════════════════════════════════════════

    companion object {
        const val GRAVITY_BASE        = 4.8f
        const val JUMP_FORCE          = -53f
        const val BASE_SPEED          = 14f
        const val MAX_SPEED           = 36f
        const val DASH_DURATION       = 0.18f
        const val DASH_COOLDOWN       = 0.60f
        const val MAX_JUMP_HOLD       = 0.18f
        const val MAX_COYOTE_TIME     = 0.12f
        const val SLOW_BASE_DURATION  = 4.5f
        const val DOUBLE_BASE_DURATION= 8f
        const val MAGNET_BASE_DURATION= 7f
        const val BOSS_MAX_HP         = 100
        const val BOSS_SHOOT_INTERVAL = 1.8f
        const val REVIVE_COST         = 50
        const val COMBO_RESET_TIME    = 3f
        const val TRAIL_MAX_HIGH      = 14
        const val TRAIL_MAX_LOW       = 5
        const val PARTICLE_MAX        = 200
        const val FLOAT_TEXT_MAX      = 25
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Fixed DT  (engine is always 60 Hz regardless of display refresh rate)
    // ═══════════════════════════════════════════════════════════════════════════

    private val dt = 1f / 60f    // 16.67 ms in seconds

    // dashCooldown is tracked separately from state (renderer doesn't need it)
    private var dashCooldown = 0f

    // ═══════════════════════════════════════════════════════════════════════════
    // Main tick  (called once per Choreographer accumulator cycle)
    // ═══════════════════════════════════════════════════════════════════════════

    /** Game-loop enum mirroring GameView.GameState. Engine only cares about
     *  a subset of states. */
    enum class Mode { MENU, PLAYING, PAUSED, GAME_OVER, OTHER }

    private var mode = Mode.MENU

    fun setMode(newMode: Mode) { mode = newMode }
    fun getMode(): Mode = mode

    /**
     * Advance the simulation by exactly one fixed timestep (1/60 s).
     * The Choreographer accumulator in GameView controls how many times
     * this is called per display frame.
     */
    fun tick() {
        val s = state   // local alias to reduce property lookup overhead

        // ── Speed ramp ────────────────────────────────────────────────────────
        if (mode == Mode.PLAYING && s.currentSpeed < MAX_SPEED) {
            s.currentSpeed += 0.005f
        }

        // ── Global timers (run in all modes) ──────────────────────────────────
        if (s.invincibility > 0f)  s.invincibility -= dt
        if (dashCooldown    > 0f)  dashCooldown     -= dt

        // ── Menu float camera ─────────────────────────────────────────────────
        if (mode == Mode.MENU) {
            s.menuCamOffset = sin(System.currentTimeMillis() * 0.0005).toFloat() * 20f
        }

        // ── Boss update (runs even while dying so cinematic plays out) ─────────
        if (s.bossActive) tickBoss(s)

        // ── Slow-mo / time-scale ──────────────────────────────────────────────
        val timeScale: Float = when {
            s.deathSlowMoTimer > 0f -> { s.deathSlowMoTimer -= dt; 0.25f }
            s.slowActive            -> 0.60f
            else                    -> 1.00f
        }

        // ── Squash-and-stretch easing ─────────────────────────────────────────
        s.animScaleX  += (s.targetScaleX - s.animScaleX) * 0.20f
        s.animScaleY  += (s.targetScaleY - s.animScaleY) * 0.20f
        s.shakeAmount *= 0.90f
        s.tiltAngle   *= 0.90f

        // ── Particle / text / parallax (run in all modes) ─────────────────────
        tickParticles()
        tickFloatingTexts()
        tickScorePopups()

        // ── Death cinematic ───────────────────────────────────────────────────
        if (mode == Mode.GAME_OVER) {
            s.deathZoom      += (s.targetDeathZoom - s.deathZoom) * 0.10f
            s.deathDarkness   = (s.deathDarkness + 0.02f).coerceAtMost(0.60f)
        }

        // ── Flash timer ───────────────────────────────────────────────────────
        if (s.flashTimer > 0f) s.flashTimer = (s.flashTimer - dt).coerceAtLeast(0f)

        // ── Shockwave (from ultra-dash) ───────────────────────────────────────
        if (s.shockwaveActive) {
            s.shockwaveRadius += 25f
            if (s.shockwaveRadius > 350f) s.shockwaveActive = false
        }

        if (mode != Mode.PLAYING) return   // ← only physics/gameplay below here

        // ── Dash timer ────────────────────────────────────────────────────────
        if (s.isDashing) {
            s.dashTimer += dt * timeScale
            if (s.dashTimer >= DASH_DURATION) {
                s.isDashing    = false
                s.targetScaleX = 1f
                s.targetScaleY = 1f
            }
        }

        // ── Physics ───────────────────────────────────────────────────────────
        tickPhysics(s, timeScale)

        // ── Trail points ──────────────────────────────────────────────────────
        tickTrail(s)

        // ── Obstacle movement ─────────────────────────────────────────────────
        tickObstacles(s, timeScale)

        // ── Spawner ───────────────────────────────────────────────────────────
        spawner.tick(dt, s.currentSpeed, s.bossActive)

        // ── Entity updates ────────────────────────────────────────────────────
        tickPowerUps(s, timeScale)
        tickCoins(s, timeScale)

        // ── Collision detection ───────────────────────────────────────────────
        checkCollisions(s)

        // ── Power-up timers ───────────────────────────────────────────────────
        tickPowerUpTimers(s)

        // ── Combo decay ───────────────────────────────────────────────────────
        if (s.comboTimer > 0f) {
            s.comboTimer -= dt
            if (s.comboTimer <= 0f) s.comboCount = 0
        }

        // ── Run distance ─────────────────────────────────────────────────────
        s.runDistanceM = (s.score * 2).coerceAtLeast(s.runDistanceM)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Physics
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Variable-gravity jump model:
     *   — While the jump button is held AND the player is still ascending
     *     (velocityY < 0) AND within [MAX_JUMP_HOLD] seconds, a small upward
     *     nudge (6 % of gravity) is subtracted each tick, making the arc
     *     shallower and the peak higher. This is the "hold to jump higher"
     *     mechanic used in Mario, Celeste, etc.
     *   — Once the hold expires or the player releases, full gravity applies.
     *
     * Coyote time: the [coyoteTime] counter is refreshed while the player is
     * touching the ground and counts down to zero in the air. A jump is valid
     * as long as coyoteTime > 0, giving a small grace window after walking off
     * a ledge.
     */
    private fun tickPhysics(s: EngineState, timeScale: Float) {
        val gravity = GRAVITY_BASE * timeScale

        if (!s.isJumping) s.jumpHoldTime = 0f

        if (s.isJumping && s.jumpHoldTime < MAX_JUMP_HOLD && s.playerVelocityY < 0f) {
            // Still rising and holding the jump button — reduce effective gravity
            s.playerVelocityY += gravity * 0.06f
            s.jumpHoldTime    += dt
        } else {
            // Falling or jump released — full gravity
            s.playerVelocityY += gravity
        }

        s.playerY        += s.playerVelocityY * timeScale
        s.lastFallSpeed   = s.playerVelocityY

        // ── Coyote time ──────────────────────────────────────────────────────
        if (s.playerY + playerSize >= groundTop) {
            s.coyoteTime = MAX_COYOTE_TIME
        } else {
            s.coyoteTime = (s.coyoteTime - dt).coerceAtLeast(0f)
        }

        // ── Ground collision ─────────────────────────────────────────────────
        if (s.playerY + playerSize >= groundTop) {
            val wasAirborne = s.isJumping || s.playerVelocityY > 5f
            if (wasAirborne) spawnLandingDust(s)

            s.playerY         = groundTop - playerSize
            s.playerVelocityY = 0f
            s.isJumping       = false
            s.canDoubleJump   = true

            // Squash on landing — spring back over 80 ms (handled by easing above)
            s.targetScaleX = 1.2f
            s.targetScaleY = 0.8f
            // Schedule the spring-back via a flag rather than postDelayed
            // (engine is timing-agnostic; the renderer reads the eased values)
        } else if (s.animScaleX > 1.05f || s.animScaleY < 0.95f) {
            // We're airborne but still mid-squash — spring back
            s.targetScaleX = 1f
            s.targetScaleY = 1f
        }

        // ── Sprite state machine ──────────────────────────────────────────────
        // Priority: DASH > JUMP (rising) > FALL (falling) > RUN (grounded).
        // LAND is a one-shot state; the renderer resets it to RUN after one cycle.
        val onGround = s.playerY + playerSize >= groundTop - 2f
        s.spriteState = when {
            s.isDashing                              -> SpriteState.DASH
            s.playerVelocityY < -4f && !onGround    -> SpriteState.JUMP
            s.playerVelocityY >  4f && !onGround    -> SpriteState.FALL
            onGround                                 -> SpriteState.RUN
            else                                     -> s.spriteState
        }

        // Advance frame index at state-specific fps.
        // RUN cycles at 12 fps for a snappy stride; air states use 8 fps.
        val frameHz = if (s.spriteState == SpriteState.RUN) 12f else 8f
        s.spriteFrameTimer += dt
        if (s.spriteFrameTimer >= 1f / frameHz) {
            s.spriteFrameTimer = 0f
            // 4 frames per strip is the default; renderer clamps to actual count.
            s.spriteFrameIdx = (s.spriteFrameIdx + 1) % 4
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Trail
    // ═══════════════════════════════════════════════════════════════════════════

    private fun tickTrail(s: EngineState) {
        // Add new point at player centre
        trailPoints.add(
            EngineTrail(
                x     = playerX + playerSize / 2f,
                y     = s.playerY + playerSize / 2f,
                size  = playerSize * 0.7f,
                alpha = 1f
            )
        )

        val maxTrail = TRAIL_MAX_HIGH   // could gate on GFX setting if reintroduced
        while (trailPoints.size > maxTrail) trailPoints.removeAt(0)

        val iter = trailPoints.iterator()
        while (iter.hasNext()) {
            val t = iter.next()
            t.alpha -= 0.07f
            t.size  *= 0.96f
            if (t.alpha <= 0f) iter.remove()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Obstacle movement
    // ═══════════════════════════════════════════════════════════════════════════

    private fun tickObstacles(s: EngineState, timeScale: Float) {
        var speed = s.currentSpeed * timeScale
        if (s.isDashing) speed *= 1.4f   // obstacles appear to rush during dash

        val iter = obstacles.iterator()
        while (iter.hasNext()) {
            val o = iter.next()
            o.x -= speed
            if (o.type == ObstacleKind.SAW) o.rotation -= 12f * timeScale
            if (o.x + o.width < -80f) iter.remove()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Power-up entity movement
    // ═══════════════════════════════════════════════════════════════════════════

    private fun tickPowerUps(s: EngineState, timeScale: Float) {
        // Power-ups scroll at 70 % of obstacle speed so they feel reachable
        val iter = powerUps.iterator()
        while (iter.hasNext()) {
            val p = iter.next()
            p.x -= s.currentSpeed * 0.7f
            if (p.x < -200f) iter.remove()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Coin movement + magnet
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Magnet attraction uses a fast inverse-square-root approximation (Quake III
     * algorithm) instead of sqrt(), saving a few microseconds per coin per frame.
     *
     * Newton–Raphson iteration:  y_n+1 = y_n × (1.5 − 0.5 × x × y_n²)
     * Starting estimate derived from the bit-hack:  i = 0x5f3759df − (i >> 1)
     */
    private fun fastInvSqrt(x: Float): Float {
        val half = 0.5f * x
        var bits = java.lang.Float.floatToIntBits(x)
        bits     = 0x5f3759df - (bits shr 1)
        var y    = java.lang.Float.intBitsToFloat(bits)
        y       *= (1.5f - half * y * y)   // one Newton–Raphson iteration
        return y
    }

    private fun tickCoins(s: EngineState, timeScale: Float) {
        val time = System.currentTimeMillis() * 0.003f   // for bob animation

        val iter = coins.iterator()
        while (iter.hasNext()) {
            val c = iter.next()
            c.x -= s.currentSpeed * timeScale

            // Gentle vertical bob so coins feel alive
            c.bobY = sin((time + c.bobOffset).toDouble()).toFloat() * 12f

            // Magnet: pull coins toward player using fast inv-sqrt distance
            if (s.magnetActive) {
                val dx     = playerX - c.x
                val dy     = (s.playerY + playerSize / 2f) - (c.y + c.bobY)
                val distSq = dx * dx + dy * dy + 0.0001f   // epsilon avoids div-by-zero
                val inv    = fastInvSqrt(distSq)
                val pull   = 30f
                c.x += dx * inv * pull
                c.y += dy * inv * pull
            }

            if (c.x < -150f) iter.remove()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Collision detection
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * All collision boxes are intentionally inset from the visual boundaries
     * to give the player a forgiving "inner hitbox" — a common game-feel
     * technique in successful runners (Temple Run, Subway Surfers, etc.).
     */
    private fun checkCollisions(s: EngineState) {
        val playerRect = RectF(
            playerX            + 25f,
            s.playerY          + 25f,
            playerX + playerSize - 25f,
            s.playerY + playerSize - 10f
        )

        // ── Obstacles ────────────────────────────────────────────────────────
        for (obs in obstacles) {
            val obsRect = when (obs.type) {
                ObstacleKind.SPIKE -> RectF(
                    obs.x + 20f,
                    groundTop - obs.height + 20f,
                    obs.x + obs.width - 20f,
                    groundTop
                )
                else -> RectF(
                    obs.x,
                    groundTop - obs.height,
                    obs.x + obs.width,
                    groundTop
                )
            }

            if (RectF.intersects(playerRect, obsRect) && !s.isDashing) {
                hitPlayer(s)
            }

            // Score point when player passes an obstacle's trailing edge.
            // The window "playerX - speed - 2 .. playerX" ensures we catch it
            // in exactly one frame regardless of speed.
            val trailingEdge = obs.x + obs.width
            if (trailingEdge < playerX && trailingEdge > playerX - s.currentSpeed - 2f) {
                awardObstacleClear(s)
            }
        }

        // ── Coins ─────────────────────────────────────────────────────────────
        val coinIter = coins.iterator()
        while (coinIter.hasNext()) {
            val c  = coinIter.next()
            val cr = RectF(c.x - c.radius, (c.y + c.bobY) - c.radius,
                c.x + c.radius, (c.y + c.bobY) + c.radius)
            if (!RectF.intersects(playerRect, cr)) continue

            coinIter.remove()
            val gain = if (s.doubleActive) 2 else 1
            s.runCoins   += gain
            s.totalCoins += gain
            // Persist asynchronously; call is cheap but batching would be better
            prefs.edit().putInt("total_coins", s.totalCoins).apply()

            // Boss spawn threshold
            if (!s.bossActive && s.runCoins >= s.nextBossCoinThr) {
                spawnBoss(s)
            }

            spawnFloatingText(c.x, c.y + c.bobY, "+$gain", Color.YELLOW)
            spawnDust(c.x, c.y + c.bobY, Color.YELLOW)
            sound.playCoin()
        }

        // ── Power-ups ─────────────────────────────────────────────────────────
        val puIter = powerUps.iterator()
        while (puIter.hasNext()) {
            val p  = puIter.next()
            val pr = RectF(p.x, p.y, p.x + p.size, p.y + p.size)
            if (RectF.intersects(playerRect, pr)) {
                activatePowerUp(s, p.type)
                val label = p.type.name.replace("_", " ")
                spawnFloatingText(playerX, s.playerY - 50f, label, Color.CYAN)
                sound.playPower()
                puIter.remove()
            } else if (p.x < -120f) {
                puIter.remove()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Score on obstacle clear
    // ─────────────────────────────────────────────────────────────────────────

    private fun awardObstacleClear(s: EngineState) {
        s.comboCount++
        s.comboTimer = COMBO_RESET_TIME

        // Close-shave haptic juice: a tiny camera recoil on every clear.
        // Scales with combo so high-streak clears feel increasingly intense.
        val recoilAmt = (1.5f + s.comboCount * 0.4f).coerceAtMost(6f)
        s.shakeAmount = s.shakeAmount.coerceAtLeast(recoilAmt)
        // Slight forward-lean on clear — reinforces the sense of momentum
        s.tiltAngle   = s.tiltAngle.coerceAtLeast(recoilAmt * 0.8f)

        val multiplier = when {
            s.comboCount >= 10 -> 3
            s.comboCount >= 5  -> 2
            else               -> 1
        }
        val base   = if (s.doubleActive) 2 else 1
        val gained = base * multiplier
        s.score   += gained

        if (s.comboCount >= 5) {
            spawnFloatingText(playerX, s.playerY - 80f, "x${s.comboCount} COMBO!", Color.parseColor("#FF6D00"))
            scorePopups.add(ScorePopup(playerX + playerSize / 2f, s.playerY - 40f, "+$gained", Color.parseColor("#FFD600")))
        } else {
            scorePopups.add(ScorePopup(playerX + playerSize / 2f, s.playerY - 30f, "+$gained", Color.WHITE))
        }
        listener.onNewScorePopup()

        if (s.currentSpeed < MAX_SPEED) s.currentSpeed += 0.06f
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Hit / death state machine
    // ═══════════════════════════════════════════════════════════════════════════

    private fun hitPlayer(s: EngineState) {
        if (s.invincibility > 0f) return

        // Ghost phase-through
        if (s.ghostActive) {
            spawnFloatingText(playerX, s.playerY, "PHASED", Color.LTGRAY)
            return
        }

        // Every hit breaks combo
        s.comboCount = 0

        // Shield absorbs one hit
        if (s.shieldActive) {
            s.shieldActive  = false
            s.invincibility = 1.0f   // 1 s invincibility window after shield breaks
            spawnFloatingText(playerX, s.playerY, "SHIELD BROKEN!", Color.CYAN)
            spawnExplosion(playerX, s.playerY, Color.CYAN)
            listener.onShake("BUMP")
            return
        }

        listener.onShake("CRASH")
        triggerGameOver(s)
    }

    fun triggerGameOver(s: EngineState = state) {
        if (mode == Mode.GAME_OVER) return

        s.reviveAvailable  = s.totalCoins >= REVIVE_COST
        s.comboCount       = 0
        s.deathSlowMoTimer = 0.9f
        s.targetDeathZoom  = 0.86f
        s.deathDarkness    = 0f
        s.shakeAmount      = 40f

        spawnExplosion(playerX, s.playerY, Color.WHITE)
        sound.playDeath()

        s.isNewHighScore = s.score > s.highScore
        if (s.isNewHighScore) {
            s.highScore = s.score
            prefs.edit().putInt("high_score", s.highScore).apply()
        }

        mode = Mode.GAME_OVER
        listener.onGameOver(s.isNewHighScore, s.score)
        if (s.reviveAvailable) listener.onReviveAvailable()

        sound.switchMusic(SoundManager.Track.OVER)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Revive
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Spend [REVIVE_COST] coins, clear hazards, and restart play from the
     * same score. Player is granted 5 s shield + 1.5 s invincibility so
     * they have time to react after the screen cuts back in.
     */
    fun revivePlayer() {
        val s = state
        if (s.totalCoins < REVIVE_COST) return

        s.totalCoins -= REVIVE_COST
        prefs.edit().putInt("total_coins", s.totalCoins).apply()

        obstacles.clear()
        powerUps.clear()
        coins.clear()
        bossProjectiles.clear()
        s.bossActive    = false

        s.comboCount       = 0
        s.comboTimer       = 0f
        s.playerY          = groundTop - playerSize
        s.playerVelocityY  = 0f
        s.shieldActive     = true
        s.shieldTimer      = 5f
        s.isDashing        = false
        s.dashTimer        = 0f
        s.flashTimer       = 0f
        s.deathSlowMoTimer = 0f
        s.deathZoom        = 1f
        s.targetDeathZoom  = 1f
        s.deathDarkness    = 0f
        s.invincibility    = 1.5f

        mode = Mode.PLAYING
        sound.switchMusic(SoundManager.Track.GAME)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Boss system
    // ═══════════════════════════════════════════════════════════════════════════

    // Mutable — overwritten by onBossBitmapLoaded() once the bitmap is measured.
    // Fallback values are used until then so spawnBoss() always has safe numbers.
    private var bossW = 260f
    private var bossH = 320f

    private fun spawnBoss(s: EngineState) {
        s.bossActive        = true
        s.bossHP            = BOSS_MAX_HP
        s.bossX             = screenWidth + bossW
        s.bossY             = groundTop - bossH * 0.55f
        s.bossShootTimer    = BOSS_SHOOT_INTERVAL
        s.nextBossCoinThr  += 33   // next boss in another 33 coins

        spawnFloatingText(screenWidth / 2f, screenHeight / 2f, "BOSS INCOMING!", Color.RED)
        listener.onBossSpawned()
        sound.switchMusic(SoundManager.Track.GAME)   // caller can override to boss music
    }

    /**
     * All boss logic per tick:
     *  1. Move boss left at a fraction of scroll speed.
     *  2. Collide with player body (no dash immunity for body contact).
     *  3. Fire projectiles on a timer; alternate-frame update halves their CPU cost.
     *  4. Remove boss when it exits the left edge.
     */
    private fun tickBoss(s: EngineState) {
        // ── Move ──────────────────────────────────────────────────────────────
        s.bossX -= s.currentSpeed * 1.0f

        // ── Body collision ────────────────────────────────────────────────────
        val bossRect   = RectF(s.bossX, s.bossY, s.bossX + bossW, s.bossY + bossH)
        val playerRect = RectF(playerX, s.playerY, playerX + playerSize, s.playerY + playerSize)
        if (RectF.intersects(bossRect, playerRect) && !s.ghostActive && !s.shieldActive) {
            hitPlayer(s)
        }

        // ── Projectile fire ───────────────────────────────────────────────────
        s.bossShootTimer -= dt
        if (s.bossShootTimer <= 0f) {
            s.bossShootTimer = BOSS_SHOOT_INTERVAL
            bossProjectiles.add(
                BossProjectile(
                    x  = s.bossX - 50f,
                    y  = s.bossY + bossH / 2f,
                    vx = -28f
                )
            )
            sound.playBoss()
        }

        // ── Alternate-frame projectile update ─────────────────────────────────
        // On odd frames: half-speed nudge (cheap).
        // On even frames: full movement + collision check.
        // This halves collision-detection cost while keeping visual smoothness.
        s.projSkipFrame = !s.projSkipFrame
        if (s.projSkipFrame) {
            bossProjectiles.forEach { p -> p.x += p.vx * 0.5f }
        } else {
            val pIter = bossProjectiles.iterator()
            while (pIter.hasNext()) {
                val p  = pIter.next()
                p.x   += p.vx
                p.y   += p.vy

                val px = playerX + playerSize / 2f
                val py = s.playerY + playerSize / 2f
                if (abs(p.x - px) < 60f && abs(p.y - py) < 60f) {
                    hitPlayer(s)
                    spawnExplosion(p.x, p.y, Color.RED)
                    pIter.remove()
                    continue
                }
                if (p.x < -200f) pIter.remove()
            }
        }

        // ── Exit condition ────────────────────────────────────────────────────
        if (s.bossX < -bossW) {
            s.bossActive = false
            bossProjectiles.clear()
            listener.onBossDefeated()
            sound.switchMusic(SoundManager.Track.GAME)
        }
    }

    /**
     * Called when a player-fired projectile hits the boss (future feature).
     * Decrements HP; triggers defeat sequence at 0.
     */
    fun damageBoss(damage: Int = 10) {
        val s = state
        if (!s.bossActive) return
        s.bossHP = (s.bossHP - damage).coerceAtLeast(0)
        spawnExplosion(s.bossX + bossW / 2f, s.bossY + bossH / 4f, Color.RED)
        if (s.bossHP == 0) {
            s.bossActive = false
            bossProjectiles.clear()
            spawnExplosion(s.bossX + bossW / 2f, s.bossY + bossH / 2f, Color.YELLOW)
            spawnFloatingText(screenWidth / 2f, screenHeight / 3f, "BOSS DEFEATED!", Color.YELLOW)
            listener.onBossDefeated()
            sound.switchMusic(SoundManager.Track.GAME)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Power-up activation + timers
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * All power-ups stack additively up to 3× their base duration.
     * Collecting the same power-up twice adds time rather than resetting,
     * which rewards players who consistently collect them.
     */
    fun activatePowerUp(s: EngineState = state, type: EnginePowerType) {
        val add = 5f   // each pickup adds 5 seconds
        when (type) {
            EnginePowerType.SHIELD -> {
                s.shieldActive = true
                s.shieldTimer  = (s.shieldTimer + add).coerceAtMost(15f)
            }
            EnginePowerType.SLOW -> {
                s.slowActive = true
                s.slowTimer  = (s.slowTimer + add).coerceAtMost(add * 3)
            }
            EnginePowerType.DOUBLE -> {
                s.doubleActive = true
                s.doubleTimer  = (s.doubleTimer + add).coerceAtMost(add * 3)
            }
            EnginePowerType.MAGNET -> {
                s.magnetActive = true
                s.magnetTimer  = (s.magnetTimer + add).coerceAtMost(add * 3)
            }
            EnginePowerType.GHOST -> {
                s.ghostActive = true
                s.ghostTimer  = (s.ghostTimer + add).coerceAtMost(add * 3)
            }
        }
    }

    private fun tickPowerUpTimers(s: EngineState) {
        if (s.shieldActive) {
            s.shieldTimer -= dt
            if (s.shieldTimer <= 0f) { s.shieldTimer = 0f; s.shieldActive = false }
        }
        if (s.slowActive) {
            s.slowTimer -= dt
            if (s.slowTimer <= 0f) s.slowActive = false
        }
        if (s.doubleActive) {
            s.doubleTimer -= dt
            if (s.doubleTimer <= 0f) s.doubleActive = false
        }
        if (s.magnetActive) {
            s.magnetTimer -= dt
            if (s.magnetTimer <= 0f) s.magnetActive = false
        }
        if (s.ghostActive) {
            s.ghostTimer -= dt
            if (s.ghostTimer <= 0f) {
                s.ghostActive = false
                spawnFloatingText(playerX, s.playerY, "GHOST ENDED", Color.WHITE)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Particle / effect helpers
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Radial explosion: [count] particles emitted at random angles with random
     * radial speed between 0 and 15 px/frame.
     */
    fun spawnExplosion(x: Float, y: Float, color: Int, count: Int = 20) {
        repeat(count) {
            val angle = Random.nextFloat() * (2f * Math.PI).toFloat()
            val speed = Random.nextFloat() * 15f
            particles.add(
                EngineParticle(
                    x = x, y = y,
                    vx = cos(angle) * speed,
                    vy = sin(angle) * speed,
                    size = 15f, life = 1f, color = color
                )
            )
        }
        if (particles.size > PARTICLE_MAX) {
            particles.subList(0, particles.size - PARTICLE_MAX).clear()
        }
    }

    /** Small upward dust puff — used on landing and coin collect. */
    fun spawnDust(x: Float, y: Float, color: Int) {
        repeat(6) {
            particles.add(
                EngineParticle(
                    x = x, y = y,
                    vx = Random.nextFloat() * 10f - 5f,
                    vy = Random.nextFloat() * -6f,
                    size = 10f, life = 0.5f, color = color
                )
            )
        }
    }

    private fun spawnLandingDust(s: EngineState) {
        spawnDust(playerX + playerSize / 2f, groundTop, Color.LTGRAY)
    }

    fun spawnFloatingText(x: Float, y: Float, text: String, color: Int) {
        if (floatingTexts.size >= FLOAT_TEXT_MAX) floatingTexts.removeAt(0)
        floatingTexts.add(EngineFloatText(x, y, text, color, life = 1f))
    }

    private fun tickParticles() {
        val iter = particles.iterator()
        while (iter.hasNext()) {
            val p = iter.next()
            p.x   += p.vx
            p.y   += p.vy
            p.life -= 0.03f
            p.size *= 0.95f
            if (p.life <= 0f) iter.remove()
        }
    }

    private fun tickFloatingTexts() {
        val iter = floatingTexts.iterator()
        while (iter.hasNext()) {
            val t = iter.next()
            t.y    += t.vy
            t.life -= 0.015f
            if (t.life <= 0f) iter.remove()
        }
    }

    private fun tickScorePopups() {
        val iter = scorePopups.iterator()
        while (iter.hasNext()) {
            val p = iter.next()
            p.y    -= 60f * dt
            p.life -= dt * 1.2f
            if (p.life <= 0f) iter.remove()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Input handlers  (called from GameView's onTouchEvent)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Tap — jump or double-jump.
     * Uses coyote time so the jump registers even a few frames after the
     * player walks off a ledge.
     */
    fun onTap() {
        val s = state
        if (mode != Mode.PLAYING) return

        if (s.coyoteTime > 0f) {
            // First jump — full height
            s.isJumping       = true
            s.jumpHoldTime    = 0f
            s.playerVelocityY = JUMP_FORCE
            s.canDoubleJump   = true
            spawnDust(playerX, s.playerY + playerSize, Color.WHITE)
            sound.playJump()
        } else if (s.canDoubleJump && !s.isDashing) {
            // Double jump — 80 % height, consumed
            s.isJumping       = true
            s.jumpHoldTime    = 0f
            s.playerVelocityY = JUMP_FORCE * 0.8f
            s.canDoubleJump   = false
            spawnDust(playerX, s.playerY + playerSize, Color.CYAN)
            sound.playJump()
        }
    }

    /** Called while the jump button is held — extends the jump apex. */
    fun onJumpHeld() {
        state.isJumping = true   // keep hold flag active; tickPhysics reads it
    }

    /**
     * Called on ACTION_UP from GameView.
     * Clears the isJumping hold flag so the variable-gravity calculation
     * immediately switches to full gravity, producing a short hop when
     * tapped briefly and a full arc when held. Without this, every tap
     * regardless of duration produced a maximum-height jump.
     */
    fun onReleaseJump() {
        // Only clear when the player is still ascending; if they've already
        // peaked and are falling, the flag is irrelevant but safe to clear.
        state.isJumping = false
    }

    /** Swipe right — dash. Has a cooldown and a short duration. */
    fun onDash() {
        val s = state
        if (mode != Mode.PLAYING) return
        if (dashCooldown > 0f || s.isDashing) return

        s.isDashing    = true
        s.dashTimer    = 0f
        s.tiltAngle    = 14f
        dashCooldown   = DASH_COOLDOWN
        s.targetScaleX = 0.7f
        s.targetScaleY = 1.3f
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Game lifecycle
    // ═══════════════════════════════════════════════════════════════════════════

    fun resetGame() {
        val s = state

        s.score              = 0
        s.currentSpeed       = BASE_SPEED
        s.runCoins           = 0
        s.runDistanceM       = 0
        s.comboCount         = 0
        s.comboTimer         = 0f
        s.isNewHighScore     = false
        s.nextBossCoinThr    = 33
        s.bossActive         = false

        s.playerY            = groundTop - playerSize
        s.playerVelocityY    = 0f
        s.isJumping          = false
        s.canDoubleJump      = false
        s.isDashing          = false
        s.dashTimer          = 0f
        dashCooldown         = 0f

        s.shieldActive       = false; s.shieldTimer    = 0f
        s.slowActive         = false; s.slowTimer      = 0f
        s.doubleActive       = false; s.doubleTimer    = 0f
        s.magnetActive       = false; s.magnetTimer    = 0f
        s.ghostActive        = false; s.ghostTimer     = 0f
        s.invincibility      = 0f
        s.flashTimer         = 0f

        s.deathSlowMoTimer   = 0f
        s.deathZoom          = 1f
        s.targetDeathZoom    = 1f
        s.deathDarkness      = 0f
        s.animScaleX         = 1f; s.targetScaleX = 1f
        s.animScaleY         = 1f; s.targetScaleY = 1f
        s.shakeAmount        = 0f
        s.tiltAngle          = 0f
        s.shockwaveActive    = false

        obstacles.clear(); coins.clear(); powerUps.clear()
        particles.clear(); trailPoints.clear(); floatingTexts.clear()
        bossProjectiles.clear(); scorePopups.clear()

        spawner.reset()
        mode = Mode.PLAYING
        sound.switchMusic(SoundManager.Track.GAME)
    }
}