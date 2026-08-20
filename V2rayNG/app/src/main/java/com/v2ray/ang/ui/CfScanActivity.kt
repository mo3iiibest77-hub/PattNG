package com.v2ray.ang.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.v2ray.ang.service.CfScanService
import com.v2ray.ang.ui.compose.AppTheme

// ── Colors ────────────────────────────────────────────────────────────────────
private val BgDark      = Color(0xFF0D0D0F)
private val BgCard      = Color(0xFF13131A)
private val BgDiscCard  = Color(0xFF0D0F0D)   // کمی سبز‌تر برای Discovery
private val Gold        = Color(0xFFC9A84C)
private val GoldDim     = Color(0xFF8A6F2E)
private val GoldGlow    = Color(0xFFE8C96A)
private val GreenGood   = Color(0xFF4CAF82)
private val GreenScan   = Color(0xFF2ECC71)   // رنگ "در حال اسکن"
private val YellowMid   = Color(0xFFD4A843)
private val RedBad      = Color(0xFFCF4848)
private val TextPrimary = Color(0xFFF0EEE8)
private val TextSecond  = Color(0xFF8A8878)
private val CardBorder  = Color(0xFF252530)
private val BorderScan  = Color(0xFF1A3A2A)   // border سبز تیره برای در حال اسکن

private enum class ScanPhase { ISP_SELECT, DISCOVERY, SCANNING, DONE }

data class DiscoveryRow(val cidr: String, val state: DiscState)
enum class DiscState { SCANNING, GOOD, FAIL }

data class IpRow(
    val ip: String,
    val state: IpState,
    val latency: Long = -1L,
    val upload: Long = 0L,
    val download: Long = 0L,
)
enum class IpState { SCANNING, GOOD, MID, FAIL }

class CfScanActivity : ComponentActivity() {
    companion object {
        fun start(ctx: Context, guid: String) =
            ctx.startActivity(Intent(ctx, CfScanActivity::class.java).putExtra("guid", guid))
    }

    private lateinit var guid: String

    // State
    private val phase       = mutableStateOf(ScanPhase.ISP_SELECT)
    private val ispName     = mutableStateOf("")
    private val discRows    = mutableStateListOf<DiscoveryRow>()
    private val discDone    = mutableIntStateOf(0)
    private val discTotal   = mutableIntStateOf(0)
    private val discGood    = mutableIntStateOf(0)
    private val ipRows      = mutableStateListOf<IpRow>()
    private val scanDone    = mutableIntStateOf(0)
    private val scanTotal   = mutableIntStateOf(30)
    private val bestIp      = mutableStateOf<String?>(null)
    private val ispProfiles = mutableStateListOf<IspProfile>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                CfScanService.BROADCAST_DISC_PROGRESS -> {
                    val done      = intent.getIntExtra("done", 0)
                    val total     = intent.getIntExtra("total", 0)
                    val cidr      = intent.getStringExtra("cidr") ?: return
                    val responded = intent.getBooleanExtra("responded", false)
                    discDone.intValue  = done
                    discTotal.intValue = total
                    if (responded) discGood.intValue++
                    // آپدیت row
                    val idx = discRows.indexOfFirst { it.cidr == cidr }
                    val newState = if (responded) DiscState.GOOD else DiscState.FAIL
                    if (idx >= 0) discRows[idx] = discRows[idx].copy(state = newState)
                    else discRows.add(DiscoveryRow(cidr, newState))
                }
                CfScanService.BROADCAST_DISC_FINISH -> {
                    val count = intent.getIntExtra("count", 0)
                    if (count > 0) {
                        phase.value = ScanPhase.SCANNING
                        ipRows.clear(); scanDone.intValue = 0; bestIp.value = null
                        CfScanService.startScan(this@CfScanActivity, guid, ispName.value)
                    } else {
                        phase.value = ScanPhase.ISP_SELECT
                    }
                    refreshProfiles()
                }
                CfScanService.BROADCAST_PROGRESS -> {
                    val done    = intent.getIntExtra("done", 0)
                    val total   = intent.getIntExtra("total", 0)
                    val ip      = intent.getStringExtra("ip") ?: return
                    val latency = intent.getLongExtra("latency", -1L)
                    val upload  = intent.getLongExtra("upload", 0L)
                    val dl      = intent.getLongExtra("download", 0L)
                    val success = intent.getBooleanExtra("success", false)
                    scanDone.intValue  = done
                    scanTotal.intValue = total
                    val state = when {
                        !success         -> IpState.FAIL
                        upload >= 80     -> IpState.GOOD
                        else             -> IpState.MID
                    }
                    val idx = ipRows.indexOfFirst { it.ip == ip }
                    val row = IpRow(ip, state, latency, upload, dl)
                    if (idx >= 0) ipRows[idx] = row else ipRows.add(row)
                }
                CfScanService.BROADCAST_FINISH -> {
                    phase.value = ScanPhase.DONE
                    bestIp.value = intent.getStringExtra("best_ip")
                    // مرتب‌سازی: خوب‌ها اول
                    val sorted = ipRows.sortedWith(
                        compareBy<IpRow> { it.state.ordinal }.thenByDescending { it.upload }
                    )
                    ipRows.clear(); ipRows.addAll(sorted)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        guid = intent.getStringExtra("guid") ?: run { finish(); return }

        val filter = IntentFilter().apply {
            addAction(CfScanService.BROADCAST_DISC_PROGRESS)
            addAction(CfScanService.BROADCAST_DISC_FINISH)
            addAction(CfScanService.BROADCAST_PROGRESS)
            addAction(CfScanService.BROADCAST_FINISH)
        }
        registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        refreshProfiles()

        setContent {
            AppTheme {
                CfScanScreen(
                    guid           = guid,
                    phase          = phase.value,
                    ispNameState   = ispName,
                    discRows       = discRows,
                    discDone       = discDone.intValue,
                    discTotal      = discTotal.intValue,
                    discGood       = discGood.intValue,
                    ipRows         = ipRows,
                    scanDone       = scanDone.intValue,
                    scanTotal      = scanTotal.intValue,
                    bestIp         = bestIp.value,
                    ispProfiles    = ispProfiles,
                    onBack         = { CfScanService.stop(this); finish() },
                    onStop         = { CfScanService.stop(this); phase.value = ScanPhase.ISP_SELECT },
                    onStartDiscovery = { name ->
                        ispName.value = name
                        discRows.clear(); discDone.intValue = 0; discGood.intValue = 0
                        phase.value = ScanPhase.DISCOVERY
                        CfScanService.startDiscovery(this, guid, name)
                    },
                    onStartScan = { name ->
                        ispName.value = name
                        ipRows.clear(); scanDone.intValue = 0; bestIp.value = null
                        phase.value = ScanPhase.SCANNING
                        CfScanService.startScan(this, guid, name)
                    },
                    onApply = { ip ->
                        CloudflareScanner.applyBestIp(guid, ip)
                        setResult(RESULT_OK, Intent().putExtra("applied_ip", ip))
                        finish()
                    },
                    onDeleteIsp = { name ->
                        IspManager.deleteProfile(this, name)
                        refreshProfiles()
                    }
                )
            }
        }
    }

    private fun refreshProfiles() {
        ispProfiles.clear()
        ispProfiles.addAll(IspManager.loadAll(this))
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }
}

@Composable
private fun CfScanScreen(
    guid: String,
    phase: ScanPhase,
    ispNameState: MutableState<String>,
    discRows: List<DiscoveryRow>,
    discDone: Int, discTotal: Int, discGood: Int,
    ipRows: List<IpRow>,
    scanDone: Int, scanTotal: Int,
    bestIp: String?,
    ispProfiles: List<IspProfile>,
    onBack: () -> Unit,
    onStop: () -> Unit,
    onStartDiscovery: (String) -> Unit,
    onStartScan: (String) -> Unit,
    onApply: (String) -> Unit,
    onDeleteIsp: (String) -> Unit,
) {
    var ispInput by remember { mutableStateOf("") }
    val isRunning = phase == ScanPhase.DISCOVERY || phase == ScanPhase.SCANNING

    Box(Modifier.fillMaxSize().background(BgDark)) {
        Column(Modifier.fillMaxSize()) {

            // ── TopBar ────────────────────────────────────────────────────────
            Box(
                Modifier.fillMaxWidth()
                    .background(Brush.horizontalGradient(listOf(BgDark, Color(0xFF1A1508), BgDark)))
                    .padding(top = 48.dp, bottom = 16.dp, start = 16.dp, end = 16.dp)
            ) {
                IconButton(onClick = onBack, Modifier.align(Alignment.CenterStart)) {
                    Icon(painterResource(R.drawable.ic_arrow_back_24dp), null, tint = Gold)
                }
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🌩️ QuietStorm Scanner", color = GoldGlow, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        when (phase) {
                            ScanPhase.DISCOVERY -> "⚡ Discovery — ${ispNameState.value}"
                            ScanPhase.SCANNING  -> "🔍 Targeted Scan — ${ispNameState.value}"
                            else -> "⚡ Cloudflare IP Scanner"
                        },
                        color = TextSecond, fontSize = 11.sp
                    )
                }
                if (isRunning) {
                    IconButton(onClick = onStop, Modifier.align(Alignment.CenterEnd)) {
                        Icon(painterResource(R.drawable.ic_stop_24dp), null, tint = RedBad)
                    }
                }
            }

            when (phase) {
                // ── ISP Select ────────────────────────────────────────────────
                ScanPhase.ISP_SELECT -> {
                    LazyColumn(
                        Modifier.weight(1f),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            Text(
                                "اینترنت گوشیت رو روی یه خط بذار، بعد ISP اون خط رو وارد کن:",
                                color = TextPrimary, fontSize = 14.sp, lineHeight = 22.sp
                            )
                        }
                        if (ispProfiles.isNotEmpty()) {
                            item { Text("ISP های ذخیره‌شده:", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                            items(ispProfiles) { p ->
                                IspCard(p,
                                    onScan = { onStartScan(p.name) },
                                    onRediscover = { onStartDiscovery(p.name) },
                                    onDelete = { onDeleteIsp(p.name) }
                                )
                            }
                            item { HorizontalDivider(color = CardBorder) }
                        }
                        item {
                            Text("+ ISP جدید:", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = ispInput,
                                onValueChange = { ispInput = it },
                                label = { Text("نام ISP", color = TextSecond) },
                                placeholder = { Text("همراه اول، ایرانسل، پیشگامان...", color = TextSecond.copy(alpha = .4f)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = {}),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Gold, unfocusedBorderColor = CardBorder,
                                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Gold,
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = { if (ispInput.isNotBlank()) onStartDiscovery(ispInput.trim()) },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Gold),
                                enabled = ispInput.isNotBlank()
                            ) {
                                Text("🔍 شروع Discovery", color = BgDark, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                        }
                    }
                }

                // ── Discovery Phase ───────────────────────────────────────────
                ScanPhase.DISCOVERY -> {
                    val listState = rememberLazyListState()
                    Column(Modifier.fillMaxSize()) {
                        // Progress header
                        Column(Modifier.fillMaxWidth().background(BgDiscCard).padding(16.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("$discDone / $discTotal رنج", color = TextSecond, fontSize = 12.sp)
                                Text("✅ $discGood رنج خوب", color = GreenGood, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(8.dp))
                            val progress = if (discTotal > 0) discDone.toFloat() / discTotal else 0f
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                color = GreenGood, trackColor = CardBorder,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "این اسکن یه بار انجام میشه و ذخیره میشه — حتی اگه بری بیرون ادامه داره",
                                color = TextSecond.copy(alpha = .6f), fontSize = 11.sp, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        // Live list
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(discRows.reversed()) { row ->
                                DiscoveryRowCard(row)
                            }
                        }
                    }
                }

                // ── Scanning + Done ───────────────────────────────────────────
                ScanPhase.SCANNING, ScanPhase.DONE -> {
                    val listState = rememberLazyListState()
                    Column(Modifier.fillMaxSize()) {
                        // ISP badge + progress
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Surface(shape = RoundedCornerShape(8.dp), color = Gold.copy(alpha = .15f)) {
                                    Text("📶 ${ispNameState.value}", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                                }
                                if (phase == ScanPhase.DONE) {
                                    val good = ipRows.count { it.state == IpState.GOOD || it.state == IpState.MID }
                                    Surface(shape = RoundedCornerShape(8.dp), color = GreenGood.copy(alpha = .15f)) {
                                        Text("✅ $good IP خوب", color = GreenGood, fontSize = 12.sp,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                                    }
                                }
                            }
                            if (phase == ScanPhase.SCANNING) {
                                Spacer(Modifier.height(8.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("🔍 در حال اسکن...", color = TextSecond, fontSize = 11.sp)
                                    Text("$scanDone / $scanTotal", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.height(4.dp))
                                val progress = if (scanTotal > 0) scanDone.toFloat() / scanTotal else 0f
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                                    color = Gold, trackColor = CardBorder,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "حتی اگه بری بیرون اسکن ادامه داره",
                                    color = TextSecond.copy(alpha = .5f), fontSize = 10.sp
                                )
                            }
                        }
                        // IP list
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(ipRows) { row ->
                                IpRowCard(row, isBest = row.ip == bestIp, onApply = { onApply(row.ip) })
                            }
                        }
                        // Apply best
                        AnimatedVisibility(bestIp != null) {
                            Box(
                                Modifier.fillMaxWidth()
                                    .background(Brush.verticalGradient(listOf(Color.Transparent, BgDark)))
                                    .padding(20.dp)
                            ) {
                                Button(
                                    onClick = { bestIp?.let(onApply) },
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
private fun DiscoveryRowCard(row: DiscoveryRow) {
    val (bg, border, textColor, label) = when (row.state) {
        DiscState.SCANNING -> listOf(Color(0xFF0D1A10), BorderScan, GreenScan, "⟳")
        DiscState.GOOD     -> listOf(Color(0xFF0F1A0F), GreenGood.copy(alpha = .4f), GreenGood, "✓")
        DiscState.FAIL     -> listOf(BgCard, CardBorder, TextSecond, "·")
    }
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg as Color)
            .border(1.dp, border as Color, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label as String, color = textColor as Color, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(16.dp))
        Text(row.cidr, color = textColor, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun IpRowCard(row: IpRow, isBest: Boolean, onApply: () -> Unit) {
    val scanning = row.state == IpState.SCANNING
    val (bg, border, tagColor, tagText) = when {
        isBest             -> listOf(Color(0xFF1A1408), Gold, Gold, "🥇 Best")
        row.state == IpState.GOOD -> listOf(Color(0xFF0F1A10), GreenGood.copy(.5f), GreenGood, "✓ GOOD")
        row.state == IpState.MID  -> listOf(BgCard, YellowMid.copy(.4f), YellowMid, "~ OK")
        row.state == IpState.SCANNING -> listOf(Color(0xFF0D1A10), GreenScan.copy(.4f), GreenScan, "⟳ scanning")
        else               -> listOf(BgCard, CardBorder, TextSecond, "✗ FAIL")
    }
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg as Color)
            .border(if (isBest) 1.5.dp else 1.dp, border as Color, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(row.ip, color = if (row.state != IpState.FAIL) TextPrimary else TextSecond, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Surface(shape = RoundedCornerShape(6.dp), color = (tagColor as Color).copy(alpha = .15f)) {
                Text(tagText as String, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = tagColor, fontSize = 11.sp)
            }
        }
        if (!scanning && row.state != IpState.FAIL) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                StatChip("Ping", "${row.latency}ms", Gold)
                StatChip("↑", "${row.upload} KB/s", GreenGood)
                StatChip("↓", "${row.download} KB/s", Color(0xFF5BA4CF))
            }
            if (!isBest) {
                TextButton(onClick = onApply, modifier = Modifier.align(Alignment.End)) {
                    Text("📌 Use this IP", color = GoldDim, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun IspCard(profile: IspProfile, onScan: () -> Unit, onRediscover: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BgCard)
            .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(profile.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text("${profile.goodCidrs.size} رنج خوب", color = TextSecond, fontSize = 11.sp)
        }
        TextButton(onClick = onDelete) { Text("حذف", color = RedBad, fontSize = 11.sp) }
        TextButton(onClick = onRediscover) { Text("Discovery مجدد", color = GoldDim, fontSize = 11.sp) }
        Button(onClick = onScan, shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Gold)) {
            Text("اسکن", color = BgDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, valueColor: Color) {
    Column {
        Text(label, color = TextSecond, fontSize = 10.sp)
        Text(value, color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

private enum class IpQuality { GOOD, MID, BAD }
