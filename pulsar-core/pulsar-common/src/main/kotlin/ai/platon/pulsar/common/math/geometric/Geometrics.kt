package ai.platon.pulsar.common.math.geometric

import java.awt.Point
import java.awt.Rectangle
import kotlin.math.abs
import kotlin.math.roundToInt

data class GeoIntPoint(var x: Int, var y: Int): Comparable<GeoIntPoint> {
    constructor(point: Point) : this(point.x, point.y)

    fun toPoint(): Point {
        return Point(x, y)
    }

    fun align(gridWidth: Int = 0, gridHeight: Int = 0): GeoIntPoint {
        val x2 = if (gridWidth == 0) x else (x.toFloat() / gridWidth).roundToInt() * gridWidth
        val y2 = if (gridHeight == 0) y else (y.toFloat() / gridHeight).roundToInt() * gridHeight
        return GeoIntPoint(x2, y2)
    }

    fun align(grid: DimI): GeoIntPoint {
        return align(grid.width, grid.height)
    }

    override fun compareTo(other: GeoIntPoint): Int {
        val r = x - other.x
        return if (r == 0) y - other.y else r
    }
}

operator fun Point.component1(): Int {
    return x
}

operator fun Point.component2(): Int {
    return y
}

fun Point.toIntPoint(): GeoIntPoint {
    return GeoIntPoint(x, y)
}

val Point.str
    get() = "[$x $y]"

val Rectangle.x2: Int
    get() {
        return x + width
    }

val Rectangle.y2: Int
    get() {
        return y + height
    }

operator fun Rectangle.component1(): Int {
    return x
}

operator fun Rectangle.component2(): Int {
    return y
}

operator fun Rectangle.component3(): Int {
    return width
}

operator fun Rectangle.component4(): Int {
    return height
}

fun Rectangle.sim(rect: Rectangle): Double {
    val overlap = this.intersection(rect)
    if (overlap.isEmpty) return 0.0
    val a = overlap.area
    val b = area.coerceAtLeast(rect.area)
    return 1.0 * a / b
}

val Rectangle.area get() = if (isEmpty) 0 else width * height

val Rectangle.str get() = "[$x $y $width $height]"

val Rectangle.str2 get() = "{x:$x y:$y w:$width h:$height}"

/**
 * The align type to define linearity
 * */
enum class AlignType {
    NONE, LEFT, V_CENTER, RIGHT, TOP, H_CENTER, BOTTOM;

    val isNone: Boolean
        get() = this != NONE

    val isVertical: Boolean
        get() = this in arrayOf(LEFT, V_CENTER, RIGHT)

    val isHorizontal: Boolean
        get() = this in arrayOf(TOP, H_CENTER, BOTTOM)
}

/**
 * Two rectangles are vertical collinear if they are intersects and have a padding, ignore the height
 * TODO: not fully tested
 * */
fun Rectangle.testAlignment(r: Rectangle, bias: Double = 0.2): AlignType {
    val b = bias.coerceAtMost(1.0)
    return when {
        abs(x - r.x)               <= b * width -> AlignType.LEFT
        abs(centerX - r.centerX)   <= b * width -> AlignType.V_CENTER
        abs(x2 - r.x2)             <= b * width -> AlignType.RIGHT
        abs(y - r.y)               <= b * height -> AlignType.TOP
        abs(centerY - r.centerY)   <= b * height -> AlignType.H_CENTER
        abs(y2 - r.y2)             <= b * height -> AlignType.BOTTOM
        else -> AlignType.NONE
    }
}

data class PointI(
    var x: Int,
    var y: Int
)

data class DimI(
    var width: Int,
    var height: Int
)

data class RectI(
    var x: Int,
    var y: Int,
    var with: Int,
    var height: Int
)

data class OffsetI(
    var x: Int,
    var y: Int,
)

data class PointD(
    var x: Double,
    var y: Double
)

data class DimD(
    var width: Double,
    var height: Double
)

data class RectD(
    var x: Double,
    var y: Double,
    var width: Double,
    var height: Double
)

data class OffsetD(
    var x: Double,
    var y: Double,
)

object GeometricUtils {

    fun findMaxRectangle(rectangles: List<Rectangle>): Rectangle? {
        // 提取所有 x 和 y 坐标边界
        val xCoords = mutableSetOf<Int>()
        val yCoords = mutableSetOf<Int>()

        // 遍历矩形，收集 x 和 y 的边界
        for (rect in rectangles) {
            xCoords.add(rect.x)
            xCoords.add(rect.x2)
            yCoords.add(rect.y)
            yCoords.add(rect.y2)
        }

        // 排序 x 和 y 的边界
        val xSorted = xCoords.sorted()
        val ySorted = yCoords.sorted()

        var maxArea = 0
        var maxRect: Rectangle? = null

        // 检查每个由 (x1, x2) 和 (y1, y2) 构成的网格区域
        for (i in 0 until xSorted.size - 1) {
            for (j in 0 until ySorted.size - 1) {
                val x1 = xSorted[i]
                val x2 = xSorted[i + 1]
                val y1 = ySorted[j]
                val y2 = ySorted[j + 1]

                // 如果该区域没有与任何矩形相交，计算其面积
                if (isEmpty(x1, x2, y1, y2, rectangles)) {
                    val area = (x2 - x1) * (y2 - y1)
                    if (area > maxArea) {
                        maxArea = area
                        maxRect = Rectangle(x1, x2, y1, y2)
                    }
                }
            }
        }

        return maxRect
    }

    // 检查区域 (x1, x2, y1, y2) 是否与任何矩形相交
    fun isEmpty(x1: Int, x2: Int, y1: Int, y2: Int, rectangles: List<Rectangle>): Boolean {
        for (rect in rectangles) {
            // 如果有相交的矩形，返回 false
            if (!(x2 <= rect.x || x1 >= rect.x2 || y2 <= rect.y || y1 >= rect.y2)) {
                return false
            }
        }
        return true
    }
}
