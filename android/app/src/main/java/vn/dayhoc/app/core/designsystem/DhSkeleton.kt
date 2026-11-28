package vn.dayhoc.app.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import vn.dayhoc.app.core.designsystem.TeachHubTokens.Dimens

/** C18 Skeleton: thanh chữ, thẻ (3 dòng + nút), dòng danh sách (avatar + 2 dòng + giá trị). Màu Fill. */
@Composable
fun DhSkeletonBar(width: Dp?, height: Dp, modifier: Modifier = Modifier, radius: Dp = 3.dp) {
    Column(
        modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .height(height)
            .clip(RoundedCornerShape(radius))
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {}
}

@Composable
fun DhSkeletonText() {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spacingControl)) {
        DhSkeletonBar(null, 12.dp)
        DhSkeletonBar(null, 12.dp)
        DhSkeletonBar(220.dp, 12.dp)
    }
}

@Composable
fun DhSkeletonCard() {
    DhCard(style = DhCardStyle.Default) {
        DhSkeletonBar(90.dp, 9.dp)
        DhSkeletonBar(180.dp, 12.dp)
        DhSkeletonBar(130.dp, 9.dp)
        DhSkeletonBar(null, 40.dp, radius = 10.dp)
    }
}

@Composable
fun DhSkeletonRow() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Dimens.spacingControl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spacingContent),
    ) {
        Column(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerLow)) {}
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.spacingControl)) {
            DhSkeletonBar(160.dp, 12.dp)
            DhSkeletonBar(110.dp, 9.dp)
        }
        DhSkeletonBar(64.dp, 12.dp)
    }
}
