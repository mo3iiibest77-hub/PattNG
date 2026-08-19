package com.v2ray.ang.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.AppDivider
import com.v2ray.ang.ui.compose.colorFabActive
import com.v2ray.ang.ui.compose.colorFabInactiveDark
import com.v2ray.ang.ui.compose.colorFabInactiveLight

private val colorScanBlue = Color(0xFF1E88E5)
private val colorScanBlueDim = Color(0xFF1565C0)
private val colorScanBg = Color(0xFF1A1A1A)
private val colorScanGreen = Color(0xFF00C853)

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
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            AppDivider()

            // اگه داره اسکن می‌کنه، progress bar نشون بده
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
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { contentDescription = displayText }
                )
            }
        }

        // Scanner FAB — سمت چپ FAB اصلی
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 96.dp)
                .offset(y = (-72).dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (isScanning) "$scanDone/$scanTotal" else "0/30",
                fontSize = 14.sp,
                color = if (isScanning) colorScanBlue else colorScanBlueDim,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 2.dp)
            )
            FloatingActionButton(
                onClick = {
                    if (isScanning) onAction(MainAction.CancelCFScan)
                    else onAction(MainAction.StartCFScan)
                },
                modifier = Modifier.size(52.dp),
                containerColor = if (isScanning) colorScanGreen else colorScanBlue
            ) {
                Icon(
                    painter = painterResource(
                        if (isScanning) R.drawable.ic_stop_24dp
                        else R.drawable.ic_scan_24dp
                    ),
                    contentDescription = if (isScanning) "Cancel scan" else "Scan IPs",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // FAB اصلی (play/stop)
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
    val fraction = if (total > 0) done.toFloat() / total else 0f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        repeat(total) { index ->
            val filled = index < done
            val dotColor by animateColorAsState(
                targetValue = if (filled) Color(0xFF2196F3) else Color(0xFF2A2A2A),
                animationSpec = tween(300),
                label = "dot_$index"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(dotColor)
            )
        }
    }
}
