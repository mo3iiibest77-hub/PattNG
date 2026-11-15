package com.v2ray.ang.ui.qsn3d

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R

/**
 * Central support & purchase configuration for NovaPulse.
 * Update [TELEGRAM_USERNAME] before publishing if your Telegram handle changes.
 */
object SupportLinks {
    const val TELEGRAM_USERNAME = "Mo3i_Best"
    const val OFFICIAL_CHANNEL_USERNAME = "NovaPulseVPN"

    val telegramDeepLink: String
        get() = "tg://resolve?domain=$TELEGRAM_USERNAME"

    val telegramWebUrl: String
        get() = "https://t.me/$TELEGRAM_USERNAME"

    fun openTelegramSupport(context: Context): Boolean = openTelegramTarget(context, TELEGRAM_USERNAME)

    /**
     * Opens any Telegram handle (`@user`), `t.me/...`, `tg://...`, or HTTPS URL cleanly,
     * falling back from the native Telegram URI scheme to the web browser if needed.
     */
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
 * Standalone Promotional, User Guide & License Purchase Screen for NovaPulse.
 * Describes strictly implemented capabilities in warm conversational wording without ever
 * asking users for raw subscription links.
 */
@Composable
fun Qs3dAboutPromoScreen(
    hasActiveLicense: Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.novapulse_back_btn), color = QsColors.ElectricCyan)
                }
                Text(
                    text = stringResource(R.string.novapulse_promo_screen_title),
                    color = QsColors.TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(56.dp))
            }
        }

        // 1. Visual Hero Card with Nature + Cyber Artwork
        item {
            Glass3dCard(
                beamSeed = 11,
                glowColor = QsColors.ElectricCyan.copy(alpha = 0.48f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    Image(
                        painter = painterResource(R.drawable.np_promo_hero),
                        contentDescription = stringResource(R.string.novapulse_promo_hero_title),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color(0x22040814),
                                        Color(0x88040814),
                                        Color(0xDD040814),
                                    )
                                )
                            )
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(14.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.novapulse_promo_hero_badge),
                            color = QsColors.AuroraMint,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.novapulse_promo_hero_title),
                            color = QsColors.TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.novapulse_promo_intro_body),
                    color = QsColors.TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
            }
        }

        // 2. Implemented Capabilities Overview
        item {
            Glass3dCard(
                beamSeed = 23,
                glowColor = QsColors.AuroraMint.copy(alpha = 0.42f)
            ) {
                Text(
                    text = stringResource(R.string.novapulse_promo_features_title),
                    color = QsColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(10.dp))
                PromoFeatureRow(
                    number = "1",
                    accent = QsColors.ElectricCyan,
                    title = stringResource(R.string.novapulse_promo_feat1_title),
                    body = stringResource(R.string.novapulse_promo_feat1_desc)
                )
                Spacer(Modifier.height(10.dp))
                PromoFeatureRow(
                    number = "2",
                    accent = QsColors.PlasmaViolet,
                    title = stringResource(R.string.novapulse_promo_feat2_title),
                    body = stringResource(R.string.novapulse_promo_feat2_desc)
                )
                Spacer(Modifier.height(10.dp))
                PromoFeatureRow(
                    number = "3",
                    accent = QsColors.AuroraMint,
                    title = stringResource(R.string.novapulse_promo_feat3_title),
                    body = stringResource(R.string.novapulse_promo_feat3_desc)
                )
                Spacer(Modifier.height(10.dp))
                PromoFeatureRow(
                    number = "4",
                    accent = QsColors.CloudflareOrange,
                    title = stringResource(R.string.novapulse_promo_feat4_title),
                    body = stringResource(R.string.novapulse_promo_feat4_desc)
                )
            }
        }

        // 3. Step-by-Step User Guide & Quick Answers
        item {
            Glass3dCard(
                beamSeed = 37,
                glowColor = QsColors.PlasmaViolet.copy(alpha = 0.44f)
            ) {
                Text(
                    text = stringResource(R.string.novapulse_promo_guide_title),
                    color = QsColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
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

        // 4. Direct License Purchase & Telegram Support CTA
        item {
            Glass3dCard(
                beamSeed = 51,
                glowColor = QsColors.ConnectedEmerald.copy(alpha = 0.48f)
            ) {
                Text(
                    text = stringResource(R.string.novapulse_promo_buy_title),
                    color = QsColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.novapulse_promo_buy_body),
                    color = QsColors.TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(QsColors.ElectricCyan.copy(alpha = 0.14f))
                        .border(0.8.dp, QsColors.ElectricCyan.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "@${SupportLinks.TELEGRAM_USERNAME}",
                        color = QsColors.ElectricCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { SupportLinks.openTelegramSupport(context) },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = QsColors.ElectricCyan)
                ) {
                    Text(
                        text = stringResource(R.string.novapulse_promo_telegram_btn),
                        color = Color(0xFF041322),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp
                    )
                }
                if (!hasActiveLicense) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.novapulse_promo_go_activate_btn),
                            color = QsColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun PromoFeatureRow(
    number: String,
    accent: Color,
    title: String,
    body: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.18f))
                .border(0.9.dp, accent.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                color = accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = QsColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = body,
                color = QsColors.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun GuideQuestionBlock(
    question: String,
    answer: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x260A1328))
            .border(0.7.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Text(
            text = question,
            color = QsColors.AuroraMint,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = answer,
            color = QsColors.TextSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
    }
}
