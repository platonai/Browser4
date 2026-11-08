package ai.platon.browser4.behavior.ghostcursor

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** A 2D point or vector in CSS pixels. */
internal data class Vector2(val x: Double, val y: Double)

/** A [Vector2] with the time, in milliseconds, at which the pointer should reach it. */
internal data class TimedPoint(val x: Double, val y: Double, val timeMillis: Double)

/**
 * The geometry behind the ghost-cursor trajectory model, ported to Kotlin.
 *
 * The original lives in `Xetera/ghost-cursor` (`src/math.ts`); every function here keeps the
 * same shape so the resulting path is recognisably the same model:
 *
 * - a cubic Bézier whose two control points sit on a random **normal line** of the
 *   start→target segment, which is what makes the path bend instead of cutting straight across
 * - [fitts], the Fitts-law difficulty index, to scale pacing with target size rather than
 *   distance alone
 * - [overshoot], a uniformly distributed point inside a disc *past* the target, which models a
 *   human who travels too far and then corrects
 * - [timedBezierCurve], arc-length sampling warped by a smooth-step profile, so the pointer
 *   accelerates out of rest and decelerates into the target instead of moving at constant speed
 *
 * Deliberate differences from the JavaScript original:
 *
 * - every function takes an explicit [Random], so the whole policy is reproducible in tests
 *   (the original reads `Math.random` directly)
 * - [overshoot] draws its radius and its angle from two independent values; the original reuses
 *   one value for both, which correlates distance with direction
 * - degenerate inputs return a sane value instead of `NaN` (the original can divide by a zero
 *   magnitude for two coincident points)
 */
internal object GhostCursorMath {
    /** Smallest control-point offset from the straight line, in pixels. */
    const val SPREAD_MIN: Double = 2.0

    /** Largest control-point offset from the straight line, in pixels. */
    const val SPREAD_MAX: Double = 200.0

    /** Control-point offset used by the convergence segment after an overshoot. */
    const val OVERSHOOT_CONVERGE_SPREAD: Double = 10.0

    /** Radius, in pixels, of the disc a long move overshoots into. */
    const val OVERSHOOT_RADIUS: Double = 120.0

    fun add(a: Vector2, b: Vector2): Vector2 = Vector2(a.x + b.x, a.y + b.y)

    fun sub(a: Vector2, b: Vector2): Vector2 = Vector2(a.x - b.x, a.y - b.y)

    fun scale(a: Vector2, factor: Double): Vector2 = Vector2(a.x * factor, a.y * factor)

    fun magnitude(a: Vector2): Double = hypot(a.x, a.y)

    fun direction(a: Vector2, b: Vector2): Vector2 = sub(b, a)

    fun perpendicular(a: Vector2): Vector2 = Vector2(a.y, -a.x)

    /** Unit vector of [a], or the zero vector when [a] is degenerate. */
    fun unit(a: Vector2): Vector2 {
        val length = magnitude(a)
        return if (length == 0.0) Vector2(0.0, 0.0) else Vector2(a.x / length, a.y / length)
    }

    fun setMagnitude(a: Vector2, amount: Double): Vector2 = scale(unit(a), amount)

    /** Uniform random value in `[min, max)`. */
    fun randomRange(min: Double, max: Double, random: Random): Double {
        if (max <= min) {
            return min
        }
        return random.nextDouble() * (max - min) + min
    }

    /** A uniformly random point on the segment `a`→`b`. */
    fun randomVectorOnLine(a: Vector2, b: Vector2, random: Random): Vector2 {
        val vec = direction(a, b)
        return add(a, scale(vec, random.nextDouble()))
    }

    /**
     * Two points on the normal of `a`→`b`, at a random position along the segment and
     * [range] pixels to either side.
     */
    fun randomNormalLine(a: Vector2, b: Vector2, range: Double, random: Random): Pair<Vector2, Vector2> {
        val randomMid = randomVectorOnLine(a, b, random)
        val normal = setMagnitude(perpendicular(direction(a, randomMid)), range)
        return add(randomMid, normal) to sub(randomMid, normal)
    }

    /**
     * The four anchors of the trajectory Bézier: both endpoints plus two control points drawn
     * from random normal lines, kept on the same side so the path bows once instead of
     * zig-zagging like an S.
     */
    fun generateBezierAnchors(a: Vector2, b: Vector2, spread: Double, random: Random): List<Vector2> {
        val positiveSide = random.nextBoolean()
        fun anchor(): Vector2 {
            val offset = if (positiveSide) {
                randomRange(SPREAD_MIN, spread, random)
            } else {
                randomRange(-spread, -SPREAD_MIN, random)
            }
            val line = randomNormalLine(a, b, offset, random)
            return if (positiveSide) line.first else line.second
        }
        return listOf(a, anchor(), anchor(), b)
    }

    /** The point at parameter [t] (0..1) of the cubic Bézier through [anchors]. */
    fun bezierPoint(anchors: List<Vector2>, t: Double): Vector2 {
        require(anchors.size == 4) { "a cubic Bézier needs exactly 4 anchors, got ${anchors.size}" }
        val u = 1.0 - t
        val p0 = anchors[0]
        val p1 = anchors[1]
        val p2 = anchors[2]
        val p3 = anchors[3]
        val b0 = u * u * u
        val b1 = 3 * u * u * t
        val b2 = 3 * u * t * t
        val b3 = t * t * t
        return Vector2(
            x = b0 * p0.x + b1 * p1.x + b2 * p2.x + b3 * p3.x,
            y = b0 * p0.y + b1 * p1.y + b2 * p2.y + b3 * p3.y,
        )
    }

    /**
     * Sample the curve into [samples] evenly parameterized points, then time each one by its
     * fraction of the total arc length warped through a smooth-step profile.
     *
     * The warp is what gives the trail its human rhythm: `smoothstep(f) = f²(3-2f)` is slow at
     * both ends and fast in the middle, so the pointer eases out of rest, crosses quickly and
     * settles on the target instead of crawling at a constant rate.
     */
    fun timedBezierCurve(
        anchors: List<Vector2>,
        samples: Int,
        totalDurationMillis: Double,
    ): List<TimedPoint> {
        val count = samples.coerceAtLeast(2)
        val points = List(count) { index -> bezierPoint(anchors, index.toDouble() / (count - 1)) }

        val cumulative = DoubleArray(count)
        for (index in 1 until count) {
            cumulative[index] = cumulative[index - 1] + magnitude(sub(points[index], points[index - 1]))
        }
        val totalLength = cumulative[count - 1]

        return points.mapIndexed { index, point ->
            val fraction = if (totalLength == 0.0) {
                index.toDouble() / (count - 1)
            } else {
                cumulative[index] / totalLength
            }
            TimedPoint(point.x, point.y, totalDurationMillis * smoothStep(fraction))
        }
    }

    /** Hermite smooth step on `[0, 1]`. */
    fun smoothStep(fraction: Double): Double {
        val f = fraction.coerceIn(0.0, 1.0)
        return f * f * (3 - 2 * f)
    }

    /**
     * The Fitts-law index of difficulty for travelling [distance] to a target [width] wide:
     * `2 · log2(distance / width + 1)`.
     *
     * A small far target is harder to acquire than a large near one, which is why the index
     * (and therefore the step count) grows with distance and shrinks with width.
     */
    fun fitts(distance: Double, width: Double): Double {
        val effectiveWidth = if (width > 0.0) width else 1.0
        return 2.0 * log2(distance / effectiveWidth + 1.0)
    }

    /**
     * A point inside a disc of [radius] centred on [point], i.e. past the target rather than on
     * it — the "travelled too far, now correct" half of a human reach.
     *
     * `r = radius · √u` keeps the distribution uniform over the disc area (a plain `radius · u`
     * would bunch points near the centre).
     */
    fun overshoot(point: Vector2, radius: Double, random: Random): Vector2 {
        val distance = radius * sqrt(random.nextDouble())
        val angle = random.nextDouble() * 2 * PI
        return Vector2(
            x = point.x + distance * cos(angle),
            y = point.y + distance * sin(angle),
        )
    }
}
