package vn.dayhoc.app.core.designsystem

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import vn.dayhoc.app.core.designsystem.TeachHubTokens.Dimens

/** Trạng thái một ô mã OTP (Figma `Inputs/OTP Cell`, node 24:2343). */
enum class DhOtpCellState { Empty, Focus, Filled, Error, Locked }

// Figma `Inputs/OTP Cell`: ô 44×52, bo 12, viền 1.5px (focus/lỗi 2px), con trỏ 2×24.
// Cỡ ô chưa có trong token nên khai tại đây, cùng cách DhPrimaryButton khai 48.dp.
private val OtpCellWidth = 44.dp
private val OtpCellHeight = 52.dp
private val OtpCellBorder = 1.5.dp
private val OtpCaretWidth = 2.dp
private val OtpCaretHeight = 24.dp

/**
 * Ô mã OTP (Figma `Inputs/OTP Cell` 24:2343): 44×52, bo 12, ký tự căn giữa.
 *
 * Màu lấy từ token: Empty/Filled viền `outline`, Focus viền `secondary` 2px kèm con trỏ nhấp nháy,
 * Error viền `error` 2px, Locked nền `surfaceContainerLow` viền `outlineVariant` chữ `onSurfaceVariant`.
 * Figma để Locked nền `#F4F4F4`, chưa có token trùng nên tạm dùng `surfaceContainerLow` (#F0F0EB).
 */
@Composable
fun DhOtpCell(
    char: Char?,
    state: DhOtpCellState,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Dimens.fieldRadius)
    val borderColor = when (state) {
        DhOtpCellState.Focus -> colors.secondary
        DhOtpCellState.Error -> colors.error
        DhOtpCellState.Locked -> colors.outlineVariant
        DhOtpCellState.Empty, DhOtpCellState.Filled -> colors.outline
    }
    val borderWidth = if (state == DhOtpCellState.Focus || state == DhOtpCellState.Error) {
        Dimens.focusRingWidth
    } else {
        OtpCellBorder
    }
    val background = if (state == DhOtpCellState.Locked) colors.surfaceContainerLow else colors.surface
    val textColor = when (state) {
        DhOtpCellState.Error -> colors.error
        DhOtpCellState.Locked -> colors.onSurfaceVariant
        else -> colors.onSurface
    }
    // Figma DayHoc/H1: 20/28 SemiBold, giãn chữ -0.015em. M3 không có cỡ 20 nên đặt tường minh.
    val digitStyle = MaterialTheme.typography.titleLarge.copy(
        fontSize = 20.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.015).em,
    )

    Box(
        modifier = modifier
            .size(width = OtpCellWidth, height = OtpCellHeight)
            .clip(shape)
            .background(background)
            .border(borderWidth, borderColor, shape),
        contentAlignment = Alignment.Center,
    ) {
        val text = char?.toString().orEmpty()
        if (text.isNotEmpty()) {
            Text(text = text, style = digitStyle, color = textColor)
        } else if (state == DhOtpCellState.Focus) {
            BlinkingCaret(color = colors.onSurface)
        }
    }
}

/** Con trỏ 2×24 của ô đang focus (Figma `Caret`), nhấp nháy để biết ô đang nhận ký tự. */
@Composable
private fun BlinkingCaret(color: Color) {
    val transition = rememberInfiniteTransition(label = "otpCaret")
    val caretAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 500), repeatMode = RepeatMode.Reverse),
        label = "otpCaretAlpha",
    )
    Box(
        modifier = Modifier
            .alpha(caretAlpha)
            .size(width = OtpCaretWidth, height = OtpCaretHeight)
            .background(color),
    )
}
