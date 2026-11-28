package vn.dayhoc.app.core.designsystem

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.dp
import vn.dayhoc.app.R
import vn.dayhoc.app.core.designsystem.TeachHubTokens.Dimens

/** Loại minh hoạ của Figma Display/Illustration; thêm loại mới bằng một glyph vector. */
enum class DhIllustrationType(@DrawableRes val glyph: Int) {
    Calendar(R.drawable.ic_calendar_blank),
}

/**
 * Minh hoạ 120×120 (Figma Display/Illustration): nền primaryContainer, đĩa primary 76 ở giữa,
 * glyph 40 màu onPrimary. Toàn bộ màu theo theme nên tự đổi Light/Dark.
 */
@Composable
fun DhIllustration(type: DhIllustrationType, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier.size(120.dp).clip(RoundedCornerShape(Dimens.sheetRadius)).background(colors.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(76.dp).clip(CircleShape).background(colors.primary), contentAlignment = Alignment.Center) {
            Icon(painterResource(type.glyph), contentDescription = null, Modifier.size(40.dp), tint = colors.onPrimary)
        }
    }
}
