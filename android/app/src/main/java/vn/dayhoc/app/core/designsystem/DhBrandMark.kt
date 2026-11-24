package vn.dayhoc.app.core.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import vn.dayhoc.app.R
import vn.dayhoc.app.core.designsystem.TeachHubTokens.Dimens

/**
 * `Brand/Logo` · Mark (Figma 80:2412): ô lime bo góc, ký hiệu D + chấm nặng màu ink.
 * Màu cố định ở mọi mode (`brand/logo` = `primary`, `brand/logo-fg` = `onPrimary`).
 *
 * Dùng 160 ở splash (B00a/B00b), 48 ở T01, 64 ở màn cập nhật; bo góc tỉ lệ như Figma (20/48).
 */
@Composable
fun DhBrandMark(size: Dp = 48.dp, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 20 / 48))
            .background(colors.primary),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_brand_mark),
            contentDescription = stringResource(R.string.app_name),
            colorFilter = ColorFilter.tint(colors.onPrimary),
            modifier = Modifier.matchParentSize(),
        )
    }
}

/**
 * `Brand/Logo` (Figma B01 Header): Mark bo góc cạnh chữ "DayHoc" đậm. Dùng ở đầu màn [T01].
 */
@Composable
fun DhBrandLogo(markSize: Dp = 48.dp, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spacingContent),
    ) {
        DhBrandMark(size = markSize)
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
