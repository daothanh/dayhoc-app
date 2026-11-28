package vn.dayhoc.app.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** C16 Avatar chữ tắt (Figma Display/Avatar): nền Fill, viền 1.5, chữ SemiBold. */
@Composable
fun DhAvatar(name: String, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.surfaceContainerHighest)
            .border(1.5.dp, colors.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(initialsOf(name), style = TeachHubText.bodyBold, color = colors.onSurface, maxLines = 1)
    }
}

private val WHITESPACE = Regex("\\s+")

/** Chữ cái đầu của từ đầu và từ cuối ("Nguyễn An" → "NA", "Đỗ Hà" → "ĐH"); một từ thì lấy chữ đầu; rỗng thì "?". */
fun initialsOf(name: String): String {
    val words = name.trim().split(WHITESPACE).filter { it.isNotEmpty() }
    if (words.isEmpty()) return "?"
    val first = words.first().first()
    val last = if (words.size > 1) words.last().first().toString() else ""
    return (first.toString() + last).uppercase()
}
