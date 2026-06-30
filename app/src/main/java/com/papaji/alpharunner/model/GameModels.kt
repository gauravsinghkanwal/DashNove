package com.papaji.alpharunner.model

import android.graphics.RectF

// ── Game states ───────────────────────────────────────────────────────────────
enum class GameState { MENU, PLAYING, PAUSED, GAME_OVER, SHOP, SETTINGS, DAILY_REWARD }
enum class ThemeMode  { DAY, NIGHT }
enum class SkinType   { DEFAULT, NINJA, POLICE, FIRE, THIEF }
enum class PowerType  { SHIELD, SLOW, DOUBLE_SCORE, MAGNET, GHOST }
enum class ObstacleType { CRATE, SPIKE, BARRIER, FIRE_TRAP }

// ── Entity data classes ───────────────────────────────────────────────────────

data class Obstacle(
    var x: Float,
    var y: Float,
    val width: Float,
    val height: Float,
    val type: ObstacleType,
    val color: Int
) {
    val rect: RectF get() = RectF(x, y, x + width, y + height)
}

data class Coin(
    var x: Float,
    var y: Float,
    val size: Float = 28f,
    var collected: Boolean = false,
    var bobOffset: Float = 0f   // phase offset for bobbing animation
)

data class PowerUp(
    var x: Float,
    var y: Float,
    val type: PowerType,
    val size: Float = 70f
)

data class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var size: Float,
    val color: Int,
    var life: Float = 1f        // 1.0 → 0.0
)

data class TrailPoint(
    val x: Float,
    val y: Float,
    val size: Float,
    var alpha: Float = 1f
)

data class FloatingText(
    var x: Float,
    var y: Float,
    val text: String,
    val color: Int,
    var life: Float = 1.5f,
    var vy: Float = -2.2f
)

data class BossProjectile(
    var x: Float,
    var y: Float,
    val vx: Float,
    val vy: Float = 0f
)

data class Star(
    val x: Float,
    val y: Float,
    val radius: Float,
    val alpha: Int,
    var twinklePhase: Float = 0f
)

// ── Upgrade model ─────────────────────────────────────────────────────────────

data class UpgradeStats(
    val magnetLevel:  Int = 0,   // max 3
    val shieldLevel:  Int = 0,   // max 3
    val coinBonus:    Int = 0    // max 3
) {
    val magnetRange:  Float get() = 200f + magnetLevel * 80f
    val shieldTime:   Float get() = 5f  + shieldLevel * 3f
    val coinMultiplier: Float get() = 1f + coinBonus * 0.25f
}

// ── Score popup (floating +N) ─────────────────────────────────────────────────

data class ScorePopup(
    var x: Float,
    var y: Float,
    val text: String,
    val color: Int,
    var life: Float = 1.2f
)
