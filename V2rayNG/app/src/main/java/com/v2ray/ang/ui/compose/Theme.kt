package com.v2ray.ang.ui.compose

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ── QuietStormNG Brand Palette ────────────────────────────────────────────────
private val QSGold          = Color(0xFFC9A84C)   // طلایی اصلی
private val QSGoldDim       = Color(0xFF8A6B2A)   // طلایی کم‌رنگ
private val QSGoldGlow      = Color(0xFFE8C96A)   // طلایی روشن
private val QSBlack         = Color(0xFF0D0D0F)   // مشکی عمیق
private val QSSurface       = Color(0xFF13131A)   // کارت مشکی
private val QSSurfaceHigh   = Color(0xFF1C1C26)   // کارت روشن‌تر
private val QSSurfaceMid    = Color(0xFF181820)   // میانی
private val QSSmoke         = Color(0xFF2A2A38)   // مه تاریک
private val QSText          = Color(0xFFF0EDE4)   // متن اصلی کرم گرم
private val QSTextSub       = Color(0xFF8A8878)   // متن ثانویه
private val QSOrange        = Color(0xFFD4833A)   // نارنجی گرم (accent)
private val QSGreen         = Color(0xFF4CAF82)   // سبز موفقیت

// ── Dark Color Scheme (QuietStormNG) ─────────────────────────────────────────
private val QSDarkColors = darkColorScheme(
    primary                = QSGold,
    onPrimary              = QSBlack,
    primaryContainer       = QSGoldDim,
    onPrimaryContainer     = QSGoldGlow,
    secondary              = QSOrange,
    onSecondary            = QSBlack,
    secondaryContainer     = Color(0xFF2A1A08),
    onSecondaryContainer   = QSOrange,
    tertiary               = QSGreen,
    onTertiary             = QSBlack,
    tertiaryContainer      = Color(0xFF0A2018),
    onTertiaryContainer    = QSGreen,
    error                  = QSRed,
    onError                = Color(0xFFFFFFFF),
    errorContainer         = Color(0xFF2A0808),
    onErrorContainer       = QSRed,
    background             = QSBlack,
    onBackground           = QSText,
    surface                = QSSurface,
    onSurface              = QSText,
    surfaceVariant         = QSSmoke,
    onSurfaceVariant       = QSTextSub,
    outline                = Color(0xFF3A3A4A),
    outlineVariant         = Color(0xFF252530),
    inverseSurface         = QSText,
    inverseOnSurface       = QSBlack,
    inversePrimary         = QSGoldDim,
    scrim                  = QSBlack,
    surfaceTint            = QSGold,
    surfaceContainerLowest = Color(0xFF080810),
    surfaceContainerLow    = Color(0xFF0F0F18),
    surfaceContainer       = QSSurface,
    surfaceContainerHigh   = QSSurfaceMid,
    surfaceContainerHighest= QSSurfaceHigh,
)

// ── Voxora light tokens ───────────────────────────────────────────────────────
// Source of truth: the Voxora Web design system on this server
// (/opt/voxora-web/DESIGN.md and /opt/voxora-web/public/styles.css, light block).
// Every value below is copied from that file, not invented.
val VoxoraPaper           = Color(0xFFFBF8F2)   // --vx-bg
val VoxoraSurface         = Color(0xFFFFFFFF)   // --vx-surface
val VoxoraSurface1        = Color(0xFFF6F1E7)   // --vx-surface-1
val VoxoraSurface2        = Color(0xFFEFE8DA)   // --vx-surface-2
val VoxoraWell            = Color(0xFFF3EEE4)   // --vx-well
val VoxoraInk             = Color(0xFF1C1812)   // --vx-content
val VoxoraInkStrong       = Color(0xFF14100B)   // --vx-content-strong
val VoxoraMuted           = Color(0xFF5E564A)   // --vx-muted
val VoxoraDisabled        = Color(0xFF857C6D)   // --vx-disabled
val VoxoraLine            = Color(0xFFE3DBCB)   // --vx-line
val VoxoraLineStrong      = Color(0xFFC9BFAC)   // --vx-line-strong
val VoxoraGoldInk         = Color(0xFF8A5A00)   // --vx-accent (5.6:1 on paper)
val VoxoraGoldFill        = Color(0xFFD9A441)   // --vx-accent-fill
val VoxoraGoldFillHover   = Color(0xFFE3B25A)   // --vx-accent-fill-hover
val VoxoraGoldContainer   = Color(0xFFF6E7C6)   // --vx-accent-container
val VoxoraGoldContainerInk= Color(0xFF6B4400)   // --vx-accent-container-ink
val VoxoraFocus           = Color(0xFFB07A12)   // --vx-focus
val VoxoraControlLine     = Color(0xFF8F8676)   // --vx-control-line
val VoxoraLive            = Color(0xFF1F5C66)   // --vx-live

// ── Quiet Storm accents ───────────────────────────────────────────────────────
// Green is deliberately a step brighter and cleaner than the reference success
// green (#2B7A4B): a fill for state and a darker ink for text on paper.
val QsGreenFill = Color(0xFF23A55A)
val QsGreenInk  = Color(0xFF17703F)
/** The app red, unchanged from the previous theme. */
val QsRed = Color(0xFFCF4848)

// ── Light Color Scheme (Voxora Web, paper and saffron) ────────────────────────
private val QSLightColors = lightColorScheme(
    primary                = VoxoraGoldInk,
    onPrimary              = Color(0xFFFFFFFF),
    primaryContainer       = VoxoraGoldContainer,
    onPrimaryContainer     = VoxoraGoldContainerInk,
    secondary              = VoxoraGoldFill,
    onSecondary            = VoxoraInk,
    secondaryContainer     = VoxoraSurface2,
    onSecondaryContainer   = VoxoraInk,
    tertiary               = QsGreenInk,
    onTertiary             = Color(0xFFFFFFFF),
    tertiaryContainer      = Color(0xFFD8F0E2),
    onTertiaryContainer    = Color(0xFF06331B),
    error                  = QsRed,
    onError                = Color(0xFFFFFFFF),
    errorContainer         = Color(0xFFFADBD8),
    onErrorContainer       = Color(0xFF5A1410),
    background             = VoxoraPaper,
    onBackground           = VoxoraInk,
    surface                = VoxoraSurface,
    onSurface              = VoxoraInkStrong,
    surfaceVariant         = VoxoraWell,
    onSurfaceVariant       = VoxoraMuted,
    outline                = VoxoraControlLine,
    outlineVariant         = VoxoraLine,
    inverseSurface         = VoxoraInk,
    inverseOnSurface       = VoxoraPaper,
    inversePrimary         = VoxoraGoldFill,
    scrim                  = Color(0xFF000000),
    surfaceTint            = VoxoraGoldInk,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow    = VoxoraPaper,
    surfaceContainer       = VoxoraSurface1,
    surfaceContainerHigh   = VoxoraSurface2,
    surfaceContainerHighest= Color(0xFFE9E2D3),
)
// ── Semantic Colors ───────────────────────────────────────────────────────────
val colorPing            = QSGreen
val colorPingRed         = QSRed
val colorConfigType      = QSGold
val colorFabActive       = QSGold
val colorFabInactiveLight= Color(0xFF8A8070)
val colorFabInactiveDark = Color(0xFF3A3A4A)
val dividerColorLight    = Color(0xFFE3DFD8)
val dividerColorDark     = Color(0xFF252530)

// Toast Colors
val toastNormalBgLight   = Color(0xB3252520)
val toastNormalBgDark    = Color(0xB33A3A4A)
val toastSuccessBg       = Color(0xB3204A30)
val toastErrorBg         = Color(0xB36A1010)
val toastInfoBg          = Color(0xB32A2050)
val toastIconCircleBg    = Color(0x33FFFFFF)
val toastTextColor       = Color.White

// ── Theme Manager ─────────────────────────────────────────────────────────────
object ThemeManager {
    private val _themeMode = MutableStateFlow(
        MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "0") ?: "0"
    )
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _dynamicColorEnabled = MutableStateFlow(false) // غیرفعال — brand ما override میشه

    val dynamicColorEnabled: StateFlow<Boolean> = _dynamicColorEnabled.asStateFlow()

    fun setThemeMode(mode: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_UI_MODE_NIGHT, mode)
        _themeMode.value = mode
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_DYNAMIC_COLOR, enabled)
        _dynamicColorEnabled.value = enabled
    }

    fun refresh() {
        _themeMode.value =
            MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "0") ?: "0"
    }
}

@Composable
fun resolveDarkTheme(): Boolean {
    val mode by ThemeManager.themeMode.collectAsState()
    return when (mode) {
        "1" -> false
        "2" -> true
        else -> isSystemInDarkTheme()
    }
}

val LocalDarkTheme = compositionLocalOf { false }

@Composable
fun AppTheme(
    darkTheme: Boolean = resolveDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) QSDarkColors else QSLightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val window = activity.window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    val snackbarController = rememberAppSnackbarController()

    CompositionLocalProvider(
        LocalDarkTheme provides darkTheme,
        LocalAppSnackbar provides snackbarController
    ) {
        MaterialTheme(colorScheme = colorScheme) {
            Box(modifier = Modifier.fillMaxSize()) {
                AppSnackbarBridge(controller = snackbarController)
                content()
                AppSnackbarHost(hostState = snackbarController.hostState)
            }
        }
    }
}
