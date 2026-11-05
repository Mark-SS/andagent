package com.dc.andagent.widget.model

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

data class DataSnapshot(
    val today: LocalDate,
    val battery: Int?,
    val sources: Map<String, Map<String, String>> = emptyMap()
) {
    fun value(source: String?, field: String?): String? {
        if (source == null || field == null) return null
        return sources[source]?.get(field)
    }
}

object Bindings {
    fun text(node: WidgetNode, data: DataSnapshot): String = node.text + when (node.binding) {
        Binding.LITERAL -> ""
        Binding.DATE -> data.today.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE))
        Binding.BATTERY -> data.battery?.let { "$it%" } ?: "电量未知"
        Binding.COUNTDOWN -> {
            val days = ChronoUnit.DAYS.between(data.today, LocalDate.parse(node.targetDate))
            when { days > 0 -> "还有 $days 天"; days == 0L -> "就是今天"; else -> "已过去 ${-days} 天" }
        }
        Binding.DATA -> data.value(node.source, node.field) ?: node.fallback ?: ""
    }

    fun progress(node: WidgetNode, data: DataSnapshot): Int = when (node.binding) {
        Binding.BATTERY -> data.battery ?: 0
        Binding.DATA -> data.value(node.source, node.field)?.toDoubleOrNull()?.roundToInt()?.coerceIn(0, 100) ?: node.value
        else -> node.value
    }
}
