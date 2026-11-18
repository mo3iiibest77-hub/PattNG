package com.v2ray.ang.ui.qsn3d

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R

/**
 * Central support, official channel & purchase configuration for NovaPulse.
 */
object SupportLinks {
    const val TELEGRAM_USERNAME = "Mo3i_Best"
    const val OFFICIAL_CHANNEL_USERNAME = "NovaPulseVPN"

    val telegramDeepLink: String
        get() = "tg://resolve?domain=$TELEGRAM_USERNAME"

    val telegramWebUrl: String
        get() = "https://t.me/$TELEGRAM_USERNAME"

    fun openTelegramSupport(context: Context): Boolean = openTelegramTarget(context, TELEGRAM_USERNAME)

    fun openOfficialChannel(context: Context): Boolean = openTelegramTarget(context, OFFICIAL_CHANNEL_USERNAME)

    fun openTelegramTarget(context: Context, target: String): Boolean {
        val clean = target.trim()
        if (clean.isEmpty()) return false

        val telegramUser = NovaPulseNoticeParser.extractTelegramUsername(clean)
            ?: clean.removePrefix("@").takeIf {
                !clean.contains("://") && Regex("""^[A-Za-z][A-Za-z0-9_]{2,31}$""").matches(it)
            }

        if (telegramUser != null) {
            val deepIntent = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=$telegramUser")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val openedDeep = runCatching {
                context.startActivity(deepIntent)
                true
            }.getOrDefault(false)
            if (openedDeep) return true

            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/$telegramUser")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val openedWeb = runCatching {
                context.startActivity(webIntent)
                true
            }.getOrDefault(false)
            if (openedWeb) return true
        } else if (clean.startsWith("https://", ignoreCase = true) || clean.startsWith("http://", ignoreCase = true)) {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(clean)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val openedWeb = runCatching {
                context.startActivity(webIntent)
                true
            }.getOrDefault(false)
            if (openedWeb) return true
        }

        Toast.makeText(
            context,
            context.getString(R.string.novapulse_telegram_open_error),
            Toast.LENGTH_SHORT
        ).show()
        return false
    }
}

/**
 * Redesigned User Guide, Service Overview & Telegram Channel / Support Screen.
 */
@Composable
fun Qs3dAboutPromoScreen(
    hasActiveLicense: Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.novapulse_back_btn), color = QsColors.ElectricCyan, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    text = stringResource(R.string.novapulse_promo_screen_title),
                    color = QsColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(48.dp))
            }
        }

        // Hero Presentation Card
        item {
            Glass3dCard(
                surfaceColor = QsColors.SurfaceElevated,
                glowColor = QsColors.ElectricCyan.copy(alpha = 0.35f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(62.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(QsColors.SurfaceInset)
                            .border(1.dp, QsColors.BorderStrong, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(R.drawable.np_app_logo),
                            contentDescription = stringResource(R.string.app_name),
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(13.dp))
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.novapulse_promo_hero_badge),
                            color = QsColors.ElectricCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.novapulse_promo_hero_title),
                            color = QsColors.TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.novapulse_promo_intro_body),
                    color = QsColors.TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
            }
        }

        // Core Features Card
        item {
            Glass3dCard {
                Text(
                    text = stringResource(R.string.novapulse_promo_features_title),
                    color = QsColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(10.dp))
                PromoFeatureRow(
                    title = stringResource(R.string.novapulse_promo_feat1_title),
                    desc = stringResource(R.string.novapulse_promo_feat1_desc),
                    accent = QsColors.ElectricCyan,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = QsColors.BorderSubtle.copy(alpha = 0.6f))
                PromoFeatureRow(
                    title = stringResource(R.string.novapulse_promo_feat2_title),
                    desc = stringResource(R.string.novapulse_promo_feat2_desc),
                    accent = QsColors.PlasmaViolet,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = QsColors.BorderSubtle.copy(alpha = 0.6f))
                PromoFeatureRow(
                    title = stringResource(R.string.novapulse_promo_feat3_title),
                    desc = stringResource(R.string.novapulse_promo_feat3_desc),
                    accent = QsColors.ConnectedEmerald,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = QsColors.BorderSubtle.copy(alpha = 0.6f))
                PromoFeatureRow(
                    title = stringResource(R.string.novapulse_promo_feat4_title),
                    desc = stringResource(R.string.novapulse_promo_feat4_desc),
                    accent = QsColors.CloudflareOrange,
                )
            }
        }

        // FAQ / Step-by-step Guide Card
        item {
            Glass3dCard {
                Text(
                    text = stringResource(R.string.novapulse_promo_guide_title),
                    color = QsColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(10.dp))
                GuideQuestionBlock(
                    question = stringResource(R.string.novapulse_promo_q1_title),
                    answer = stringResource(R.string.novapulse_promo_q1_body)
                )
                Spacer(Modifier.height(10.dp))
                GuideQuestionBlock(
                    question = stringResource(R.string.novapulse_promo_q2_title),
                    answer = stringResource(R.string.novapulse_promo_q2_body)
                )
                Spacer(Modifier.height(10.dp))
                GuideQuestionBlock(
                    question = stringResource(R.string.novapulse_promo_q3_title),
                    answer = stringResource(R.string.novapulse_promo_q3_body)
                )
            }
        }

        // Official Channel + Support Purchase Card
        item {
            Glass3dCard(
                surfaceColor = QsColors.SurfaceElevated,
                glowColor = QsColors.ConnectedEmerald.copy(alpha = 0.35f)
            ) {
                Text(
                    text = stringResource(R.string.novapulse_promo_buy_title),
                    color = QsColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.novapulse_promo_buy_body),
                    color = QsColors.TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 19.sp
                )
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(9.dp),
                        color = QsColors.ElectricCyan.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, QsColors.ElectricCyan.copy(alpha = 0.38f)),
                    ) {
                        Text(
                            text = "@${SupportLinks.TELEGRAM_USERNAME}",
                            style = QsTextStyles.MonoCode,
                            color = QsColors.ElectricCyan,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(9.dp),
                        color = QsColors.AuroraMint.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, QsColors.AuroraMint.copy(alpha = 0.38f)),
                    ) {
                        Text(
                            text = "@${SupportLinks.OFFICIAL_CHANNEL_USERNAME}",
                            style = QsTextStyles.MonoCode,
                            color = QsColors.AuroraMint,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = { SupportLinks.openTelegramSupport(context) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(11.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = QsColors.ElectricCyan,
                        contentColor = Color(0xFF04151E),
                    )
                ) {
                    Text(
                        text = stringResource(R.string.novapulse_promo_telegram_btn),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { SupportLinks.openOfficialChannel(context) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(11.dp),
                    border = BorderStroke(1.dp, QsColors.AuroraMint.copy(alpha = 0.45f)),
                ) {
                    Text(
                        text = stringResource(R.string.novapulse_channel_join_btn),
                        color = QsColors.AuroraMint,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    )
                }

                if (!hasActiveLicense) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(11.dp),
                        border = BorderStroke(1.dp, QsColors.BorderStrong)
                    ) {
                        Text(
                            text = stringResource(R.string.novapulse_promo_go_activate_btn),
                            color = QsColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PromoFeatureRow(
    title: String,
    desc: String,
    accent: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = QsColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = desc,
                color = QsColors.TextSecondary,
                fontSize = 13.sp,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
private fun GuideQuestionBlock(
    question: String,
    answer: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(QsColors.SurfaceInset)
            .border(1.dp, QsColors.BorderSubtle, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(
            text = question,
            color = QsColors.ElectricCyan,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = answer,
            color = QsColors.TextSecondary,
            fontSize = 13.sp,
            lineHeight = 20.sp
        )
    }
}
