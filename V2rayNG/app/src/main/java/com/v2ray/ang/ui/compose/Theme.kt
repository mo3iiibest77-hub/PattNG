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
private val QSRed           = Color(0xFFCF4848)   // قرمز خطا

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

// ── Light Color Scheme (روشن ولی هنوز گرم) ───────────────────────────────────
private val QSLightColors = lightColorScheme(
    primary                = Color(0xFF8A6B2A),
    onPrimary              = Color(0xFFFFFFFF),
    primaryContainer       = Color(0xFFF5E6C0),
    onPrimaryContainer     = Color(0xFF3D2800),
    secondary              = QSOrange,
    onSecondary            = Color(0xFFFFFFFF),
    secondaryContainer     = Color(0xFFFFE8D0),
    onSecondaryContainer   = Color(0xFF3D1800),
    tertiary               = Color(0xFF2E7A55),
    onTertiary             = Color(0xFFFFFFFF),
    tertiaryContainer      = Color(0xFFB8F0D8),
    onTertiaryContainer    = Color(0xFF003020),
    error                  = Color(0xFFBA1A1A),
    onError                = Color(0xFFFFFFFF),
    errorContainer         = Color(0xFFFFDAD6),
    onErrorContainer       = Color(0xFF410002),
    background             = Color(0xFFFAF7F2),   // کرم گرم خیلی ملایم
    onBackground           = Color(0xFF1A1610),
    surface                = Color(0xFFFAF7F2),
    onSurface              = Color(0xFF1A1610),
    surfaceVariant         = Color(0xFFEDE8DF),
    onSurfaceVariant       = Color(0xFF4A4540),
    outline                = Color(0xFF8A8070),
    outlineVariant         = Color(0xFFD4CCC0),
    inverseSurface         = Color(0xFF2A2620),
    inverseOnSurface       = Color(0xFFF5F0E8),
    inversePrimary         = QSGold,
    scrim                  = Color(0xFF000000),
    surfaceTint            = Color(0xFF8A6B2A),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow    = Color(0xFFF5F2EC),
    surfaceContainer       = Color(0xFFEFEBE4),
    surfaceContainerHigh   = Color(0xFFE9E5DE),
    surfaceContainerHighest= Color(0xFFE3DFD8),
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
