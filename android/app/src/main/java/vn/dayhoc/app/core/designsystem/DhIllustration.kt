package vn.dayhoc.app.core.designsystem

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import vn.dayhoc.app.R
import vn.dayhoc.app.core.designsystem.TeachHubTokens.Dimens

/** Loại minh hoạ của Figma Display/Illustration; thêm loại mới bằng một glyph vector. */
enum class DhIllustrationType(@DrawableRes val glyph: Int, val glyphShift: Dp = 0.dp, val badge: Boolean = false) {
    Calendar(R.drawable.ic_calendar_blank),

    /** Glyph lệch 4 lên trái nhường chỗ cho badge dấu cộng ở góc dưới phải. */
    CalendarPlus(R.drawable.ic_calendar_blank, glyphShift = (-4).dp, badge = true),
}

/**
 * Minh hoạ 120×120 (Figma Display/Illustration): nền primaryContainer, đĩa primary 76 ở giữa,
 * glyph 40 màu onPrimary, [DhIllustrationType.CalendarPlus] thêm badge 30 nền onPrimary chứa dấu cộng 16 màu primary. Toàn bộ màu theo theme nên tự đổi Light/Dark.
 */
@Composable
fun DhIllustration(type: DhIllustrationType, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier.size(120.dp).clip(RoundedCornerShape(Dimens.sheetRadius)).background(colors.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(76.dp).clip(CircleShape).background(colors.primary))
        Icon(
            painterResource(type.glyph),
            contentDescription = null,
            Modifier.size(40.dp).offset(type.glyphShift, type.glyphShift),
            tint = colors.onPrimary,
        )
        if (type.badge) {
            Box(
                Modifier.align(Alignment.TopStart).offset(76.dp, 76.dp).size(30.dp).clip(CircleShape).background(colors.onPrimary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_plus), contentDescription = null, Modifier.size(16.dp), tint = colors.primary)
            }
        }
    }
}
