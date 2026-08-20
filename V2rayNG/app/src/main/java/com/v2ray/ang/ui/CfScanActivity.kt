package com.v2ray.ang.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.senpai.CandidateResult
import com.v2ray.ang.senpai.CloudflareScanner
import com.v2ray.ang.senpai.IspManager
import com.v2ray.ang.senpai.IspProfile
import com.v2ray.ang.senpai.ScanCallback
import com.v2ray.ang.ui.compose.AppTheme

private val BgDark      = Color(0xFF0D0D0F)
private val BgCard      = Color(0xFF13131A)
private val Gold        = Color(0xFFC9A84C)
private val GoldDim     = Color(0xFF8A6F2E)
private val GoldGlow    = Color(0xFFE8C96A)
private val GreenGood   = Color(0xFF4CAF82)
private val YellowMid   = Color(0xFFD4A843)
private val RedBad      = Color(0xFFCF4848)
private val TextPrimary = Color(0xFFF0EEE8)
private val TextSecond  = Color(0xFF8A8878)
private val CardBorder  = Color(0xFF252530)

private enum class ScanPhase {
    ISP_SELECT,      // انتخاب ISP
    DISCOVERY,       // فاز ۱: پیدا کردن رنج‌های خوب
    SCANNING,        // فاز ۲: اسکن targeted
    DONE,            // تموم شد
}

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
                    guid = guid,
                    onBack = { finish() },
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
    val ctx = androidx.compose.ui.platform.LocalContext.current

    // ── State ──────────────────────────────────────────────────────────────────
    var phase by remember { mutableStateOf(ScanPhase.ISP_SELECT) }
    var ispName by remember { mutableStateOf("") }
    var ispNameInput by remember { mutableStateOf("") }

    // Discovery state
    var discDone by remember { mutableIntStateOf(0) }
    var discTotal by remember { mutableIntStateOf(0) }
    var discGoodCount by remember { mutableIntStateOf(0) }

    // Scan state
    val results = remember { mutableStateListOf<CandidateResult>() }
    var scanDone by remember { mutableIntStateOf(0) }
    var scanTotal by remember { mutableIntStateOf(30) }
    var bestIp by remember { mutableStateOf<String?>(null) }

    // ISP list
    val ispProfiles = remember { mutableStateListOf<IspProfile>() }
    LaunchedEffect(Unit) {
        ispProfiles.clear()
        ispProfiles.addAll(IspManager.loadAll(ctx))
    }

    // ── Top Bar ────────────────────────────────────────────────────────────────
    Box(Modifier.fillMaxSize().background(BgDark)) {
        Column(Modifier.fillMaxSize()) {

            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.horizontalGradient(listOf(BgDark, Color(0xFF1A1508), BgDark)))
                    .padding(top = 48.dp, bottom = 16.dp, start = 16.dp, end = 16.dp)
            ) {
                IconButton(onClick = {
                    CloudflareScanner.cancel()
                    onBack()
                }, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(painterResource(R.drawable.ic_arrow_back_24dp), null, tint = Gold)
                }
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🌩️ QuietStorm Scanner", color = GoldGlow, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text("⚡ Cloudflare IP Scanner", color = TextSecond, fontSize = 11.sp, letterSpacing = 2.sp)
                }
                if (phase == ScanPhase.DISCOVERY || phase == ScanPhase.SCANNING) {
                    IconButton(onClick = { CloudflareScanner.cancel() }, modifier = Modifier.align(Alignment.CenterEnd)) {
                        Icon(painterResource(R.drawable.ic_stop_24dp), null, tint = RedBad)
                    }
                }
            }

            // ── Content ────────────────────────────────────────────────────────
            when (phase) {

                // ── فاز ۰: انتخاب ISP ─────────────────────────────────────────
                ScanPhase.ISP_SELECT -> {
                    LazyColumn(
                        Modifier.weight(1f),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Text(
                                "اینترنت گوشیت رو روی یه خط بذار، بعد ISP اون خط رو وارد کن:",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                lineHeight = 22.sp,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                        // ── ISP های ذخیره شده ─────────────────────────────────
                        if (ispProfiles.isNotEmpty()) {
                            item {
                                Text("ISP های ذخیره‌شده:", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            items(ispProfiles) { p ->
                                IspProfileCard(
                                    profile = p,
                                    onScan = {
                                        ispName = p.name
                                        phase = ScanPhase.SCANNING
                                        results.clear(); scanDone = 0; bestIp = null
                                        CloudflareScanner.scanForIsp(ctx, guid, p.name, callback = object : ScanCallback {
                                            override fun onProgress(result: CandidateResult, d: Int, t: Int) {
                                                results.add(result); scanDone = d; scanTotal = t
                                            }
                                            override fun onFinish(best: CandidateResult?) {
                                                phase = ScanPhase.DONE
                                                bestIp = best?.ip
                                                val sorted = results.sortedWith(compareByDescending<CandidateResult> { it.isSuccess }.thenByDescending { it.uploadKBps })
                                                results.clear(); results.addAll(sorted)
                                            }
                                            override fun onCancelled() { phase = ScanPhase.DONE }
                                        })
                                    },
                                    onDelete = {
                                        IspManager.deleteProfile(ctx, p.name)
                                        ispProfiles.clear()
                                        ispProfiles.addAll(IspManager.loadAll(ctx))
                                    }
                                )
                            }
                            item { Divider(color = CardBorder, modifier = Modifier.padding(vertical = 4.dp)) }
                        }
                        // ── اضافه کردن ISP جدید ────────────────────────────────
                        item {
                            Text("+ اضافه کردن ISP جدید:", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = ispNameInput,
                                onValueChange = { ispNameInput = it },
                                label = { Text("نام ISP", color = TextSecond) },
                                placeholder = { Text("مثلاً: همراه اول، ایرانسل، پیشگامان", color = TextSecond.copy(alpha = .5f)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = {}),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Gold,
                                    unfocusedBorderColor = CardBorder,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary,
                                    cursorColor = Gold,
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    val name = ispNameInput.trim()
                                    if (name.isBlank()) return@Button
                                    ispName = name
                                    // اگه این ISP قبلاً discovery شده مستقیم اسکن کن
                                    val existing = IspManager.getProfile(ctx, name)
                                    if (existing != null && existing.goodCidrs.isNotEmpty()) {
                                        phase = ScanPhase.SCANNING
                                        results.clear(); scanDone = 0; bestIp = null
                                        CloudflareScanner.scanForIsp(ctx, guid, name, callback = object : ScanCallback {
                                            override fun onProgress(result: CandidateResult, d: Int, t: Int) {
                                                results.add(result); scanDone = d; scanTotal = t
                                            }
                                            override fun onFinish(best: CandidateResult?) {
                                                phase = ScanPhase.DONE; bestIp = best?.ip
                                                val sorted = results.sortedWith(compareByDescending<CandidateResult> { it.isSuccess }.thenByDescending { it.uploadKBps })
                                                results.clear(); results.addAll(sorted)
                                            }
                                            override fun onCancelled() { phase = ScanPhase.DONE }
                                        })
                                    } else {
                                        // باید اول discovery بشه
                                        phase = ScanPhase.DISCOVERY
                                        discDone = 0; discGoodCount = 0
                                        CloudflareScanner.discoverGoodCidrs(
                                            context = ctx,
                                            ispName = name,
                                            guid = guid,
                                            onProgress = { d, t, _, responded ->
                                                discDone = d; discTotal = t
                                                if (responded) discGoodCount++
                                            },
                                            onFinish = { goodCidrs ->
                                                if (goodCidrs.isNotEmpty()) {
                                                    phase = ScanPhase.SCANNING
                                                    results.clear(); scanDone = 0; bestIp = null
                                                    CloudflareScanner.scanForIsp(ctx, guid, name, callback = object : ScanCallback {
                                                        override fun onProgress(result: CandidateResult, d: Int, t: Int) {
                                                            results.add(result); scanDone = d; scanTotal = t
                                                        }
                                                        override fun onFinish(best: CandidateResult?) {
                                                            phase = ScanPhase.DONE; bestIp = best?.ip
                                                            val sorted = results.sortedWith(compareByDescending<CandidateResult> { it.isSuccess }.thenByDescending { it.uploadKBps })
                                                            results.clear(); results.addAll(sorted)
                                                            ispProfiles.clear()
                                                            ispProfiles.addAll(IspManager.loadAll(ctx))
                                                        }
                                                        override fun onCancelled() { phase = ScanPhase.DONE }
                                                    })
                                                } else {
                                                    phase = ScanPhase.ISP_SELECT
                                                }
                                            }
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Gold),
                                enabled = ispNameInput.isNotBlank()
                            ) {
                                Text("🔍 شروع اسکن", color = BgDark, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                        }
                    }
                }

                // ── فاز ۱: Discovery ──────────────────────────────────────────
                ScanPhase.DISCOVERY -> {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("🔎 Discovery — $ispName", color = GoldGlow, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("داره رنج‌های Cloudflare رو برای این ISP بررسی می‌کنه...", color = TextSecond, fontSize = 12.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(24.dp))
                        val progress = if (discTotal > 0) discDone.toFloat() / discTotal else 0f
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                            color = Gold,
                            trackColor = CardBorder,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("$discDone / $discTotal رنج", color = TextSecond, fontSize = 11.sp)
                            Text("✅ $discGoodCount رنج خوب", color = GreenGood, fontSize = 11.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "این اسکن یه بار انجام میشه و ذخیره میشه.\nدفعه بعد مستقیم اسکن سریع داری.",
                            color = TextSecond.copy(alpha = .7f),
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 18.sp,
                        )
                    }
                }

                // ── فاز ۲ + DONE: Scanning & نتایج ──────────────────────────
                ScanPhase.SCANNING, ScanPhase.DONE -> {
                    Column(Modifier.fillMaxSize()) {
                        // ISP badge
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(shape = RoundedCornerShape(8.dp), color = Gold.copy(alpha = .15f)) {
                                Text("📶 $ispName", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                            }
                        }
                        // Progress
                        AnimatedVisibility(phase == ScanPhase.SCANNING) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("🔍 Scanning...", color = TextSecond, fontSize = 12.sp)
                                    Text("$scanDone / $scanTotal", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.height(6.dp))
                                val progress = if (scanTotal > 0) scanDone.toFloat() / scanTotal else 0f
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                                    color = Gold, trackColor = CardBorder,
                                )
                            }
                        }
                        // Done banner
                        AnimatedVisibility(phase == ScanPhase.DONE && results.isNotEmpty()) {
                            val successCount = results.count { it.isSuccess }
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF0F1A0F))
                                    .border(1.dp, GreenGood.copy(alpha = .3f), RoundedCornerShape(10.dp))
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(painterResource(R.drawable.ic_action_done), null, tint = GreenGood, modifier = Modifier.size(18.dp))
                                Text("✅ $successCount IP خوب از ${results.size} تست", color = GreenGood, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        // IP List
                        LazyColumn(
                            Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(results, key = { it.ip }) { r ->
                                IpCard(result = r, isBest = r.ip == bestIp, onSelect = { onApplyAndConnect(r.ip) })
                            }
                        }
                        // Apply button
                        AnimatedVisibility(bestIp != null) {
                            Box(
                                Modifier.fillMaxWidth()
                                    .background(Brush.verticalGradient(listOf(Color.Transparent, BgDark)))
                                    .padding(20.dp)
                            ) {
                                Button(
                                    onClick = { bestIp?.let(onApplyAndConnect) },
                                    modifier = Modifier.fillMaxWidth().height(52.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Gold),
                                ) {
                                    Text("⚡ Apply Best IP", color = BgDark, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IspProfileCard(
    profile: IspProfile,
    onScan: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BgCard)
            .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(profile.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text("${profile.goodCidrs.size} رنج خوب", color = TextSecond, fontSize = 11.sp)
        }
        TextButton(onClick = onDelete) {
            Text("حذف", color = RedBad, fontSize = 12.sp)
        }
        Button(
            onClick = onScan,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Gold),
        ) {
            Text("اسکن", color = BgDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

@Composable
private fun IpCard(result: CandidateResult, isBest: Boolean, onSelect: () -> Unit) {
    val quality = when {
        !result.isSuccess        -> IpQuality.BAD
        result.uploadKBps >= 200 -> IpQuality.GOOD
        result.uploadKBps >= 80  -> IpQuality.MID
        else                     -> IpQuality.MID
    }
    val borderColor = when {
        isBest                    -> Gold
        quality == IpQuality.GOOD -> GreenGood.copy(alpha = .5f)
        quality == IpQuality.MID  -> YellowMid.copy(alpha = .4f)
        else                      -> RedBad.copy(alpha = .3f)
    }
    val tagColor = when (quality) { IpQuality.GOOD -> GreenGood; IpQuality.MID -> YellowMid; IpQuality.BAD -> RedBad }
    val tagText  = when (quality) { IpQuality.GOOD -> "✓ GOOD";  IpQuality.MID -> "~ OK";    IpQuality.BAD -> "✗ FAIL" }

    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isBest) Color(0xFF1A1408) else BgCard)
            .border(if (isBest) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(result.ip, color = if (result.isSuccess) TextPrimary else TextSecond, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Surface(shape = RoundedCornerShape(6.dp), color = tagColor.copy(alpha = .15f)) {
                Text(tagText, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = tagColor)
            }
        }
        if (result.isSuccess) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatChip("Ping",     "${result.latencyMs}ms",      Gold)
                StatChip("↑ Upload", "${result.uploadKBps} KB/s",  GreenGood)
                StatChip("↓ Dn",    "${result.downloadKBps} KB/s", Color(0xFF5BA4CF))
            }
            if (!isBest) {
                TextButton(onClick = onSelect, modifier = Modifier.align(Alignment.End)) {
                    Text("📌 Use this IP", color = GoldDim, fontSize = 12.sp)
                }
            } else {
                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(painterResource(R.drawable.ic_action_done), null, tint = Gold, modifier = Modifier.size(14.dp))
                    Text("🥇 Best", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
