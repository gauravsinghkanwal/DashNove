package com.papaji.alpharunner.ui

import android.app.Activity
import android.content.Context
import android.graphics.*
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.papaji.alpharunner.R
import kotlin.math.*
import kotlin.random.Random

class GameView(context: Context) : View(context), Choreographer.FrameCallback {

    // ======================
    //     GAME STATES
    // ======================
    private enum class GameState { MENU, PLAYING, PAUSED, SHOP, SETTINGS, GAME_OVER }
    private enum class GfxQuality { LOW, MEDIUM, HIGH }


    private var gameState = GameState.MENU

    private enum class ThemeMode { NIGHT, DAY }

    private var themeMode = ThemeMode.DAY

    enum class SkinType { DEFAULT, NINJA, POLICE, FIRE, THIEF }

    // ======================
    //       PLAYER
    // ======================
    private var playerX = 150f
    private var playerY = 0f
    private var playerSize = 130f
    private var playerVelocityY = 0f

    private var isJumping = false
    private var canDoubleJump = false
    private var jumpHoldTime = 0f
    private val maxJumpHold = 0.18f




    // Movement polish
    private var tiltAngle = 0f
    private var animScaleX = 1f
    private var animScaleY = 1f
    private var targetScaleX = 1f
    private var targetScaleY = 1f
    private var shakeAmount = 0f
    private var lastFallSpeed = 0f
    private val mountains = mutableListOf<ParallaxLayer>()
    private val cloudsMid = mutableListOf<ParallaxLayer>()
    private val cloudsFront = mutableListOf<ParallaxLayer>()

    // Safety
    private var invincibilityTimer = 0f
    private var ownedSkins = mutableSetOf<String>()
    private var selectedSkin: SkinType = SkinType.DEFAULT

    // ---------- UPGRADE SYSTEM ----------
    private var magnetLevel = 0
    private var shieldLevel = 0
    private var slowLevel = 0
    private var scoreLevel = 0
    private val maxUpgradeLevel = 5
    private val upgradeCost = listOf(50, 100, 150, 200, 300)
    private var sawBitmap: Bitmap? = null
    private var dayBackgroundBitmap: Bitmap? = null
    private var shieldTimer = 0f

    // -------------------------
// RAVAN BOSS SYSTEM (FIXED)
// -------------------------
    private var bossActive = false

    private var bossX = 0f
    private var bossY = 0f
    private var bossSpeed = 0f
    private var bossRotation = 0f

    private lateinit var ravanBitmap: Bitmap
    private var bossW = 0f
    private var bossH = 0f

    private var bossHP = 100
    private var bossMaxHP = 100

    // Spawn after 33 coins → 66 → 99 → ...
    private var nextBossCoinThreshold = 33

    // 🔥 NEW (Required to fix errors)
    private var bossShootTimer = 0f
    private val bossShootInterval = 1.8f

    private var projectileUpdateSkip = false

    private data class BossProjectile(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float
    )

    private val bossProjectiles = mutableListOf<BossProjectile>()



    private data class ParallaxLayer(
        var x: Float,
        var y: Float,
        var speed: Float,
        var bitmap: Bitmap
    )

    // GFX Rects
    private val gfxLowRect = RectF()
    private val gfxMedRect = RectF()
    private val gfxHighRect = RectF()
    // -------------------------

    // Dash
    private var isDashing = false
    private var dashTimer = 0f
    private val dashDuration = 0.18f
    private var swipeConsumed = false
    private var downX = 0f
    private var downY = 0f
    private var dashCooldown = 0f
    private val dashCooldownDuration = 0.6f
    private var powerGhostBitmap: Bitmap? = null

    // PowerUp timers
    private val SLOW_BASE_DURATION = 4.5f
    private val DOUBLE_BASE_DURATION = 8f
    private val MAGNET_BASE_DURATION = 7f

    // Coyote time
    private var coyoteTime = 0f
    private val maxCoyote = 0.12f

    // Shockwave
    private var shockwaveActive = false
    private var shockwaveRadius = 0f
    private var shockwaveX = 0f
    private var shockwaveY = 0f

    // Cinematic
    private var deathZoom = 1f
    private var targetDeathZoom = 1f
    private var deathDarkness = 0f
    private var deathSlowMoTimer = 0f
    private var flashTimer = 0f
    private var menuCamOffset = 0f

    // ======================
    //   GAME CONSTANTS
    // ======================
    private val GRAVITY_BASE = 4.8f
    private val JUMP_FORCE = -53f
    private val BASE_SPEED = 14f
    private val MAX_SPEED = 36f

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tempPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var currentSpeed = BASE_SPEED
    private var difficultyLevel = 1f

    private var score = 0
    private var highScore = 0
    private var totalCoins = 0
    private var runCoins = 0

    private val prefs = context.getSharedPreferences("dash_nova_prefs", Context.MODE_PRIVATE)

    // Upgrade Rects
    private val upMagnetRect = RectF()
    private val upShieldRect = RectF()
    private val upSlowRect = RectF()
    private val upScoreRect = RectF()

    // ======================
    //       OBJECTS
    // ======================
    private enum class ObstacleType { BOX, SPIKE, SAW }



    private data class Obstacle(
        var x: Float,
        var width: Float,
        var height: Float,
        var type: ObstacleType,
        var rotation: Float = 0f
    )

    private val obstacles = mutableListOf<Obstacle>()

    private data class TrailPoint(var x: Float, var y: Float, var size: Float, var alpha: Float)

    private val trailPoints = mutableListOf<TrailPoint>()

    private var spawnTimer = 0f
    private var gfxQuality = GfxQuality.HIGH
    private var dailyRewardShown = false

    // Coins
    private data class Coin(var x: Float, var y: Float, val r: Float = 40f)

    private val coins = mutableListOf<Coin>()

    // PowerUps
    private enum class PowerType { SHIELD, SLOW, DOUBLE, MAGNET, GHOST }
    private data class PowerUp(var x: Float, var y: Float, var size: Float, val type: PowerType)

    private val powerUps = mutableListOf<PowerUp>()

    // PowerUp states
    private var shieldActive = false
    private var slowTimeActive = false
    private var slowTimeTimer = 0f
    private var doubleScoreActive = false
    private var doubleScoreTimer = 0f
    private var magnetActive = false
    private var magnetTimer = 0f

    private var ghostActive = false
    private var ghostTimer = 0f

    // Revive
    private var reviveAvailable = false
    private val reviveCost = 50
    private val reviveRect = RectF()


    // ======================
    //     UI ELEMENTS
    // ======================
    private val playBtn = RectF()
    private val shopBtn = RectF()
    private val exitBtn = RectF()
    private val pauseBtn = RectF()
    private val restartBtn = RectF()
    private val menuBtn = RectF()
    private val backBtn = RectF()
    private val settingsBtn = RectF()
    private val sfxToggleRect = RectF()
    private val musicToggleRect = RectF()
    private val settingsBackRect = RectF()
    private val resumeBtn = RectF()
    private val pausedMenuBtn = RectF()

    // ======================
    //     PARTICLES / FX
    // ======================
    private data class Particle(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var size: Float,
        var life: Float,
        var color: Int
    )

    private val particles = mutableListOf<Particle>()

    private data class FloatingText(
        var x: Float,
        var y: Float,
        val text: String,
        val color: Int,
        var life: Float
    )

    private val floatingTexts = mutableListOf<FloatingText>()

    private data class Star(var x: Float, var y: Float, var radius: Float, var speed: Float)

    private val starsBack = mutableListOf<Star>()
    private val starsFront = mutableListOf<Star>()
    private val starPaintBack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; alpha = 90
    }
    private val starPaintFront = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; alpha = 200
    }

    // ======================
    //       GRAPHICS
    // ======================
    private var playerBitmap: Bitmap? = null
    private var crateBitmap: Bitmap? = null
    private var spikeBitmap: Bitmap? = null
    private var coinBitmap: Bitmap? = null
    private var powerShieldBitmap: Bitmap? = null
    private var powerSlowBitmap: Bitmap? = null
    private var powerDoubleBitmap: Bitmap? = null
    private var powerMagnetBitmap: Bitmap? = null
    private var gridBitmap: Bitmap? = null
    private val themeDayRect = RectF()
    private val themeNightRect = RectF()

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
    }
    private val bgPaint = Paint()
    private val obstaclePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val playerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint().apply {
        color = Color.parseColor("#BC13FE")
        strokeWidth = 3f
        alpha = 100
        style = Paint.Style.STROKE
    }
    private var gridOffset = 0f
    private val skinNinja =
        Bitmap.createScaledBitmap(
            BitmapFactory.decodeResource(resources, R.drawable.skin_ninja),
            130,
            130,
            true
        )

    private val skinPolice =
        Bitmap.createScaledBitmap(
            BitmapFactory.decodeResource(resources, R.drawable.skin_police),
            130,
            130,
            true
        )

    private val skinThief =
        Bitmap.createScaledBitmap(
            BitmapFactory.decodeResource(resources, R.drawable.skin_thief),
            130,
            130,
            true
        )

    private val skinFire =
        Bitmap.createScaledBitmap(
            BitmapFactory.decodeResource(resources, R.drawable.skin_fire),
            130,
            130,
            true
        )



    // ======================
    //       SOUND
    // ======================
    private var mpMenu: MediaPlayer? = null
    private var mpGame: MediaPlayer? = null
    private var mpBoss: MediaPlayer? = null
    private var mpOver: MediaPlayer? = null
    private var mediaPlayer: MediaPlayer? = null

    private lateinit var soundPool: SoundPool

    private var jumpSound = 0
    private var deathSound = 0
    private var coinSound = 0
    private var powerSound = 0
    private var dashSound = 0
    private var clickSound = 0
    private var soundLoaded = false
    private var sfxEnabled = prefs.getBoolean("sfx_enabled", true)
    private var musicEnabled = prefs.getBoolean("music_enabled", true)
    private var currentMusic: MediaPlayer? = null

    // ======================
    //      SYSTEM
    // ======================
    private var loopRunning = false
    private var screenGroundTop = 0f
    private val vibrator = context.getSystemService(android.os.Vibrator::class.java)

    // new – shop hit-boxes for sprite skins
    private val skinBoxRects = mutableMapOf<SkinType, RectF>()

    init {
        highScore = prefs.getInt("high_score", 0)
        totalCoins = prefs.getInt("total_coins", 0)

        ownedSkins = prefs.getStringSet("owned_skins", setOf("DEFAULT"))!!
            .toMutableSet()

        val skinName = prefs.getString("selected_skin", "DEFAULT")!!
        selectedSkin = runCatching { SkinType.valueOf(skinName) }.getOrDefault(SkinType.DEFAULT)

        val savedTheme = prefs.getString("theme_mode", "NIGHT")
        themeMode = if (savedTheme == "DAY") ThemeMode.DAY else ThemeMode.NIGHT

        val qualityOrdinal = prefs.getInt("gfx_quality", 2)
        gfxQuality = GfxQuality.values()[qualityOrdinal.coerceIn(0, GfxQuality.values().size - 1)]

        initMusic(context)
        loadSkins()
        initSound(context)
    }

    // ---------- SKIN HELPERS (new clean system) ----------

    private fun getSkinColor(skin: SkinType = selectedSkin): Int = when (skin) {
        SkinType.DEFAULT -> Color.parseColor("#00F5FF")
        SkinType.NINJA -> Color.parseColor("#00FF7F")
        SkinType.POLICE -> Color.parseColor("#FFEA00")
        SkinType.FIRE -> Color.parseColor("#FF007F")
        SkinType.THIEF -> Color.parseColor("#FFAA00")
    }

    private fun getSkinCost(skin: SkinType): Int = when (skin) {
        SkinType.DEFAULT -> 0
        SkinType.NINJA,
        SkinType.POLICE,
        SkinType.FIRE,
        SkinType.THIEF -> 50      // change per-skin later if you want
    }

    private fun saveSkinSelection() {
        prefs.edit()
            .putString("selected_skin", selectedSkin.name)
            .putStringSet("owned_skins", ownedSkins.toSet())
            .apply()
    }

    // ----------------------------------------------------

    private fun saveGfxQuality() {
        prefs.edit().putInt("gfx_quality", gfxQuality.ordinal).apply()
    }

    private fun loadBitmaps(w: Int, h: Int) {
        if (playerBitmap != null) return

        fun loadSafe(resId: Int, targetW: Int, targetH: Int): Bitmap? {
            return try {
                val drawable = ContextCompat.getDrawable(context, resId) ?: return null
                val bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bitmap
            } catch (e: Exception) {
                null
            }
        }

        try {
            playerBitmap = loadSafe(R.drawable.player, 130, 130)
            crateBitmap = loadSafe(R.drawable.crate, 130, 130)
            spikeBitmap = loadSafe(R.drawable.spike, 130, 100)
            coinBitmap = loadSafe(R.drawable.coin, 70, 70)

            // --- Pre-render SAW into bitmap ---
            try {
                sawBitmap = Bitmap.createBitmap(140, 140, Bitmap.Config.ARGB_8888)
                val c = Canvas(sawBitmap!!)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = 6f
                }
                c.drawCircle(70f, 70f, 55f, p)
            } catch (_: Exception) {
            }

            powerShieldBitmap = loadSafe(R.drawable.power_shield, 80, 80)
            powerSlowBitmap = loadSafe(R.drawable.power_slow, 80, 80)
            powerDoubleBitmap = loadSafe(R.drawable.power_double, 80, 80)
            powerMagnetBitmap = loadSafe(R.drawable.power_magnet, 80, 80)
            powerGhostBitmap = loadSafe(R.drawable.power_ghost, 80, 80)
// ---------------------------
// LOAD RAVAN BOSS IMAGE
// ---------------------------
            try {
                val rawBoss = BitmapFactory.decodeResource(resources, R.drawable.ravan_boss)

                // Adjust scale if needed
                val scale = 0.15f

                ravanBitmap = Bitmap.createScaledBitmap(
                    rawBoss,
                    (rawBoss.width * scale).toInt(),
                    (rawBoss.height * scale).toInt(),
                    true
                )

                // Save boss size
                bossW = ravanBitmap.width.toFloat()
                bossH = ravanBitmap.height.toFloat()

            } catch (e: Exception) {
                println("Ravan boss not found: $e")
            }

            // Day Mode Background
            dayBackgroundBitmap = loadSafe(R.drawable.bg_day, w, h)
        } catch (e: Exception) {
            println("Bitmaps not found, using shapes.")
        }

        // Parallax background assets
        val mountainBmp = loadSafe(R.drawable.mountain, w, h / 2)
        val cloudMidBmp = loadSafe(R.drawable.cloud_mid, w / 2, h / 4)
        val cloudFrontBmp = loadSafe(R.drawable.cloud_front, w / 2, h / 3)

        if (mountainBmp != null && cloudMidBmp != null && cloudFrontBmp != null) {
            mountains.clear()
            cloudsMid.clear()
            cloudsFront.clear()

            // Mountains
            for (i in 0..2) {
                mountains.add(
                    ParallaxLayer(
                        x = i * w.toFloat(),
                        y = h * 0.45f,
                        speed = 0.4f,
                        bitmap = mountainBmp
                    )
                )
            }

            // Mid Clouds
            for (i in 0..3) {
                cloudsMid.add(
                    ParallaxLayer(
                        x = i * (w / 2f),
                        y = h * 0.20f,
                        speed = 1.2f,
                        bitmap = cloudMidBmp
                    )
                )
            }

            // Front Clouds
            for (i in 0..3) {
                cloudsFront.add(
                    ParallaxLayer(
                        x = i * (w / 2f),
                        y = h * 0.10f,
                        speed = 1.7f,
                        bitmap = cloudFrontBmp
                    )
                )
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        screenGroundTop = h - 220f
        loadBitmaps(w, h)
        initStars(w, h)
        setupButtons(w, h)
        if (gameState == GameState.MENU) resetGame()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!loopRunning) return
        update()
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    // ----------------------
    //       UPDATE LOOP
    // ----------------------
    private fun update() {
        val dt = 0.016f
        var timeScale = 1f

        if (gameState == GameState.PLAYING && currentSpeed < MAX_SPEED) {
            currentSpeed += 0.005f
        }

        // Timers
        if (invincibilityTimer > 0f) invincibilityTimer -= dt
        if (dashCooldown > 0f) dashCooldown -= dt

        if (ghostActive) {
            ghostTimer -= dt
            if (ghostTimer <= 0f) {
                ghostActive = false
                spawnFloatingText(playerX, playerY, "GHOST ENDED", Color.WHITE)
            }
        }

        // -------------------------
// BOSS UPDATE LOGIC (FIXED)
// -------------------------
        if (bossActive) {
// -------------------
// BOSS BODY COLLISION
// -------------------
            val bossRect = RectF(
                bossX,
                bossY,
                bossX + bossW,
                bossY + bossH
            )

            val playerRect = RectF(
                playerX,
                playerY,
                playerX + playerSize,
                playerY + playerSize
            )

// Collision
            if (RectF.intersects(bossRect, playerRect) && !ghostActive && !shieldActive) {
                hitPlayer()
            }

            // Move boss
            bossX -= currentSpeed * 1.0f


            // Shoot timer
            bossShootTimer -= dt
            if (bossShootTimer <= 0f) {
                bossShootTimer = bossShootInterval

                // Fire projectile
                bossProjectiles.add(
                    BossProjectile(
                        x = bossX - 50f,
                        y = bossY + bossH / 2f,
                        vx = -28f,
                        vy = 0f
                    )
                )

                playSfx(dashSound, 0.6f)
            }

            // Skip every alternate frame to reduce lag
            projectileUpdateSkip = !projectileUpdateSkip
            if (projectileUpdateSkip) {
                bossProjectiles.forEach { p ->
                    p.x += p.vx * 0.5f
                }
                return
            }

            // Update projectiles
            val iter = bossProjectiles.iterator()
            while (iter.hasNext()) {
                val p = iter.next()
                p.x += p.vx

                // Player collision
                val px = playerX + playerSize / 2f
                val py = playerY + playerSize / 2f

                if (abs(p.x - px) < 60f && abs(p.y - py) < 60f) {
                    hitPlayer()
                    iter.remove()
                    spawnExplosion(p.x, p.y, Color.RED)
                }

                // Remove old bullets
                if (p.x < -200f) iter.remove()
            }

            // Boss exits screen
            if (bossX < -bossW) {
                bossActive = false
                bossProjectiles.clear()
            }
        }


        // Slow-mo
        if (deathSlowMoTimer > 0f) {
            deathSlowMoTimer -= dt
            timeScale = 0.25f
        } else if (slowTimeActive) {
            timeScale = 0.6f
        }

        // Anim easing
        animScaleX += (targetScaleX - animScaleX) * 0.2f
        animScaleY += (targetScaleY - animScaleY) * 0.2f
        shakeAmount *= 0.9f
        tiltAngle *= 0.9f

        // Menu floating
        if (gameState == GameState.MENU) {
            menuCamOffset = sin(System.currentTimeMillis() * 0.0005).toFloat() * 20f
            checkDailyReward()
        }

        updateParticles()
        updateFloatingText()
        updateParallax()

        // Death cinematic
        if (gameState == GameState.GAME_OVER) {
            deathZoom += (targetDeathZoom - deathZoom) * 0.1f
            deathDarkness = (deathDarkness + 0.02f).coerceAtMost(0.6f)
        }

        // Shockwave
        if (shockwaveActive) {
            shockwaveRadius += 25f
            if (shockwaveRadius > 350f) shockwaveActive = false
        }

        if (flashTimer > 0f) {
            flashTimer -= 0.016f
            if (flashTimer < 0f) flashTimer = 0f
        }

        if (gameState != GameState.PLAYING) return

        // Dashing
        if (isDashing) {
            dashTimer += dt * timeScale
            if (dashTimer >= dashDuration) {
                isDashing = false
                targetScaleX = 1f
                targetScaleY = 1f
            }
        }

        // Physics
        val gravity = GRAVITY_BASE * timeScale
        if (!isJumping) jumpHoldTime = 0f
        if (isJumping && jumpHoldTime < maxJumpHold && playerVelocityY < 0) {
            playerVelocityY += gravity * 0.06f   // smoother upward float
            jumpHoldTime += dt
        }
        else {
            playerVelocityY += gravity
        }

        playerY += playerVelocityY * timeScale
        lastFallSpeed = playerVelocityY

        // Coyote
        if (playerY + playerSize >= screenGroundTop) {
            coyoteTime = maxCoyote
        } else {
            coyoteTime -= dt
            if (coyoteTime < 0f) coyoteTime = 0f
        }

        // Ground collision
        if (playerY + playerSize >= screenGroundTop) {
            if (playerVelocityY > 5f) spawnLandingDust()

            playerY = screenGroundTop - playerSize
            playerVelocityY = 0f
            isJumping = false
            canDoubleJump = true
            targetScaleX = 1.2f
            targetScaleY = 0.8f
            postDelayed({
                targetScaleX = 1f
                targetScaleY = 1f
            }, 80)
        }

        // Trail
        if (gameState == GameState.PLAYING) {
            val factor = when (gfxQuality) {
                GfxQuality.LOW -> 0.5f
                GfxQuality.MEDIUM -> 0.8f
                GfxQuality.HIGH -> 1.0f
            }
            trailPoints.add(
                TrailPoint(
                    playerX + playerSize / 2f,
                    playerY + playerSize / 2f,
                    playerSize * 0.7f,
                    1f * factor
                )
            )
            val maxTrail = if (gfxQuality == GfxQuality.LOW) 5 else 14
            while (trailPoints.size > maxTrail) trailPoints.removeAt(0)
            val itT = trailPoints.iterator()
            while (itT.hasNext()) {
                val t = itT.next()
                t.alpha -= 0.07f
                t.size *= 0.96f
                if (t.alpha <= 0f) itT.remove()
            }
        }

        // Move obstacles
        val obIter = obstacles.iterator()
        while (obIter.hasNext()) {
            val o = obIter.next()
            var speed = currentSpeed * timeScale
            if (isDashing) speed *= 1.4f
            o.x -= speed
            if (o.type == ObstacleType.SAW) o.rotation -= 12f * timeScale
            if (o.x + o.width < -80f) obIter.remove()
        }

        // Spawner
        spawnTimer -= dt
        if (spawnTimer <= 0f) {
            if (!bossActive) {
                spawnRandomPattern()
                val baseGapSec = 1.4f
                val speedFactor = (currentSpeed / BASE_SPEED).coerceIn(1f, 2.0f)
                val randomFactor = 0.8f + Random.nextFloat() * 1.2f
                spawnTimer = baseGapSec * randomFactor / speedFactor
            } else {
                spawnTimer = 0.5f
            }
        }

        updatePowerUps(timeScale)
        updateCoins(timeScale)
        checkCollisions()

        // Powerup timers
        if (slowTimeActive) {
            slowTimeTimer -= dt
            if (slowTimeTimer <= 0f) slowTimeActive = false
        }

        if (doubleScoreActive) {
            doubleScoreTimer -= dt
            if (doubleScoreTimer <= 0f) doubleScoreActive = false
        }
        if (shieldActive) {
            shieldTimer -= dt
            if (shieldTimer <= 0f) {
                shieldTimer = 0f
                shieldActive = false
            }
        }

        if (ghostActive) {
            ghostTimer -= dt
            if (ghostTimer <= 0f) ghostActive = false
        }

        if (magnetActive) {
            magnetTimer -= dt
            if (magnetTimer <= 0f) magnetActive = false
        }

    }

    // ----------------------
    //   SPAWNER LOGIC
    // ----------------------
    private fun spawnRandomPattern() {
        val bx = width.toFloat() + 80f
        val patternRoll = Random.nextFloat()

        when {
            patternRoll < 0.15f -> spawnSingleObstacle(bx)
            patternRoll < 0.30f -> spawnDoubleObstacleRow(bx)
            // patternRoll < 0.45f -> spawnStackedObstacle(bx)
            patternRoll < 0.60f -> spawnObstacleWithCoins(bx)
            patternRoll < 0.70f -> spawnCoinWave(bx)
            patternRoll < 0.90f -> spawnPowerUpNearSafeGap(bx)
            else -> spawnMixedObstacleCoinPower(bx)
        }
    }

    private fun spawnBoss() {

        bossActive = true
        bossHP = bossMaxHP

        // Place Ravan boss off-screen to the right
        bossX = width + bossW
        bossY = screenGroundTop - bossH * 0.55f
        // Adjust height
        bossRotation = 0f

        // Boss intro movement
        bossSpeed = currentSpeed * 0.4f

        spawnFloatingText(width/2f, height/2f, "BOSS INCOMING!", Color.RED)
        switchMusic(mpBoss)  // if you have boss music
    }


    private fun switchMusic(target: MediaPlayer?) {
        if (!musicEnabled) return
        if (currentMusic === target) return

        currentMusic?.pause()
        currentMusic?.seekTo(0)

        currentMusic = target
        currentMusic?.start()
    }

    private fun lastObstacleX(): Float {
        if (obstacles.isEmpty()) return -9999f
        return obstacles.maxOf { it.x + it.width }
    }
    private fun spawnSpike(baseX: Float) {
        val safeX = safeSpawnX(baseX)

        val spikeW = 120f
        val spikeH = 110f

        // Always spawn spike on ground
        obstacles.add(
            Obstacle(
                safeX,
                spikeW,
                spikeH,
                ObstacleType.SPIKE
            )
        )
    }

    private fun spawnSingleObstacle(baseX: Float) {
        val safeX = safeSpawnX(baseX)
        val type = ObstacleType.values().random()
        val w = if (type == ObstacleType.SPIKE) 120f else 140f
        val h = if (type == ObstacleType.BOX) 140f else 110f
        obstacles.add(Obstacle(safeX, w, h, type))
    }

    private fun doUltraDash() {
        isDashing = true
        dashTimer = 0f
        tiltAngle = 14f
        shockwaveActive = true
        shockwaveRadius = 20f

        val it = obstacles.iterator()
        while (it.hasNext()) {
            val o = it.next()
            if (o.x < playerX + 400f) {
                spawnExplosion(o.x, screenGroundTop - o.height, Color.YELLOW)
                it.remove()
                score += 5
            }
        }
    }

    private fun spawnDoubleObstacleRow(baseX: Float) {
        val gap = 220f

        val type1 = ObstacleType.BOX
        val type2 = if (Random.nextBoolean()) ObstacleType.SPIKE else ObstacleType.BOX

        val w1 = 140f
        val h1 = 140f

        val w2 = if (type2 == ObstacleType.SPIKE) 120f else 140f
        val h2 = if (type2 == ObstacleType.BOX) 140f else 110f

        val safeX = safeSpawnX(baseX)

        obstacles.add(Obstacle(safeX, w1, h1, type1))
        obstacles.add(Obstacle(safeX + w1 + gap, w2, h2, type2))

        if (Random.nextFloat() < 0.6f) {
            coins.add(
                Coin(
                    safeX + w1 + gap / 2f,
                    screenGroundTop - 170f
                )
            )
        }
    }




    private fun spawnObstacleWithCoins(baseX: Float) {
        val safeX = safeSpawnX(baseX)

        // 1) Spawn one obstacle
        val type = if (Random.nextFloat() < 0.5f) ObstacleType.BOX else ObstacleType.SPIKE
        val w = if (type == ObstacleType.SPIKE) 120f else 140f
        val h = if (type == ObstacleType.BOX) 140f else 110f

        obstacles.add(Obstacle(safeX, w, h, type))

        // 2) Nice curved arc of coins OVER the obstacle
        val coinCount = 7               // more coins in the arc
        val spacing = 75f               // distance between coins
        val centerX = safeX + w / 2f    // middle above obstacle

        val startX = centerX - (spacing * (coinCount - 1) / 2f)

        // baseY is just above the obstacle top
        val baseY = screenGroundTop - h - 60f
        val arcHeight = 170f            // how high the curve goes

        for (i in 0 until coinCount) {
            // t goes from -1 to +1 across the arc
            val mid = (coinCount - 1) / 2f
            val t = (i - mid) / mid     // -1 .. 0 .. +1

            // Parabola: highest in the center, lower at ends
            val cx = startX + i * spacing
            val cy = baseY - arcHeight * (1f - t * t)

            coins.add(Coin(cx, cy))
        }
    }


    private fun spawnCoinWave(baseX: Float) {
        val count = 6
        val spacing = 90f
        val baseY = screenGroundTop - 220f - Random.nextFloat() * 100f
        val isDiagonalUp = Random.nextBoolean()
        for (i in 0 until count) {
            val cx = baseX + i * spacing
            val offsetY = if (isDiagonalUp) -i * 18f else i * 18f
            coins.add(Coin(cx, baseY + offsetY))
        }
    }

    private fun spawnPowerUpNearSafeGap(baseX: Float) {
        val px = baseX + 100f
        val py = screenGroundTop - 250f - Random.nextFloat() * 60f
        val type = PowerType.values().random()
        powerUps.add(PowerUp(px, py, 60f, type))
    }

    private fun safeSpawnX(baseX: Float): Float {
        val last = lastObstacleX()
        val minGap = 480f
        val dynamicGap = currentSpeed * 18f
        val gap = max(minGap, dynamicGap)
        return max(baseX, last + gap)
    }

    private fun spawnMixedObstacleCoinPower(baseX: Float) {
        val safeX = safeSpawnX(baseX)

        spawnSingleObstacle(safeX)

        val cCount = 3 + Random.nextInt(2)
        val baseY = screenGroundTop - 180f
        for (i in 0 until cCount) {
            coins.add(Coin(safeX + 100f + i * 75f, baseY - Random.nextFloat() * 70f))
        }

        if (Random.nextFloat() < 0.4f) {
            powerUps.add(PowerUp(safeX + 140f, baseY - 120f, 60f, PowerType.values().random()))
        }
    }
    private fun spawnCrate(baseX: Float) {
        val safeX = safeSpawnX(baseX)

        val boxW = 140f
        val boxH = 140f

        // Always spawn crate on ground
        obstacles.add(
            Obstacle(
                safeX,
                boxW,
                boxH,
                ObstacleType.BOX
            )
        )
    }

    // ----------------------
    //     COLLISIONS
    // ----------------------
    private fun checkCollisions() {
        val playerRect =
            RectF(
                playerX + 25f,
                playerY + 25f,
                playerX + playerSize - 25f,
                playerY + playerSize - 10f
            )

        // Obstacles
        obstacles.forEach { obs ->

            val rect = when (obs.type) {
                ObstacleType.SPIKE -> RectF(
                    obs.x + 20f,
                    screenGroundTop - obs.height + 20f,
                    obs.x + obs.width - 20f,
                    screenGroundTop
                )

                else -> RectF(
                    obs.x,
                    screenGroundTop - obs.height,
                    obs.x + obs.width,
                    screenGroundTop
                )
            }
            if (RectF.intersects(playerRect, rect) && !isDashing) hitPlayer()
            if (obs.x + obs.width < playerX && obs.x + obs.width > playerX - currentSpeed - 2f) {
                score += if (doubleScoreActive) 2 else 1
                if (currentSpeed < MAX_SPEED) currentSpeed += 0.06f
            }
        }

        // Coins
        val cIter = coins.iterator()
        while (cIter.hasNext()) {
            val c = cIter.next()
            val cr = RectF(c.x - c.r, c.y - c.r, c.x + c.r, c.y + c.r)
            if (RectF.intersects(playerRect, cr)) {
                cIter.remove()

                // 🔥 Double coins when DOUBLE power is active
                val gain = if (doubleScoreActive) 2 else 1

                runCoins += gain         // for boss threshold
                totalCoins += gain       // for shop / HUD
                prefs.edit().putInt("total_coins", totalCoins).apply()

                if (!bossActive && runCoins >= nextBossCoinThreshold) {
                    spawnBoss()
                    // next spawn at +10 coins
                    nextBossCoinThreshold += 10
                }

                // Show correct text (+1 or +2)
                spawnFloatingText(c.x, c.y, "+$gain", Color.YELLOW)
                spawnDust(c.x, c.y, Color.YELLOW)
                playCoinPickupSound()
            }
        }

        // PowerUps
        val pIter = powerUps.iterator()
        while (pIter.hasNext()) {
            val p = pIter.next()
            val pr = RectF(p.x, p.y, p.x + p.size, p.y + p.size)
            if (RectF.intersects(playerRect, pr)) {
                activatePowerUp(p.type)
                spawnFloatingText(playerX, playerY - 50f, p.type.name.replace("_", " "), Color.CYAN)
                playPowerPickupSound()
                pIter.remove()
            } else if (p.x < -120f) {
                pIter.remove()
            }
        }
    }

    private fun hitPlayer() {
        if (invincibilityTimer > 0f) return

        if (ghostActive) {
            spawnFloatingText(playerX, playerY, "PHASED", Color.LTGRAY)
            return
        }

        if (shieldActive) {
            shieldActive = false
            spawnFloatingText(playerX, playerY, "SHIELD BROKEN!", Color.CYAN)
            spawnExplosion(playerX, playerY, Color.CYAN)
            shakeDevice("BUMP")
            invincibilityTimer = 1.0f
            return
        }

        if (gameState != GameState.GAME_OVER) {
            shakeDevice("CRASH")
            postDelayed({ }, 50)
        }
        gameOver()
    }

    private fun gameOver() {
        reviveAvailable = totalCoins >= reviveCost
        if (gameState == GameState.GAME_OVER) return
        switchMusic(mpOver)

        deathSlowMoTimer = 0.9f
        targetDeathZoom = 0.86f
        deathDarkness = 0f
        shakeAmount = 40f
        spawnExplosion(playerX, playerY, Color.WHITE)
        playDeathSound()
        if (score > highScore) {
            highScore = score
            prefs.edit().putInt("high_score", highScore).apply()
        }
        gameState = GameState.GAME_OVER
    }

    private fun initMusic(c: Context) {
        try {
            mpMenu = MediaPlayer.create(c, R.raw.menu_soft_packa).apply {
                this?.isLooping = true
                this?.setVolume(0.5f, 0.5f)
            }

            mpGame = MediaPlayer.create(c, R.raw.game_soft_packa)
            mpBoss = MediaPlayer.create(c, R.raw.boss_soft_packa)
            mpOver = MediaPlayer.create(c, R.raw.over_soft_packa)


            listOf(mpMenu, mpGame, mpBoss, mpOver).forEach { mp ->
                mp?.isLooping = true
                mp?.setVolume(0.5f, 0.5f)
            }

            switchMusic(mpMenu)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun revivePlayer() {
        gameState = GameState.PLAYING
        obstacles.clear()
        powerUps.clear()
        coins.clear()
        playerY = screenGroundTop - playerSize
        playerVelocityY = 0f
        shieldActive = true
        isDashing = false
        dashTimer = 0f
        flashTimer = 0f
        deathSlowMoTimer = 0f
        deathZoom = 1f
        targetDeathZoom = 1f
        deathDarkness = 0f
    }

    // ----------------------
    //        DRAW
    // ----------------------
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Day background
        if (themeMode == ThemeMode.DAY && dayBackgroundBitmap != null) {
            val bmp = dayBackgroundBitmap!!
            val src = Rect(0, 0, bmp.width, bmp.height)
            val dst = Rect(0, 0, width, height)
            canvas.drawBitmap(bmp, src, dst, null)
        } else {
            val top = Color.parseColor("#0F2027")
            val bot = Color.parseColor("#203A43")
            bgPaint.shader =
                LinearGradient(0f, 0f, 0f, height.toFloat(), top, bot, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        }

        // Neon grid & parallax
        if (themeMode == ThemeMode.NIGHT) {
            drawMoon(canvas)

            mountains.forEach { canvas.drawBitmap(it.bitmap, it.x, it.y, null) }
            cloudsMid.forEach { canvas.drawBitmap(it.bitmap, it.x, it.y, null) }
            cloudsFront.forEach { canvas.drawBitmap(it.bitmap, it.x, it.y, null) }
        }

        // Stars
        starsBack.forEach { canvas.drawCircle(it.x, it.y, it.radius, starPaintBack) }
        starsFront.forEach { canvas.drawCircle(it.x, it.y, it.radius, starPaintFront) }

        // Ground
        val gColor =
            if (themeMode == ThemeMode.NIGHT) Color.parseColor("#111111") else Color.parseColor("#333333")
        val groundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gColor }
        canvas.drawRect(0f, screenGroundTop, width.toFloat(), height.toFloat(), groundPaint)

        val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = getSkinColor() }
        canvas.drawRect(0f, screenGroundTop, width.toFloat(), screenGroundTop + 10f, edgePaint)

        // Game world
        if (gameState != GameState.MENU) {
            canvas.save()
            if (gameState == GameState.GAME_OVER) {
                canvas.scale(deathZoom, deathZoom, width / 2f, height / 2f)
            }

            val isGlitching = isDashing || gameState == GameState.GAME_OVER
            if (isGlitching) {
                canvas.save(); canvas.translate(-12f, 0f)
                val saveLayerLeft =
                    canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), 200)
                drawGameWorld(canvas)
                canvas.restoreToCount(saveLayerLeft); canvas.restore()

                canvas.save(); canvas.translate(12f, 0f)
                val saveLayerRight =
                    canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), 200)
                drawGameWorld(canvas)
                canvas.restoreToCount(saveLayerRight); canvas.restore()
            } else {
                drawGameWorld(canvas)
            }
            drawHUD(canvas)
            canvas.restore()
        }

        // Menus
        when (gameState) {
            GameState.MENU -> drawMenu(canvas)
            GameState.SHOP -> drawShop(canvas)
            GameState.SETTINGS -> drawSettings(canvas)
            GameState.GAME_OVER -> drawGameOver(canvas)
            GameState.PAUSED -> drawPaused(canvas)
            else -> {}
        }

        // Vignette & flash
        val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                width / 2f,
                height / 2f,
                width.toFloat(),
                intArrayOf(Color.TRANSPARENT, Color.argb(180, 0, 0, 0)),
                floatArrayOf(0.6f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), vignettePaint)

        if (flashTimer > 0f) {
            val alpha = (flashTimer * 800).toInt().coerceIn(0, 180)
            canvas.drawColor(Color.argb(alpha, 255, 0, 0))
        }

        if (gameState == GameState.GAME_OVER) {
            canvas.drawColor(Color.argb((deathDarkness * 255).toInt(), 0, 0, 0))
        }
    }

    private fun drawNeonGrid(canvas: Canvas) {
        if (gridBitmap == null) {
            gridBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val c = Canvas(gridBitmap!!)

            val horizonY = height * 0.4f

            // horizontal
            for (i in 0..15) {
                val yPos = screenGroundTop + (i * 40f)
                if (yPos < height) {
                    gridPaint.alpha = (i * 15).coerceIn(0, 200)
                    c.drawLine(0f, yPos, width.toFloat(), yPos, gridPaint)
                }
            }

            // diagonal
            val centerX = width / 2f
            gridPaint.alpha = 80


            // sun
            val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f,
                    horizonY - 150f,
                    0f,
                    horizonY + 50f,
                    Color.parseColor("#FFD700"),
                    Color.parseColor("#FF0055"),
                    Shader.TileMode.CLAMP
                )
            }
            c.drawCircle(centerX, horizonY - 80f, 120f, sunPaint)
        }

        canvas.drawBitmap(gridBitmap!!, 0f, 0f, null)
    }

    private fun drawGameWorld(canvas: Canvas) {

        // ----- BOSS RENDER -----
        // ----- BOSS RENDER (RAVAN PNG) -----
        if (bossActive) {
            canvas.save()

            val shakeX = (Random.nextFloat() - 0.5f) * 3f
            canvas.drawBitmap(ravanBitmap, bossX + shakeX, bossY, null)

        }



        // Obstacles
        obstacles.forEach { obs ->
            val oy = screenGroundTop - obs.height



            when (obs.type) {
                ObstacleType.BOX -> {
                    if (crateBitmap != null)
                        canvas.drawBitmap(crateBitmap!!, obs.x, oy, null)
                    else
                        canvas.drawRect(
                            obs.x,
                            oy,
                            obs.x + obs.width,
                            screenGroundTop,
                            obstaclePaint
                        )
                }

                ObstacleType.SPIKE -> {
                    if (spikeBitmap != null)
                        canvas.drawBitmap(spikeBitmap!!, obs.x, oy, null)
                    else {
                        val path = Path().apply {
                            moveTo(obs.x, screenGroundTop)
                            lineTo(obs.x + obs.width / 2f, oy)
                            lineTo(obs.x + obs.width, screenGroundTop)
                            close()
                        }
                        canvas.drawPath(path, obstaclePaint)
                    }
                }

                ObstacleType.SAW -> {
                    canvas.save()
                    val cx = obs.x + obs.width / 2f
                    val cy = oy + obs.height / 2f
                    canvas.rotate(obs.rotation, cx, cy)
                    sawBitmap?.let { canvas.drawBitmap(it, obs.x, oy, null) }
                    canvas.restore()
                }
            }
        }

        // Coins
        coins.forEach { c ->
            if (coinBitmap != null) canvas.drawBitmap(coinBitmap!!, c.x - 35f, c.y - 35f, null)
            else {
                val coinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW }
                canvas.drawCircle(c.x, c.y, c.r, coinPaint)
            }
        }

        // Powerups
        powerUps.forEach { p ->
            val (col, icon) = when (p.type) {
                PowerType.SHIELD -> Color.CYAN to powerShieldBitmap
                PowerType.SLOW -> Color.MAGENTA to powerSlowBitmap
                PowerType.DOUBLE -> Color.YELLOW to powerDoubleBitmap
                PowerType.MAGNET -> Color.RED to powerMagnetBitmap
                PowerType.GHOST -> Color.LTGRAY to powerGhostBitmap
            }

            val cx = p.x + p.size / 2f
            val cy = p.y + p.size / 2f

            glowPaint.color = col
            glowPaint.alpha = 140
            glowPaint.setShadowLayer(25f, 0f, 0f, col)
            canvas.drawCircle(cx, cy, p.size * 0.40f, glowPaint)

            icon?.let { bmp ->
                val scale = (p.size * 2.5f) / bmp.width
                val iw = bmp.width * scale
                val ih = bmp.height * scale
                canvas.drawBitmap(
                    bmp,
                    null,
                    RectF(cx - iw / 2, cy - ih / 2, cx + iw / 2, cy + ih / 2),
                    null
                )
            }
        }

        // Player & FX
        canvas.save()
        playerPaint.alpha = if (ghostActive || invincibilityTimer > 0f) 128 else 255

        val shakeX = (Random.nextFloat() - 0.5f) * shakeAmount
        val shakeY = (Random.nextFloat() - 0.5f) * shakeAmount
        canvas.translate(shakeX, shakeY)
        canvas.scale(animScaleX, animScaleY, playerX + playerSize / 2f, playerY + playerSize / 2f)
        canvas.rotate(tiltAngle, playerX + playerSize / 2f, playerY + playerSize / 2f)

        // Trail
        trailPoints.forEach { t ->
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = getSkinColor()
                alpha = (t.alpha * 255).toInt().coerceIn(0, 255)
                maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawCircle(t.x, t.y, t.size / 2f, tp)
        }

        if (shockwaveActive) {
            val swPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.CYAN; style = Paint.Style.STROKE; strokeWidth = 6f
                alpha = (300 - shockwaveRadius).toInt().coerceIn(0, 255)
            }
            canvas.drawCircle(shockwaveX, shockwaveY, shockwaveRadius, swPaint)
        }

        val skinBitmap: Bitmap? = when (selectedSkin) {
            SkinType.DEFAULT -> playerBitmap
            SkinType.NINJA -> skinNinja
            SkinType.POLICE -> skinPolice
            SkinType.FIRE -> skinFire
            SkinType.THIEF -> skinThief
        }

        if (skinBitmap != null) {
            val scaled = Bitmap.createScaledBitmap(
                skinBitmap!!,
                playerSize.toInt(),
                playerSize.toInt(),
                true
            )
            canvas.drawBitmap(scaled, playerX, playerY, null)

        } else {
            val pPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = getSkinColor() }
            canvas.drawRoundRect(
                playerX,
                playerY,
                playerX + playerSize,
                playerY + playerSize,
                20f,
                20f,
                pPaint
            )
        }

        if (shieldActive) {
            val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.CYAN; style = Paint.Style.STROKE; strokeWidth = 5f; alpha = 150
            }
            // canvas.drawCircle(
            //       playerX + playerSize / 2f,
            //     playerY + playerSize / 2f,
            //     playerSize * 0.8f,
            //     sp
            // )
        }
        canvas.restore()

        // Particles
        particles.forEach { p ->
            val pp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = p.color; alpha = (p.life * 255).toInt().coerceIn(0, 255)
            }
            canvas.drawCircle(p.x, p.y, p.size, pp)
        }

        // Floating text
        floatingTexts.forEach { t ->
            textPaint.color = t.color
            textPaint.textSize = 45f
            textPaint.alpha = (t.life * 255).toInt().coerceIn(0, 255)
            canvas.drawText(t.text, t.x, t.y, textPaint)
        }
    }

    private fun shakeDevice(type: String) {
        if (!sfxEnabled) return
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val amp = when (type) {
                "TINY" -> 40
                "BUMP" -> 100
                "CRASH" -> 255
                else -> 100
            }
            val duration = when (type) {
                "TINY" -> 20L
                "BUMP" -> 60L
                "CRASH" -> 500L
                else -> 100L
            }
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(duration, amp))
        } else {
            vibrator.vibrate(50)
        }
    }

    private fun defeatBoss() {
        bossActive = false
        spawnExplosion(bossX, bossY, Color.YELLOW)
        spawnFloatingText(playerX, playerY, "BOSS DOWN +100", Color.YELLOW)
        totalCoins += 100
        prefs.edit().putInt("total_coins", totalCoins).apply()
        switchMusic(mpGame)
    }

    private fun checkDailyReward() {
        if (dailyRewardShown) return
        val dayNow = System.currentTimeMillis() / (24L * 60L * 60L * 1000L)
        val lastDay = prefs.getLong("daily_last_day", -1L)
        var streak = prefs.getInt("daily_streak", 0)

        if (dayNow == lastDay) {
            dailyRewardShown = true; return
        }
        streak = when {
            lastDay == -1L -> 1
            dayNow - lastDay == 1L -> (streak + 1).coerceAtMost(7)
            else -> 1
        }

        val reward = when (streak) {
            1 -> 20
            2 -> 40
            3 -> 60
            4 -> 80
            5 -> 120
            6 -> 160
            else -> 250
        }
        totalCoins += reward
        prefs.edit()
            .putInt("total_coins", totalCoins)
            .putLong("daily_last_day", dayNow)
            .putInt("daily_streak", streak)
            .apply()
        dailyRewardShown = true
        spawnFloatingText(
            width / 2f - 150f,
            screenGroundTop - 260f,
            "DAILY +$reward",
            Color.YELLOW
        )
    }

    // ===============================
// MOON / PLANET DRAW
// ===============================
    private fun drawMoon(canvas: Canvas) {

        // Position of moon (customize as needed)
        val px = width / 2f
        val py = height * 0.28f     // same height as old sun

        val radius = 120f           // you can change to 140f for bigger moon

        // ---- Gradient moon shader ----
        val moonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                px, py, radius,
                intArrayOf(
                    Color.rgb(220, 220, 230),  // bright center
                    Color.rgb(180, 180, 190),  // mid shade
                    Color.rgb(130, 130, 140)   // outer shadow
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }

        // Draw gradient moon
        canvas.drawCircle(px, py, radius, moonPaint)

        // ---- Rim light ----
        val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
            alpha = 120
        }
        canvas.drawCircle(px, py, radius + 2f, rimPaint)
// ---- Outer Glow / Aura ----
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            maskFilter = BlurMaskFilter(65f, BlurMaskFilter.Blur.NORMAL)
            color = Color.argb(140, 200, 200, 255)   // soft blue-white glow
        }

// Glow circle slightly larger than the moon
        canvas.drawCircle(px, py, radius * 1.55f, glowPaint)


// ---- Soft Light Bloom (secondary glow) ----
        val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            maskFilter = BlurMaskFilter(120f, BlurMaskFilter.Blur.NORMAL)
            color = Color.argb(90, 180, 200, 255)
        }

// Very large faint circle for cinematic feel
        canvas.drawCircle(px, py, radius * 2.4f, bloomPaint)

        // ---- Craters ----
        val craterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(140, 140, 150)
            alpha = 70
        }

        // Random-look craters
        canvas.drawCircle(px - 30f, py - 20f, 16f, craterPaint)
        canvas.drawCircle(px + 35f, py + 5f, 13f, craterPaint)
        canvas.drawCircle(px - 10f, py + 25f, 10f, craterPaint)
        canvas.drawCircle(px + 15f, py - 35f, 8f, craterPaint)
    }


    private fun drawHUD(canvas: Canvas) {


        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.color = Color.YELLOW
        textPaint.textSize = 58f
        canvas.drawText("COINS: $totalCoins", width - 50f, 100f, textPaint)

        var hx = 50f
        val hy = 150f
        if (shieldActive) {
            val left = shieldTimer.coerceAtLeast(0f)
            drawHudPill(canvas, hx, hy, "🛡", left, Color.parseColor("#FFC107"))
            hx += 170f
        }

        if (slowTimeActive) {
            val left = slowTimeTimer.coerceAtLeast(0f)
            drawHudPill(canvas, hx, hy, "⏳", left, Color.MAGENTA)
            hx += 170f
        }

        if (doubleScoreActive) {
            val left = doubleScoreTimer.coerceAtLeast(0f)
            drawHudPill(canvas, hx, hy, "✨", left, Color.YELLOW)
            hx += 170f
        }

        if (magnetActive) {
            val left = magnetTimer.coerceAtLeast(0f)
            drawHudPill(canvas, hx, hy, "🧲", left, Color.RED)
            hx += 170f
        }

        // 👻 NEW: Ghost timer pill
        if (ghostActive) {
            val left = ghostTimer.coerceAtLeast(0f)
            drawHudPill(canvas, hx, hy, "👻", left, Color.LTGRAY)
            hx += 170f
        }




        pauseBtn.set(width - 150f, 140f, width - 50f, 240f)
        drawButton(canvas, pauseBtn, "||", Color.DKGRAY)
    }

    private fun drawMenu(canvas: Canvas) {
        canvas.save()
        canvas.translate(0f, menuCamOffset)
        drawDarkOverlay(canvas)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.CYAN
        textPaint.textSize = 100f
        canvas.drawText("ALPHA RUNNER", width / 2f, 250f, textPaint)
        textPaint.textSize = 40f
        textPaint.color = Color.WHITE
        canvas.drawText("BEST: $highScore", width / 2f, 320f, textPaint)

        val btnW = 500f;
        val btnH = 120f;
        val cx = width / 2f;
        var y = height / 2f - 100f
        playBtn.set(cx - btnW / 2f, y, cx + btnW / 2f, y + btnH)
        drawButton(canvas, playBtn, "START", Color.parseColor("#00C853")); y += 150f
        shopBtn.set(cx - btnW / 2f, y, cx + btnW / 2f, y + btnH)
        drawButton(canvas, shopBtn, "SHOP", Color.parseColor("#FF6D00")); y += 150f
        settingsBtn.set(cx - btnW / 2f, y, cx + btnW / 2f, y + btnH)
        drawButton(canvas, settingsBtn, "SETTINGS", Color.parseColor("#2962FF")); y += 150f
        exitBtn.set(cx - btnW / 2f, y, cx + btnW / 2f, y + btnH)
        drawButton(canvas, exitBtn, "EXIT", Color.parseColor("#D50000"))
        canvas.restore()
    }

    private fun drawShop(canvas: Canvas) {
        drawDarkOverlay(canvas)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 80f
        textPaint.color = Color.YELLOW
        canvas.drawText("ARMORY", width / 2f, 150f, textPaint)
        val sub = Paint(textPaint).apply { textSize = 50f; color = Color.WHITE }
        canvas.drawText("Coins: $totalCoins", width / 2f, 230f, sub)

        backBtn.set(50f, 50f, 250f, 150f)
        drawButton(canvas, backBtn, "< BACK", Color.DKGRAY)

        // ----- SKIN SHOP (sprite system) -----
        val centerX = width / 2f
        var y = 350f

        textPaint.textSize = 60f
        textPaint.color = Color.CYAN
        canvas.drawText("SKINS", centerX, y, textPaint)
        y += 60f

        val skinSize = 220f
        val spacing = 60f

        val skins = listOf(
            SkinType.DEFAULT,
            SkinType.NINJA,
            SkinType.POLICE,
            SkinType.FIRE,
            SkinType.THIEF
        )

        skinBoxRects.clear()

// Grid: 3 skins per row
        val cols = 3
        val startX = (width - (cols * skinSize + (cols - 1) * spacing)) / 2f
        var xPos = startX
        var yPos = 350f

        for ((i, skin) in skins.withIndex()) {

            val rect = RectF(xPos, yPos, xPos + skinSize, yPos + skinSize)
            skinBoxRects[skin] = rect

            val bmp = when (skin) {
                SkinType.DEFAULT -> playerBitmap
                SkinType.NINJA -> skinNinja
                SkinType.POLICE -> skinPolice
                SkinType.FIRE -> skinFire
                SkinType.THIEF -> skinThief
            }

            bmp?.let {
                val scaled = Bitmap.createScaledBitmap(it, skinSize.toInt(), skinSize.toInt(), true)
                canvas.drawBitmap(scaled, rect.left, rect.top, null)
            }

            // Cost / Owned
            textPaint.textSize = 45f
            textPaint.color =
                if (ownedSkins.contains(skin.name)) Color.GREEN else Color.WHITE

            canvas.drawText(
                if (ownedSkins.contains(skin.name)) "Owned" else "50 Coins",
                rect.centerX(),
                rect.bottom + 45f,
                textPaint
            )

            // Highlight selected skin
            if (selectedSkin == skin) {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 8f
                    color = Color.CYAN
                }
                canvas.drawRoundRect(rect, 25f, 25f, p)
            }

            // Move x/y for next skin
            xPos += skinSize + spacing
            if ((i + 1) % cols == 0) {
                xPos = startX
                yPos += skinSize + 140f
            }
        }
    }

    private fun drawGameOver(canvas: Canvas) {
        drawDarkOverlay(canvas)

        val centerX = width / 2f

        // 1️⃣ REVIVE BUTTON (TOP)
        if (reviveAvailable) {
            reviveRect.set(
                centerX - 200f,
                height / 2f - 280f,   // moved to top
                centerX + 200f,
                height / 2f - 220f
            )
            drawButton(canvas, reviveRect, "REVIVE ($reviveCost)", Color.CYAN)
        }

        // 2️⃣ GAME OVER TITLE
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 100f
        textPaint.color = Color.RED
        canvas.drawText("GAME OVER", centerX, height / 2f - 80f, textPaint)

        // 3️⃣ SCORE + BEST
        textPaint.textSize = 50f
        textPaint.color = Color.WHITE
        canvas.drawText("SCORE: $score", centerX, height / 2f + 20f, textPaint)
        canvas.drawText("BEST: $highScore", centerX, height / 2f + 80f, textPaint)

        // 4️⃣ RETRY BUTTON
        restartBtn.set(
            centerX - 200f,
            height / 2f + 130f,
            centerX + 200f,
            height / 2f + 250f
        )
        drawButton(canvas, restartBtn, "RETRY", Color.WHITE)

        // 5️⃣ MENU BUTTON
        menuBtn.set(
            centerX - 200f,
            height / 2f + 280f,
            centerX + 200f,
            height / 2f + 400f
        )
        drawButton(canvas, menuBtn, "MENU", Color.DKGRAY)
    }

    private fun drawSettings(canvas: Canvas) {
        drawDarkOverlay(canvas)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 90f
        textPaint.color = Color.CYAN
        canvas.drawText("SETTINGS", width / 2f, 150f, textPaint)

        // TOP-LEFT BACK BUTTON (same as shop)
        settingsBackRect.set(50f, 50f, 250f, 150f)
        drawButton(canvas, settingsBackRect, "< BACK", Color.DKGRAY)


        val centerX = width / 2f
        var y = 260f
        val toggleW = 600f
        val toggleH = 110f

        musicToggleRect.set(centerX - toggleW / 2, y, centerX + toggleW / 2, y + toggleH)
        drawButton(
            canvas,
            musicToggleRect,
            if (musicEnabled) "MUSIC: ON" else "MUSIC: OFF",
            Color.DKGRAY
        )
        y += 140f

        sfxToggleRect.set(centerX - toggleW / 2, y, centerX + toggleW / 2, y + toggleH)
        drawButton(
            canvas,
            sfxToggleRect,
            if (sfxEnabled) "SFX: ON" else "SFX: OFF",
            Color.DKGRAY
        )
        y += 140f

        textPaint.textSize = 50f
        textPaint.color = Color.WHITE
        canvas.drawText("THEME", centerX, y, textPaint)
        y += 60f

        val themeW = 280f
        val themeH = 100f
        val spacing = 40f

        themeDayRect.set(centerX - themeW - spacing, y, centerX - spacing, y + themeH)
        themeNightRect.set(centerX + spacing, y, centerX + themeW + spacing, y + themeH)

        drawButton(canvas, themeDayRect, "DAY MODE", Color.parseColor("#4CAF50"))
        drawButton(canvas, themeNightRect, "NIGHT MODE", Color.parseColor("#3F51B5"))

        y += 140f

        textPaint.textSize = 46f
        textPaint.color = Color.LTGRAY
        canvas.drawText("GRAPHICS", centerX, y, textPaint)
        y += 55f

        val qW = 220f
        val qH = 90f

        gfxLowRect.set(centerX - qW - 40f, y, centerX - 40f, y + qH)
        gfxMedRect.set(centerX + 40f, y, centerX + qW + 40f, y + qH)

        drawButton(canvas, gfxLowRect, "LOW", Color.DKGRAY)
        drawButton(canvas, gfxMedRect, "MED", Color.DKGRAY)

        y += 130f

        gfxHighRect.set(centerX - qW / 2, y, centerX + qW / 2, y + qH)
        drawButton(canvas, gfxHighRect, "HIGH", Color.DKGRAY)

        y += 150f


    }

    private fun drawPaused(canvas: Canvas) {
        drawDarkOverlay(canvas)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 80f
        textPaint.color = Color.WHITE
        canvas.drawText("PAUSED", width / 2f, height / 2f - 200f, textPaint)

        val cx = width / 2f

        resumeBtn.set(cx - 200f, height / 2f - 50f, cx + 200f, height / 2f + 70f)
        drawButton(canvas, resumeBtn, "RESUME", Color.parseColor("#00C853"))

        pausedMenuBtn.set(cx - 200f, height / 2f + 130f, cx + 200f, height / 2f + 250f)
        drawButton(canvas, pausedMenuBtn, "MENU", Color.parseColor("#D50000"))
    }


    private fun drawHudPill(
        canvas: Canvas,
        x: Float,
        y: Float,
        icon: String,
        secondsLeft: Float?,
        tint: Int
    ) {
        val rect = RectF(x, y, x + 160f, y + 70f)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 0, 0, 0) }
        canvas.drawRoundRect(rect, 24f, 24f, bg)
        val border =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 3f; color = tint
            }
        canvas.drawRoundRect(rect, 24f, 24f, border)

        textPaint.textSize = 38f; textPaint.color = Color.WHITE
        val cy = rect.centerY() - (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(icon, rect.left + 14f, cy, textPaint)

        if (secondsLeft != null) {
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("${secondsLeft.toInt() + 1}s", rect.right - 12f, cy, textPaint)
        }
    }

    private fun drawButton(canvas: Canvas, rect: RectF, label: String, btnColor: Int) {
        val radius = 35f
        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                rect.left,
                rect.top,
                rect.right,
                rect.bottom,
                lighten(btnColor, 0.35f),
                darken(btnColor, 0.35f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, radius, radius, basePaint)

        val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                rect.left,
                rect.top,
                rect.left,
                rect.top + rect.height() * 0.45f,
                Color.argb(160, 255, 255, 255),
                Color.argb(0, 255, 255, 255),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(
            RectF(rect.left + 6, rect.top + 6, rect.right - 6, rect.centerY()),
            radius,
            radius,
            shine
        )

        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 3f; alpha = 130
        }
        canvas.drawRoundRect(rect, radius, radius, border)

        textPaint.textAlign = Paint.Align.CENTER; textPaint.textSize = 50f; textPaint.color =
            Color.WHITE
        val cy = rect.centerY() - (textPaint.fontMetrics.ascent + textPaint.fontMetrics.descent) / 2
        canvas.drawText(label, rect.centerX(), cy, textPaint)
    }

    fun releaseResources() {
        listOf(mpMenu, mpGame, mpBoss, mpOver).forEach { mp ->
            mp?.release()
        }
        mpMenu = null
        mpGame = null
        mpBoss = null
        mpOver = null
        currentMusic = null

        soundPool.release()
    }

    private fun lighten(color: Int, factor: Float): Int {
        val r = min(255, ((Color.red(color) * (1 - factor)) + 255 * factor).toInt())
        val g = min(255, ((Color.green(color) * (1 - factor)) + 255 * factor).toInt())
        val b = min(255, ((Color.blue(color) * (1 - factor)) + 255 * factor).toInt())
        return Color.rgb(r, g, b)
    }

    private fun darken(color: Int, factor: Float): Int {
        val r = max(0, (Color.red(color) * (1 - factor)).toInt())
        val g = max(0, (Color.green(color) * (1 - factor)).toInt())
        val b = max(0, (Color.blue(color) * (1 - factor)).toInt())
        return Color.rgb(r, g, b)
    }

    private fun drawDarkOverlay(canvas: Canvas) {
        canvas.drawColor(Color.argb(220, 10, 10, 20))
    }

    // ----------------------
    //       INPUT
    // ----------------------
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        val x = event?.x ?: return false
        val y = event.y
        targetScaleX = 0.93f
        targetScaleY = 0.93f
        postDelayed({ targetScaleX = 1f; targetScaleY = 1f }, 120)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                swipeConsumed = false

                when (gameState) {
                    GameState.MENU -> {
                        if (playBtn.contains(x, y)) {
                            playClickSound(); stopAllMusic()
                            resetGame(); gameState = GameState.PLAYING
                            switchMusic(mpGame)
                        } else if (shopBtn.contains(x, y)) {
                            playClickSound(); stopAllMusic()

                            gameState = GameState.SHOP
                        } else if (settingsBtn.contains(x, y)) {
                            playClickSound(); stopAllMusic()

                            gameState = GameState.SETTINGS
                        } else if (exitBtn.contains(x, y)) {
                            playClickSound(); stopAllMusic()

                            (context as? Activity)?.finish()
                        }
                    }

                    GameState.SETTINGS -> {
                        when {
                            musicToggleRect.contains(x, y) -> {
                                musicEnabled = !musicEnabled
                                prefs.edit().putBoolean("music_enabled", musicEnabled).apply()
                                if (!musicEnabled) {
                                    currentMusic?.pause()
                                } else {
                                    when (gameState) {
                                        GameState.MENU ->  switchMusic(mpMenu)

                                        GameState.PLAYING -> switchMusic(mpGame)
                                        GameState.GAME_OVER -> switchMusic(mpOver)
                                        else -> {}
                                    }
                                }
                                playClickSound()
                                stopAllMusic()
                            }

                            sfxToggleRect.contains(x, y) -> {
                                sfxEnabled = !sfxEnabled
                                prefs.edit().putBoolean("sfx_enabled", sfxEnabled).apply()
                                playClickSound()
                            }

                            gfxLowRect.contains(x, y) -> {
                                gfxQuality = GfxQuality.LOW; saveGfxQuality(); playClickSound()
                            }

                            gfxMedRect.contains(x, y) -> {
                                gfxQuality = GfxQuality.MEDIUM; saveGfxQuality(); playClickSound()
                            }

                            gfxHighRect.contains(x, y) -> {
                                gfxQuality = GfxQuality.HIGH; saveGfxQuality(); playClickSound()
                            }

                            themeDayRect.contains(x, y) -> {
                                themeMode = ThemeMode.DAY
                                prefs.edit().putString("theme_mode", "DAY").apply()
                                playClickSound()
                                invalidate()
                            }

                            themeNightRect.contains(x, y) -> {
                                themeMode = ThemeMode.NIGHT
                                prefs.edit().putString("theme_mode", "NIGHT").apply()
                                playClickSound()
                                invalidate()
                            }

                            settingsBackRect.contains(x, y) -> {
                                playClickSound()
                                gameState = GameState.MENU
                                switchMusic(mpMenu)
                            }
                        }
                    }

                    GameState.SHOP -> handleShopTouch(x, y)

                    GameState.GAME_OVER -> {
                        if (reviveAvailable && reviveRect.contains(x, y)) {
                            totalCoins -= reviveCost
                            prefs.edit().putInt("total_coins", totalCoins).apply()
                            revivePlayer()
                        } else if (restartBtn.contains(x, y)) {
                            resetGame(); gameState = GameState.PLAYING
                        } else if (menuBtn.contains(x, y)) {
                            stopAllMusic()
                            gameState = GameState.MENU
                            switchMusic(mpMenu)
                        }
                    }

                    GameState.PAUSED -> {
                        when {
                            resumeBtn.contains(x, y) -> {
                                playClickSound()
                                gameState = GameState.PLAYING
                                switchMusic(mpGame)
                            }

                            pausedMenuBtn.contains(x, y) -> {
                                playClickSound()
                                stopAllMusic()
                                gameState = GameState.MENU
                                switchMusic(mpMenu)
                            }
                        }
                    }


                    GameState.PLAYING -> {
                        if (pauseBtn.contains(x, y)) {
                            stopAllMusic()
                            gameState = GameState.PAUSED; return true
                        }
                        if (coyoteTime > 0f) {
                            isJumping = true; jumpHoldTime = 0f; playerVelocityY =
                                JUMP_FORCE; canDoubleJump =
                                true
                            spawnDust(playerX, playerY + playerSize, Color.WHITE); playJumpSound()
                        } else if (canDoubleJump && !isDashing) {
                            isJumping = true; jumpHoldTime = 0f; playerVelocityY =
                                JUMP_FORCE * 0.8f; canDoubleJump = false
                            spawnDust(playerX, playerY + playerSize, Color.CYAN); playJumpSound()
                        }
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (gameState == GameState.PLAYING && !swipeConsumed) {
                    val dx = x - downX
                    if (dx > 100 && abs(y - downY) < 100 && dashCooldown <= 0f) {
                        isDashing = true; dashTimer = 0f; dashCooldown =
                            dashCooldownDuration; swipeConsumed =
                            true; tiltAngle = 10f
                        shockwaveActive = true; shockwaveX =
                            playerX + playerSize / 2f; shockwaveY =
                            playerY + playerSize / 2f; shockwaveRadius =
                            20f
                        playDashSound()
                    }
                }
            }

            MotionEvent.ACTION_UP -> isJumping = false
        }
        return true
    }

    private fun handleShopTouch(x: Float, y: Float) {
        if (backBtn.contains(x, y)) {
            gameState = GameState.MENU
            switchMusic(mpMenu)
            return
        }

        // Skin taps
        for ((skin, rect) in skinBoxRects) {
            if (rect.contains(x, y)) {
                val cost = getSkinCost(skin)
                if (ownedSkins.contains(skin.name)) {
                    selectedSkin = skin
                    saveSkinSelection()
                    playClickSound()
                } else if (totalCoins >= cost) {
                    totalCoins -= cost
                    ownedSkins.add(skin.name)
                    selectedSkin = skin
                    prefs.edit().putInt("total_coins", totalCoins).apply()
                    saveSkinSelection()
                    playCoinPickupSound()
                } else {
                    spawnFloatingText(rect.centerX(), rect.top - 20f, "NOT ENOUGH", Color.RED)
                    playClickSound()
                }
                return
            }
        }

        // Upgrades
        fun tryBuyUpgrade(currentLevel: Int, cost: Int, onBuy: () -> Unit) {
            if (currentLevel < maxUpgradeLevel && totalCoins >= cost) {
                totalCoins -= cost
                onBuy()
                prefs.edit().putInt("total_coins", totalCoins).apply()
                saveUpgrades()
                playCoinPickupSound()
            }
        }

        if (upMagnetRect.contains(x, y))
            tryBuyUpgrade(
                magnetLevel,
                if (magnetLevel < 5) upgradeCost[magnetLevel] else 0
            ) { magnetLevel++ }
        if (upShieldRect.contains(x, y))
            tryBuyUpgrade(
                shieldLevel,
                if (shieldLevel < 5) upgradeCost[shieldLevel] else 0
            ) { shieldLevel++ }
        if (upSlowRect.contains(x, y))
            tryBuyUpgrade(
                slowLevel,
                if (slowLevel < 5) upgradeCost[slowLevel] else 0
            ) { slowLevel++ }
        if (upScoreRect.contains(x, y))
            tryBuyUpgrade(
                scoreLevel,
                if (scoreLevel < 5) upgradeCost[scoreLevel] else 0
            ) { scoreLevel++ }
    }

    private fun resetGame() {
        score = 0; currentSpeed = BASE_SPEED;
        runCoins = 0
        nextBossCoinThreshold = 33
        bossActive = false
        bossProjectiles.clear()
        difficultyLevel = 1f
        playerY = screenGroundTop - playerSize; playerVelocityY = 0f
        isJumping = false; canDoubleJump = false; isDashing = false; dashTimer = 0f
        shieldActive = false; slowTimeActive = false; doubleScoreActive = false; magnetActive =
            false; flashTimer =
            0f
        bossActive = false

        obstacles.clear(); coins.clear(); powerUps.clear(); particles.clear(); floatingTexts.clear()
        deathSlowMoTimer = 0f; deathZoom = 1f; targetDeathZoom = 1f; deathDarkness = 0f
        animScaleX = 1f; animScaleY = 1f; coyoteTime = 0f; spawnTimer = 0f
    }

    // ----------------------
    //      HELPERS / FX
    // ----------------------
    private fun spawnExplosion(x: Float, y: Float, color: Int) {
        val factor = qualityFactor()
        val count = (20 * factor).toInt().coerceAtLeast(4)
        repeat(count) {
            val a = Random.nextFloat() * (2f * Math.PI).toFloat()
            val s = Random.nextFloat() * 15f
            particles.add(Particle(x, y, cos(a) * s, sin(a) * s, 15f, 1f, color))
        }
    }


    private fun qualityFactor(): Float = when (gfxQuality) {
        GfxQuality.LOW -> 0.4f; GfxQuality.MEDIUM -> 0.7f; GfxQuality.HIGH -> 1.0f
    }

    private fun spawnDust(x: Float, y: Float, color: Int) {
        val factor = qualityFactor()
        val count = (6 * factor).toInt().coerceAtLeast(2)
        repeat(count) {
            particles.add(
                Particle(
                    x,
                    y,
                    Random.nextFloat() * 10f - 5f,
                    Random.nextFloat() * -6f,
                    10f,
                    0.5f,
                    color
                )
            )
        }
    }

    private fun spawnLandingDust() {
        spawnDust(playerX + playerSize / 2f, screenGroundTop, Color.LTGRAY)
    }

    private fun spawnFloatingText(x: Float, y: Float, text: String, color: Int) {
        floatingTexts.add(FloatingText(x, y, text, color, 1f))
        if (floatingTexts.size > 25) floatingTexts.removeAt(0)
    }

    private fun updateParticles() {
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.x += p.vx; p.y += p.vy; p.life -= 0.03f; p.size *= 0.95f
            if (p.life <= 0f) it.remove()
        }
        if (particles.size > 200) particles.removeAt(0)
    }


    private fun fastInvSqrt(x: Float): Float {
        val half = 0.5f * x
        var i = java.lang.Float.floatToIntBits(x)
        i = 0x5f3759df - (i shr 1)
        var y = java.lang.Float.intBitsToFloat(i)
        y *= (1.5f - half * y * y)
        return y
    }

    // ----------------------------------------------------------
// FLOATING TEXT
// ----------------------------------------------------------
    private fun updateFloatingText() {
        val it = floatingTexts.iterator()
        while (it.hasNext()) {
            val t = it.next()
            t.y -= 2f
            t.life -= 0.015f

            if (t.life <= 0f)
                it.remove()
        }
    }

    // ----------------------------------------------------------
// POWER UPS
// ----------------------------------------------------------
    private fun updatePowerUps(timeScale: Float) {
        val it = powerUps.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.x -= currentSpeed * 0.7f

            if (p.x < -200f)
                it.remove()
        }
    }

    // ----------------------------------------------------------
// COINS + MAGNET
// ----------------------------------------------------------
    private fun updateCoins(timeScale: Float) {
        val it = coins.iterator()
        while (it.hasNext()) {
            val c = it.next()
            c.x -= currentSpeed * timeScale

            if (magnetActive) {
                val dx = (playerX - c.x)
                val dy = (playerY - c.y)
                val distSq = dx * dx + dy * dy + 0.0001f

                val inv = fastInvSqrt(distSq)   // no sqrt
                val speed = 30f

                c.x += dx * inv * speed
                c.y += dy * inv * speed
            }

            if (c.x < -150f)
                it.remove()
        }
    }

    // ----------------------------------------------------------
// PARALLAX
// ----------------------------------------------------------
    private fun updateParallax() {

        fun moveLayer(list: MutableList<ParallaxLayer>) {
            if (list.isEmpty()) return

            val w = list[0].bitmap.width.toFloat()

            for (l in list) {
                l.x -= l.speed
                if (l.x < -w) {
                    l.x += w * list.size
                }
            }
        }

        moveLayer(mountains)
        moveLayer(cloudsMid)
        moveLayer(cloudsFront)

        // Stars
        fun moveStar(list: MutableList<Star>, factor: Float) {
            list.forEach { s ->
                s.x -= s.speed * factor
                if (s.x < 0f) {
                    s.x = width.toFloat()
                    s.y = Random.nextFloat() * height
                }
            }
        }

        moveStar(starsBack, 0.4f)
        moveStar(starsFront, 0.8f)
    }

    // ----------------------------------------------------------
// INIT STARS
// ----------------------------------------------------------
    private fun initStars(w: Int, h: Int) {
        starsBack.clear()
        starsFront.clear()

        repeat(28) {
            starsBack.add(Star(Random.nextFloat() * w, Random.nextFloat() * h, 3f, 1f))
        }

        repeat(20) {
            starsFront.add(Star(Random.nextFloat() * w, Random.nextFloat() * h, 5f, 2f))
        }
    }

    // ----------------------------------------------------------
// LOAD SKINS (OLD SYSTEM REMOVED)
// Only upgrade values remain.
// ----------------------------------------------------------
    private fun loadSkins() {
        magnetLevel = prefs.getInt("up_magnet", 0)
        shieldLevel = prefs.getInt("up_shield", 0)
        slowLevel = prefs.getInt("up_slow", 0)
        scoreLevel = prefs.getInt("up_score", 0)
    }

    // ----------------------------------------------------------
// SAVE UPGRADES
// ----------------------------------------------------------
    private fun saveUpgrades() {
        prefs.edit()
            .putInt("up_magnet", magnetLevel)
            .putInt("up_shield", shieldLevel)
            .putInt("up_slow", slowLevel)
            .putInt("up_score", scoreLevel)
            .apply()
    }

    // ----------------------------------------------------------
// SOUND INIT
// ----------------------------------------------------------
    private fun initSound(c: Context) {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setAudioAttributes(attrs)
            .setMaxStreams(6)
            .build()

        soundPool.setOnLoadCompleteListener { _, _, _ -> soundLoaded = true }

        try {
            jumpSound = soundPool.load(c, R.raw.jump, 1)
            deathSound = soundPool.load(c, R.raw.death, 1)
            coinSound = soundPool.load(c, R.raw.coin, 1)
            powerSound = soundPool.load(c, R.raw.power, 1)
            dashSound = soundPool.load(c, R.raw.dash, 1)
            clickSound = soundPool.load(c, R.raw.click, 1)
        } catch (e: Exception) {
            println("Sounds not found, audio disabled.")
        }
    }

    // ----------------------------------------------------------
// PLAY SFX
// ----------------------------------------------------------
    private fun playSfx(id: Int, pitch: Float = 1f) {
        if (!soundLoaded || !sfxEnabled || id == 0) return
        soundPool.play(id, 1f, 1f, 1, 0, pitch)
    }

    private fun playJumpSound() = playSfx(jumpSound, 1.0f + Random.nextFloat() * 0.1f)
    private fun playDeathSound() = playSfx(deathSound)
    private fun playCoinPickupSound() = playSfx(coinSound, 1.1f)
    private fun playPowerPickupSound() = playSfx(powerSound, 0.9f)
    private fun playDashSound() = playSfx(dashSound, 1.2f)
    private fun playClickSound() = playSfx(clickSound)
    private fun stopAllMusic() {
        mpMenu?.pause()
        mpGame?.pause()
        mpBoss?.pause()
        mpOver?.pause()
    }

    // ----------------------------------------------------------
// ACTIVATE POWERUP
// ----------------------------------------------------------
    private fun activatePowerUp(type: PowerType) {
        val addTime = 5f   // every pickup adds +5s

        when (type) {
            PowerType.SHIELD -> {
                shieldActive = true
                shieldTimer += 5f      // add 5 seconds
                if (shieldTimer > 15f) shieldTimer = 15f   // max +15s
            }

            PowerType.SLOW -> {
                slowTimeActive = true
                slowTimeTimer += addTime
                if (slowTimeTimer > addTime * 3) slowTimeTimer = addTime * 3
            }

            PowerType.DOUBLE -> {
                doubleScoreActive = true
                doubleScoreTimer += addTime
                if (doubleScoreTimer > addTime * 3) doubleScoreTimer = addTime * 3
            }

            PowerType.MAGNET -> {
                magnetActive = true
                magnetTimer += addTime
                if (magnetTimer > addTime * 3) magnetTimer = addTime * 3
            }

            PowerType.GHOST -> {
                ghostActive = true
                ghostTimer += addTime
                if (ghostTimer > addTime * 3) ghostTimer = addTime * 3
            }
        }
    }

    // ----------------------------------------------------------
// BUTTON SETUP
// ----------------------------------------------------------
    private fun setupButtons(w: Int, h: Int) {
        val cx = w / 2f
        val cy = h / 2f
        val btnW = 500f
        val btnH = 120f
        var y = cy - 140f

        playBtn.set(cx - btnW / 2f, y, cx + btnW / 2f, y + btnH)
        y += 150f

        shopBtn.set(cx - btnW / 2f, y, cx + btnW / 2f, y + btnH)
        y += 150f

        settingsBtn.set(cx - btnW / 2f, y, cx + btnW / 2f, y + btnH)
        y += 150f

        exitBtn.set(cx - btnW / 2f, y, cx + btnW / 2f, y + btnH)

        restartBtn.set(cx - btnW / 2f, cy + 100f, cx + btnW / 2f, cy + 100f + btnH)
        menuBtn.set(cx - btnW / 2f, cy + 250f, cx + btnW / 2f, cy + 250f + btnH)

        pauseBtn.set(w - 150f, 140f, w - 50f, 240f)
        backBtn.set(50f, 50f, 250f, 150f)
    }

    // ----------------------------------------------------------
// PAUSE / RESUME
// ----------------------------------------------------------
    fun pause() {
        loopRunning = false
        Choreographer.getInstance().removeFrameCallback(this)
        currentMusic?.pause()
    }

    fun resume() {
        if (!loopRunning) {
            loopRunning = true
            Choreographer.getInstance().postFrameCallback(this)

            if (musicEnabled)
                currentMusic?.start()
        }
    }
}
