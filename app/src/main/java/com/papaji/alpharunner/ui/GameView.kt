package com.papaji.alpharunner.ui

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.papaji.alpharunner.R
import com.papaji.alpharunner.engine.GameEngine
import com.papaji.alpharunner.engine.GameEngine.Mode
import com.papaji.alpharunner.engine.GameSpawner.EnginePowerType
import com.papaji.alpharunner.render.GameRenderer
import com.papaji.alpharunner.render.UiRenderer
import com.papaji.alpharunner.sound.SoundManager
import kotlin.math.abs
import kotlin.random.Random

/**
 * GameView — the slim coordinator for Alpha Runner.
 *
 * Responsibilities (and ONLY these):
 *   1. Owns all Android resources: Bitmaps, SharedPreferences, Vibrator.
 *   2. Drives the Choreographer loop and routes fixed-step ticks to GameEngine.
 *   3. Delegates all drawing to GameRenderer (world) and UiRenderer (UI).
 *   4. Routes touch events to UiRenderer hit-tests and GameEngine input methods.
 *   5. Bridges the engine listener interface to sound / vibration / game-state.
 *
 * Every piece of game logic lives in GameEngine.
 * Every draw call lives in GameRenderer or UiRenderer.
 * This file intentionally has no physics, no spawn math, no Paint objects.
 */
class GameView(context: Context) : View(context), Choreographer.FrameCallback,
    GameEngine.GameEngineListener {

    // ═══════════════════════════════════════════════════════════════════════
    // Screen-level game state (what the player sees — not physics state)
    // ═══════════════════════════════════════════════════════════════════════

    enum class GameState { MENU, PLAYING, PAUSED, SHOP, SETTINGS, GAME_OVER }
    private var gameState = GameState.MENU

    // Skin identity
    enum class SkinType { DEFAULT, NINJA, POLICE, FIRE, THIEF }
    private var selectedSkin: SkinType = SkinType.DEFAULT
    private var ownedSkins   = mutableSetOf("DEFAULT")

    // Upgrade levels (loaded from prefs; pushed to engine at run start)
    private var magnetLevel  = 0
    private var shieldLevel  = 0
    private var slowLevel    = 0
    private var scoreLevel   = 0
    private val maxUpgradeLevel = 5
    private val upgradeCost     = listOf(50, 100, 150, 200, 300)

    // Theme
    enum class ThemeMode { NIGHT, DAY }
    private var themeMode: ThemeMode = ThemeMode.NIGHT

    // Audio preference flags (SoundManager reads these at construction)
    private var musicEnabled = true
    private var sfxEnabled   = true

    // Tutorial
    private var showTutorial  = false
    private var tutorialAlpha = 0f

    // Menu pulse (drives title glow animation in UiRenderer.drawMenu)
    private var menuPulse = 0f

    // Daily reward gate
    private var dailyRewardShown = false

    // ═══════════════════════════════════════════════════════════════════════
    // Sub-modules
    // ═══════════════════════════════════════════════════════════════════════

    private val prefs: SharedPreferences =
        context.getSharedPreferences("alpha_runner_prefs", Context.MODE_PRIVATE)

    private val sound    = SoundManager(context)
    private val engine   = GameEngine(sound, this, prefs)
    private val renderer = GameRenderer(engine)
    private val ui       = UiRenderer(engine)

    private val vibrator = context.getSystemService(Vibrator::class.java)

    // ═══════════════════════════════════════════════════════════════════════
    // Parallax + stars (owned here; renderer reads as typed lists)
    // ═══════════════════════════════════════════════════════════════════════

    private val citySkyline = mutableListOf<GameRenderer.ParallaxLayer>()  // far layer
    private val mountains   = mutableListOf<GameRenderer.ParallaxLayer>()  // mid layer
    private val cloudsMid   = mutableListOf<GameRenderer.ParallaxLayer>()
    private val cloudsFront = mutableListOf<GameRenderer.ParallaxLayer>()  // near layer
    private val starsBack   = mutableListOf<GameRenderer.StarLayer>()
    private val starsFront  = mutableListOf<GameRenderer.StarLayer>()

    // ═══════════════════════════════════════════════════════════════════════
    // Bitmap fields
    // ═══════════════════════════════════════════════════════════════════════

    private var playerBitmap:        Bitmap? = null
    private var skinNinja:           Bitmap? = null
    private var skinPolice:          Bitmap? = null
    private var skinFire:            Bitmap? = null
    private var skinThief:           Bitmap? = null
    private var crateBitmap:         Bitmap? = null
    private var spikeBitmap:         Bitmap? = null
    private var sawBitmap:           Bitmap? = null
    private var coinBitmap:          Bitmap? = null
    private var ravanBitmap:         Bitmap? = null
    private var dayBackgroundBitmap: Bitmap? = null
    private var vignetteBitmap:      Bitmap? = null
    private var moonBitmap:          Bitmap? = null
    private var moonDrawX            = 0f
    private var moonDrawY            = 0f
    private var powerShieldBitmap:   Bitmap? = null
    private var powerSlowBitmap:     Bitmap? = null
    private var powerDoubleBitmap:   Bitmap? = null
    private var powerMagnetBitmap:   Bitmap? = null
    private var powerGhostBitmap:    Bitmap? = null

    // Skin cache keyed by SkinType.name — scaled to playerSize at load time, never at draw time
    private val scaledSkinCache = mutableMapOf<String, Bitmap?>()

    // ── Sprite animation hook ─────────────────────────────────────────────
    // When animated sprite strips are ready, replace playerBitmap with:
    //   interface AnimatedSprite { fun frameFor(state: SpriteState): Bitmap }
    //   enum class SpriteState { RUN, JUMP, FALL, DASH, LAND }
    //   private var playerSprite: AnimatedSprite? = null
    //   In GameRenderer.drawPlayerBody: bmp = playerSprite?.frameFor(currentSpriteState)

    // ═══════════════════════════════════════════════════════════════════════
    // Choreographer loop
    // ═══════════════════════════════════════════════════════════════════════

    private var loopRunning  = false
    private var lastTime     = 0L
    private var accumulator  = 0L
    private val targetFrameNs = 16_666_666L   // 1/60 s

    // ═══════════════════════════════════════════════════════════════════════
    // Touch gesture tracking
    // ═══════════════════════════════════════════════════════════════════════

    private var downX          = 0f
    private var downY          = 0f
    private var swipeConsumed  = false
    private val swipeThreshold = 100f

    // ═══════════════════════════════════════════════════════════════════════
    // Google Play Games Services — architecture hook
    // ═══════════════════════════════════════════════════════════════════════
    //
    // Integration checklist:
    //   1. Add to build.gradle:
    //        implementation("com.google.android.gms:play-services-games-v2:+")
    //   2. Uncomment the stubs below.
    //   3. Call connectPlayGames() from init{} after prefs are loaded.
    //   4. Call submitLeaderboardScore(score) inside onGameOver().
    //   5. Wire showLeaderboard() to a UI button in drawMenu or drawGameOver.
    //
    // companion object { private const val GPS_LEADERBOARD_ID = "your_id_here" }
    // private var gpsConnected = false
    //
    // private fun connectPlayGames() {
    //     PlayGames.getGamesSignInClient(context as Activity).isAuthenticated
    //         .addOnCompleteListener { task ->
    //             gpsConnected = task.isSuccessful && task.result.isAuthenticated
    //             if (!gpsConnected)
    //                 PlayGames.getGamesSignInClient(context as Activity).signIn()
    //         }
    // }
    //
    // private fun submitLeaderboardScore(score: Int) {
    //     if (!gpsConnected) return
    //     PlayGames.getLeaderboardsClient(context as Activity)
    //         .submitScore(GPS_LEADERBOARD_ID, score.toLong())
    // }
    //
    // fun showLeaderboard() {
    //     if (!gpsConnected) return
    //     PlayGames.getLeaderboardsClient(context as Activity)
    //         .getLeaderboardIntent(GPS_LEADERBOARD_ID)
    //         .addOnSuccessListener { (context as Activity).startActivityForResult(it, 9001) }
    // }

    // ═══════════════════════════════════════════════════════════════════════
    // Mobile Ads SDK (Rewarded Ads for revive) — architecture hook
    // ═══════════════════════════════════════════════════════════════════════
    //
    // Integration checklist:
    //   1. Add to build.gradle:
    //        implementation("com.google.android.gms:play-services-ads:+")
    //   2. Add App ID to AndroidManifest.xml inside <application>.
    //   3. Call MobileAds.initialize(context) in MainActivity.onCreate before setContentView.
    //   4. Uncomment the stubs below; replace AD_UNIT_ID.
    //   5. Call loadRewardedAd() at the bottom of resume().
    //   6. In handleGameOverTouch, call showRewardedAdForRevive() when coins are insufficient.
    //
    // companion object { private const val AD_UNIT_ID = "ca-app-pub-XXXXXXXX/XXXXXXXXXX" }
    // private var rewardedAd: com.google.android.gms.ads.rewarded.RewardedAd? = null
    //
    // fun loadRewardedAd() {
    //     com.google.android.gms.ads.rewarded.RewardedAd.load(
    //         context, AD_UNIT_ID,
    //         com.google.android.gms.ads.AdRequest.Builder().build(),
    //         object : com.google.android.gms.ads.rewarded.RewardedAdLoadCallback() {
    //             override fun onAdLoaded(ad: RewardedAd) { rewardedAd = ad }
    //             override fun onAdFailedToLoad(e: LoadAdError) { rewardedAd = null }
    //         }
    //     )
    // }
    //
    // private fun showRewardedAdForRevive() {
    //     val ad = rewardedAd ?: return
    //     pause()   // stop the game loop while the ad plays
    //     ad.show(context as Activity) { _ ->
    //         engine.revivePlayer()
    //         gameState = GameState.PLAYING
    //         loadRewardedAd()   // pre-fetch next ad immediately
    //         resume()
    //     }
    //     rewardedAd = null
    // }

    // ═══════════════════════════════════════════════════════════════════════
    // init — read prefs, wire sub-modules, start the loop
    // ═══════════════════════════════════════════════════════════════════════

    init {
        // Persistence: restore all user-facing state before the first draw
        engine.state.highScore  = prefs.getInt("high_score",  0)
        engine.state.totalCoins = prefs.getInt("total_coins", 0)

        ownedSkins   = prefs.getStringSet("owned_skins", setOf("DEFAULT"))!!.toMutableSet()
        val skinName = prefs.getString("selected_skin", "DEFAULT") ?: "DEFAULT"
        selectedSkin = runCatching { SkinType.valueOf(skinName) }.getOrDefault(SkinType.DEFAULT)

        themeMode    = if (prefs.getString("theme_mode", "NIGHT") == "DAY") ThemeMode.DAY else ThemeMode.NIGHT
        musicEnabled = prefs.getBoolean("music_enabled", true)
        sfxEnabled   = prefs.getBoolean("sfx_enabled",  true)

        sound.musicEnabled = musicEnabled
        sound.sfxEnabled   = sfxEnabled

        showTutorial = !prefs.getBoolean("tutorial_shown", false)

        loadUpgrades()
        applyUpgradesToEngine()

        sound.switchMusic(SoundManager.Track.MENU)
        // connectPlayGames()   // GPS hook
    }

    // ═══════════════════════════════════════════════════════════════════════
    // onSizeChanged — fires once on first layout and again on any rotation
    // ═══════════════════════════════════════════════════════════════════════

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val groundTop  = h - 220f
        val playerSize = 130f

        // Push geometry into every sub-module that depends on screen dimensions
        engine.onSurfaceChanged(w.toFloat(), h.toFloat(), groundTop, playerSize)
        renderer.onSurfaceChanged(w.toFloat(), h.toFloat(), groundTop, playerSize)
        ui.onSurfaceChanged(w.toFloat(), h.toFloat())

        // Bitmaps: loadBitmaps guards with a null-check so it only allocates once
        // per process; bakeStaticBitmaps runs every resize so vignette/moon
        // always match the current surface dimensions exactly
        loadBitmaps(w, h)
        bakeStaticBitmaps(w, h)
        pushAssetsToRenderer()

        initStars(w, h)

        // Keep engine in MENU mode until PLAY is tapped
        if (gameState == GameState.MENU) engine.setMode(Mode.MENU)

        if (!loopRunning) {
            loopRunning = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Choreographer.FrameCallback.doFrame — fixed-step accumulator
    // ═══════════════════════════════════════════════════════════════════════

    override fun doFrame(frameTimeNanos: Long) {
        if (!loopRunning) return
        Choreographer.getInstance().postFrameCallback(this)

        if (lastTime == 0L) { lastTime = frameTimeNanos; return }

        val elapsed  = frameTimeNanos - lastTime
        lastTime     = frameTimeNanos

        // Clamp accumulator to 4 ticks (prevents spiral of death after resume or debugger break)
        accumulator = (accumulator + elapsed).coerceAtMost(targetFrameNs * 4)

        // Fixed-step physics: always 60 Hz regardless of display refresh rate (60/90/120/144)
        while (accumulator >= targetFrameNs) {
            if (engine.getMode() == Mode.PLAYING) engine.tick()
            accumulator -= targetFrameNs
        }

        // Visual-frame-rate updates (every display frame, not capped to 60 Hz)
        // Push current engine speed into SoundManager so adaptive SFX pitch works
        sound.updateSpeed(engine.state.currentSpeed)
        menuPulse = (menuPulse + 0.03f) % (2f * Math.PI.toFloat())
        if (gameState == GameState.PLAYING && showTutorial)
            tutorialAlpha = (tutorialAlpha + 0.03f).coerceAtMost(1f)
        if (gameState == GameState.PLAYING || gameState == GameState.MENU)
            updateParallax()
        checkDailyReward()

        invalidate()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // onDraw — delegates entirely to renderer and ui layers in the correct order
    // ═══════════════════════════════════════════════════════════════════════

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s      = engine.state
        val rTheme = if (themeMode == ThemeMode.NIGHT) GameRenderer.ThemeMode.NIGHT
        else GameRenderer.ThemeMode.DAY

        // ── Layer 1: sky background ───────────────────────────────────────
        renderer.drawBackground(canvas, rTheme, dayBackgroundBitmap)
        // Draw exactly one celestial body — moon at night, synthwave sun by day.
        // Previously both rendered simultaneously because the sun was baked into
        // the static grid bitmap that always draws regardless of theme.
        when (themeMode) {
            ThemeMode.NIGHT -> renderer.drawMoon(canvas)
            ThemeMode.DAY   -> renderer.drawSynthwaveSun(canvas)
        }
        renderer.drawParallaxLayers(canvas, rTheme, citySkyline, mountains, cloudsMid, cloudsFront)
        renderer.drawStars(canvas, starsBack, starsFront)
        renderer.drawNeonGrid(canvas)
        renderer.drawGroundStrip(canvas, rTheme, getSkinColor())

        // ── Layer 2: game world (suppressed on the main menu) ────────────
        if (gameState != GameState.MENU) {
            canvas.save()

            // Death zoom: scale the canvas around the screen centre
            if (gameState == GameState.GAME_OVER)
                canvas.scale(s.deathZoom, s.deathZoom, width / 2f, height / 2f)

            // Chromatic-aberration glitch during dash or death — draw world twice
            // with a ±12 px horizontal offset and 80 % alpha each pass
            val isGlitching = s.isDashing || gameState == GameState.GAME_OVER
            if (isGlitching) {
                canvas.save(); canvas.translate(-12f, 0f)
                val lL = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), 200)
                renderer.drawGameWorld(canvas, getSkinColor(), selectedSkin.name)
                canvas.restoreToCount(lL); canvas.restore()

                canvas.save(); canvas.translate(12f, 0f)
                val rL = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), 200)
                renderer.drawGameWorld(canvas, getSkinColor(), selectedSkin.name)
                canvas.restoreToCount(rL); canvas.restore()
            } else {
                renderer.drawGameWorld(canvas, getSkinColor(), selectedSkin.name)
            }

            ui.drawHUD(canvas, getSkinColor(), showTutorial, tutorialAlpha)
            canvas.restore()
        }

        // ── Layer 3: screen overlays (menus, sheets) ─────────────────────
        when (gameState) {
            GameState.MENU      -> ui.drawMenu(canvas, s.highScore, menuPulse)
            GameState.SHOP      -> {
                ui.drawShop(
                    canvas, scaledSkinCache,
                    SkinType.values().map { it.name },
                    ownedSkins, selectedSkin.name, s.totalCoins
                )
                ui.drawUpgrades(
                    canvas,
                    listOf(magnetLevel, shieldLevel, slowLevel, scoreLevel),
                    maxUpgradeLevel,
                    upgradeCost,
                    s.totalCoins
                )
            }
            GameState.SETTINGS  -> ui.drawSettings(
                canvas, musicEnabled, sfxEnabled,
                if (themeMode == ThemeMode.NIGHT) GameRenderer.ThemeMode.NIGHT
                else GameRenderer.ThemeMode.DAY
            )
            GameState.GAME_OVER -> ui.drawGameOver(canvas)
            GameState.PAUSED    -> ui.drawPaused(canvas)
            else -> {}
        }

        // ── Layer 4: full-screen flash / death vignette ───────────────────
        ui.drawFlashOverlay(canvas)
        ui.drawDeathOverlay(canvas)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // onTouchEvent — reads UiRenderer hit rects, routes to engine input methods
    // ═══════════════════════════════════════════════════════════════════════

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        val x = event?.x ?: return false
        val y = event.y

        when (event.action) {

            MotionEvent.ACTION_DOWN -> {
                downX         = x
                downY         = y
                swipeConsumed = false

                // ── Coach-marks interceptor hook ──────────────────────────
                // Replace this comment block with your InteractiveOnboarding
                // controller when first-run overlays are ready.
                // Example:
                //   if (coachMarks.isActive) { coachMarks.onTap(x, y); return true }

                when (gameState) {
                    GameState.MENU      -> handleMenuTouch(x, y)
                    GameState.SETTINGS  -> handleSettingsTouch(x, y)
                    GameState.SHOP      -> handleShopTouch(x, y)
                    GameState.GAME_OVER -> handleGameOverTouch(x, y)
                    GameState.PAUSED    -> handlePausedTouch(x, y)
                    GameState.PLAYING   -> handlePlayingTouchDown(x, y)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (gameState == GameState.PLAYING && !swipeConsumed) {
                    val dx = x - downX
                    // Right-swipe = dash; guard against diagonals
                    if (dx > swipeThreshold && abs(y - downY) < swipeThreshold) {
                        engine.onDash()
                        swipeConsumed = true
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                // Clear the jump-hold flag so short taps produce short hops.
                // Without this every tap produced a full max-height jump
                // because isJumping was never set back to false.
                engine.onReleaseJump()
            }
        }

        return true
    }

    // ── Touch sub-handlers (one per game state) ───────────────────────────

    private fun handleMenuTouch(x: Float, y: Float) {
        when {
            ui.playBtn.contains(x, y) -> {
                sound.playClick()
                engine.resetGame()
                gameState = GameState.PLAYING
                sound.switchMusic(SoundManager.Track.GAME)
            }
            ui.shopBtn.contains(x, y) -> {
                sound.playClick()
                gameState = GameState.SHOP
            }
            ui.settingsBtn.contains(x, y) -> {
                sound.playClick()
                gameState = GameState.SETTINGS
            }
            ui.exitBtn.contains(x, y) -> {
                sound.playClick()
                (context as? Activity)?.finish()
            }
        }
    }

    private fun handleSettingsTouch(x: Float, y: Float) {
        when {
            ui.musicToggleRect.contains(x, y) -> {
                musicEnabled       = !musicEnabled
                sound.musicEnabled = musicEnabled
                prefs.edit().putBoolean("music_enabled", musicEnabled).apply()
                if (musicEnabled) sound.switchMusic(SoundManager.Track.MENU) else sound.stopAll()
                sound.playClick()
            }
            ui.sfxToggleRect.contains(x, y) -> {
                sfxEnabled       = !sfxEnabled
                sound.sfxEnabled = sfxEnabled
                prefs.edit().putBoolean("sfx_enabled", sfxEnabled).apply()
                sound.playClick()
            }
            ui.themeDayRect.contains(x, y) -> {
                themeMode = ThemeMode.DAY
                prefs.edit().putString("theme_mode", "DAY").apply()
                sound.playClick()
            }
            ui.themeNightRect.contains(x, y) -> {
                themeMode = ThemeMode.NIGHT
                prefs.edit().putString("theme_mode", "NIGHT").apply()
                sound.playClick()
            }
            ui.settingsBackRect.contains(x, y) -> {
                sound.playClick()
                gameState = GameState.MENU
                sound.switchMusic(SoundManager.Track.MENU)
            }
        }
    }

    private fun handleShopTouch(x: Float, y: Float) {
        if (ui.backBtn.contains(x, y)) {
            sound.playClick()
            gameState = GameState.MENU
            sound.switchMusic(SoundManager.Track.MENU)
            return
        }
        // Skin tile taps
        for ((skinName, rect) in ui.skinBoxRects) {
            if (!rect.contains(x, y)) continue
            val skin = runCatching { SkinType.valueOf(skinName) }.getOrNull() ?: continue
            val cost = getSkinCost(skin)
            when {
                ownedSkins.contains(skinName) -> {
                    selectedSkin = skin; saveSkinSelection(); sound.playClick()
                }
                engine.state.totalCoins >= cost -> {
                    engine.state.totalCoins -= cost
                    ownedSkins.add(skinName); selectedSkin = skin
                    prefs.edit().putInt("total_coins", engine.state.totalCoins).apply()
                    saveSkinSelection(); sound.playCoin()
                }
                else -> {
                    engine.spawnFloatingText(rect.centerX(), rect.top - 20f, "NOT ENOUGH 🪙", 0xFFFF4444.toInt())
                    sound.playClick()
                }
            }
            return
        }
        val screenW = width.toFloat()
        val screenH = height.toFloat()
        // Upgrade tile taps
        fun tryBuy(level: Int, onBuy: () -> Unit) {
            if (level >= maxUpgradeLevel) return
            val cost = upgradeCost.getOrElse(level) { Int.MAX_VALUE }
            if (engine.state.totalCoins >= cost) {
                engine.state.totalCoins -= cost
                prefs.edit().putInt("total_coins", engine.state.totalCoins).apply()
                onBuy(); saveUpgrades(); applyUpgradesToEngine(); sound.playCoin()
            } else {
                engine.spawnFloatingText(screenW / 2f, screenH * 0.7f, "NOT ENOUGH 🪙", 0xFFFF4444.toInt())
                sound.playClick()
            }
        }
        if (ui.upMagnetRect.contains(x, y)) { tryBuy(magnetLevel) { magnetLevel++ }; return }
        if (ui.upShieldRect.contains(x, y)) { tryBuy(shieldLevel) { shieldLevel++ }; return }
        if (ui.upSlowRect.contains(x, y))   { tryBuy(slowLevel)   { slowLevel++   }; return }
        if (ui.upScoreRect.contains(x, y))  { tryBuy(scoreLevel)  { scoreLevel++  }; return }
    }

    private fun handleGameOverTouch(x: Float, y: Float) {
        when {
            ui.restartBtn.contains(x, y) -> {
                sound.playClick()
                engine.resetGame()
                gameState = GameState.PLAYING
                sound.switchMusic(SoundManager.Track.GAME)
            }
            ui.menuBtn.contains(x, y) -> {
                sound.playClick()
                gameState = GameState.MENU
                sound.switchMusic(SoundManager.Track.MENU)
            }
            ui.reviveRect.contains(x, y) && engine.state.reviveAvailable -> {
                engine.revivePlayer()
                gameState = GameState.PLAYING
                sound.switchMusic(SoundManager.Track.GAME)
                // Ad-funded revive hook:
                // if (rewardedAd != null) showRewardedAdForRevive()
                // else engine.revivePlayer()
            }
        }
    }

    private fun handlePausedTouch(x: Float, y: Float) {
        when {
            ui.resumeBtn.contains(x, y) -> {
                sound.playClick()
                gameState = GameState.PLAYING
                sound.switchMusic(SoundManager.Track.GAME)
            }
            ui.pausedMenuBtn.contains(x, y) -> {
                sound.playClick()
                gameState = GameState.MENU
                sound.switchMusic(SoundManager.Track.MENU)
            }
        }
    }

    private fun handlePlayingTouchDown(x: Float, y: Float) {
        if (ui.pauseBtn.contains(x, y)) {
            sound.stopAll(); gameState = GameState.PAUSED; return
        }
        // Dismiss first-run tutorial on first intentional tap
        if (showTutorial) {
            showTutorial  = false
            tutorialAlpha = 0f
            prefs.edit().putBoolean("tutorial_shown", true).apply()
        }
        engine.onTap()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GameEngineListener — engine calls these on the main thread
    // ═══════════════════════════════════════════════════════════════════════

    override fun onGameOver(isNewBest: Boolean, finalScore: Int) {
        gameState = GameState.GAME_OVER
        sound.switchMusic(SoundManager.Track.OVER)
        // submitLeaderboardScore(finalScore)   // GPS hook — uncomment when ready
    }

    override fun onReviveAvailable() { /* engine.state.reviveAvailable drives the UI */ }

    override fun onBossSpawned() {
        // Switch to a dedicated boss track when R.raw.music_boss is added:
        // sound.switchMusic(SoundManager.Track.BOSS)
        sound.switchMusic(SoundManager.Track.GAME)
    }

    override fun onBossDefeated() = sound.switchMusic(SoundManager.Track.GAME)

    override fun onShake(type: String) {
        if (!sfxEnabled) return
        val (ms, amp) = when (type) {
            "CRASH" -> 500L to 255; "BUMP" -> 60L to 100; "TINY" -> 20L to 40
            else    -> 80L to 100
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
            vibrator?.vibrate(VibrationEffect.createOneShot(ms, amp))
        else @Suppress("DEPRECATION") vibrator?.vibrate(ms)
    }

    override fun onNewScorePopup() { /* invalidate() fires every frame already */ }

    // ═══════════════════════════════════════════════════════════════════════
    // Bitmap loading
    // ═══════════════════════════════════════════════════════════════════════

    private fun loadSafe(resId: Int, w: Int, h: Int): Bitmap? {
        val d = ContextCompat.getDrawable(context, resId) ?: return null
        return try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
                val c = Canvas(bmp); d.setBounds(0, 0, w, h); d.draw(c)
            }
        } catch (_: Exception) { null }
    }

    private fun loadBitmaps(w: Int, h: Int) {
        if (playerBitmap != null) return   // already loaded; skip re-allocation

        val pSz = 130
        playerBitmap      = loadSafe(R.drawable.player,       pSz, pSz)
        skinNinja         = loadSafe(R.drawable.skin_ninja,   pSz, pSz)
        skinPolice        = loadSafe(R.drawable.skin_police,  pSz, pSz)
        skinFire          = loadSafe(R.drawable.skin_fire,    pSz, pSz)
        skinThief         = loadSafe(R.drawable.skin_thief,   pSz, pSz)
        crateBitmap       = loadSafe(R.drawable.crate,        pSz, pSz)
        spikeBitmap       = loadSafe(R.drawable.spike,        pSz, 100)
        coinBitmap        = loadSafe(R.drawable.coin,          70,  70)
        powerShieldBitmap = loadSafe(R.drawable.power_shield,  80,  80)
        powerSlowBitmap   = loadSafe(R.drawable.power_slow,    80,  80)
        powerDoubleBitmap = loadSafe(R.drawable.power_double,  80,  80)
        powerMagnetBitmap = loadSafe(R.drawable.power_magnet,  80,  80)
        powerGhostBitmap  = loadSafe(R.drawable.power_ghost,   80,  80)
        dayBackgroundBitmap = loadSafe(R.drawable.bg_day, w, h)

        // Saw: procedurally rendered (no art file required)
        sawBitmap = Bitmap.createBitmap(140, 140, Bitmap.Config.ARGB_8888).also { bmp ->
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 6f
            }
            c.drawCircle(70f, 70f, 55f, p)
        }

        // Boss: scale raw PNG by 15 % for screen fit
        try {
            val raw = BitmapFactory.decodeResource(resources, R.drawable.ravan_boss)
            val s   = 0.15f
            ravanBitmap = Bitmap.createScaledBitmap(raw, (raw.width*s).toInt(), (raw.height*s).toInt(), true)
            // Tell engine the exact hitbox dimensions
            ravanBitmap?.let { engine.onBossBitmapLoaded(it.width.toFloat(), it.height.toFloat()) }
        } catch (_: Exception) { }

        // Pre-scale skin bitmaps once — drawGameWorld/shop never call createScaledBitmap
        mapOf("DEFAULT" to playerBitmap, "NINJA" to skinNinja, "POLICE" to skinPolice,
            "FIRE" to skinFire, "THIEF" to skinThief).forEach { (key, bmp) ->
            scaledSkinCache[key] = bmp?.let { Bitmap.createScaledBitmap(it, pSz, pSz, true) }
        }

        // Parallax layers — city (far) → mountains (mid) → clouds (near)
        // city_skyline.png not yet in res/drawable — GameRenderer falls back
        // to drawProceduralSkyline() automatically when citySkyline list is empty.
        val cityBmp: Bitmap? = null   // loadSafe(R.drawable.city_skyline, w, h / 2)
        val mBmp    = loadSafe(R.drawable.mountain,     w,     h / 2)
        val cMBmp   = loadSafe(R.drawable.cloud_mid,    w / 2, h / 4)
        val cFBmp   = loadSafe(R.drawable.cloud_front,  w / 2, h / 3)

        // City skyline: farthest back, slowest base speed (0.2)
        citySkyline.clear()
        if (cityBmp != null) {
            for (i in 0..2)
                citySkyline.add(GameRenderer.ParallaxLayer(i * w.toFloat(), h * 0.30f, 0.2f, cityBmp))
        }

        mountains.clear(); cloudsMid.clear(); cloudsFront.clear()
        if (mBmp != null)
            for (i in 0..2) mountains.add(  GameRenderer.ParallaxLayer(i * w.toFloat(), h * 0.45f, 0.4f, mBmp))
        if (cMBmp != null)
            for (i in 0..3) cloudsMid.add(  GameRenderer.ParallaxLayer(i * (w / 2f),   h * 0.20f, 1.2f, cMBmp))
        if (cFBmp != null)
            for (i in 0..3) cloudsFront.add(GameRenderer.ParallaxLayer(i * (w / 2f),   h * 0.10f, 1.7f, cFBmp))
    }

    /** Bakes the two expensive full-screen bitmaps once per surface resize. */
    private fun bakeStaticBitmaps(w: Int, h: Int) {
        // Vignette — radial darkening bitmap; drawn on top of the world every frame
        vignetteBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(w / 2f, h / 2f, w.toFloat(),
                    intArrayOf(Color.TRANSPARENT, Color.argb(180, 0, 0, 0)),
                    floatArrayOf(0.6f, 1f), Shader.TileMode.CLAMP)
            }
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        }

        // Moon — bloom → soft glow → radial gradient body → rim highlight → craters
        val mR  = 120f
        val mSz = ((mR * 3.5f) * 2).toInt()
        moonDrawX = w / 2f - mSz / 2f
        moonDrawY = h * 0.28f - mSz / 2f
        moonBitmap = Bitmap.createBitmap(mSz, mSz, Bitmap.Config.ARGB_8888).also { bmp ->
            val c = Canvas(bmp); val ox = mSz / 2f; val oy = mSz / 2f
            c.drawCircle(ox, oy, mR * 2.4f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                maskFilter = BlurMaskFilter(90f, BlurMaskFilter.Blur.NORMAL)
                color = Color.argb(80, 180, 200, 255) })
            c.drawCircle(ox, oy, mR * 1.55f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                maskFilter = BlurMaskFilter(50f, BlurMaskFilter.Blur.NORMAL)
                color = Color.argb(130, 200, 200, 255) })
            c.drawCircle(ox, oy, mR, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(ox, oy, mR,
                    intArrayOf(Color.rgb(220,220,230), Color.rgb(180,180,190), Color.rgb(130,130,140)),
                    floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP) })
            c.drawCircle(ox, oy, mR + 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.WHITE; alpha = 120 })
            val crater = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(140,140,150); alpha = 70 }
            listOf(Triple(-30f,-20f,16f), Triple(35f,5f,13f), Triple(-10f,25f,10f), Triple(15f,-35f,8f))
                .forEach { (dx,dy,r) -> c.drawCircle(ox+dx, oy+dy, r, crater) }
        }
    }

    private fun pushAssetsToRenderer() {
        renderer.assets = GameRenderer.Assets(
            crateBitmap     = crateBitmap,
            spikeBitmap     = spikeBitmap,
            sawBitmap       = sawBitmap,
            coinBitmap      = coinBitmap,
            ravanBitmap     = ravanBitmap,
            vignetteBitmap  = vignetteBitmap,
            moonBitmap      = moonBitmap,
            moonDrawX       = moonDrawX,
            moonDrawY       = moonDrawY,
            scaledSkinCache = scaledSkinCache,
            powerBitmaps    = mapOf(
                EnginePowerType.SHIELD to powerShieldBitmap,
                EnginePowerType.SLOW   to powerSlowBitmap,
                EnginePowerType.DOUBLE to powerDoubleBitmap,
                EnginePowerType.MAGNET to powerMagnetBitmap,
                EnginePowerType.GHOST  to powerGhostBitmap
            )
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Stars + parallax update (pure position bookkeeping — no logic)
    // ═══════════════════════════════════════════════════════════════════════

    private fun initStars(w: Int, h: Int) {
        starsBack.clear(); starsFront.clear()
        repeat(28) { starsBack.add( GameRenderer.StarLayer(Random.nextFloat()*w, Random.nextFloat()*h, 3f, 1f)) }
        repeat(20) { starsFront.add(GameRenderer.StarLayer(Random.nextFloat()*w, Random.nextFloat()*h, 5f, 2f)) }
    }

    private fun updateParallax() {
        // Scale every layer's movement by the engine's current speed so the
        // world feels faster as the run accelerates. Clamped at 2.5x so
        // distant layers don't blur into a wash of colour at max speed.
        val speedFactor = (engine.state.currentSpeed / GameEngine.BASE_SPEED)
            .coerceIn(1f, 2.5f)

        fun moveLayer(list: MutableList<GameRenderer.ParallaxLayer>) {
            if (list.isEmpty()) return
            val bmpW = list[0].bitmap.width.toFloat()
            list.forEach { l ->
                l.x -= l.speed * speedFactor
                if (l.x < -bmpW) l.x += bmpW * list.size
            }
        }

        // Draw order (back to front): city -> mountains -> cloudsMid -> cloudsFront
        moveLayer(citySkyline)    // base speed 0.2 — barely moves, sells depth
        moveLayer(mountains)      // base speed 0.4
        moveLayer(cloudsMid)      // base speed 1.2
        moveLayer(cloudsFront)    // base speed 1.7 — rushes past, sells speed

        starsBack.forEach  { s ->
            s.x -= s.speed * 0.4f * speedFactor
            if (s.x < 0f) { s.x = width.toFloat(); s.y = Random.nextFloat() * height }
        }
        starsFront.forEach { s ->
            s.x -= s.speed * 0.8f * speedFactor
            if (s.x < 0f) { s.x = width.toFloat(); s.y = Random.nextFloat() * height }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Daily reward
    // ═══════════════════════════════════════════════════════════════════════

    private fun checkDailyReward() {
        if (dailyRewardShown || gameState != GameState.MENU) return
        val dayNow  = System.currentTimeMillis() / 86_400_000L
        val lastDay = prefs.getLong("daily_last_day", -1L)
        if (dayNow == lastDay) { dailyRewardShown = true; return }
        var streak = prefs.getInt("daily_streak", 0)
        streak = when { lastDay == -1L -> 1; dayNow - lastDay == 1L -> (streak + 1).coerceAtMost(7); else -> 1 }
        val reward = when (streak) { 1->20; 2->40; 3->60; 4->80; 5->120; 6->160; else->250 }
        engine.state.totalCoins += reward
        prefs.edit().putInt("total_coins", engine.state.totalCoins)
            .putLong("daily_last_day", dayNow).putInt("daily_streak", streak).apply()
        dailyRewardShown = true
        engine.spawnFloatingText(width / 2f - 150f, height * 0.6f, "🎁 DAILY +$reward 🪙", Color.YELLOW)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Skin / prefs helpers
    // ═══════════════════════════════════════════════════════════════════════

    private fun getSkinColor(): Int = when (selectedSkin) {
        SkinType.DEFAULT -> Color.parseColor("#00F5FF")
        SkinType.NINJA   -> Color.parseColor("#00FF7F")
        SkinType.POLICE  -> Color.parseColor("#FFEA00")
        SkinType.FIRE    -> Color.parseColor("#FF007F")
        SkinType.THIEF   -> Color.parseColor("#FFAA00")
    }

    private fun getSkinCost(skin: SkinType) = if (skin == SkinType.DEFAULT) 0 else 50

    private fun saveSkinSelection() {
        prefs.edit().putString("selected_skin", selectedSkin.name)
            .putStringSet("owned_skins", ownedSkins).apply()
    }

    private fun loadUpgrades() {
        magnetLevel = prefs.getInt("up_magnet", 0); shieldLevel = prefs.getInt("up_shield", 0)
        slowLevel   = prefs.getInt("up_slow",   0); scoreLevel  = prefs.getInt("up_score",  0)
    }

    private fun saveUpgrades() {
        prefs.edit().putInt("up_magnet", magnetLevel).putInt("up_shield", shieldLevel)
            .putInt("up_slow", slowLevel).putInt("up_score", scoreLevel).apply()
    }

    /** Push upgrade multipliers into engine state so they take effect on the next run. */
    private fun applyUpgradesToEngine() {
        // Stubbed pending GameEngine upgrade fields:
        // engine.state.magnetRangeBonus  = magnetLevel * 80f
        // engine.state.shieldTimeBonus   = shieldLevel * 3f
        // engine.state.slowTimeBonus     = slowLevel   * 2f
        // engine.state.coinMultiplierAdd = scoreLevel  * 0.25f
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════════

    fun pause() {
        loopRunning = false
        Choreographer.getInstance().removeFrameCallback(this)
        sound.pauseMusic()
    }

    fun resume() {
        if (!loopRunning) {
            loopRunning = true
            lastTime    = 0L   // reset so the first delta is not a huge spike
            Choreographer.getInstance().postFrameCallback(this)
        }
        sound.resumeMusic()
        // loadRewardedAd()   // pre-fetch ad on every app resume
    }

    fun releaseResources() {
        loopRunning = false
        Choreographer.getInstance().removeFrameCallback(this)
        sound.release()
        vignetteBitmap?.recycle()
        moonBitmap?.recycle()
    }
}