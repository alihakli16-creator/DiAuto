package com.andrerinas.openheadunit.hud

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Modern dual-card instrument cluster navigation view matching Apple Maps / SealNav layout:
 * 1. Upper Card: Blue rounded maneuver chip with crisp white turn arrow, large bold distance,
 *    street name, and optional bottom "THEN ↱ Next Road" strip.
 * 2. Lower Card: Floating 3-column ETA pill (ETA / duration, DISTANCE, ARRIVAL in vibrant cyan/blue).
 * Built for BYD Seal U DM-i DiLink instrument cluster.
 */
internal class ClusterTurnCardView(context: Context) : View(context) {
    private var guidance: BydGuidance? = null
    private var plan: ClusterMapConfig.Window? = null
    private var night = false

    private val accent = Color.rgb(10, 132, 255) // Apple Blue #0A84FF
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
    }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
    }
    private val distancePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val subDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val thenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
    }
    private val summaryLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val summaryValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val arrivalValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    private val rect = RectF()
    private val chipRect = RectF()
    private var glyph: Drawable? = null
    private var glyphRes = 0

    init { applyPalette() }

    fun setPlan(next: ClusterMapConfig.Window?) {
        plan = next
        invalidate()
    }

    /** Day: translucent light frosted glass; night: translucent dark frosted glass. */
    fun setNightMode(next: Boolean) {
        if (next == night) return
        night = next
        applyPalette()
        invalidate()
    }

    fun setGuidance(next: BydGuidance?) {
        visibility = if (next == null) GONE else VISIBLE
        if (guidance == next) return
        guidance = next
        invalidate()
    }

    private fun applyPalette() {
        if (night) {
            // Night mode: dark frosted glass, white text, glowing cyan arrival
            glassPaint.color = Color.argb(220, 16, 20, 26)
            strokePaint.color = Color.argb(45, 255, 255, 255)
            distancePaint.color = Color.WHITE
            roadPaint.color = Color.argb(215, 225, 228, 235)
            subDividerPaint.color = Color.argb(35, 255, 255, 255)
            thenPaint.color = Color.argb(170, 175, 180, 190)
            dividerPaint.color = Color.argb(35, 255, 255, 255)
            summaryLabelPaint.color = Color.argb(175, 165, 170, 180)
            summaryValuePaint.color = Color.WHITE
            arrivalValuePaint.color = Color.rgb(0, 199, 190) // Glowing cyan #00C7BE
        } else {
            // Day mode: light frosted glass, dark text, vibrant blue arrival
            glassPaint.color = Color.argb(235, 250, 250, 252)
            strokePaint.color = Color.argb(40, 0, 0, 0)
            distancePaint.color = Color.rgb(28, 28, 30)
            roadPaint.color = Color.rgb(60, 60, 67)
            subDividerPaint.color = Color.argb(25, 0, 0, 0)
            thenPaint.color = Color.rgb(120, 120, 128)
            dividerPaint.color = Color.argb(30, 0, 0, 0)
            summaryLabelPaint.color = Color.rgb(120, 120, 128)
            summaryValuePaint.color = Color.rgb(28, 28, 30)
            arrivalValuePaint.color = Color.rgb(10, 132, 255) // Vivid Apple Blue #0A84FF
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val next = guidance ?: return
        val pos = try { com.andrerinas.openheadunit.App.provide(context).settings.clusterTurnCardPosition } catch (_: Exception) { 0 }
        val card = ClusterTurnCardPlacement.card(width, height, plan, pos) ?: return
        val w = card.width.toFloat()
        val cardLeft = card.left.toFloat()
        val cardTop = card.top.toFloat()

        val hasNextStep = next.nextRoad.isNotBlank()
        val hasSummary = next.remainingSeconds > 0 || next.remainingMeters > 0 || next.eta.isNotBlank()

        val upperH = if (hasNextStep) w * 0.38f else w * 0.31f
        val gap = 8f
        val lowerH = (w * 0.17f).coerceIn(48f, 54f)
        val cardRadius = 18f
        val lowerRadius = 16f

        // ==========================================
        // 1. UPPER CARD (Maneuver Card)
        // ==========================================
        rect.set(cardLeft, cardTop, cardLeft + w, cardTop + upperH)
        canvas.drawRoundRect(rect, cardRadius, cardRadius, glassPaint)
        canvas.drawRoundRect(rect, cardRadius, cardRadius, strokePaint)

        val maneuverH = if (hasNextStep) upperH * 0.74f else upperH

        // Blue chip for maneuver arrow
        val chipSize = maneuverH * 0.72f
        val chipLeft = cardLeft + 12f
        val chipTop = cardTop + (maneuverH - chipSize) / 2f
        val chipRadius = chipSize * 0.25f
        chipRect.set(chipLeft, chipTop, chipLeft + chipSize, chipTop + chipSize)
        canvas.drawRoundRect(chipRect, chipRadius, chipRadius, chipPaint)

        // Draw white arrow glyph inside blue chip
        drawGlyph(canvas, next, chipLeft, chipTop, chipSize)

        // Distance & Road text
        val textLeft = chipLeft + chipSize + 12f
        val maxTextWidth = cardLeft + w - 12f - textLeft
        if (maxTextWidth > 0f) {
            val distLabel = distanceLabel(next.distanceMeters)
            distancePaint.textSize = maneuverH * 0.36f
            val distBaseline = cardTop + maneuverH * 0.44f
            canvas.drawText(ellipsize(distLabel, maxTextWidth, distancePaint), textLeft, distBaseline, distancePaint)

            val road = if (next.roundaboutExit in 1..9) {
                context.getString(R.string.turn_card_exit, next.roundaboutExit)
            } else if (next.road.isNotBlank()) {
                next.road
            } else {
                maneuverActionText(next.clusterIcon)
            }
            if (road.isNotBlank()) {
                roadPaint.textSize = maneuverH * 0.20f
                val roadBaseline = cardTop + maneuverH * 0.78f
                canvas.drawText(ellipsize(road, maxTextWidth, roadPaint), textLeft, roadBaseline, roadPaint)
            }
        }

        // Optional bottom THEN row inside upper card
        if (hasNextStep) {
            val dividerY = cardTop + maneuverH
            canvas.drawLine(cardLeft + 12f, dividerY, cardLeft + w - 12f, dividerY, subDividerPaint)

            val subH = upperH - maneuverH
            val thenPrefix = when (Locale.getDefault().language) {
                "tr" -> "SONRA"
                "fr" -> "puis"
                else -> "THEN"
            }
            val thenFull = "$thenPrefix ${miniArrow(next.nextClusterIcon)} ${next.nextRoad}"
            thenPaint.textSize = subH * 0.44f
            val thenY = dividerY + subH / 2f - (thenPaint.descent() + thenPaint.ascent()) / 2f
            canvas.drawText(ellipsize(thenFull, w - 24f, thenPaint), cardLeft + 14f, thenY, thenPaint)
        }

        // ==========================================
        // 2. LOWER CARD (ETA / Summary Card)
        // ==========================================
        if (hasSummary) {
            val lowerTop = cardTop + upperH + gap
            rect.set(cardLeft, lowerTop, cardLeft + w, lowerTop + lowerH)
            canvas.drawRoundRect(rect, lowerRadius, lowerRadius, glassPaint)
            canvas.drawRoundRect(rect, lowerRadius, lowerRadius, strokePaint)

            val colW = w / 3f

            // Vertical dividers between the 3 columns
            val divTop = lowerTop + lowerH * 0.20f
            val divBottom = lowerTop + lowerH * 0.80f
            canvas.drawLine(cardLeft + colW, divTop, cardLeft + colW, divBottom, dividerPaint)
            canvas.drawLine(cardLeft + colW * 2f, divTop, cardLeft + colW * 2f, divBottom, dividerPaint)

            // Y baselines for Labels and Values
            summaryLabelPaint.textSize = lowerH * 0.22f
            val labelY = lowerTop + lowerH * 0.36f
            val valCenterY = lowerTop + lowerH * 0.72f

            // Column 1: ETA
            val cx1 = cardLeft + colW * 0.5f
            canvas.drawText("ETA", cx1, labelY, summaryLabelPaint)
            val durText = formatDuration(next.remainingSeconds)
            drawAutoScaledText(canvas, durText, cx1, valCenterY, colW * 0.88f, lowerH * 0.32f, summaryValuePaint)

            // Column 2: DISTANCE
            val cx2 = cardLeft + colW * 1.5f
            canvas.drawText("DISTANCE", cx2, labelY, summaryLabelPaint)
            val distText = formatDistance(next.remainingMeters)
            drawAutoScaledText(canvas, distText, cx2, valCenterY, colW * 0.88f, lowerH * 0.32f, summaryValuePaint)

            // Column 3: ARRIVAL
            val cx3 = cardLeft + colW * 2.5f
            canvas.drawText("ARRIVAL", cx3, labelY, summaryLabelPaint)
            val arrText = formatArrival(next)
            drawAutoScaledText(canvas, arrText, cx3, valCenterY, colW * 0.88f, lowerH * 0.34f, arrivalValuePaint)
        }
    }

    private fun drawAutoScaledText(
        canvas: Canvas,
        text: String,
        cx: Float,
        centerY: Float,
        maxWidth: Float,
        baseSize: Float,
        paint: Paint
    ) {
        paint.textSize = baseSize
        while (paint.measureText(text) > maxWidth && paint.textSize > 9f) {
            paint.textSize -= 0.5f
        }
        val y = centerY - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, cx, y, paint)
    }

    private fun formatDuration(seconds: Int): String {
        if (seconds <= 0) return "--"
        val hours = seconds / 3600
        val minutes = ((seconds % 3600) + 30) / 60
        val isTr = Locale.getDefault().language == "tr"
        return when {
            hours > 0 -> if (isTr) "${hours} sa ${minutes} dk" else "${hours}h ${minutes}m"
            else -> if (isTr) "${maxOf(1, minutes)} dk" else "${maxOf(1, minutes)} min"
        }
    }

    private fun formatDistance(meters: Int): String {
        if (meters < 0) return "--"
        val km = meters / 1000.0
        return when {
            km >= 10.0 -> String.format(Locale.getDefault(), "%.0f km", km)
            km >= 1.0 -> String.format(Locale.getDefault(), "%.1f km", km)
            else -> "$meters m"
        }
    }

    private fun formatArrival(next: BydGuidance): String {
        if (next.eta.isNotBlank()) return next.eta
        if (next.remainingSeconds > 0) {
            val arrival = Date(System.currentTimeMillis() + next.remainingSeconds * 1000L)
            return SimpleDateFormat("HH:mm", Locale.getDefault()).format(arrival)
        }
        return "--:--"
    }

    private fun miniArrow(icon: Int): String = when (icon) {
        2, 4, 6 -> "↰"
        3, 5, 7 -> "↱"
        8 -> "⮌"
        19 -> "⮎"
        11, 12, 17, 18 -> "⟲"
        else -> "↑"
    }

    private fun maneuverActionText(icon: Int): String {
        val isTr = Locale.getDefault().language == "tr"
        return when (icon) {
            2, 4, 6 -> if (isTr) "Sola dönün" else "Turn left"
            3, 5, 7 -> if (isTr) "Sağa dönün" else "Turn right"
            8 -> if (isTr) "U dönüşü yapın" else "Make a U-turn"
            19 -> if (isTr) "Sağdan U dönüşü yapın" else "Make a U-turn"
            11, 12, 17, 18 -> if (isTr) "Kavşaktan çıkın" else "Roundabout exit"
            else -> if (isTr) "Düz devam edin" else "Continue straight"
        }
    }

    private fun drawGlyph(canvas: Canvas, next: BydGuidance, left: Float, top: Float, side: Float) {
        val res = glyphRes(next.clusterIcon)
        if (res != glyphRes) {
            glyph = ContextCompat.getDrawable(context, res)?.mutate()?.apply { setTint(Color.WHITE) }
            glyphRes = res
        }
        val inset = side * 0.14f
        glyph?.setBounds((left + inset).toInt(), (top + inset).toInt(), (left + side - inset).toInt(), (top + side - inset).toInt())
        glyph?.draw(canvas)
        val exit = next.roundaboutExit.takeIf { it in 1..9 } ?: return
        val d = side * 0.38f
        val cx = left + side - d * 0.45f
        val cy = top + side - d * 0.45f
        canvas.drawCircle(cx, cy, d / 2f, badgeBgPaint)
        badgeTextPaint.textSize = d * 0.65f
        canvas.drawText(exit.toString(), cx, cy - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f, badgeTextPaint)
    }

    private fun distanceLabel(meters: Int): String = when {
        meters <= 20 -> context.getString(R.string.turn_card_now)
        meters < 1000 -> context.getString(R.string.turn_card_distance_m, meters)
        else -> context.getString(R.string.turn_card_distance_km, meters / 100 / 10f)
    }

    private fun ellipsize(text: String, maxWidth: Float, paint: Paint): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && paint.measureText(text.take(end) + "…") > maxWidth) end--
        return if (end == 0) "…" else text.take(end) + "…"
    }

    companion object {
        /** DiPlay's glyph table for the cluster icon codes. */
        fun glyphRes(icon: Int): Int = when (icon) {
            2 -> R.drawable.ic_turn_card_turn_left
            3 -> R.drawable.ic_turn_card_turn_right
            4 -> R.drawable.ic_turn_card_turn_slight_left
            5 -> R.drawable.ic_turn_card_turn_slight_right
            6 -> R.drawable.ic_turn_card_turn_sharp_left
            7 -> R.drawable.ic_turn_card_turn_sharp_right
            8 -> R.drawable.ic_turn_card_u_turn_left
            19 -> R.drawable.ic_turn_card_u_turn_right
            11, 12, 17, 18 -> R.drawable.ic_turn_card_roundabout_right
            15 -> R.drawable.ic_turn_card_sports_score
            else -> R.drawable.ic_turn_card_straight
        }
    }
}

/** Where the card sits on the cluster panel. Pure, so the geometry is testable. */
internal object ClusterTurnCardPlacement {
    // DiPlay's measured clear centre of a DiLink 5.0 cluster, in percent of the panel.
    private const val SAFE_LEFT = 35
    private const val SAFE_RIGHT = 36
    private const val ASPECT_UPPER = 2.64f
    private const val ASPECT_TOTAL = 1.70f

    data class Box(val left: Int, val top: Int, val width: Int, val height: Int)

    fun card(panelWidth: Int, panelHeight: Int, plan: ClusterMapConfig.Window?, positionMode: Int = 0): Box? {
        if (panelWidth <= 0 || panelHeight <= 0) return null
        val (width, centerX, centerY) = when {
            // Side window of Scenario/Simple: across the top of the narrow map.
            plan != null && !plan.fullMap ->
                Triple(plan.width * 80 / 100, plan.left + plan.width / 2, plan.top + plan.height * 22 / 100)
            // Full map:
            plan != null -> {
                val cx = when (positionMode) {
                    0 -> panelWidth * 18 / 100 // Sol
                    1 -> panelWidth * 50 / 100 // Ortala
                    else -> panelWidth * 35 / 100 // Sağ
                }
                Triple(panelWidth * 16 / 100, cx, plan.top + plan.height * 25 / 100)
            }
            // DiLink 5.0 full panel (BYD Seal U DM-i):
            else -> {
                val cx = when (positionMode) {
                    0 -> panelWidth * 22 / 100 // Sol (sürücü ekranı sembollerinden uzak!)
                    1 -> panelWidth * 50 / 100 // Ortala
                    else -> panelWidth * 76 / 100 // Sağ (DiPlay orijinali)
                }
                Triple(panelWidth * (100 - SAFE_LEFT - SAFE_RIGHT) / 100 * 55 / 100,
                    cx, panelHeight * 30 / 100)
            }
        }
        val cardWidth = width.coerceIn(150, panelWidth)
        val baseCardHeight = (cardWidth / ASPECT_UPPER).toInt().coerceAtLeast(58).coerceAtMost(panelHeight)
        val left = (centerX - cardWidth / 2).coerceIn(0, panelWidth - cardWidth)
        // Keep top edge pinned strictly to v11/v13 position so top indicators (clock, icons) are never approached
        val top = (centerY - baseCardHeight / 2).coerceIn(0, panelHeight - baseCardHeight)
        // Extend card downward to accommodate both the upper maneuver card and the lower ETA summary pill
        val cardHeight = (cardWidth / ASPECT_TOTAL).toInt().coerceAtLeast(baseCardHeight + 56).coerceAtMost(panelHeight - top)
        return Box(left, top, cardWidth, cardHeight)
    }
}
