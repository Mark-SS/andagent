package io.nous.andagent.widget.render

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.SizeF
import android.util.TypedValue
import io.nous.andagent.widget.model.*
import kotlin.math.max
import kotlin.math.roundToInt

/** Computes a layout for the usable size reported by the launcher, in dp. */
object WidgetSizing {
    data class Fitted(val spec: WidgetSpec, val footer: Boolean, val condensed: Boolean)

    @Suppress("DEPRECATION")
    fun sizes(options: Bundle): List<SizeF> {
        val exact = (if (android.os.Build.VERSION.SDK_INT >= 31) options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES) else null)
            ?.filter { it.width.isFinite() && it.height.isFinite() && it.width > 0 && it.height > 0 }?.distinct()?.take(16)
        if (!exact.isNullOrEmpty()) return exact
        val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300).coerceAtLeast(40)
        val maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minW).coerceAtLeast(40)
        val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180).coerceAtLeast(40)
        val maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minH).coerceAtLeast(40)
        return listOf(SizeF(minW.toFloat(), maxH.toFloat()), SizeF(maxW.toFloat(), minH.toFloat())).distinct()
    }

    fun fit(context: Context, original: WidgetSpec, width: Float, height: Float, data: DataSnapshot): Fitted {
        val density = context.resources.displayMetrics.density
        val footer = height >= 180
        val padding = minOf(original.padding, if (height < 140) 8 else 12)
        val availableWidth = (width - padding * 2).coerceAtLeast(16f)
        val availableHeight = (height - padding * 2 - if (footer) 48 else 0).coerceAtLeast(1f)
        fun measured(n: WidgetNode, w: Float): Float {
            val inner = (w - n.padding * 2).coerceAtLeast(1f)
            val content = when (n.type) {
                NodeType.COLUMN -> n.children.sumOf { measured(it, inner).toDouble() }.toFloat()
                NodeType.ROW -> {
                    val fixed = n.children.filter { it.type == NodeType.SPACER }.sumOf { it.size }
                    val share = ((inner - fixed) / max(1, n.children.count { it.type != NodeType.SPACER })).coerceAtLeast(1f)
                    n.children.maxOfOrNull { measured(it, if (it.type == NodeType.SPACER) it.size.toFloat() else share) } ?: 0f
                }
                NodeType.SPACER -> n.size.toFloat()
                NodeType.PROGRESS -> 8f
                NodeType.TEXT -> {
                    val value = Bindings.text(n, data)
                    val paint = TextPaint().apply { textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, n.textSize.toFloat(), context.resources.displayMetrics) }
                    val layout = StaticLayout.Builder.obtain(value, 0, value.length, paint, max(1, (inner * density).roundToInt()))
                        .setUseLineSpacingFromFallbacks(true).setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setMaxLines(n.maxLines)
                        .setEllipsize(TextUtils.TruncateAt.END).build()
                    layout.height / density
                }
            }
            return content + n.padding * 2
        }
        fun scale(n: WidgetNode, factor: Float): WidgetNode = n.copy(
            textSize = max(11, (n.textSize * factor).roundToInt()),
            size = (n.size * factor).roundToInt(), padding = (n.padding * factor).roundToInt(),
            children = n.children.map { scale(it, factor) }
        )
        for (step in 0..10) {
            val root = scale(original.root, 1f - step * .05f)
            if (measured(root, availableWidth) <= availableHeight) {
                return Fitted(original.copy(padding = padding, root = root), footer, step > 0)
            }
        }
        // A tiny card cannot display every field. Keep the main reading and add context only if it fits.
        fun flatten(n: WidgetNode): List<WidgetNode> = if (n.type == NodeType.TEXT) listOf(n) else n.children.flatMap(::flatten)
        val texts = flatten(original.root)
        val primary = texts.firstOrNull { it.binding == Binding.BATTERY || it.binding == Binding.COUNTDOWN }
            ?: texts.maxByOrNull { it.textSize }
            ?: WidgetNode(NodeType.TEXT, text = original.name)
        val reduced = primary.copy(textSize = minOf(primary.textSize, 24), maxLines = 1, padding = 0)
        var root = WidgetNode(NodeType.COLUMN, children = listOf(reduced))
        val title = texts.firstOrNull { it !== primary && it.binding == Binding.LITERAL }
        if (title != null) {
            val withTitle = root.copy(children = listOf(title.copy(textSize = 11, maxLines = 1, padding = 0), reduced))
            if (measured(withTitle, availableWidth) <= availableHeight) root = withTitle
        }
        for (size in reduced.textSize downTo 11) {
            val candidate = root.copy(children = root.children.map { if (it == reduced) it.copy(textSize = size) else it })
            if (measured(candidate, availableWidth) <= availableHeight) return Fitted(original.copy(padding = padding, root = candidate), false, true)
        }
        return Fitted(original.copy(padding = 0, root = WidgetNode(NodeType.COLUMN, children = listOf(reduced.copy(textSize = 11)))), false, true)
    }
}
