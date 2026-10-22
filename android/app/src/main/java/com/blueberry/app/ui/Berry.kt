package com.blueberry.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.blueberry.app.R

/** Ягоды черники (та же картинка, что в иконке приложения). */
@Composable
fun Berry(size: Dp, modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.ic_berry), contentDescription = null, modifier = modifier.size(size))
}

/** Круглый аватар с ягодой. */
@Composable
fun BerryAvatar(size: Dp = 40.dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) { Berry(size * 0.72f) }
}

/** Заголовок в шапке: ягода и название. */
@Composable
fun BrandTitle(text: String) {
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
        Berry(30.dp)
        androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
        androidx.compose.material3.Text(text, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    }
}
