package com.v2ray.ang.ui.qsn3d

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import com.v2ray.ang.util.Utils
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.ApkAssetOption
import com.v2ray.ang.dto.CheckUpdateResult
import java.util.Locale

private data class NoticePalette(
    val primaryAccent: Color,
    val secondaryAccent: Color,
    val badgeBg: Color,
    val badgeBorder: Color,
    val cardTopGlow: Color,
    val cardBorderStart: Color,
    val cardBorderEnd: Color,
)

private fun paletteFor(severity: NoticeSeverity): NoticePalette = when (severity) {
    NoticeSeverity.MANDATORY_SECURITY -> NoticePalette(
        primaryAccent = QsColors.SolarGold,
        secondaryAccent = QsColors.DangerCrimson,
        badgeBg = Color(0x33EF4444),
        badgeBorder = Color(0x99F59E0B),
        cardTopGlow = Color(0x3DF59E0B),
        cardBorderStart = Color(0xCCF59E0B),
        cardBorderEnd = Color(0x88EF4444),
    )

    NoticeSeverity.SECURITY_NOTICE -> NoticePalette(
        primaryAccent = QsColors.SolarGold,
        secondaryAccent = QsColors.ElectricCyan,
        badgeBg = Color(0x2BF59E0B),
        badgeBorder = Color(0x8CF59E0B),
        cardTopGlow = Color(0x33F59E0B),
        cardBorderStart = Color(0xBBF59E0B),
        cardBorderEnd = Color(0x7A38BDF8),
    )

    NoticeSeverity.UPDATE_INFO -> NoticePalette(
        primaryAccent = QsColors.AuroraMint,
        secondaryAccent = QsColors.ElectricCyan,
        badgeBg = Color(0x2B2DD4BF),
        badgeBorder = Color(0x8C2DD4BF),
        cardTopGlow = Color(0x382DD4BF),
        cardBorderStart = Color(0xC42DD4BF),
        cardBorderEnd = Color(0x9438BDF8),
    )

    NoticeSeverity.INFO -> NoticePalette(
        primaryAccent = QsColors.ElectricCyan,
        secondaryAccent = QsColors.PlasmaViolet,
        badgeBg = Color(0x2B38BDF8),
        badgeBorder = Color(0x8C38BDF8),
        cardTopGlow = Color(0x3338BDF8),
        cardBorderStart = Color(0xBA38BDF8),
        cardBorderEnd = Color(0x7AA855F7),
    )
}

/**
 * Full-screen dialog wrapper for NovaPulse app updates inside QuietStormActivity.
 */
@Composable
fun NovaPulseUpdateDialog(
    state: Qs3dUiState,
    onDownloadOption: (ApkAssetOption) -> Unit,
    onInstallReady: () -> Unit,
    onRecheck: () -> Unit,
    onDismiss: () -> Unit,
) {
    val result = state.availableUpdate ?: return
    NovaPulseUpdateDialog(
        result = result,
        currentVersionName = BuildConfig.VERSION_NAME,
        isCheckingUpdate = state.isCheckingUpdate,
        downloadProgress = state.updateDownloadProgress,
        readyUpdateApkPath = state.readyUpdateApkPath,
        updateNeedsInstallPerm = state.updateNeedsInstallPerm,
        onDownloadOption = onDownloadOption,
        onInstallReady = onInstallReady,
        onRecheck = onRecheck,
        onDismiss = onDismiss,
    )
}

/**
 * Shared update dialog overload usable by both QuietStormActivity and CheckUpdateActivity.
 */
@Composable
fun NovaPulseUpdateDialog(
    result: CheckUpdateResult,
    currentVersionName: String,
    isCheckingUpdate: Boolean,
    downloadProgress: Int?,
    readyUpdateApkPath: String?,
    updateNeedsInstallPerm: Boolean,
    onDownloadOption: (ApkAssetOption) -> Unit,
    onInstallReady: () -> Unit,
    onRecheck: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xB802050E))
                .padding(horizontal = 16.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            NovaPulseUpdateDialogCard(
                result = result,
                currentVersionName = currentVersionName,
                isCheckingUpdate = isCheckingUpdate,
                downloadProgress = downloadProgress,
                readyUpdateApkPath = readyUpdateApkPath,
                updateNeedsInstallPerm = updateNeedsInstallPerm,
                onDownloadOption = onDownloadOption,
                onInstallReady = onInstallReady,
                onRecheck = onRecheck,
                onDismiss = onDismiss,
            )
        }
    }
}

/**
 * Architectural 3D Glassmorphic Update Notice Card (standalone Composable so it renders identically
 * inside Dialogs and headless UI/screenshot verification).
 */
@Composable
fun NovaPulseUpdateDialogCard(
    result: CheckUpdateResult,
    currentVersionName: String,
    isCheckingUpdate: Boolean = false,
    downloadProgress: Int? = null,
    readyUpdateApkPath: String? = null,
    updateNeedsInstallPerm: Boolean = false,
    onDownloadOption: (ApkAssetOption) -> Unit,
    onInstallReady: () -> Unit = {},
    onRecheck: () -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    val notes = result.releaseNotes.orEmpty()
    val isPre244Current = remember(currentVersionName) {
        isPreCutoffVersion(currentVersionName, majorCutoff = 2, minorCutoff = 4, patchCutoff = 4)
    }
    val parsed = remember(notes, isPre244Current) {
        NovaPulseNoticeParser.parse(notes, isMandatoryHint = isPre244Current)
    }
    val palette = remember(parsed.severity) { paletteFor(parsed.severity) }

    val assets = result.availableAssets
    val deviceMatchOption = assets.firstOrNull { it.isDeviceMatch }
        ?: assets.firstOrNull { it.abi.equals(result.recommendedAbi, ignoreCase = true) }
        ?: assets.firstOrNull()
    val universalOption = assets.firstOrNull { it.isUniversal && it.fileName != deviceMatchOption?.fileName }
    val versionTag = result.latestVersion?.takeIf { it.isNotBlank() } ?: ""
    val layoutDir = if (parsed.isRtl || NovaPulseNoticeParser.isMostlyRtl(stringResource(R.string.novapulse_update_card_title))) {
        LayoutDirection.Rtl
    } else {
        LocalLayoutDirection.current
    }

    val badgeLabel = when (parsed.severity) {
        NoticeSeverity.MANDATORY_SECURITY -> stringResource(R.string.novapulse_notice_badge_mandatory)
        NoticeSeverity.SECURITY_NOTICE -> stringResource(R.string.novapulse_notice_badge_security)
        NoticeSeverity.UPDATE_INFO -> stringResource(R.string.novapulse_notice_badge_update)
        NoticeSeverity.INFO -> stringResource(R.string.novapulse_notice_badge_info)
    }

    NovaPulseNoticeShell(
        palette = palette,
        severity = parsed.severity,
        badgeLabel = badgeLabel,
        title = stringResource(R.string.novapulse_update_available_title_fmt, "v$versionTag"),
        subtitle = stringResource(R.string.novapulse_update_fast_gateway_note),
        currentVersionLabel = "v$currentVersionName",
        targetVersionLabel = versionTag.takeIf { it.isNotBlank() }?.let { "v$it" },
        abiBadgeLabel = result.recommendedAbi,
        layoutDirection = layoutDir,
    ) {
        SelectionContainer(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                parsed.sections.forEach { section ->
                    NovaPulseSectionCard(section = section, palette = palette)
                }

                if (parsed.contactActions.isNotEmpty()) {
                    NovaPulseQuickActionsBar(
                        actions = parsed.contactActions,
                        title = stringResource(R.string.novapulse_notice_official_support_title),
                    )
                }
            }
        }

        if (downloadProgress != null) {
            Spacer(Modifier.height(12.dp))
            NovaPulseDownloadProgressCard(
                progress = downloadProgress,
                palette = palette,
            )
        }

        if (readyUpdateApkPath != null && updateNeedsInstallPerm) {
            Spacer(Modifier.height(10.dp))
            NovaPulseInlineAlertStrip(
                text = stringResource(R.string.novapulse_update_perm_needed),
                accent = QsColors.SolarGold,
            )
        }

        Spacer(Modifier.height(14.dp))

        if (readyUpdateApkPath != null) {
            NovaPulseGradientPrimaryButton(
                text = stringResource(R.string.novapulse_update_install_now_btn),
                palette = palette,
                onClick = onInstallReady,
            )
            Spacer(Modifier.height(8.dp))
        } else {
            if (deviceMatchOption != null) {
                val sizeTag = formatApkSizeMb(deviceMatchOption.sizeBytes)
                val btnText = buildString {
                    append(stringResource(R.string.novapulse_update_device_abi_btn_fmt, deviceMatchOption.abi))
                    if (sizeTag.isNotEmpty()) append(" • $sizeTag")
                }
                NovaPulseGradientPrimaryButton(
                    text = btnText,
                    enabled = downloadProgress == null,
                    palette = palette,
                    onClick = { onDownloadOption(deviceMatchOption) },
                )
            }

            if (universalOption != null) {
                Spacer(Modifier.height(8.dp))
                val uSize = formatApkSizeMb(universalOption.sizeBytes)
                OutlinedButton(
                    onClick = { onDownloadOption(universalOption) },
                    enabled = downloadProgress == null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, QsColors.ElectricCyan.copy(alpha = 0.50f)),
                    contentPadding = PaddingValues(vertical = 11.dp, horizontal = 12.dp),
                ) {
                    Text(
                        text = buildString {
                            append(stringResource(R.string.novapulse_update_universal_btn))
                            if (uSize.isNotEmpty()) append(" • $uSize")
                        },
                        color = QsColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onRecheck,
                enabled = !isCheckingUpdate && downloadProgress == null,
            ) {
                if (isCheckingUpdate) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = QsColors.ElectricCyan,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = stringResource(R.string.novapulse_update_check_btn),
                    color = QsColors.ElectricCyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.novapulse_update_later_btn),
                    color = QsColors.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * Dedicated Security / License / Legacy Cutoff Notification Dialog using the exact same
 * multi-layer 3D architectural surface and clickable Telegram/Web links.
 */
@Composable
fun NovaPulseSecurityNoticeDialog(
    message: String,
    title: String? = null,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xB802050E))
                .padding(horizontal = 16.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            NovaPulseSecurityNoticeCard(
                message = message,
                title = title,
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
fun NovaPulseSecurityNoticeCard(
    message: String,
    title: String? = null,
    onDismiss: () -> Unit = {},
) {
    val parsed = remember(message) { NovaPulseNoticeParser.parse(message) }
    val palette = remember(parsed.severity) { paletteFor(parsed.severity) }
    val effectiveTitle = title?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.novapulse_notice_security_title)
    val badgeLabel = when (parsed.severity) {
        NoticeSeverity.MANDATORY_SECURITY -> stringResource(R.string.novapulse_notice_badge_mandatory)
        NoticeSeverity.SECURITY_NOTICE -> stringResource(R.string.novapulse_notice_badge_security)
        NoticeSeverity.UPDATE_INFO -> stringResource(R.string.novapulse_notice_badge_update)
        NoticeSeverity.INFO -> stringResource(R.string.novapulse_notice_badge_info)
    }
    val dir = if (parsed.isRtl || NovaPulseNoticeParser.isMostlyRtl(effectiveTitle)) {
        LayoutDirection.Rtl
    } else {
        LocalLayoutDirection.current
    }

    NovaPulseNoticeShell(
        palette = palette,
        severity = parsed.severity,
        badgeLabel = badgeLabel,
        title = effectiveTitle,
        subtitle = null,
        currentVersionLabel = null,
        targetVersionLabel = null,
        abiBadgeLabel = null,
        layoutDirection = dir,
    ) {
        SelectionContainer(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                parsed.sections.forEach { section ->
                    NovaPulseSectionCard(section = section, palette = palette)
                }

                if (parsed.contactActions.isNotEmpty()) {
                    NovaPulseQuickActionsBar(
                        actions = parsed.contactActions,
                        title = stringResource(R.string.novapulse_notice_official_support_title),
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        NovaPulseGradientPrimaryButton(
            text = stringResource(R.string.novapulse_notice_ack_btn),
            palette = palette,
            onClick = onDismiss,
        )
    }
}

/**
 * Redesigned glassmorphic confirmation dialog (replacing plain Material AlertDialog prompts).
 */
@Composable
fun NovaPulseConfirmNoticeDialog(
    title: String,
    body: String,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val parsed = remember(body) { NovaPulseNoticeParser.parse(body) }
    val palette = remember { paletteFor(NoticeSeverity.INFO) }
    val dir = if (parsed.isRtl || NovaPulseNoticeParser.isMostlyRtl(title)) {
        LayoutDirection.Rtl
    } else {
        LocalLayoutDirection.current
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xB802050E))
                .padding(horizontal = 16.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            NovaPulseNoticeShell(
                palette = palette,
                severity = NoticeSeverity.INFO,
                badgeLabel = stringResource(R.string.novapulse_notice_badge_info),
                title = title,
                subtitle = null,
                currentVersionLabel = null,
                targetVersionLabel = null,
                abiBadgeLabel = null,
                layoutDirection = dir,
            ) {
                SelectionContainer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        parsed.sections.forEach { section ->
                            NovaPulseSectionCard(section = section, palette = palette)
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                NovaPulseGradientPrimaryButton(
                    text = confirmText,
                    palette = palette,
                    onClick = onConfirm,
                )
                Spacer(Modifier.height(6.dp))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = dismissText,
                        color = QsColors.TextSecondary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

/**
 * Floating glassmorphic SnackbarHost used across NovaPulse screens.
 */
@Composable
fun Qs3dSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier,
    ) { data: SnackbarData ->
        val msg = data.visuals.message
        val isRtl = remember(msg) { NovaPulseNoticeParser.isMostlyRtl(msg) }
        val isErrorLike = remember(msg) {
            msg.contains("خطا") || msg.contains("ناموفق") || msg.contains("مسدود") ||
                msg.contains("لغو") || msg.contains("error", ignoreCase = true) ||
                msg.contains("failed", ignoreCase = true)
        }
        val accent = if (isErrorLike) QsColors.SolarGold else QsColors.ElectricCyan
        val dir = if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr

        CompositionLocalProvider(LocalLayoutDirection provides dir) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xF0121C38), Color(0xF00A1022))
                        )
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.horizontalGradient(
                            listOf(accent.copy(alpha = 0.78f), QsColors.PlasmaViolet.copy(alpha = 0.45f))
                        ),
                        shape = RoundedCornerShape(16.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
                Text(
                    text = NovaPulseNoticeParser.isolateLtrTokensForDisplay(msg),
                    color = QsColors.TextPrimary,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NovaPulseNoticeShell(
    palette: NoticePalette,
    severity: NoticeSeverity,
    badgeLabel: String,
    title: String,
    subtitle: String?,
    currentVersionLabel: String?,
    targetVersionLabel: String?,
    abiBadgeLabel: String?,
    layoutDirection: LayoutDirection,
    content: @Composable ColumnScope.() -> Unit,
) {
    val motionActive = rememberMotionActive()
    val beaconPulse: Float = if (motionActive) {
        val t = rememberInfiniteTransition(label = "notice_beacon")
        t.animateFloat(
            initialValue = 0.65f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "beacon_alpha",
        ).value
    } else {
        0.90f
    }

    val shape = RoundedCornerShape(26.dp)
    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF131D3A),
                            Color(0xFF0B1226),
                            Color(0xFF070B18),
                        )
                    )
                )
                .border(
                    width = 1.4.dp,
                    brush = Brush.linearGradient(
                        listOf(
                            palette.cardBorderStart,
                            QsColors.ElectricCyan.copy(alpha = 0.40f),
                            palette.cardBorderEnd,
                        )
                    ),
                    shape = shape,
                )
        ) {
            // Subtle architectural corner ambient sheen
            Canvas(
                modifier = Modifier
                    .matchParentSize()
            ) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            palette.cardTopGlow.copy(alpha = palette.cardTopGlow.alpha * beaconPulse),
                            Color.Transparent,
                        ),
                        center = Offset(size.width * 0.16f, size.height * 0.08f),
                        radius = size.width * 0.72f,
                    ),
                    radius = size.width * 0.72f,
                    center = Offset(size.width * 0.16f, size.height * 0.08f),
                )
                drawLine(
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.28f),
                            Color.Transparent,
                        )
                    ),
                    start = Offset(size.width * 0.12f, 1.5f),
                    end = Offset(size.width * 0.88f, 1.5f),
                    strokeWidth = 2f,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                // Header row: emblem + badge & title
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    NoticeBeaconEmblem(
                        severity = severity,
                        palette = palette,
                        pulse = beaconPulse,
                    )

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(palette.badgeBg)
                                .border(0.8.dp, palette.badgeBorder, RoundedCornerShape(999.dp))
                                .padding(horizontal = 9.dp, vertical = 3.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(palette.primaryAccent.copy(alpha = beaconPulse))
                            )
                            Text(
                                text = badgeLabel,
                                color = palette.primaryAccent,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                            )
                        }

                        Text(
                            text = NovaPulseNoticeParser.isolateLtrTokensForDisplay(title),
                            color = QsColors.TextPrimary,
                            fontSize = 16.5.sp,
                            lineHeight = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                }

                // Version transition + ABI strip when rendering an update manifest
                if (!currentVersionLabel.isNullOrBlank() && !targetVersionLabel.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0x550F1E3F))
                                .border(0.8.dp, palette.primaryAccent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                Text(
                                    text = currentVersionLabel,
                                    color = QsColors.TextSecondary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "→",
                                    color = palette.primaryAccent,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = targetVersionLabel,
                                    color = palette.primaryAccent,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                )
                            }

                            if (!abiBadgeLabel.isNullOrBlank()) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0x4038BDF8))
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                ) {
                                    Text(
                                        text = abiBadgeLabel,
                                        color = QsColors.ElectricCyan,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                }

                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(QsColors.ConnectedEmerald)
                        )
                        Text(
                            text = subtitle,
                            color = QsColors.ConnectedEmerald,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                content()
            }
        }
    }
}

@Composable
private fun NoticeBeaconEmblem(
    severity: NoticeSeverity,
    palette: NoticePalette,
    pulse: Float,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        palette.primaryAccent.copy(alpha = 0.22f * pulse),
                        palette.secondaryAccent.copy(alpha = 0.14f),
                    )
                )
            )
            .border(1.dp, palette.primaryAccent.copy(alpha = 0.55f), RoundedCornerShape(15.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(26.dp)) {
            val w = size.width
            val h = size.height
            // Faceted NovaPulse Shield outline
            val shield = Path().apply {
                moveTo(w * 0.5f, h * 0.06f)
                lineTo(w * 0.90f, h * 0.22f)
                lineTo(w * 0.86f, h * 0.60f)
                quadraticTo(w * 0.72f, h * 0.84f, w * 0.5f, h * 0.95f)
                quadraticTo(w * 0.28f, h * 0.84f, w * 0.14f, h * 0.60f)
                lineTo(w * 0.10f, h * 0.22f)
                close()
            }
            drawPath(
                path = shield,
                color = palette.primaryAccent.copy(alpha = 0.16f),
            )
            drawPath(
                path = shield,
                color = palette.primaryAccent,
                style = Stroke(width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )

            when (severity) {
                NoticeSeverity.MANDATORY_SECURITY,
                NoticeSeverity.SECURITY_NOTICE -> {
                    // Alert mark inside shield
                    drawLine(
                        color = palette.primaryAccent,
                        start = Offset(w * 0.5f, h * 0.30f),
                        end = Offset(w * 0.5f, h * 0.56f),
                        strokeWidth = 2.6f,
                        cap = StrokeCap.Round,
                    )
                    drawCircle(
                        color = palette.primaryAccent,
                        radius = w * 0.055f,
                        center = Offset(w * 0.5f, h * 0.70f),
                    )
                }

                NoticeSeverity.UPDATE_INFO -> {
                    // Upward orbital chevron inside shield
                    val arrow = Path().apply {
                        moveTo(w * 0.34f, h * 0.52f)
                        lineTo(w * 0.50f, h * 0.33f)
                        lineTo(w * 0.66f, h * 0.52f)
                    }
                    drawPath(
                        path = arrow,
                        color = palette.primaryAccent,
                        style = Stroke(width = 2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                    drawLine(
                        color = palette.primaryAccent,
                        start = Offset(w * 0.50f, h * 0.35f),
                        end = Offset(w * 0.50f, h * 0.69f),
                        strokeWidth = 2.4f,
                        cap = StrokeCap.Round,
                    )
                }

                NoticeSeverity.INFO -> {
                    // Check pulse inside shield
                    val check = Path().apply {
                        moveTo(w * 0.33f, h * 0.53f)
                        lineTo(w * 0.46f, h * 0.65f)
                        lineTo(w * 0.68f, h * 0.38f)
                    }
                    drawPath(
                        path = check,
                        color = palette.primaryAccent,
                        style = Stroke(width = 2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }
        }
    }
}

@Composable
private fun NovaPulseSectionCard(
    section: NoticeSection,
    palette: NoticePalette,
) {
    val sectionAccent: Color
    val bgTint: Color
    val borderTint: Color
    when (section.kind) {
        NoticeSectionKind.ALERT -> {
            sectionAccent = if (palette.secondaryAccent == QsColors.DangerCrimson) QsColors.SolarGold else palette.primaryAccent
            bgTint = Color(0x4726131C)
            borderTint = sectionAccent.copy(alpha = 0.42f)
        }

        NoticeSectionKind.SAFE_STATUS -> {
            sectionAccent = QsColors.ConnectedEmerald
            bgTint = Color(0x450B2320)
            borderTint = QsColors.ConnectedEmerald.copy(alpha = 0.40f)
        }

        NoticeSectionKind.SUPPORT -> {
            sectionAccent = QsColors.ElectricCyan
            bgTint = Color(0x4A0E1B37)
            borderTint = QsColors.ElectricCyan.copy(alpha = 0.35f)
        }

        NoticeSectionKind.GENERAL -> {
            sectionAccent = palette.primaryAccent
            bgTint = Color(0x42101B36)
            borderTint = Color.White.copy(alpha = 0.12f)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(bgTint)
            .border(0.9.dp, borderTint, RoundedCornerShape(15.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!section.heading.isNullOrBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(14.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(sectionAccent)
                )
                Text(
                    text = NovaPulseNoticeParser.isolateLtrTokensForDisplay(section.heading),
                    color = sectionAccent,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }

        section.lines.forEach { line ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                if (line.isBullet) {
                    Box(
                        modifier = Modifier
                            .padding(top = 7.dp)
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(sectionAccent.copy(alpha = 0.85f))
                    )
                }
                NovaPulseRichText(
                    segments = line.segments,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NovaPulseRichText(
    segments: List<NoticeTextSegment>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val linkStyles = remember {
        TextLinkStyles(
            style = SpanStyle(
                color = QsColors.ElectricCyan,
                fontWeight = FontWeight.Bold,
                textDecoration = TextDecoration.Underline,
            )
        )
    }

    val annotated: AnnotatedString = remember(segments) {
        buildAnnotatedString {
            for (seg in segments) {
                when (seg) {
                    is NoticeTextSegment.Plain -> {
                        if (seg.isLtrIsolated) {
                            withStyle(
                                SpanStyle(
                                    color = QsColors.TextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace,
                                )
                            ) {
                                append(seg.text)
                            }
                        } else {
                            append(seg.text)
                        }
                    }

                    is NoticeTextSegment.Link -> {
                        val link = LinkAnnotation.Clickable(
                            tag = seg.target,
                            styles = linkStyles,
                        ) {
                            SupportLinks.openTelegramTarget(context, seg.target)
                        }
                        withLink(link) {
                            append(seg.text)
                        }
                    }
                }
            }
        }
    }

    val isPureLinkLine = remember(segments) {
        segments.size == 1 && segments[0] is NoticeTextSegment.Link
    }

    val textContent: @Composable () -> Unit = {
        Text(
            text = annotated,
            modifier = modifier,
            style = TextStyle(
                color = QsColors.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Normal,
                textDirection = if (isPureLinkLine) TextDirection.Ltr else TextDirection.Content,
            ),
        )
    }

    if (isPureLinkLine) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            textContent()
        }
    } else {
        textContent()
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun NovaPulseQuickActionsBar(
    actions: List<NoticeContactAction>,
    title: String,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x3D0B1730))
            .border(0.8.dp, QsColors.ElectricCyan.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            text = title,
            color = QsColors.TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            actions.forEach { action ->
                val accent = if (action.isChannel) QsColors.AuroraMint else QsColors.ElectricCyan
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(11.dp))
                        .background(accent.copy(alpha = 0.14f))
                        .border(0.9.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(11.dp))
                        .combinedClickable(
                            onClick = { SupportLinks.openTelegramTarget(context, action.target) },
                            onLongClick = {
                                Utils.setClipboard(context, action.subLabel)
                                android.widget.Toast.makeText(
                                    context,
                                    "Copied ${action.subLabel}",
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            },
                        )
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    TelegramPaperPlaneIcon(tint = accent)
                    Column {
                        Text(
                            text = action.label,
                            color = QsColors.TextPrimary,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = action.subLabel,
                                color = accent,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TelegramPaperPlaneIcon(tint: Color) {
    Canvas(modifier = Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val plane = Path().apply {
            moveTo(w * 0.92f, h * 0.14f)
            lineTo(w * 0.08f, h * 0.48f)
            lineTo(w * 0.40f, h * 0.59f)
            lineTo(w * 0.74f, h * 0.30f)
            lineTo(w * 0.48f, h * 0.65f)
            lineTo(w * 0.76f, h * 0.88f)
            close()
        }
        drawPath(
            path = plane,
            color = tint,
            style = Stroke(width = 1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
private fun NovaPulseDownloadProgressCard(
    progress: Int,
    palette: NoticePalette,
) {
    val clamped = progress.coerceIn(0, 100)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x4D0D1A34))
            .border(0.8.dp, palette.primaryAccent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.novapulse_update_downloading_fmt, clamped),
                color = palette.primaryAccent,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
            )
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    text = "$clamped%",
                    color = QsColors.TextPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(Color(0x38FFFFFF))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(clamped / 100f)
                    .height(7.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(palette.primaryAccent, palette.secondaryAccent)
                        )
                    )
            )
        }
    }
}

@Composable
private fun NovaPulseInlineAlertStrip(
    text: String,
    accent: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.14f))
            .border(0.9.dp, accent.copy(alpha = 0.50f), RoundedCornerShape(12.dp))
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Text(
            text = text,
            color = QsColors.TextPrimary,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun NovaPulseGradientPrimaryButton(
    text: String,
    palette: NoticePalette,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(palette.primaryAccent, QsColors.ElectricCyan)
                )
            ),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = QsColors.DeepVoid,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = QsColors.DeepVoid.copy(alpha = 0.5f),
        ),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(vertical = 12.dp, horizontal = 14.dp),
    ) {
        Text(
            text = text,
            color = QsColors.DeepVoid,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 13.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun isPreCutoffVersion(versionName: String, majorCutoff: Int, minorCutoff: Int, patchCutoff: Int): Boolean {
    val clean = versionName.trim().removePrefix("v").removePrefix("V")
        .substringBefore("-").substringBefore("+")
    val parts = clean.split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
    val maj = parts.getOrElse(0) { 0 }
    val min = parts.getOrElse(1) { 0 }
    val pat = parts.getOrElse(2) { 0 }
    if (maj != majorCutoff) return maj < majorCutoff
    if (min != minorCutoff) return min < minorCutoff
    return pat < patchCutoff
}

private fun formatApkSizeMb(sizeBytes: Long): String {
    if (sizeBytes <= 0L) return ""
    val mb = sizeBytes / (1024.0 * 1024.0)
    return String.format(Locale.US, "%.1f MB", mb)
}
