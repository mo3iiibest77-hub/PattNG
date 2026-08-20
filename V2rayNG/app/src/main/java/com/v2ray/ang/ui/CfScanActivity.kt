package com.v2ray.ang.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.senpai.CloudflareScanner
import com.v2ray.ang.senpai.IspManager
import com.v2ray.ang.senpai.IspProfile
import com.v2ray.ang.service.CfScanService
import com.v2ray.ang.ui.compose.AppTheme

// ══════════════════════════════════════════════════════════════════════════════
// ── IP SCAN palette  (طلایی — تم اصلی)
// ══════════════════════════════════════════════════════════════════════════════
private val S_Bg        = Color(0xFF0D0D0F)
private val S_Card      = Color(0xFF13131A)
private val S_Gold      = Color(0xFFC9A84C)
private val S_GoldDim   = Color(0xFF8A6F2E)
private val S_GoldGlow  = Color(0xFFE8C96A)
private val S_Green     = Color(0xFF4CAF82)
private val S_Yellow    = Color(0xFFD4A843)
private val S_Red       = Color(0xFFCF4848)
private val S_Blue      = Color(0xFF5BA4CF)
private val S_TextPri   = Color(0xFFF0EEE8)
private val S_TextSec   = Color(0xFF8A8878)
private val S_Border    = Color(0xFF252530)

// ══════════════════════════════════════════════════════════════════════════════
// ── DISCOVERY palette  (سبز-ماتریکس — کاملاً متضاد)
// ══════════════════════════════════════════════════════════════════════════════
private val D_Bg        = Color(0xFF0B0E1A)   // تقریباً مشکی سبز
private val D_Card      = Color(0xFF111829)
private val D_Bright    = Color(0xFF00D4FF)
private val D_Mid       = Color(0xFF0099BB)
private val D_Dim       = Color(0xFF0D1F33)
private val D_TextPri   = Color(0xFFF0F4FF)
private val D_TextSec   = Color(0xFF607090)
private val D_Border    = Color(0xFF1A2A4A)
private val D_Fail      = Color(0xFF080C18)
private val D_FailBrd   = Color(0xFF0D1530)
private val D_Accent2   = Color(0xFF00E5A0)

// ══════════════════════════════════════════════════════════════════════════════

private enum class ScanPhase { ISP_SELECT, DISCOVERY, SCANNING, DONE }

data class DiscoveryRow(val cidr: String, val state: DiscState)
enum class DiscState { SCANNING, GOOD, FAIL }

data class IpRow(
    val ip: String,
    val state: IpState,
    val latency: Long  = -1L,
    val upload: Long   = 0L,
    val download: Long = 0L,
)
enum class IpState { SCANNING, GOOD, MID, FAIL }

// ══════════════════════════════════════════════════════════════════════════════
class CfScanActivity : ComponentActivity() {

    companion object {
        fun start(ctx: Context, guid: String) =
            ctx.startActivity(
                Intent(ctx, CfScanActivity::class.java)
                    .putExtra("guid", guid)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
    }

    private lateinit var guid: String

    // ── Compose state ─────────────────────────────────────────────────────────
    private val phase        = mutableStateOf(ScanPhase.ISP_SELECT)
    private val ispName      = mutableStateOf("")
    private val discRows     = mutableStateListOf<DiscoveryRow>()
    private val discDone     = mutableIntStateOf(0)
    private val discTotal    = mutableIntStateOf(0)
    private val discGood     = mutableIntStateOf(0)
    private val ipRows       = mutableStateListOf<IpRow>()
    private val scanDone     = mutableIntStateOf(0)
    private val scanTotal    = mutableIntStateOf(30)
    private val bestIp       = mutableStateOf<String?>(null)
    private val ispProfiles  = mutableStateListOf<IspProfile>()

    // ── Broadcast receiver ────────────────────────────────────────────────────
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
                    val idx = discRows.indexOfFirst { it.cidr == cidr }
                    val ns  = if (responded) DiscState.GOOD else DiscState.FAIL
                    if (idx >= 0) discRows[idx] = discRows[idx].copy(state = ns)
                    else discRows.add(DiscoveryRow(cidr, ns))
                }

                CfScanService.BROADCAST_DISC_FINISH -> {
                    val count = intent.getIntExtra("count", 0)
                    refreshProfiles()
                    if (count > 0) {
                        phase.value = ScanPhase.SCANNING
                        ipRows.clear(); scanDone.intValue = 0; bestIp.value = null
                        CfScanService.startScan(this@CfScanActivity, guid, ispName.value)
                    } else {
                        phase.value = ScanPhase.ISP_SELECT
                    }
                }

                CfScanService.BROADCAST_PROGRESS -> {
                    val ip      = intent.getStringExtra("ip") ?: return
                    val latency = intent.getLongExtra("latency", -1L)
                    val upload  = intent.getLongExtra("upload", 0L)
                    val dl      = intent.getLongExtra("download", 0L)
                    val success = intent.getBooleanExtra("success", false)
                    scanDone.intValue  = intent.getIntExtra("done", 0)
                    scanTotal.intValue = intent.getIntExtra("total", 30)
                    val st  = when { !success -> IpState.FAIL; upload >= 80 -> IpState.GOOD; else -> IpState.MID }
                    val row = IpRow(ip, st, latency, upload, dl)
                    val idx = ipRows.indexOfFirst { it.ip == ip }
                    if (idx >= 0) ipRows[idx] = row else ipRows.add(row)
                }

                CfScanService.BROADCAST_FINISH -> {
                    phase.value = ScanPhase.DONE
                    bestIp.value = intent.getStringExtra("best_ip")
                    val sorted = ipRows.sortedWith(
                        compareBy<IpRow> { it.state.ordinal }.thenByDescending { it.upload }
                    )
                    ipRows.clear(); ipRows.addAll(sorted)
                }
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        guid = intent.getStringExtra("guid") ?: run { finish(); return }

        registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(CfScanService.BROADCAST_DISC_PROGRESS)
                addAction(CfScanService.BROADCAST_DISC_FINISH)
                addAction(CfScanService.BROADCAST_PROGRESS)
                addAction(CfScanService.BROADCAST_FINISH)
            },
            RECEIVER_NOT_EXPORTED
        )
        refreshProfiles()

        setContent {
            AppTheme {
                CfScanScreen(
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
                    onBack         = { finish() },
                    onStop         = {
                        CfScanService.stop(this)
                        phase.value = ScanPhase.ISP_SELECT
                    },
                    onStartDiscovery = { name ->
                        ispName.value = name
                        discRows.clear()
                        discDone.intValue  = 0
                        discGood.intValue  = 0
                        discTotal.intValue = 0
                        phase.value = ScanPhase.DISCOVERY
                        CfScanService.startDiscovery(this, guid, name)
                    },
                    onStartScan = { name ->
                        ispName.value = name
                        ipRows.clear()
                        scanDone.intValue = 0
                        bestIp.value = null
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

    // ── وقتی user برمیگرده — phase رو از service state بخون ─────────────────
    override fun onResume() {
        super.onResume()
        refreshProfiles()
        // اگه service داره اجرا میشه و ما ISP_SELECT هستیم → restore
        val svc = CfScanService.currentState
        if (svc != null && phase.value == ScanPhase.ISP_SELECT) {
            ispName.value = svc.ispName
            phase.value = when (svc.mode) {
                "discovery" -> ScanPhase.DISCOVERY
                "scan"      -> ScanPhase.SCANNING
                else        -> ScanPhase.ISP_SELECT
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    private fun refreshProfiles() {
        ispProfiles.clear()
        ispProfiles.addAll(IspManager.loadAll(this))
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// ── Root Screen
// ══════════════════════════════════════════════════════════════════════════════
@Composable
private fun CfScanScreen(
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
    val isDiscovery = phase == ScanPhase.DISCOVERY
    val isRunning   = phase == ScanPhase.DISCOVERY || phase == ScanPhase.SCANNING

    // رنگ‌بندی کل صفحه بر اساس phase
    val screenBg = if (isDiscovery) D_Bg else S_Bg
    val topAccent = if (isDiscovery) D_Bright else S_Gold

    Box(Modifier.fillMaxSize().background(screenBg)) {
        Column(Modifier.fillMaxSize()) {

            // ── TopBar ────────────────────────────────────────────────────────
            TopBar(
                phase        = phase,
                ispName      = ispNameState.value,
                isRunning    = isRunning,
                isDiscovery  = isDiscovery,
                onBack       = onBack,
                onStop       = onStop,
            )

            when (phase) {
                ScanPhase.ISP_SELECT -> IspSelectScreen(
                    profiles         = ispProfiles,
                    onStartDiscovery = onStartDiscovery,
                    onStartScan      = onStartScan,
                    onDelete         = onDeleteIsp,
                )
                ScanPhase.DISCOVERY  -> DiscoveryScreen(
                    discRows  = discRows,
                    done      = discDone,
                    total     = discTotal,
                    goodCount = discGood,
                )
                ScanPhase.SCANNING,
                ScanPhase.DONE       -> IpScanScreen(
                    ispName  = ispNameState.value,
                    phase    = phase,
                    ipRows   = ipRows,
                    done     = scanDone,
                    total    = scanTotal,
                    bestIp   = bestIp,
                    onApply  = onApply,
                )
            }
        }
    }
}

// ── TopBar ────────────────────────────────────────────────────────────────────
@Composable
private fun TopBar(
    phase: ScanPhase,
    ispName: String,
    isRunning: Boolean,
    isDiscovery: Boolean,
    onBack: () -> Unit,
    onStop: () -> Unit,
) {
    val bg     = if (isDiscovery) D_Card else Color(0xFF0F0F17)
    val accent = if (isDiscovery) D_Bright else S_Gold
    val title  = if (isDiscovery) "[ RANGE DISCOVERY ]" else "⚡ QuietStorm Scanner"
    val sub    = when (phase) {
        ScanPhase.DISCOVERY -> ">> mapping cloudflare ranges for $ispName"
        ScanPhase.SCANNING  -> "🔍 targeted scan — $ispName"
        ScanPhase.DONE      -> "✅ scan complete — $ispName"
        else                -> "select ISP to begin"
    }
    val titleFont = if (isDiscovery) FontFamily.Monospace else FontFamily.Default

    Box(
        Modifier.fillMaxWidth().background(bg)
            .padding(top = 48.dp, bottom = 14.dp, start = 16.dp, end = 16.dp)
    ) {
        IconButton(onClick = onBack, Modifier.align(Alignment.CenterStart)) {
            Icon(painterResource(R.drawable.ic_arrow_back_24dp), null, tint = accent)
        }
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, color = accent, fontSize = 16.sp,
                fontWeight = FontWeight.Bold, fontFamily = titleFont)
            Text(sub, color = if (isDiscovery) D_TextSec else S_TextSec, fontSize = 11.sp,
                fontFamily = if (isDiscovery) FontFamily.Monospace else FontFamily.Default)
        }
        if (isRunning) {
            IconButton(onClick = onStop, Modifier.align(Alignment.CenterEnd)) {
                Icon(painterResource(R.drawable.ic_stop_24dp), null,
                    tint = if (isDiscovery) D_Bright else S_Red)
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// ── ISP Select Screen  (تم طلایی)
// ══════════════════════════════════════════════════════════════════════════════
@Composable
private fun IspSelectScreen(
    profiles: List<IspProfile>,
    onStartDiscovery: (String) -> Unit,
    onStartScan: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                "اینترنت گوشیت رو روی یه خط بذار،\nبعد ISP اون خط رو انتخاب یا وارد کن:",
                color = S_TextPri, fontSize = 14.sp, lineHeight = 22.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        if (profiles.isNotEmpty()) {
            item {
                Text("📁 ISP های ذخیره‌شده:", color = S_Gold,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            items(profiles) { p ->
                SavedIspCard(
                    profile = p,
                    onScan = { onStartScan(p.name) },
                    onRediscover = { onStartDiscovery(p.name) },
                    onDelete = { onDelete(p.name) },
                    onAddManualCidr = { cidr -> IspManager.addManualCidr(ctx, p.name, cidr); refreshProfiles() },
                    onRemoveManualCidr = { cidr -> IspManager.removeManualCidr(ctx, p.name, cidr); refreshProfiles() }
                ) }
                )
            }
            item { HorizontalDivider(color = S_Border, modifier = Modifier.padding(vertical = 4.dp)) }
        }

        item {
            Text("+ ISP جدید:", color = S_Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label       = { Text("نام ISP", color = S_TextSec) },
                placeholder = { Text("همراه اول، ایرانسل، پیشگامان...",
                    color = S_TextSec.copy(alpha = .4f)) },
                singleLine  = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {}),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = S_Gold,
                    unfocusedBorderColor = S_Border,
                    focusedTextColor     = S_TextPri,
                    unfocusedTextColor   = S_TextPri,
                    cursorColor          = S_Gold,
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick  = { if (input.isNotBlank()) onStartDiscovery(input.trim()) },
                enabled  = input.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape    = RoundedCornerShape(12.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = S_Gold),
            ) {
                Text("🔍 شروع Discovery", color = S_Bg,
                    fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun SavedIspCard(
    profile: IspProfile,
    onScan: () -> Unit,
    onRediscover: () -> Unit,
    onDelete: () -> Unit,
    onAddManualCidr: (String) -> Unit,
    onRemoveManualCidr: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var showAddCidr by remember { mutableStateOf(false) }
    var cidrInput by remember { mutableStateOf("") }
    val hasPartial = profile.lastScannedIndex > 0

    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BgCard)
            .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(profile.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${profile.goodCidrs.size} رنج", color = GreenGood, fontSize = 11.sp)
                    if (profile.manualCidrs.isNotEmpty())
                        Text("+${profile.manualCidrs.size} دستی", color = Gold, fontSize = 11.sp)
                    if (hasPartial)
                        Text("(ناتمام ${profile.lastScannedIndex})", color = YellowMid, fontSize = 10.sp)
                }
            }
            IconButton(onClick = { expanded = !expanded }, Modifier.size(32.dp)) {
                Icon(
                    painterResource(if (expanded) R.drawable.ic_expand_less_24dp else R.drawable.ic_expand_more_24dp),
                    null, tint = TextSecond, modifier = Modifier.size(18.dp)
                )
            }
            TextButton(onClick = onDelete) { Text("حذف", color = RedBad, fontSize = 11.sp) }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TextButton(
                onClick = onRediscover,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColors(contentColor = GoldDim)
            ) { Text(if (hasPartial) "ادامه Discovery" else "Discovery مجدد", fontSize = 11.sp) }
            Button(
                onClick = onScan,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Gold)
            ) { Text("اسکن IP", color = BgDark, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                HorizontalDivider(color = CardBorder, modifier = Modifier.padding(bottom = 8.dp))
                if (profile.manualCidrs.isNotEmpty()) {
                    Text("رنج‌های دستی:", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    profile.manualCidrs.forEach { cidr ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("• $cidr", color = Gold, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            IconButton(onClick = { onRemoveManualCidr(cidr) }, Modifier.size(24.dp)) {
                                Icon(painterResource(R.drawable.ic_delete_24dp), null, tint = RedBad, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                if (profile.goodCidrs.isNotEmpty()) {
                    Text("رنج‌های کشف‌شده (${profile.goodCidrs.size}):", color = GreenGood, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    profile.goodCidrs.take(10).forEach { cidr ->
                        Text("• $cidr", color = TextSecond, fontSize = 11.sp, modifier = Modifier.padding(vertical = 1.dp))
                    }
                    if (profile.goodCidrs.size > 10)
                        Text("... و ${profile.goodCidrs.size - 10} رنج دیگه", color = TextSecond.copy(.5f), fontSize = 10.sp)
                }
                Spacer(Modifier.height(8.dp))
                if (showAddCidr) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = cidrInput,
                            onValueChange = { cidrInput = it },
                            placeholder = { Text("104.16.0.0/12", color = TextSecond.copy(.4f), fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Gold, unfocusedBorderColor = CardBorder,
                                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Gold,
                            ),
                        )
                        Button(
                            onClick = {
                                if (cidrInput.isNotBlank()) { onAddManualCidr(cidrInput.trim()); cidrInput = ""; showAddCidr = false }
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Gold),
                        ) { Text("اضافه", color = BgDark, fontSize = 12.sp) }
                    }
                } else {
                    TextButton(onClick = { showAddCidr = true }, colors = ButtonDefaults.textButtonColors(contentColor = Gold)) {
                        Text("+ اضافه کردن رنج دستی", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}


@Composable
private fun DiscoveryScreen(
    discRows: List<DiscoveryRow>,
    done: Int, total: Int, goodCount: Int,
) {
    val listState = rememberLazyListState()
    val progress  = if (total > 0) done.toFloat() / total else 0f

    Column(Modifier.fillMaxSize()) {

        // ── Header ────────────────────────────────────────────────────────────
        Column(
            Modifier.fillMaxWidth()
                .background(D_Card)
                .padding(16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("$done / $total", color = D_TextSec,
                    fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                Text("[ ${goodCount} GOOD ]", color = D_Accent2,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(8.dp))
            // progress bar سبز ماتریکس
            Box(
                Modifier.fillMaxWidth().height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(D_Dim)
            ) {
                Box(
                    Modifier.fillMaxWidth(progress).fillMaxHeight()
                        .background(
                            Brush.horizontalGradient(listOf(D_Mid, D_Bright))
                        )
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                ">> scanning cloudflare ranges — runs in background even if you leave",
                color = D_TextSec, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // ── Live list ─────────────────────────────────────────────────────────
        LazyColumn(
            state            = listState,
            modifier         = Modifier.weight(1f),
            contentPadding   = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            items(discRows.reversed()) { row ->
                DiscoveryRowCard(row)
            }
        }
    }
}

@Composable
private fun DiscoveryRowCard(row: DiscoveryRow) {
    val (bg, brdColor, txt, prefix) = when (row.state) {
        DiscState.SCANNING -> listOf(Color(0xFF041209), D_Dim, D_Mid, ">")
        DiscState.GOOD     -> listOf(Color(0xFF061A14), D_Accent2, D_Accent2, "GOOD")
        DiscState.FAIL     -> listOf(D_Fail, D_FailBrd, D_TextSec.copy(alpha = .4f), "··")
    }
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(bg as Color)
            .border(1.dp, brdColor as Color, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            prefix as String,
            color      = txt as Color,
            fontSize   = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier   = Modifier.width(20.dp)
        )
        Text(
            row.cidr,
            color      = txt,
            fontSize   = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier   = Modifier.weight(1f)
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// ── IP Scan Screen  (تم طلایی — اصلی)
// ══════════════════════════════════════════════════════════════════════════════
@Composable
private fun IpScanScreen(
    ispName: String,
    phase: ScanPhase,
    ipRows: List<IpRow>,
    done: Int, total: Int,
    bestIp: String?,
    onApply: (String) -> Unit,
) {
    val listState = rememberLazyListState()

    Column(Modifier.fillMaxSize()) {

        // ── Header ────────────────────────────────────────────────────────────
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // ISP badge
                Surface(shape = RoundedCornerShape(8.dp), color = S_Gold.copy(alpha = .12f)) {
                    Text(
                        "📶 $ispName",
                        color    = S_Gold,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                if (phase == ScanPhase.DONE) {
                    val good = ipRows.count { it.state == IpState.GOOD || it.state == IpState.MID }
                    Surface(shape = RoundedCornerShape(8.dp), color = S_Green.copy(alpha = .12f)) {
                        Text(
                            "✅ $good IP خوب",
                            color    = S_Green,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            if (phase == ScanPhase.SCANNING) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("در حال اسکن...", color = S_TextSec, fontSize = 11.sp)
                    Text("$done / $total", color = S_Gold,
                        fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                val pct = if (total > 0) done.toFloat() / total else 0f
                LinearProgressIndicator(
                    progress    = { pct },
                    modifier    = Modifier.fillMaxWidth().height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color       = S_Gold,
                    trackColor  = S_Border,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "حتی اگه بری بیرون اسکن ادامه داره — برگرد و نتیجه رو ببین",
                    color    = S_TextSec.copy(alpha = .5f),
                    fontSize = 10.sp
                )
            }
        }

        // ── IP list ───────────────────────────────────────────────────────────
        LazyColumn(
            state               = listState,
            modifier            = Modifier.weight(1f),
            contentPadding      = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(ipRows) { row ->
                IpRowCard(
                    row    = row,
                    isBest = row.ip == bestIp,
                    onApply = { onApply(row.ip) }
                )
            }
        }

        // ── Apply best button ─────────────────────────────────────────────────
        AnimatedVisibility(bestIp != null) {
            Box(
                Modifier.fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, S_Bg))
                    )
                    .padding(20.dp)
            ) {
                Button(
                    onClick  = { bestIp?.let(onApply) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape    = RoundedCornerShape(14.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = S_Gold),
                ) {
                    Text("⚡ Apply Best IP", color = S_Bg,
                        fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun IpRowCard(row: IpRow, isBest: Boolean, onApply: () -> Unit) {
    val scanning = row.state == IpState.SCANNING
    val (bg, brdColor, tagColor, tagTxt) = when {
        isBest                     -> listOf(Color(0xFF1A1408), S_Gold,   S_Gold,   "🥇 Best")
        row.state == IpState.GOOD  -> listOf(Color(0xFF0F1A10), S_Green,  S_Green,  "✓ GOOD")
        row.state == IpState.MID   -> listOf(S_Card,            S_Yellow, S_Yellow, "~ OK")
        row.state == IpState.SCANNING -> listOf(Color(0xFF0D1610), S_Green.copy(.4f), S_Green, "⟳")
        else                       -> listOf(S_Card,            S_Border, S_TextSec, "✗ FAIL")
    }
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg as Color)
            .border(if (isBest) 1.5.dp else 1.dp, brdColor as Color, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text(
                row.ip,
                color      = if (row.state != IpState.FAIL) S_TextPri else S_TextSec,
                fontWeight = FontWeight.Bold,
                fontSize   = 14.sp
            )
            Surface(shape = RoundedCornerShape(6.dp), color = (tagColor as Color).copy(.15f)) {
                Text(
                    tagTxt as String,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    color    = tagColor,
                    fontSize = 11.sp
                )
            }
        }
        if (!scanning && row.state != IpState.FAIL) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatChip("Ping",  "${row.latency}ms",    S_Gold)
                StatChip("↑ Up",  "${row.upload} KB/s",  S_Green)
                StatChip("↓ Dn",  "${row.download} KB/s", S_Blue)
            }
            if (!isBest) {
                TextButton(
                    onClick  = onApply,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("📌 Use this IP", color = S_GoldDim, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, valueColor: Color) {
    Column {
        Text(label, color = S_TextSec, fontSize = 10.sp)
        Text(value, color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
