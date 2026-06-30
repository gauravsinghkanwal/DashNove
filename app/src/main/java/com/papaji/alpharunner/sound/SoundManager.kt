package com.papaji.alpharunner.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import com.papaji.alpharunner.R
import kotlin.random.Random

/**
 * SoundManager — owns every audio resource for Alpha Runner.
 *
 * Usage:
 *   val sound = SoundManager(context, prefs)
 *   sound.musicEnabled = true
 *   sound.switchMusic(SoundManager.Track.MENU)
 *   sound.playJump()
 *   sound.release()   // call from onDestroy
 */
class SoundManager(private val context: Context) {

    // ── Public toggles (persist via GameView prefs) ──────────────
    var musicEnabled: Boolean = true
    var sfxEnabled:   Boolean = true

    /**
     * Updated by GameView every frame from engine.state.currentSpeed.
     * SFX methods read this to scale pitch and volume, giving audio
     * feedback that the player is accelerating toward max speed.
     * Range: BASE_SPEED(14f) → MAX_SPEED(36f).
     */
    var currentSpeed: Float = 14f

    // Pre-computed normalised speed factor [0..1]; derived in updateSpeed()
    private var speedNorm: Float = 0f

    /** Call once per frame (or at least before playing SFX). */
    fun updateSpeed(speed: Float) {
        currentSpeed = speed
        // Normalise against the known speed range so calculations below
        // are independent of the absolute speed values.
        speedNorm = ((speed - 14f) / (36f - 14f)).coerceIn(0f, 1f)
    }

    // ── Tracks ────────────────────────────────────────────────────
    enum class Track { MENU, GAME, OVER, BOSS }

    private var mpMenu:  MediaPlayer? = null
    private var mpGame:  MediaPlayer? = null
    private var mpOver:  MediaPlayer? = null
    private var mpBoss:  MediaPlayer? = null   // null until boss music exists
    private var current: MediaPlayer? = null

    // ── SFX ───────────────────────────────────────────────────────
    private var pool:        SoundPool? = null
    private var jumpSound:   Int = 0
    private var deathSound:  Int = 0
    private var coinSound:   Int = 0
    private var powerSound:  Int = 0
    private var clickSound:  Int = 0
    private var bossSound:   Int = 0

    init {
        initMusic()
        initSfx()
    }

    // ── Music ─────────────────────────────────────────────────────

    private fun initMusic() {
        mpMenu = buildPlayer(R.raw.menu_soft_packa, loop = true)
        mpGame = buildPlayer(R.raw.game_soft_packa, loop = true)
        mpOver = buildPlayer(R.raw.over_soft_packa, loop = false)
        // Boss track is optional. buildPlayer returns null on any error so the
        // when-branch in switchMusic() falls back to mpGame automatically.
        mpBoss = buildPlayer(R.raw.boss_soft_packa, loop = true)
    }

    private fun buildPlayer(resId: Int, loop: Boolean): MediaPlayer? = try {
        MediaPlayer.create(context, resId)?.apply { isLooping = loop; setVolume(0.7f, 0.7f) }
    } catch (e: Exception) { null }

    fun switchMusic(track: Track) {
        if (!musicEnabled) return
        val target = when (track) {
            Track.MENU -> mpMenu
            Track.GAME -> mpGame
            Track.OVER -> mpOver
            Track.BOSS -> mpBoss ?: mpGame   // fall back to game music if no boss track
        }
        if (current == target) return
        current?.pause()
        current = target
        target?.apply { if (!isPlaying) { seekTo(0); start() } }
    }

    fun stopAll() {
        listOf(mpMenu, mpGame, mpOver, mpBoss).forEach { it?.pause() }
        current = null
    }

    fun pauseMusic()  { current?.pause() }
    fun resumeMusic() { if (musicEnabled) current?.start() }

    // ── SFX ───────────────────────────────────────────────────────

    private fun initSfx() {
        val attr = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        pool = SoundPool.Builder().setMaxStreams(6).setAudioAttributes(attr).build()
        pool?.let { sp ->
            jumpSound  = sp.load(context, R.raw.jump,  1)
            deathSound = sp.load(context, R.raw.death, 1)
            coinSound  = sp.load(context, R.raw.coin,  1)
            powerSound = sp.load(context, R.raw.power, 1)
            clickSound = sp.load(context, R.raw.click, 1)
            // sfx_boss is optional — falls back to deathSound if the file is absent
            bossSound  = try { sp.load(context, R.raw.dash, 1) }
            catch (_: Exception) { deathSound }
        }
    }

    /**
     * Core SFX player with per-call pitch control.
     * Left and right volume are both set to [vol] so the sound stays centred.
     */
    private fun play(id: Int, pitch: Float = 1f, vol: Float = 0.9f) {
        if (!sfxEnabled || id == 0) return
        pool?.play(id, vol, vol, 1, 0, pitch)
    }

    /**
     * Jump pitch scales from 1.0 at base speed to 1.35 at max speed.
     * This makes late-game jumps sound sharper and more urgent, reinforcing
     * the escalating tension of high-speed runs.
     *
     * Additional ±5 % random jitter keeps repeated jumps from sounding robotic.
     */
    fun playJump() {
        val basePitch   = 1.0f + speedNorm * 0.35f
        val jitter      = 1.0f + (Random.nextFloat() - 0.5f) * 0.10f
        play(jumpSound, basePitch * jitter)
    }

    /** Death sound stays at fixed pitch — it's a one-shot finality cue. */
    fun playDeath() = play(deathSound, 0.92f)

    /**
     * Coin pickup pitch rises slightly with speed — fast coins sound "tighter".
     * Volume also dips 15 % at max speed so coins don't overwhelm the mix
     * when the player is magnetically vacuuming them up at top pace.
     */
    fun playCoin() {
        val pitch = 1.0f + speedNorm * 0.20f + Random.nextFloat() * 0.15f
        val vol   = 0.9f - speedNorm * 0.15f
        play(coinSound, pitch, vol)
    }

    /**
     * Power-up collection: pitch rises to convey urgency at high speed,
     * and volume bumps up slightly so the cue cuts through the mix.
     */
    fun playPower() {
        val pitch = 0.95f + speedNorm * 0.25f
        play(powerSound, pitch, (0.9f + speedNorm * 0.10f).coerceAtMost(1f))
    }

    /** Click is a UI cue — always neutral pitch regardless of game speed. */
    fun playClick() = play(clickSound, 1.0f)

    /** Boss SFX: drops in pitch at high speed to feel heavier and more menacing. */
    fun playBoss()  = play(bossSound, 1.0f - speedNorm * 0.15f)

    // ── Lifecycle ─────────────────────────────────────────────────

    fun release() {
        listOf(mpMenu, mpGame, mpOver, mpBoss).forEach { mp ->
            try { mp?.stop(); mp?.release() } catch (_: Exception) {}
        }
        mpMenu = null; mpGame = null; mpOver = null; mpBoss = null; current = null
        pool?.release(); pool = null
    }
}