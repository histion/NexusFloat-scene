package com.jj.nexusfloat.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jj.nexusfloat.ui.theme.MdThemeOnSurfaceVariant

/**
 * 统计页的折线图。
 *
 * 没用第三方图表库：这个模块一共就两张图（充电曲线、放电曲线），形态固定——
 * 一条主线 + 最多两条副线、左右两个纵轴、横轴是时间。为这点需求拉一个图表库
 * 进来，体积先涨个几百 KB，配色还得再套一层适配，本界面所有颜色都是手写的
 * （见 Theme.kt），对不上更麻烦。
 *
 * 数据点里允许有 null，表示那个时刻没有采样。断点会被断开而不是连直线——
 * 息屏期间没有采样是常态，连起来会画出一条骗人的斜线。
 */
data class ChartLine(
    val label: String,
    val color: Color,
    /** 跟 x 轴等长的数值序列；null 表示该处缺数据 */
    val points: List<Float?>,
    /** true 用右轴刻度，false 用左轴 */
    val useRightAxis: Boolean = false,
    /** 虚线：给温度这种参考性指标用，免得抢了主线的视觉重量 */
    val dashed: Boolean = false
)

private val ChartHeight = 168.dp
/**
 * 左右留出来的刻度文字宽度。
 *
 * 不用对外暴露具体数值，但 DrawScope 里的图标塔绘制要靠它跟折线图对齐——
 * 塔和曲线是同一个绘图区，左右界必须完全一致。
 */
internal val ChartAxisGutter = 36.dp
/** 不画右轴时右边只留一点点空隙 */
internal val ChartAxisGutterSmall = 8.dp
private val TopPadding = 10.dp
private val BottomLabelsHeight = 16.dp

@Composable
fun LineChart(
    lines: List<ChartLine>,
    leftMin: Float,
    leftMax: Float,
    modifier: Modifier = Modifier,
    rightMin: Float = 0f,
    rightMax: Float = 1f,
    showRightAxis: Boolean = false,
    leftFormat: (Float) -> String = { it.toInt().toString() },
    rightFormat: (Float) -> String = { it.toInt().toString() },
    /** 横轴两端和中间的三个标签，空列表就不画 */
    xLabels: List<String> = emptyList(),
    /** 第一条曲线下面铺渐变，让主线有个「面积」的份量 */
    fillPrimary: Boolean = true,
    /**
     * 每个数据点对应的真实时间戳（毫秒），必须和 points 等长且递增。
     *
     * 给了就按**时间比例**排布横坐标，不给就按点序号等距排。
     * 这两种只有「采样间隔严格均匀」时才等价，而它经常不均匀：
     * 开着「仅亮屏时记录」时息屏期间根本不采样，进程被杀也会留一段空洞。
     * 按序号排会把空洞整体压扁，曲线和下面的时间标签、图里的图标塔就对不上了。
     */
    xValues: List<Long>? = null,
    /**
     * 叠在折线下面的「应用图标塔」，见 [AppTower]。
     *
     * 给了它，绘图区会套一圈描边、底部多出一条图标塔带，曲线画在塔**上面**；
     * 不给就还是纯粹的一张折线图（充电曲线就是这么用的）。
     */
    appTower: AppTower? = null
) {
    val measurer = rememberTextMeasurer()
    val gridColor = MdThemeOnSurfaceVariant.copy(alpha = 0.18f)
    val axisTextColor = MdThemeOnSurfaceVariant
    val plotBorderColor = MdThemeOnSurfaceVariant.copy(alpha = 0.30f)
    val iconPlaceholderColor = MdThemeOnSurfaceVariant.copy(alpha = 0.30f)
    val labelStyle = TextStyle(color = axisTextColor, fontSize = 9.sp)
    val xLabelStyle = TextStyle(color = axisTextColor, fontSize = 9.sp)

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(ChartHeight)
        ) {
            val leftPad = ChartAxisGutter.toPx()
            val rightPad = if (showRightAxis) ChartAxisGutter.toPx() else ChartAxisGutterSmall.toPx()
            val topPad = TopPadding.toPx()
            val bottomPad = if (xLabels.isEmpty()) 6.dp.toPx() else BottomLabelsHeight.toPx()

            val plotW = size.width - leftPad - rightPad
            val plotH = size.height - topPad - bottomPad
            if (plotW <= 0f || plotH <= 0f) {
                return@Canvas
            }

            // ---- 横向网格 + 左右刻度 ----
            val gridCount = 4
            for (i in 0..gridCount) {
                val y = topPad + plotH * i / gridCount
                drawLine(
                    color = gridColor,
                    start = Offset(leftPad, y),
                    end = Offset(leftPad + plotW, y),
                    strokeWidth = 1f
                )
                val leftValue = leftMax - (leftMax - leftMin) * i / gridCount
                val leftText = leftFormat(leftValue)
                drawText(
                    textMeasurer = measurer,
                    text = leftText,
                    style = labelStyle,
                    topLeft = Offset(
                        x = leftPad - 4.dp.toPx() - measureWidth(measurer, leftText, labelStyle),
                        y = y - 5.dp.toPx()
                    )
                )
                if (showRightAxis) {
                    val rightValue = rightMax - (rightMax - rightMin) * i / gridCount
                    drawText(
                        textMeasurer = measurer,
                        text = rightFormat(rightValue),
                        style = labelStyle,
                        topLeft = Offset(leftPad + plotW + 4.dp.toPx(), y - 5.dp.toPx())
                    )
                }
            }

            // ---- 应用图标塔：画在网格之上、曲线之下 ----
            // 层次是照 Scene 的「使用过程」来的：塔立在绘图区底线上，折线压在塔
            // 上面。一个是一根亮线、一个是成片的色块，叠起来仍然读得清
            if (appTower != null) {
                drawAppTower(
                    tower = appTower,
                    leftPad = leftPad,
                    plotW = plotW,
                    plotTop = topPad,
                    plotH = plotH,
                    borderColor = plotBorderColor,
                    placeholderColor = iconPlaceholderColor
                )
            }

            // ---- 曲线 ----
            val count = lines.maxOfOrNull { it.points.size } ?: 0
            if (count >= 2) {
                lines.forEachIndexed { index, line ->
                    val path = Path()
                    var started = false
                    var lastX = 0f
                    var lastY = 0f
                    line.points.forEachIndexed { i, value ->
                        if (value == null) {
                            started = false
                            return@forEachIndexed
                        }
                        val x = xAt(i, count, xValues, leftPad, plotW)
                        val v = if (line.useRightAxis) {
                            scale(value, rightMin, rightMax)
                        } else {
                            scale(value, leftMin, leftMax)
                        }
                        val y = topPad + plotH * (1f - v)
                        if (started) {
                            path.lineTo(x, y)
                        } else {
                            path.moveTo(x, y)
                            started = true
                        }
                        lastX = x
                        lastY = y
                    }

                    if (index == 0 && fillPrimary) {
                        // 面积只铺在第一条线下面。多条线都铺渐变会互相糊在一起
                        val area = buildAreaPath(line, count, leftPad, plotW, topPad, plotH,
                            leftMin, leftMax, rightMin, rightMax, xValues)
                        if (area != null) {
                            drawPath(
                                path = area,
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        line.color.copy(alpha = 0.30f),
                                        line.color.copy(alpha = 0.02f)
                                    ),
                                    startY = topPad,
                                    endY = topPad + plotH
                                )
                            )
                        }
                    }

                    drawPath(
                        path = path,
                        color = line.color,
                        style = Stroke(
                            width = if (index == 0) 2.4f.dp.toPx() else 1.6f.dp.toPx(),
                            cap = StrokeCap.Round,
                            pathEffect = if (line.dashed) {
                                PathEffect.dashPathEffect(
                                    floatArrayOf(6.dp.toPx(), 5.dp.toPx())
                                )
                            } else {
                                null
                            }
                        )
                    )

                    // 末尾画个圆点，一眼看到当前值落在哪
                    if (index == 0 && count > 0 && line.points.lastOrNull() != null) {
                        drawCircle(
                            color = line.color,
                            radius = 3.dp.toPx(),
                            center = Offset(lastX, lastY)
                        )
                    }
                }
            }

            // ---- 横轴标签 ----
            if (xLabels.isNotEmpty()) {
                val y = size.height - BottomLabelsHeight.toPx() + 2.dp.toPx()
                xLabels.forEachIndexed { i, text ->
                    val w = measureWidth(measurer, text, xLabelStyle)
                    val x = when (i) {
                        0 -> leftPad
                        xLabels.size - 1 -> leftPad + plotW - w
                        else -> leftPad + plotW / 2f - w / 2f
                    }
                    drawText(
                        textMeasurer = measurer,
                        text = text,
                        style = xLabelStyle,
                        topLeft = Offset(x, y)
                    )
                }
            }
        }

        if (lines.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                lines.forEach { line ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(line.color)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = line.label,
                            fontSize = 10.sp,
                            color = MdThemeOnSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** 把第一条线的折线闭合成一个可用于填充的区域 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.buildAreaPath(
    line: ChartLine,
    count: Int,
    leftPad: Float,
    plotW: Float,
    topPad: Float,
    plotH: Float,
    leftMin: Float,
    leftMax: Float,
    rightMin: Float,
    rightMax: Float,
    xValues: List<Long>?
): Path? {
    val indices = line.points.indices.filter { line.points[it] != null }
    if (indices.size < 2) {
        return null
    }
    val path = Path()
    var firstX = 0f
    var lastX = 0f
    val baseline = topPad + plotH
    indices.forEachIndexed { n, i ->
        val value = line.points[i] ?: return@forEachIndexed
        val v = if (line.useRightAxis) scale(value, rightMin, rightMax)
        else scale(value, leftMin, leftMax)
        val x = xAt(i, count, xValues, leftPad, plotW)
        val y = topPad + plotH * (1f - v)
        if (n == 0) {
            path.moveTo(x, baseline)
            path.lineTo(x, y)
            firstX = x
        } else {
            path.lineTo(x, y)
        }
        lastX = x
    }
    path.lineTo(lastX, baseline)
    path.close()
    if (firstX == lastX) {
        return null
    }
    return path
}

/**
 * 第 index 个数据点的横坐标。
 *
 * 优先按 xValues（真实时间戳）定比例；xValues 缺失、长度对不上、或者时间跨度为 0
 * （同一毫秒里的多个点）时就退回「按序号等距」——退化处理比画不出来强。
 */
private fun xAt(
    index: Int,
    count: Int,
    xValues: List<Long>?,
    leftPad: Float,
    plotW: Float
): Float {
    if (count <= 1) {
        return leftPad
    }
    if (xValues != null && xValues.size == count) {
        val span = xValues[count - 1] - xValues[0]
        if (span > 0L) {
            return leftPad + plotW * ((xValues[index] - xValues[0]).toFloat() / span.toFloat())
        }
    }
    return leftPad + plotW * index / (count - 1).toFloat()
}

/** 把数值映射到 0–1；区间退化（min == max）时统一给 0.5，免得除零 */
private fun scale(value: Float, min: Float, max: Float): Float {
    if (max - min < 0.0001f) {
        return 0.5f
    }
    return ((value - min) / (max - min)).coerceIn(0f, 1f)
}

/** 量一段文字的宽度，用来做右对齐和居中 */
private fun measureWidth(measurer: TextMeasurer, text: String, style: TextStyle): Float {
    return measurer.measure(text, style).size.width.toFloat()
}

/**
 * 横向条形图的一行，用于应用用时 / 耗电排行。
 *
 * 没用 Canvas：这种「一行一个标签 + 一条比例条 + 右侧数值」的结构用 Compose 的
 * 布局写出来天然就是响应式的，字长变化、字体缩放都不用管。
 */
@Composable
fun RankBarRow(
    label: String,
    valueText: String,
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    barHeight: Dp = 6.dp
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                color = com.jj.nexusfloat.ui.theme.MdThemeOnSurface,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = valueText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MdThemeOnSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.15f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0.01f, 1f))
                    .height(barHeight)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

/** 只要一条比例条，不要上面的标签行。应用榜那行自己排好了版，再套一层就重了 */
@Composable
fun BarOnly(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    barHeight: Dp = 5.dp
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.15f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                .height(barHeight)
                .clip(CircleShape)
                .background(color)
        )
    }
}

/** 分段占比条：亮屏 / 待机这种「两段加起来是一整天」的场景 */
@Composable
fun SegmentedBar(
    segments: List<Pair<Float, Color>>,
    modifier: Modifier = Modifier,
    height: Dp = 10.dp
) {
    val total = segments.sumOf { it.first.toDouble() }.toFloat()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (total <= 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .clip(CircleShape)
                    .background(MdThemeOnSurfaceVariant.copy(alpha = 0.15f))
            )
            return@Row
        }
        segments.forEach { (weight, color) ->
            Box(
                modifier = Modifier
                    .weight(weight.coerceAtLeast(0.0001f))
                    .height(height)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

// ============================================================
// 应用图标塔（Scene「使用过程」那种呈现）
//
// 它是**折线图的一部分**，不是图外面另开一块：塔立在绘图区底线上，自下而上码
// 应用图标——底下的是这一段里用得最久的，越往上越零碎；整排柱子高低起伏，就是
// 「手机用了多少」的波形。曲线随后画在塔上面。
//
// 关键在横轴：塔和曲线共用同一个绘图区、同一套「时间 → 横坐标」的比例，所以
// 「那一刻在用什么」和「那一刻还剩多少电」是对着看的，不是两张图各看各的。
// ============================================================

/** 图标塔里的一个应用；时长已经在该时间桶里聚合过 */
data class TimelineApp(val pkg: String, val durationMs: Long)

/**
 * 折线图里叠的应用图标塔。
 *
 * @param buckets 每个时间桶里各应用的时长，外层下标就是桶序号
 * @param icons 包名 → 图标；缺的包名画灰块占位
 */
data class AppTower(
    val buckets: List<List<TimelineApp>>,
    val icons: Map<String, ImageBitmap> = emptyMap()
)

/** 图标塔占绘图区高度的比例。占满整高会把曲线埋掉，太小又看不出起伏 */
private const val TowerBandFraction = 0.55f
/** 单个图标的边长上限；实际还会按柱宽再收一次，免得相邻柱子叠在一起 */
private val TowerIconMax = 12.dp
private val TowerIconGap = 1.dp

/**
 * 在绘图区里画应用图标塔。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAppTower(
    tower: AppTower,
    leftPad: Float,
    plotW: Float,
    plotTop: Float,
    plotH: Float,
    borderColor: Color,
    placeholderColor: Color
) {
    val count = tower.buckets.size
    if (count <= 0 || plotW <= 0f || plotH <= 0f) {
        return
    }
    // 绘图区描边：塔需要一条看得见的「地面」和左右界，不然图标像浮在卡片上
    drawRoundRect(
        color = borderColor,
        topLeft = Offset(leftPad, plotTop),
        size = Size(plotW, plotH),
        cornerRadius = CornerRadius(6.dp.toPx()),
        style = Stroke(width = 1f)
    )

    val bucketW = plotW / count
    // 柱宽定图标大小：桶数一多，图标必须跟着缩，不然左右会压到相邻的柱子上
    val iconPx = minOf(TowerIconMax.toPx(), bucketW - 1.dp.toPx()).coerceAtLeast(4.dp.toPx())
    val rowPx = iconPx + TowerIconGap.toPx()
    val maxRows = (plotH * TowerBandFraction / rowPx).toInt().coerceAtLeast(1)
    val bottom = plotTop + plotH
    val side = iconPx.toInt().coerceAtLeast(1)

    val columns = buildTowerColumns(tower.buckets, maxRows)
    columns.forEachIndexed { index, column ->
        if (column.isEmpty()) {
            return@forEachIndexed
        }
        val centerX = leftPad + bucketW * index + bucketW / 2f
        var row = 0
        for ((pkg, iconCount) in column) {
            val bitmap = tower.icons[pkg]
            for (n in 0 until iconCount) {
                if (row >= maxRows) {
                    break
                }
                val left = (centerX - iconPx / 2f).toInt()
                val top = (bottom - rowPx * (row + 1)).toInt()
                if (bitmap != null) {
                    drawImage(
                        image = bitmap,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(bitmap.width, bitmap.height),
                        dstOffset = IntOffset(left, top),
                        dstSize = IntSize(side, side)
                    )
                } else {
                    // 图标拿不到（应用已卸载、图标读取失败）时画个灰块占位。
                    // 直接跳过的话那一格凭空消失，看着像那段时间没用手机
                    drawRoundRect(
                        color = placeholderColor,
                        topLeft = Offset(left.toFloat(), top.toFloat()),
                        size = Size(iconPx, iconPx),
                        cornerRadius = CornerRadius(iconPx * 0.25f)
                    )
                }
                row++
            }
        }
    }
}

/**
 * 把「每桶每应用时长」摊成「每桶画几个图标、按什么顺序摞」。
 *
 * 两步：
 * 1. 定柱高。按「这一桶的前台总时长 ÷ 所有桶里最高的那一桶」定，最多 maxRows 行。
 * 2. 分格子。桶内每个应用先占一格，剩下的格子给「时长 ÷ 已占格数」最大的那个
 *    （最大余数法）。这样高度和时长成正比，图标总数又不会超过柱高。
 *
 * 返回的每个内层列表是**自下而上**的顺序，画的时候从第 0 个往上摞就行。
 */
private fun buildTowerColumns(
    buckets: List<List<TimelineApp>>,
    maxRows: Int
): List<List<Pair<String, Int>>> {
    if (buckets.isEmpty() || maxRows <= 0) {
        return emptyList()
    }
    val totals = buckets.map { bucket -> bucket.sumOf { it.durationMs } }
    val peak = totals.maxOrNull() ?: 0L
    return buckets.mapIndexed { index, apps ->
        val total = totals[index]
        if (total <= 0L || peak <= 0L || apps.isEmpty()) {
            return@mapIndexed emptyList()
        }
        val rows = Math.round(total.toDouble() / peak * maxRows).toInt().coerceIn(1, maxRows)
        val sorted = apps.sortedByDescending { it.durationMs }
        // 应用个数比柱高还多时只留用得最久的前几个：一格至少要放一个图标，
        // 硬塞会画到柱子外面去
        val top = if (sorted.size > rows) sorted.subList(0, rows) else sorted
        val counts = IntArray(top.size) { 1 }
        var used = top.size
        while (used < rows) {
            var best = 0
            var bestScore = -1.0
            for (i in top.indices) {
                val score = top[i].durationMs.toDouble() / counts[i]
                if (score > bestScore) {
                    bestScore = score
                    best = i
                }
            }
            counts[best]++
            used++
        }
        top.mapIndexed { i, app -> app.pkg to counts[i] }
    }
}

/** SQL 已经按「桶 + 包名」聚过合了，这里只是摊成按桶下标的列表，桶内不会重复 */
internal fun bucketize(rows: List<com.jj.nexusfloat.stats.StatsStore.FgBucket>, buckets: Int):
        List<List<TimelineApp>> {
    if (buckets <= 0) {
        return emptyList()
    }
    val out = ArrayList<MutableList<TimelineApp>>(buckets)
    for (i in 0 until buckets) {
        out.add(ArrayList())
    }
    for (r in rows) {
        out[r.bucket.coerceIn(0, buckets - 1)].add(TimelineApp(r.pkg, r.durationMs))
    }
    return out
}

/** 每个应用在一段区间里的前台总时长，按降序。图标塔下面的图例用它 */
internal fun towerTotals(rows: List<com.jj.nexusfloat.stats.StatsStore.FgBucket>):
        List<Pair<String, Long>> {
    val map = HashMap<String, Long>()
    for (r in rows) {
        map[r.pkg] = (map[r.pkg] ?: 0L) + r.durationMs
    }
    return map.entries
        .sortedByDescending { it.value }
        .map { it.key to it.value }
}
