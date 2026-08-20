package com.v2ray.ang.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.AppDivider
import com.v2ray.ang.ui.compose.colorFabActive
import com.v2ray.ang.ui.compose.colorFabInactiveDark
import com.v2ray.ang.ui.compose.colorFabInactiveLight

private val Gold       = Color(0xFFC9A84C)
private val GoldDim    = Color(0xFF8A6B2A)
private val GoldGlow   = Color(0xFFE8C96A)
private val ScanCancel = Color(0xFFCF4848)

@Composable
fun MainBottomBar(
    displayText: String,
    isRunning: Boolean,
    isDarkTheme: Boolean,
    isScanning: Boolean,
    scanDone: Int,
    scanTotal: Int,
    onAction: (MainAction) -> Unit
) {
    // انیمیشن pulse برای دکمه اسکن
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    val labelAlpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "labelAlpha"
    )

    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            AppDivider()
            if (isScanning && scanTotal > 0) {
                ScanProgressBar(done = scanDone, total = scanTotal)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clickable { onAction(MainAction.TestCurrentServer) }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { contentDescription = displayText }
                )
            }
        }

        // Scanner FAB + label
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 96.dp)
                .offset(y = (-84).dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // متن بالای دکمه
            Text(
                text = if (isScanning) "در حال اسکن..." else "بهترین IP را\nپیدا کن",
                fontSize = 10.sp,
                color = if (isScanning) GoldGlow else Gold,
                fontWeight = FontWeight.Medium,
                fontStyle = FontStyle.Italic,
                textAlign = TextAlign.Center,
                lineHeight = 13.sp,
                modifier = Modifier
                    .alpha(if (isScanning) pulseAlpha else labelAlpha)
                    .padding(bottom = 4.dp)
            )

            FloatingActionButton(
                onClick = {
                    if (isScanning) onAction(MainAction.CancelCFScan)
                    else onAction(MainAction.StartCFScan)
                },
                modifier = Modifier
                    .size(52.dp)
                    .alpha(if (isScanning) pulseAlpha else 1f),
                containerColor = if (isScanning) ScanCancel else Gold,
            ) {
                Icon(
                    painter = painterResource(
                        if (isScanning) R.drawable.ic_stop_24dp
                        else R.drawable.ic_scan_24dp
                    ),
                    contentDescription = if (isScanning) "Cancel" else "IP Scan",
                    tint = Color(0xFF0D0D0F),
                    modifier = Modifier.size(22.dp)
                )
            }

            Text(
                text = "🌐 اسکن IP",
                fontSize = 9.sp,
                color = GoldDim,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(top = 3.dp)
            )
        }

        // FAB اصلی
        FloatingActionButton(
            onClick = { onAction(MainAction.ToggleService) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 24.dp)
                .offset(y = (-52).dp)
                .navigationBarsPadding(),
            containerColor = if (isRunning) colorFabActive
            else if (isDarkTheme) colorFabInactiveDark
            else colorFabInactiveLight
        ) {
            Icon(
                painter = if (isRunning) painterResource(R.drawable.ic_stop_24dp)
                else painterResource(R.drawable.ic_play_24dp),
                contentDescription = stringResource(
                    if (isRunning) R.string.acc_stop else R.string.acc_start
                ),
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun ScanProgressBar(done: Int, total: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        repeat(total) { index ->
            val filled = index < done
            val dotColor by animateColorAsState(
                targetValue = if (filled) Gold else Color(0xFF252530),
                animationSpec = tween(300),
                label = "dot_$index"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(dotColor)
            )
        }
    }
}
