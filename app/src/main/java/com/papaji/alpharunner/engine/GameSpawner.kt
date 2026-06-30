package com.papaji.alpharunner.engine

import android.graphics.Color
import kotlin.math.max
import kotlin.random.Random

/**
 * GameSpawner — owns every algorithm that decides what enters the screen
 * and where. It is a pure logic class: no Android views, no drawing.
 *
 * The Engine calls [tick] every update frame while PLAYING. The spawner
 * decrements its own internal timer and fires the appropriate pattern
 * when the timer expires. All spawned entities are pushed directly into
 * the mutable lists that GameEngine exposes to the renderer.
 *
 * @param lists        Write-only handles to the engine's entity lists.
 * @param screenRef    Runtime screen dimensions supplied by the engine.
 */
class GameSpawner(
    private val lists: EntityLists,
    val screenRef: ScreenRef
) {

    // ─────────────────────────────────────────────────────────────────────────
    // Public handles injected by the engine
    // ─────────────────────────────────────────────────────────────────────────

    /** Write-only access to the live entity lists owned by GameEngine. */
    data class EntityLists(
        val obstacles:   MutableList<EngineObstacle>,
        val coins:       MutableList<EngineCoin>,
        val powerUps:    MutableList<EnginePowerUp>,
        val floatTexts:  MutableList<EngineFloatText>
    )

    /** Live screen metrics updated by GameEngine every time the surface changes. */
    data class ScreenRef(
        var width:       Float = 0f,
        var groundTop:   Float = 0f
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Internal timer
    // ─────────────────────────────────────────────────────────────────────────

    private var spawnTimer = 0f

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /** Called once per engine tick while the boss is NOT active.
     *  @param dt           Fixed delta-time (seconds), same value the engine uses.
     *  @param currentSpeed Current scroll speed so gap sizing stays consistent.
     *  @param bossActive   Suppresses obstacle spawning during boss encounter.
     */
    fun tick(dt: Float, currentSpeed: Float, bossActive: Boolean) {
        spawnTimer -= dt
        if (spawnTimer > 0f) return

        if (!bossActive) {
            spawnRandomPattern(currentSpeed)
            // Gap between waves shrinks as speed grows, but has a floor so it
            // stays humanly reactable (≥ 0.55 s even at max speed).
            val baseGapSec  = 1.4f
            val speedFactor = (currentSpeed / BASE_SPEED).coerceIn(1f, 2.0f)
            val jitter      = 0.8f + Random.nextFloat() * 1.2f
            spawnTimer = (baseGapSec * jitter / speedFactor).coerceAtLeast(0.55f)
        } else {
            // Boss is on screen — no obstacles, just wait
            spawnTimer = 0.5f
        }
    }

    /** Hard-reset the timer (call from resetGame / revive). */
    fun reset() {
        spawnTimer = 1.0f   // small initial grace period when the run starts
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pattern dispatcher
    // ─────────────────────────────────────────────────────────────────────────

    private fun spawnRandomPattern(speed: Float) {
        val bx = screenRef.width + 80f     // start just off the right edge
        val roll = Random.nextFloat()

        when {
            roll < 0.14f -> spawnSingleObstacle(bx, speed)
            roll < 0.28f -> spawnDoubleObstacleRow(bx, speed)
            roll < 0.45f -> spawnObstacleWithCoinArc(bx, speed)
            roll < 0.58f -> spawnCoinWave(bx)
            roll < 0.72f -> spawnDiagonalCoinLadder(bx)
            roll < 0.88f -> spawnPowerUpNearSafeGap(bx)
            else          -> spawnMixedObstacleCoinPower(bx, speed)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Individual patterns
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Pattern 1 — Single obstacle.
     * A lone box or spike on the ground. Always safe to dash or jump over.
     */
    fun spawnSingleObstacle(baseX: Float, speed: Float) {
        val safeX = safeSpawnX(baseX, speed)
        val type  = randomObstacleType()
        val (w, h) = dimensionsFor(type)
        lists.obstacles.add(
            EngineObstacle(
                x        = safeX,
                width    = w,
                height   = h,
                type     = type,
                rotation = 0f
            )
        )
    }

    /**
     * Pattern 2 — Double obstacle row.
     * Two obstacles with a gap in between. A single coin rewards players who
     * squeeze through rather than jumping over both.
     */
    fun spawnDoubleObstacleRow(baseX: Float, speed: Float) {
        val safeX = safeSpawnX(baseX, speed)
        val gap   = 220f

        val t1 = ObstacleKind.BOX
        val t2 = if (Random.nextBoolean()) ObstacleKind.SPIKE else ObstacleKind.BOX

        val (w1, h1) = dimensionsFor(t1)
        val (w2, h2) = dimensionsFor(t2)

        lists.obstacles.add(EngineObstacle(safeX,          w1, h1, t1))
        lists.obstacles.add(EngineObstacle(safeX + w1 + gap, w2, h2, t2))

        // Tempt players with a coin in the gap to incentivise threading it
        if (Random.nextFloat() < 0.65f) {
            lists.coins.add(
                EngineCoin(
                    x          = safeX + w1 + gap / 2f,
                    y          = screenRef.groundTop - 180f,
                    bobOffset  = Random.nextFloat() * Math.PI.toFloat() * 2f
                )
            )
        }
    }

    /**
     * Pattern 3 — Obstacle with coin arc overhead.
     * One obstacle at ground level with 7 coins in a parabolic arc above it.
     * The arc peaks exactly over the obstacle's centre, rewarding players who
     * jump at the correct moment (getting both the obstacle clearance and the
     * coins in one smooth arc).
     *
     * Arc formula:  y_i = baseY − arcHeight × (1 − t_i²)
     * where t_i ∈ [−1, +1] across the spread of coins.
     */
    fun spawnObstacleWithCoinArc(baseX: Float, speed: Float) {
        val safeX = safeSpawnX(baseX, speed)
        val type  = if (Random.nextFloat() < 0.5f) ObstacleKind.BOX else ObstacleKind.SPIKE
        val (w, h) = dimensionsFor(type)
        lists.obstacles.add(EngineObstacle(safeX, w, h, type))

        val coinCount = 7
        val spacing   = 75f
        val centreX   = safeX + w / 2f
        val startX    = centreX - spacing * (coinCount - 1) / 2f
        val baseY     = screenRef.groundTop - h - 60f   // just above obstacle top
        val arcHeight = 170f                              // peak height of the arc

        for (i in 0 until coinCount) {
            val mid = (coinCount - 1) / 2f
            val t   = (i - mid) / mid.coerceAtLeast(1f)  // guard against div-by-zero if count = 1
            val cx  = startX + i * spacing
            val cy  = baseY - arcHeight * (1f - t * t)
            lists.coins.add(EngineCoin(cx, cy, bobOffset = Random.nextFloat() * 6.28f))
        }
    }

    /**
     * Pattern 4 — Horizontal coin wave.
     * 6 coins in a gentle slope that can run either up or down.
     * Simple, readable, feels fair.
     */
    fun spawnCoinWave(baseX: Float) {
        val count     = 6
        val spacing   = 90f
        val isDiagUp  = Random.nextBoolean()
        val baseY     = screenRef.groundTop - 220f - Random.nextFloat() * 100f

        for (i in 0 until count) {
            val cx = baseX + i * spacing
            val dy = if (isDiagUp) -i * 18f else i * 18f
            lists.coins.add(EngineCoin(cx, baseY + dy, bobOffset = i * 0.4f))
        }
    }

    /**
     * Pattern 5 — Diagonal coin ladder.
     * Coins ascend steeply, requiring the player to jump twice in quick
     * succession to collect them all. Teaches double-jump timing.
     */
    fun spawnDiagonalCoinLadder(baseX: Float) {
        val count   = 8
        val spacingX = 70f
        val spacingY = -38f                            // negative = going upward
        val startY   = screenRef.groundTop - 170f

        for (i in 0 until count) {
            lists.coins.add(
                EngineCoin(
                    x         = baseX + i * spacingX,
                    y         = startY + i * spacingY,
                    bobOffset = i * 0.3f
                )
            )
        }
    }

    /**
     * Pattern 6 — Free power-up.
     * A single power-up floats at jump height in an empty lane so the
     * player can grab it without having to thread any obstacles.
     */
    fun spawnPowerUpNearSafeGap(baseX: Float) {
        val px   = baseX + 100f
        val py   = screenRef.groundTop - 250f - Random.nextFloat() * 60f
        val type = EnginePowerType.values().random()
        lists.powerUps.add(EnginePowerUp(px, py, size = 60f, type = type))
    }

    /**
     * Pattern 7 — Mixed: obstacle + coins + optional power-up.
     * The "elite" pattern. One obstacle, 3–4 coins scattered nearby, and a
     * 40 % chance of a power-up above. High reward, highest pressure.
     */
    fun spawnMixedObstacleCoinPower(baseX: Float, speed: Float) {
        val safeX  = safeSpawnX(baseX, speed)
        spawnSingleObstacle(safeX, speed)

        val cCount = 3 + Random.nextInt(2)
        val baseY  = screenRef.groundTop - 180f
        for (i in 0 until cCount) {
            lists.coins.add(
                EngineCoin(
                    x         = safeX + 100f + i * 75f,
                    y         = baseY - Random.nextFloat() * 70f,
                    bobOffset = Random.nextFloat() * 6.28f
                )
            )
        }

        if (Random.nextFloat() < 0.4f) {
            lists.powerUps.add(
                EnginePowerUp(
                    x    = safeX + 140f,
                    y    = baseY - 120f,
                    size = 60f,
                    type = EnginePowerType.values().random()
                )
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the furthest right edge of any existing obstacle,
     * or a sentinel that effectively disables the gap-enforcement
     * when the field is empty.
     */
    private fun lastObstacleEdge(): Float =
        if (lists.obstacles.isEmpty()) -9999f
        else lists.obstacles.maxOf { it.x + it.width }

    /**
     * Enforces a minimum gap between successive obstacles so the player
     * always has enough screen time to react.
     *
     * The gap grows with speed:  gap = max(480, speed × 18)
     * This means at top speed the gap widens rather than shrinking, because
     * faster scroll means obstacles arrive sooner in real time.
     */
    private fun safeSpawnX(baseX: Float, speed: Float): Float {
        val lastEdge   = lastObstacleEdge()
        val dynamicGap = speed * 18f
        val minGap     = 480f
        val gap        = max(minGap, dynamicGap)
        return max(baseX, lastEdge + gap)
    }

    private fun randomObstacleType(): ObstacleKind = ObstacleKind.values().random()

    /** Returns (width, height) for each obstacle kind. */
    private fun dimensionsFor(type: ObstacleKind): Pair<Float, Float> = when (type) {
        ObstacleKind.SPIKE -> Pair(120f, 110f)
        ObstacleKind.BOX   -> Pair(140f, 140f)
        ObstacleKind.SAW   -> Pair(120f, 120f)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Entity data classes (live here so both Engine and Spawner share them
    // without depending on the View layer)
    // ─────────────────────────────────────────────────────────────────────────

    enum class ObstacleKind { BOX, SPIKE, SAW }

    data class EngineObstacle(
        var x:        Float,
        var width:    Float,
        var height:   Float,
        var type:     ObstacleKind,
        var rotation: Float = 0f
    )

    data class EngineCoin(
        var x:         Float,
        var y:         Float,
        val radius:    Float = 40f,
        var bobOffset: Float = 0f,  // phase offset for vertical bob animation
        var bobY:      Float = 0f   // computed each frame by GameEngine
    )

    data class EnginePowerUp(
        var x:    Float,
        var y:    Float,
        var size: Float,
        val type: EnginePowerType
    )

    enum class EnginePowerType { SHIELD, SLOW, DOUBLE, MAGNET, GHOST }

    data class EngineFloatText(
        var x:     Float,
        var y:     Float,
        val text:  String,
        val color: Int,
        var life:  Float = 1.5f,
        var vy:    Float = -2.2f
    )

    companion object {
        private const val BASE_SPEED = 14f
    }
}
