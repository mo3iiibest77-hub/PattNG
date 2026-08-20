package com.v2ray.ang.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.v2ray.ang.ui.compose.AppTheme
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import com.v2ray.ang.R
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.senpai.CandidateResult
import com.v2ray.ang.senpai.CloudflareScanner
import com.v2ray.ang.senpai.ScanCallback

// ── Brand Colors ─────────────────────────────────────────────────────────────
private val BgDark       = Color(0xFF0D0D0F)
private val BgCard       = Color(0xFF13131A)
private val Gold         = Color(0xFFC9A84C)
private val GoldDim      = Color(0xFF8A6F2E)
private val GoldGlow     = Color(0xFFE8C96A)
private val GreenGood    = Color(0xFF4CAF82)
private val YellowMid    = Color(0xFFD4A843)
private val RedBad       = Color(0xFFCF4848)
private val TextPrimary  = Color(0xFFF0EEE8)
private val TextSecond   = Color(0xFF8A8878)
private val CardBorder   = Color(0xFF252530)
// ─────────────────────────────────────────────────────────────────────────────

class CfScanActivity : ComponentActivity() {

    companion object {
        fun start(ctx: Context, guid: String) =
            ctx.startActivity(Intent(ctx, CfScanActivity::class.java).putExtra("guid", guid))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val guid = intent.getStringExtra("guid") ?: run { finish(); return }

        setContent {
            AppTheme {
            CfScanScreen(
                guid        = guid,
                onBack      = { finish() },
                onApplyAndConnect = { ip ->
                    CloudflareScanner.applyBestIp(guid, ip)
                    setResult(RESULT_OK, Intent().putExtra("applied_ip", ip))
                    finish()
                }
            )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CfScanScreen(
    guid: String,
    onBack: () -> Unit,
    onApplyAndConnect: (String) -> Unit,
) {
    val results    = remember { mutableStateListOf<CandidateResult>() }
    var done       by remember { mutableIntStateOf(0) }
    var total      by remember { mutableIntStateOf(30) }
    var scanning   by remember { mutableStateOf(false) }
    var bestIp     by remember { mutableStateOf<String?>(null) }
    val ctx        = androidx.compose.ui.platform.LocalContext.current

    // Auto-start scan on open
    LaunchedEffect(guid) {
        scanning = true
        results.clear()
        done = 0
        CloudflareScanner.scan(
            context  = ctx,
            guid     = guid,
            callback = object : ScanCallback {
                override fun onProgress(result: CandidateResult, d: Int, t: Int) {
                    results.add(result)
                    done  = d
                    total = t
                }
                override fun onFinish(best: CandidateResult?) {
                    scanning = false
                    bestIp   = best?.ip
                    // sort: successful first, by upload desc
                    val sorted = results.sortedWith(
                        compareByDescending<CandidateResult> { it.isSuccess }
                            .thenByDescending { it.uploadKBps }
                    )
                    results.clear()
                    results.addAll(sorted)
                }
                override fun onCancelled() { scanning = false }
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose { CloudflareScanner.cancel() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        Column(Modifier.fillMaxSize()) {

            // ── Top bar ──────────────────────────────────────────────────────
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(listOf(BgDark, Color(0xFF1A1508), BgDark))
                    )
                    .padding(top = 48.dp, bottom = 16.dp, start = 16.dp, end = 16.dp)
            ) {
                IconButton(onClick = onBack, Modifier.align(Alignment.CenterStart)) {
                    Icon(painterResource(R.drawable.ic_arrow_back_24dp), null, tint = Gold)
                }
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "QuietStorm Scanner",
                        color = GoldGlow,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )
                    Text(
                        "Cloudflare IP Optimizer",
                        color = TextSecond,
                        fontSize = 11.sp,
                        letterSpacing = 2.sp,
                    )
                }
                if (scanning) {
                    IconButton(onClick = { CloudflareScanner.cancel() }, Modifier.align(Alignment.CenterEnd)) {
                        Icon(painterResource(R.drawable.ic_stop_24dp), null, tint = RedBad)
                    }
                }
            }

            // ── Progress ─────────────────────────────────────────────────────
            AnimatedVisibility(scanning) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Scanning IPs...", color = TextSecond, fontSize = 12.sp)
                        Text("$done / $total", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    val progress = if (total > 0) done.toFloat() / total else 0f
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                        color    = Gold,
                        trackColor = CardBorder,
                    )
                }
            }

            // ── Animated scanning message ─────────────────────────────────
            AnimatedVisibility(
                visible = scanning,
                enter = fadeIn(tween(500)),
                exit = fadeOut(tween(300))
            ) {
                val infiniteTransition = rememberInfiniteTransition(label = "scan_pulse")
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.4f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
                    label = "alpha"
                )
                val messages = listOf(
                    "در حال جستجوی بهترین مسیر...",
                    "اسکن رنج‌های Cloudflare...",
                    "تست کیفیت اتصال...",
                    "بهینه‌سازی برای ISP ایران..."
                )
                val messageIndex by infiniteTransition.animateValue(
                    initialValue = 0, targetValue = messages.size - 1,
                    typeConverter = Int.VectorConverter,
                    animationSpec = infiniteRepeatable(tween(2000), RepeatMode.Restart),
                    label = "msg"
                )
                Text(
                    text = messages[messageIndex],
                    color = Gold.copy(alpha = alpha),
                    fontSize = 12.sp,
                    fontStyle = FontStyle.Italic,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }

            // ── Status banner (after scan) ────────────────────────────────
            AnimatedVisibility(!scanning && results.isNotEmpty()) {
                val successCount = results.count { it.isSuccess }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0F1A0F))
                        .border(1.dp, GreenGood.copy(alpha = .3f), RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(painterResource(R.drawable.ic_action_done), null, tint = GreenGood, modifier = Modifier.size(18.dp))
                    Text(
                        "Found $successCount usable IPs out of ${results.size} tested",
                        color = GreenGood,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            // ── IP List ───────────────────────────────────────────────────
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(results, key = { it.ip }) { r ->
                    IpCard(
                        result    = r,
                        isBest    = r.ip == bestIp,
                        onSelect  = { onApplyAndConnect(r.ip) },
                    )
                }
            }

            // ── Bottom button ─────────────────────────────────────────────
            AnimatedVisibility(bestIp != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(listOf(Color.Transparent, BgDark))
                        )
                        .padding(20.dp)
                ) {
                    Button(
                        onClick = { bestIp?.let(onApplyAndConnect) },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape    = RoundedCornerShape(14.dp),
                        colors   = ButtonDefaults.buttonColors(containerColor = Gold),
                    ) {
                        Text(
                            "⚡  Apply Best IP & Connect",
                            color      = BgDark,
                            fontWeight = FontWeight.Bold,
                            fontSize   = 15.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IpCard(
    result: CandidateResult,
    isBest: Boolean,
    onSelect: () -> Unit,
) {
    val quality = when {
        !result.isSuccess         -> IpQuality.BAD
        result.uploadKBps >= 200  -> IpQuality.GOOD
        result.uploadKBps >= 80   -> IpQuality.MID
        else                      -> IpQuality.MID
    }

    val borderColor = when {
        isBest          -> Gold
        quality == IpQuality.GOOD -> GreenGood.copy(alpha = .5f)
        quality == IpQuality.MID  -> YellowMid.copy(alpha = .4f)
        else            -> RedBad.copy(alpha = .3f)
    }
    val tagColor = when (quality) {
        IpQuality.GOOD -> GreenGood
        IpQuality.MID  -> YellowMid
        IpQuality.BAD  -> RedBad
    }
    val tagText = when (quality) {
        IpQuality.GOOD -> "✓  GOOD"
        IpQuality.MID  -> "~  OK"
        IpQuality.BAD  -> "✗  FAIL"
    }

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isBest) Color(0xFF1A1408) else BgCard)
            .border(
                width = if (isBest) 1.5.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(14.dp)
            )
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

            // IP + tag
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Text(
                    result.ip,
                    color      = if (result.isSuccess) TextPrimary else TextSecond,
                    fontWeight = FontWeight.Bold,
                    fontSize   = 15.sp,
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = tagColor.copy(alpha = .15f),
                ) {
                    Text(
                        tagText,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        color    = tagColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            if (result.isSuccess) {
                // Stats row
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    StatChip("Ping",     "${result.latencyMs}ms", Gold)
                    StatChip("↑ Upload", "${result.uploadKBps} KB/s", GreenGood)
                    StatChip("↓ Dn",    "${result.downloadKBps} KB/s", Color(0xFF5BA4CF))
                }

                // Select button
                if (!isBest) {
                    TextButton(
                        onClick  = onSelect,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text("Use this IP", color = GoldDim, fontSize = 12.sp)
                    }
                } else {
                    Row(
                        Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(painterResource(R.drawable.ic_action_done), null, tint = Gold, modifier = Modifier.size(14.dp))
                        Text("Best IP", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, valueColor: Color) {
    Column {
        Text(label, color = TextSecond, fontSize = 10.sp)
        Text(value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

private enum class IpQuality { GOOD, MID, BAD }
