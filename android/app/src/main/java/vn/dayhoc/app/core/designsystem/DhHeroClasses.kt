package vn.dayhoc.app.core.designsystem

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vn.dayhoc.app.R
import vn.dayhoc.app.core.designsystem.TeachHubTokens.Dimens

// Khung gốc của hình minh họa trong Figma B01 (node 88:11172), đơn vị dp.
private val HeroWidth = 358.dp
private val HeroHeight = 232.dp

/** Nửa cạnh vòng tròn nhân vật, dùng để suy ra tâm vòng khi vẽ đường nối. */
private val RingRadius = 27.dp

/** Nét đứt của đường nối nhân vật (Figma: strokeDash 4,4). */
private val LinkDash = 4.dp

/** Bóng tag nổi (Figma: DS/Elevation/2 · 0 4 12 · 12%). */
private val TagElevation = 4.dp

/**
 * Hình minh họa "Thầy – Trò – Gia đình" của [T01] (Figma B01): ba nhân vật nối nhau trong khung
 * primaryContainer, kèm ba nhãn trạng thái. Mọi màu lấy từ token nên tự đổi theo Light/Dark.
 *
 * Bố cục giữ đúng tỉ lệ khung 358x232 rồi co giãn theo bề rộng thật của màn.
 */
@Composable
fun DhHeroClasses(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    // `success`/`warning` chưa nằm trong M3 ColorScheme nên lấy trực tiếp theo mode.
    val success = if (isSystemInDarkTheme()) TeachHubTokens.Dark.successColor else TeachHubTokens.Light.successColor
    val warning = if (isSystemInDarkTheme()) TeachHubTokens.Dark.warningColor else TeachHubTokens.Light.warningColor
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val scale = maxWidth / HeroWidth
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(HeroHeight * scale)
                .clip(RoundedCornerShape(Dimens.sheetRadius))
                .background(colors.primaryContainer),
        ) {
            Box(
                modifier = Modifier
                    .size(HeroWidth, HeroHeight)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0f)
                    },
            ) {
                // Hai vệt sáng nền, tràn ra ngoài khung nên bị cắt theo bo góc.
                Box(
                    Modifier
                        .offset(x = 236.dp, y = (-70).dp)
                        .size(180.dp)
                        .clip(CircleShape)
                        .background(colors.primary.copy(alpha = 0.55f)),
                )
                Box(
                    Modifier
                        .offset(x = (-46).dp, y = 150.dp)
                        .size(120.dp)
                        .clip(CircleShape)
                        .background(colors.primary.copy(alpha = 0.35f)),
                )

                // Đường nối tâm ba vòng nhân vật (Figma: Link GV–HS, GV–PH, HS–PH, nét đứt 4,4).
                Canvas(Modifier.size(HeroWidth, HeroHeight)) {
                    val stroke = 1.5.dp.toPx()
                    val ring = RingRadius.toPx()
                    val dash = PathEffect.dashPathEffect(floatArrayOf(LinkDash.toPx(), LinkDash.toPx()))
                    fun center(x: Int, y: Int) = Offset(x.dp.toPx() + ring, y.dp.toPx() + ring)
                    val c = colors.outline
                    drawLine(c, center(152, 51), center(69, 123), stroke, pathEffect = dash)
                    drawLine(c, center(152, 51), center(235, 123), stroke, pathEffect = dash)
                    drawLine(c, center(69, 123), center(235, 123), stroke, pathEffect = dash)
                }

                PersonNode(
                    initials = R.string.hero_avatar_teacher,
                    label = R.string.hero_role_teacher,
                    modifier = Modifier.offset(x = 152.dp, y = 51.dp),
                )
                PersonNode(
                    initials = R.string.hero_avatar_student,
                    label = R.string.hero_role_student,
                    modifier = Modifier.offset(x = 69.dp, y = 123.dp),
                )
                PersonNode(
                    initials = R.string.hero_avatar_family,
                    label = R.string.hero_role_family,
                    modifier = Modifier.offset(x = 235.dp, y = 123.dp),
                )

                HeroTag(
                    label = R.string.hero_tag_checked,
                    container = success,
                    icon = R.drawable.ic_check,
                    modifier = Modifier.offset(x = 14.dp, y = 16.dp),
                )
                HeroTag(
                    label = R.string.hero_tag_invoice,
                    container = colors.onSurface,
                    modifier = Modifier.offset(x = 228.dp, y = 16.dp),
                )
                HeroTag(
                    label = R.string.hero_tag_absent,
                    container = warning,
                    modifier = Modifier.offset(x = 126.dp, y = 200.dp),
                )
            }
        }
    }
}

@Composable
private fun PersonNode(@StringRes initials: Int, @StringRes label: Int, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier.width(54.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.spacingInline),
    ) {
        // Vòng trắng bao ngoài avatar (Figma: Ring 54, lót 3).
        Box(
            modifier = Modifier.size(54.dp).clip(CircleShape).background(colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceContainerHighest)
                    .border(1.5.dp, colors.outlineVariant, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(initials),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
            }
        }
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
        )
    }
}

@Composable
private fun HeroTag(
    @StringRes label: Int,
    container: Color,
    modifier: Modifier,
    @DrawableRes icon: Int? = null,
) {
    val colors = MaterialTheme.colorScheme
    // Icon và chữ của tag dùng chung một màu nội dung (Figma: chữ trắng trên tag đặc).
    val contentColor = colors.inverseOnSurface
    Row(
        modifier = modifier
            .height(24.dp)
            .shadow(elevation = TagElevation, shape = RoundedCornerShape(Dimens.tagRadius), clip = false)
            .clip(RoundedCornerShape(Dimens.tagRadius))
            .background(container)
            .padding(start = if (icon != null) Dimens.spacingInline else Dimens.spacingControl, end = Dimens.spacingControl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spacingInline),
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(12.dp),
            )
        }
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
        )
    }
}
