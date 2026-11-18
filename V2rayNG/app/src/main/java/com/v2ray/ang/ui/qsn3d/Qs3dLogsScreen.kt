package com.v2ray.ang.ui.qsn3d

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.senpai.AppLogEntry
import com.v2ray.ang.senpai.AppLogLevel

enum class LogFilterBucket {
    ALL,
    CORE,
    WARNINGS_ERRORS,
}

/**
 * In-App Diagnostics & Core Log Viewer.
 * Displays strictly redacted SmartConnect, License, Failover, and Xray core events.
 */
@Composable
fun Qs3dLogsScreen(
    logs: List<AppLogEntry>,
    searchQuery: String,
    filterBucket: LogFilterBucket,
    onSearchChange: (String) -> Unit,
    onFilterBucketChange: (LogFilterBucket) -> Unit,
    onRefreshSystemLogs: () -> Unit,
    onCopyAllLogs: () -> Unit,
    onClearLogs: () -> Unit,
    onBack: () -> Unit,
) {
    val visibleLogs = logs.filter { entry ->
        val matchesBucket = when (filterBucket) {
            LogFilterBucket.ALL -> true
            LogFilterBucket.CORE -> entry.level == AppLogLevel.CORE || entry.tag.contains("Xray", ignoreCase = true)
            LogFilterBucket.WARNINGS_ERRORS -> entry.level == AppLogLevel.WARN || entry.level == AppLogLevel.ERROR
        }
        val q = searchQuery.trim()
        val matchesQuery = q.isEmpty() ||
            entry.tag.contains(q, ignoreCase = true) ||
            entry.message.contains(q, ignoreCase = true)
        matchesBucket && matchesQuery
    }.asReversed()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.novapulse_back_btn), color = QsColors.ElectricCyan, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    text = stringResource(R.string.novapulse_logs_title),
                    color = QsColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(48.dp))
            }
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = QsColors.SurfaceElevated,
                border = BorderStroke(1.dp, QsColors.BorderSubtle),
            ) {
                Text(
                    text = stringResource(R.string.novapulse_logs_privacy_note),
                    color = QsColors.TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                )
            }
        }

        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = {
                    Text(
                        text = stringResource(R.string.novapulse_logs_search_hint),
                        color = QsColors.TextMuted,
                        fontSize = 13.sp,
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = QsColors.ElectricCyan,
                    unfocusedBorderColor = QsColors.BorderSubtle,
                    focusedContainerColor = QsColors.SurfaceInset,
                    unfocusedContainerColor = QsColors.SurfaceInset,
                    focusedTextColor = QsColors.TextPrimary,
                    unfocusedTextColor = QsColors.TextPrimary,
                ),
                shape = RoundedCornerShape(12.dp),
            )
        }

        // Segmented Filter Bar
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(QsColors.SurfaceInset)
                    .border(1.dp, QsColors.BorderSubtle, RoundedCornerShape(12.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LogSegmentTab(
                    label = stringResource(R.string.novapulse_logs_filter_all),
                    selected = filterBucket == LogFilterBucket.ALL,
                    onClick = { onFilterBucketChange(LogFilterBucket.ALL) },
                    modifier = Modifier.weight(1f),
                )
                LogSegmentTab(
                    label = stringResource(R.string.novapulse_logs_filter_core),
                    selected = filterBucket == LogFilterBucket.CORE,
                    onClick = { onFilterBucketChange(LogFilterBucket.CORE) },
                    modifier = Modifier.weight(1f),
                )
                LogSegmentTab(
                    label = stringResource(R.string.novapulse_logs_filter_warn),
                    selected = filterBucket == LogFilterBucket.WARNINGS_ERRORS,
                    onClick = { onFilterBucketChange(LogFilterBucket.WARNINGS_ERRORS) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Action Controls
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onRefreshSystemLogs,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, QsColors.BorderStrong),
                ) {
                    Text(stringResource(R.string.novapulse_logs_refresh_btn), color = QsColors.ElectricCyan, fontSize = 11.sp, maxLines = 1)
                }
                OutlinedButton(
                    onClick = onCopyAllLogs,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, QsColors.BorderStrong),
                ) {
                    Text(stringResource(R.string.novapulse_logs_copy_btn), color = QsColors.TextPrimary, fontSize = 11.sp, maxLines = 1)
                }
                OutlinedButton(
                    onClick = onClearLogs,
                    modifier = Modifier.weight(0.8f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, QsColors.DangerCrimson.copy(alpha = 0.45f)),
                ) {
                    Text(stringResource(R.string.novapulse_logs_clear_btn), color = QsColors.DangerCrimson, fontSize = 11.sp, maxLines = 1)
                }
            }
        }

        if (visibleLogs.isEmpty()) {
            item {
                Glass3dCard(surfaceColor = QsColors.SurfaceElevated) {
                    Text(
                        text = stringResource(R.string.novapulse_logs_empty),
                        color = QsColors.TextSecondary,
                        fontSize = 13.sp,
                    )
                }
            }
        } else {
            items(visibleLogs, key = { it.id }) { entry ->
                val levelColor = when (entry.level) {
                    AppLogLevel.ERROR -> QsColors.DangerCrimson
                    AppLogLevel.WARN -> QsColors.SolarGold
                    AppLogLevel.CORE -> QsColors.ConnectedEmerald
                    AppLogLevel.INFO -> QsColors.ElectricCyan
                }

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = QsColors.SurfaceCard,
                        border = BorderStroke(1.dp, QsColors.BorderSubtle),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(levelColor.copy(alpha = 0.16f))
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            text = entry.level.shortLabel,
                                            style = QsTextStyles.MonoCode,
                                            color = levelColor,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                    Text(
                                        text = entry.tag,
                                        style = QsTextStyles.MonoCode,
                                        color = QsColors.TextSecondary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    text = entry.timeText,
                                    style = QsTextStyles.MonoCode,
                                    color = QsColors.TextMuted,
                                    fontSize = 10.sp,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = entry.message,
                                style = QsTextStyles.MonoCode,
                                color = QsColors.TextPrimary,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogSegmentTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) QsColors.SurfaceElevated else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) QsColors.ElectricCyan else QsColors.TextSecondary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}
