package app.tidybox

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.tidybox.db.RecentTx
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import app.tidybox.db.TidyBoxDb
import app.tidybox.db.TxDetail
import app.tidybox.engine.TxType
import app.tidybox.engine.knownCategories
import app.tidybox.sms.backfill
import app.tidybox.sms.seenSenders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TidyTheme { App() } }
    }
}

private val PERMS = arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
private val EXPENSE = setOf("PURCHASE", "BILL", "GOV", "ATM_OUT", "TRANSFER_OUT", "INVESTMENT")
private val INCOME = setOf("SALARY", "DEPOSIT", "TRANSFER_IN", "REFUND")
private const val DAY = 86_400_000L

private fun RecentTx.month(): String =
    occurred_at?.take(7) ?: SimpleDateFormat("yyyy-MM", Locale.US).format(Date(received_at))

// ---------------------------------------------------------------- look
// A ledger, not a neon wallet: ink on cool paper, one brand green, brick for money out. The same
// on every phone (no wallpaper-derived colours), so screenshots and support match what users see.
private val Green = Color(0xFF146C5A)
private val GreenSoft = Color(0xFFD5EDE3)
private val Brick = Color(0xFFB9422C)
private val Ink = Color(0xFF16201B)
private val Paper = Color(0xFFF6F7F5)
private val PaperRaised = Color(0xFFFFFFFF)
private val Mist = Color(0xFFE6EAE7)
private val Night = Color(0xFF000000)
private val NightRaised = Color(0xFF15191A)
private val NightMist = Color(0xFF232A2B)

private val LightScheme = lightColorScheme(
    primary = Green, onPrimary = Color.White, primaryContainer = GreenSoft, onPrimaryContainer = Ink,
    secondaryContainer = Mist, onSecondaryContainer = Ink, tertiary = Green,
    error = Brick, background = Paper, onBackground = Ink, surface = Paper, onSurface = Ink,
    surfaceVariant = Mist, onSurfaceVariant = Color(0xFF5B6660), outlineVariant = Mist,
    surfaceContainer = PaperRaised, surfaceContainerHigh = PaperRaised, surfaceContainerLow = PaperRaised,
)
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7FD1B4), onPrimary = Ink, primaryContainer = Color(0xFF1E4A3F), onPrimaryContainer = Color(0xFFD5EDE3),
    secondaryContainer = NightMist, onSecondaryContainer = Color(0xFFE6EAE7), tertiary = Color(0xFF7FD1B4),
    error = Color(0xFFF0907A), background = Night, onBackground = Color(0xFFEDEFEE), surface = Night, onSurface = Color(0xFFEDEFEE),
    surfaceVariant = NightMist, onSurfaceVariant = Color(0xFFA5AFAA), outlineVariant = NightMist,
    surfaceContainer = NightRaised, surfaceContainerHigh = NightMist, surfaceContainerLow = NightRaised,
)

/** Money is set in tabular figures so columns of amounts line up. */
private val TextStyle.tabular get() = copy(fontFeatureSettings = "tnum")

@Composable
private fun TidyTheme(content: @Composable () -> Unit) {
    val base = MaterialTheme.typography
    val typography = base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Medium),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium),
    )
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme, typography = typography, content = content)
}

// Category colours: one per category, used for the bar, the avatar tint and the chip dot.
private val CATEGORY_COLOR = mapOf(
    "Cash" to Color(0xFF6B7280), "Finance" to Color(0xFF3B5BDB), "Food" to Color(0xFFE8590C), "Fuel" to Color(0xFF7A5230),
    "Government" to Color(0xFF495057), "Groceries" to Color(0xFF2F9E44), "Health" to Color(0xFFE03131), "Income" to Color(0xFF1E8E5A),
    "Other" to Color(0xFF868E96), "Services" to Color(0xFF0B7285), "Shopping" to Color(0xFFC2255C), "Software" to Color(0xFF6741D9),
    "Telecom" to Color(0xFF1971C2), "Transfer" to Color(0xFF0CA678), "Transport" to Color(0xFFF08C00), "Travel" to Color(0xFF1098AD),
)
private val UNCATEGORIZED_COLOR = Color(0xFFADB5BD)
private fun categoryColor(cat: String?) = CATEGORY_COLOR[cat] ?: UNCATEGORIZED_COLOR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(PERMS.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }) }
    var rows by remember { mutableStateOf(emptyList<RecentTx>()) }
    var status by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf<Long?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = it.values.all { v -> v } }

    var seen by remember { mutableStateOf(emptyList<String>()) }

    fun reload() { scope.launch { rows = withContext(Dispatchers.IO) { Db.get(ctx).tidyBoxQueries.recentTx().executeAsList() } } }
    fun import(sinceMillis: Long) {
        scope.launch {
            val (n, found) = withContext(Dispatchers.IO) { backfill(ctx, sinceMillis) { c -> scope.launch { status = ctx.getString(R.string.importing, c) } } }
            status = ctx.getString(R.string.imported, n, found)
            reload()
            // Nothing landed: show the sender IDs on the phone so one tap fixes the allowlist.
            seen = if (found == 0) withContext(Dispatchers.IO) { seenSenders(ctx) } else emptyList()
        }
    }
    // Import is automatic: the moment SMS is readable, history is read once. Manual ranges in
    // Settings remain for re-runs. The receiver handles everything that arrives after this.
    LaunchedEffect(granted) {
        // New app version = possibly new templates: re-read what the old engine rejected.
        if (EngineVersion.changed(ctx)) {
            val n = withContext(Dispatchers.IO) { reparseUnread(Db.get(ctx), RulePacks.current(ctx), KeepRaw.get(ctx)) }
            if (n > 0) status = ctx.getString(R.string.reparsed, n)
        }
        reload()
        if (granted && !Imported.get(ctx)) { Imported.set(ctx); import(0) }
    }
    fun allow(id: String) { Senders.set(ctx, Senders.get(ctx) + id); import(0) }

    picking?.let { t ->
        TxSheet(t, onDismiss = { picking = null }, onChanged = { reload() })
    }

    BackHandler(enabled = settings) { settings = false }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(if (settings) R.string.settings else R.string.app_name), style = MaterialTheme.typography.titleLarge) },
            navigationIcon = { if (settings) IconButton(onClick = { settings = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.inbox)) } },
            actions = { if (!settings) IconButton(onClick = { settings = true }) { Icon(Icons.Outlined.Settings, stringResource(R.string.settings)) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
    }) { pad ->
        if (settings) Settings(Modifier.padding(pad).padding(horizontal = 20.dp), granted, status, ::import, onReload = ::reload)
        else Inbox(Modifier.padding(pad).padding(horizontal = 16.dp), granted, rows, status, seen, onAsk = { ask.launch(PERMS) }, onPick = { picking = it.id }, onAllow = ::allow)
    }
}

private val CATEGORY_EMOJI = mapOf(
    "Cash" to "💵", "Finance" to "🏦", "Food" to "🍔", "Fuel" to "⛽", "Government" to "🏛️", "Groceries" to "🛒",
    "Health" to "🩺", "Income" to "💼", "Other" to "📦", "Services" to "🛠️", "Shopping" to "🛍️", "Software" to "💻",
    "Telecom" to "📱", "Transfer" to "🔁", "Transport" to "🚕", "Travel" to "✈️",
)
private val MONEY: NumberFormat = NumberFormat.getNumberInstance(Locale.getDefault()).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }
private fun money(v: Double): String = MONEY.format(v)
private fun whole(v: Double): String = NumberFormat.getIntegerInstance(Locale.getDefault()).format(Math.round(v))

private fun RecentTx.day(): LocalDate =
    occurred_at?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
        ?: Instant.ofEpochMilli(received_at).atZone(ZoneId.systemDefault()).toLocalDate()
private fun RecentTx.time(): String = occurred_at?.drop(11)?.take(5)
    ?: Instant.ofEpochMilli(received_at).atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))

@Composable
private fun Inbox(modifier: Modifier, granted: Boolean, rows: List<RecentTx>, status: String, seen: List<String>, onAsk: () -> Unit, onPick: (RecentTx) -> Unit, onAllow: (String) -> Unit) {
    val uncategorized = stringResource(R.string.uncategorized)
    val today = LocalDate.now()
    val dayFmt = DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.getDefault())
    // One month on screen at a time, newest first; ‹ › in the summary move through history.
    val months = remember(rows) { rows.groupBy { it.month() }.toSortedMap(reverseOrder()) }
    val keys = months.keys.toList()
    var monthIdx by rememberSaveable { mutableIntStateOf(0) }
    val idx = monthIdx.coerceIn(0, maxOf(0, keys.size - 1))
    val month = keys.getOrNull(idx)
    val txs = month?.let { months[it] }.orEmpty()
    val mainCurrency = txs.groupingBy { it.currency }.eachCount().maxByOrNull { it.value }?.key
    // Tap a category chip to see only that category; "" is the uncategorized bucket.
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = if (filter == null) txs else txs.filter { (it.category ?: "") == filter }

    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 32.dp)) {
        if (!granted) item {
            Surface(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.padding(20.dp)) {
                    Text(stringResource(R.string.privacy_line), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = onAsk, Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.allow_sms)) }
                    Text(stringResource(R.string.android15_note), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (rows.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("📭", style = MaterialTheme.typography.displayMedium)
                Text(stringResource(R.string.empty), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Text(status, style = MaterialTheme.typography.bodySmall)
                if (seen.isNotEmpty()) {
                    Text(stringResource(R.string.scan_help), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        for (id in seen) TextButton(onClick = { onAllow(id) }) { Text("+ $id") }
                    }
                }
            }
        }
        if (month != null) {
            item(key = "m-$month") {
                MonthSummary(month, txs, mainCurrency ?: "", uncategorized, filter,
                    hasNewer = idx > 0, hasOlder = idx < keys.size - 1,
                    onNewer = { monthIdx = idx - 1 }, onOlder = { monthIdx = idx + 1 },
                    onFilter = { filter = if (filter == it) null else it })
            }
            shown.groupBy { it.day() }.toSortedMap(reverseOrder()).forEach { (day, dayTxs) ->
                item(key = "d-$day") {
                    val label = when (day) {
                        today -> stringResource(R.string.today)
                        today.minusDays(1) -> stringResource(R.string.yesterday)
                        else -> day.format(dayFmt)
                    }
                    Text(label, Modifier.padding(start = 4.dp, top = 20.dp, bottom = 6.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(dayTxs, key = { it.id }) { t -> TxRow(t, uncategorized, mainCurrency, onPick) }
            }
        }
    }
}

@Composable
private fun TxRow(t: RecentTx, uncategorized: String, mainCurrency: String?, onPick: (RecentTx) -> Unit) {
    val name = t.merchant ?: t.type.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    val category = t.category ?: uncategorized
    val tint = categoryColor(t.category)
    val (sign, color) = when (t.type) {
        in EXPENSE -> "−" to MaterialTheme.colorScheme.error
        in INCOME -> "+" to MaterialTheme.colorScheme.tertiary
        else -> "" to MaterialTheme.colorScheme.onSurface
    }
    ListItem(
        modifier = Modifier.padding(vertical = 2.dp).clip(RoundedCornerShape(16.dp)).clickable { onPick(t) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        leadingContent = {
            Box(Modifier.size(44.dp).clip(CircleShape).background(tint.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Text(CATEGORY_EMOJI[t.category] ?: name.first().uppercase(), style = MaterialTheme.typography.titleMedium, color = tint)
            }
        },
        headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = { Text("$category · ${t.time()}", style = MaterialTheme.typography.bodySmall) },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(sign + money(t.amount), style = MaterialTheme.typography.titleMedium.tabular, color = color)
                if (t.currency != mainCurrency) Text(t.currency, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

/**
 * The month at a glance: totals in the month's main currency, then the tidy box — one bar,
 * every category in proportion — with every category chip visible (wrapped, never scrolled).
 * Rows in other currencies are listed, never converted or summed (design §7); the note says so.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MonthSummary(month: String, txs: List<RecentTx>, main: String, uncategorized: String, filter: String?, hasNewer: Boolean, hasOlder: Boolean, onNewer: () -> Unit, onOlder: () -> Unit, onFilter: (String) -> Unit) {
    val ctx = LocalContext.current
    val inMain = txs.filter { it.currency == main }
    val spent = inMain.filter { it.type in EXPENSE }.sumOf { it.amount }
    val received = inMain.filter { it.type in INCOME }.sumOf { it.amount }
    val byCat = inMain.filter { it.type in EXPENSE }.groupBy { it.category }
        .mapValues { it.value.sumOf { t -> t.amount } }.entries.sortedByDescending { it.value }
    val fx = txs.size - inMain.size
    val title = runCatching { YearMonth.parse(month).format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())) }.getOrDefault(month)
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onOlder, enabled = hasOlder) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.older_month)) }
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            IconButton(onClick = onNewer, enabled = hasNewer) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.newer_month)) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.spent), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("−${money(spent)}", style = MaterialTheme.typography.displaySmall.tabular, color = MaterialTheme.colorScheme.error, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.received), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("+${money(received)}", style = MaterialTheme.typography.headlineSmall.tabular, color = MaterialTheme.colorScheme.tertiary, maxLines = 1)
            }
        }
        Text(main, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (byCat.isNotEmpty()) {
            // The tidy box: spend as one bar, each category a segment in proportion.
            Row(Modifier.fillMaxWidth().padding(top = 16.dp).height(12.dp).clip(RoundedCornerShape(6.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for ((cat, sum) in byCat) Box(Modifier.weight((sum / spent).toFloat().coerceAtLeast(0.01f)).fillMaxHeight().background(categoryColor(cat)))
            }
            FlowRow(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((cat, sum) in byCat) Row(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(if (filter == (cat ?: "")) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                        .clickable { onFilter(cat ?: "") }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(categoryColor(cat)))
                    Text("${CATEGORY_EMOJI[cat] ?: ""} ${cat ?: uncategorized}".trim(), Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelMedium)
                    Text(whole(sum), Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelMedium.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (fx > 0) Text(stringResource(R.string.fx_excluded, fx), Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (filter != null) Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.showing_only, if (filter == "") uncategorized else filter), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            if (filter == "") TextButton(onClick = {
                // Merchant names and counts only — no amounts, no dates — for a GitHub issue that
                // grows the shared dictionary. The user sees the text in the share sheet first.
                val text = ctx.getString(R.string.share_uncategorized_intro) + "\n\n" +
                    Db.get(ctx).tidyBoxQueries.uncategorizedMerchants().executeAsList().joinToString("\n") { "${it.merchant} ×${it.n}" }
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
            }) { Text(stringResource(R.string.share_uncategorized)) }
            TextButton(onClick = { onFilter(filter) }) { Text(stringResource(R.string.clear_filter)) }
        }
        HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * Everything about one transaction, and every correction the user can make to it: direction
 * (expense / income), category, removal, and the original message it came from.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TxSheet(txId: Long, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var t by remember { mutableStateOf<TxDetail?>(null) }
    fun load() { scope.launch { t = withContext(Dispatchers.IO) { Db.get(ctx).tidyBoxQueries.txDetail(txId).executeAsOneOrNull() } } }
    LaunchedEffect(txId) { load() }
    fun edit(block: (TidyBoxDb) -> Unit) { scope.launch { withContext(Dispatchers.IO) { block(Db.get(ctx)) }; load(); onChanged() } }
    val d = t ?: return
    val uncategorized = stringResource(R.string.uncategorized)
    val name = d.merchant ?: d.type.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    val isExpense = d.type in EXPENSE
    val isIncome = d.type in INCOME
    val cats = remember { knownCategories(RulePacks.current(ctx)) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(Modifier.size(48.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Box(contentAlignment = Alignment.Center) { Text(CATEGORY_EMOJI[d.category] ?: name.first().uppercase(), style = MaterialTheme.typography.titleLarge) }
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(d.occurred_at, d.card_last4?.let { "•••• $it" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    (if (isExpense) "−" else if (isIncome) "+" else "") + money(d.amount) + " " + d.currency,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isExpense) MaterialTheme.colorScheme.error else if (isIncome) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                )
            }

            Text(stringResource(R.string.direction), Modifier.padding(top = 20.dp, bottom = 6.dp), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = isExpense, onClick = { edit { it.tidyBoxQueries.setType(TxType.PURCHASE.name, d.id) } }, label = { Text(stringResource(R.string.expense)) })
                FilterChip(selected = isIncome, onClick = { edit { it.tidyBoxQueries.setType(TxType.DEPOSIT.name, d.id) } }, label = { Text(stringResource(R.string.income)) })
            }

            Text(stringResource(R.string.category), Modifier.padding(top = 16.dp, bottom = 6.dp), style = MaterialTheme.typography.labelLarge)
            if (d.merchant_key != null) Text(stringResource(R.string.category_applies_to_merchant, d.merchant ?: ""), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (cat in cats) FilterChip(
                    selected = cat == (d.category ?: uncategorized),
                    onClick = { edit { db -> d.merchant_key?.let { correct(db, it, cat) } ?: correctRow(db, d.id, cat) } },
                    label = { Text("${CATEGORY_EMOJI[cat] ?: ""} $cat".trim()) },
                )
            }

            Text(stringResource(R.string.original_message), Modifier.padding(top = 20.dp, bottom = 6.dp), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(R.string.from_sender, d.sender), style = MaterialTheme.typography.bodySmall)
            Surface(Modifier.fillMaxWidth().padding(top = 6.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(d.body.ifEmpty { stringResource(R.string.message_not_kept) }, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }

            TextButton(onClick = { edit { it.tidyBoxQueries.setHidden(1, d.id) }; onDismiss() }, Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.remove_transaction), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun Section(title: String) =
    Text(title, Modifier.padding(top = 28.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

@Composable
private fun Settings(modifier: Modifier, granted: Boolean, status: String, onImport: (Long) -> Unit, onReload: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var senders by remember { mutableStateOf(Senders.get(ctx).sorted()) }
    var newSender by remember { mutableStateOf("") }
    var seen by remember { mutableStateOf<List<String>?>(null) }
    fun allow(id: String) { Senders.set(ctx, Senders.get(ctx) + id); senders = Senders.get(ctx).sorted(); seen = seen?.minus(id) }
    var passphrase by remember { mutableStateOf("") }
    var backupStatus by remember { mutableStateOf("") }
    val now = System.currentTimeMillis()
    val exportTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                val pw = passphrase.toCharArray()
                try { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(exportEncrypted(Db.get(ctx), pw)) } }
                finally { pw.fill('\u0000') }
            }
            backupStatus = ctx.getString(R.string.exported)
        }
    }
    val importFrom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            backupStatus = runCatching {
                withContext(Dispatchers.IO) {
                    val blob = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    val pw = passphrase.toCharArray()
                    try { importEncrypted(Db.get(ctx), Senders.get(ctx), RulePacks.current(ctx), KeepRaw.get(ctx), blob, pw) } finally { pw.fill('\u0000') }
                }
            }.map { (m, r) -> ctx.getString(R.string.imported_backup, m, r) }.getOrElse { ctx.getString(R.string.import_failed) }
            onReload()
        }
    }
    var pack by remember { mutableStateOf(RulePacks.current(ctx)) }
    var packStatus by remember { mutableStateOf("") }
    val loadPack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            packStatus = runCatching {
                withContext(Dispatchers.IO) {
                    val text = ctx.contentResolver.openInputStream(uri)!!.use { String(it.readBytes()) }
                    // A pack carries templates too: re-read what the old ones rejected.
                    RulePacks.install(ctx, text).also { recategorizeAll(Db.get(ctx), it); reparseUnread(Db.get(ctx), it, KeepRaw.get(ctx)) }
                }
            }.map { pack = it; "" }.getOrElse { ctx.getString(R.string.rulepack_bad) }
            onReload()
        }
    }
    var keepRaw by remember { mutableStateOf(KeepRaw.get(ctx)) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        Section(stringResource(R.string.privacy_title))
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.keep_raw), Modifier.weight(1f).padding(top = 12.dp))
            Switch(checked = keepRaw, onCheckedChange = { on ->
                keepRaw = on
                scope.launch { withContext(Dispatchers.IO) { KeepRaw.set(ctx, on, Db.get(ctx)) } }
            })
        }
        Text(stringResource(R.string.keep_raw_help), style = MaterialTheme.typography.bodySmall)

        Section(stringResource(R.string.rulepack_title))
        Text(stringResource(R.string.rulepack_help), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.rulepack_current, pack.name, pack.version), style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(onClick = { loadPack.launch(arrayOf("application/json", "*/*")) }) { Text(stringResource(R.string.rulepack_load)) }
            TextButton(onClick = {
                RulePacks.reset(ctx); pack = RulePacks.current(ctx)
                scope.launch { withContext(Dispatchers.IO) { recategorizeAll(Db.get(ctx), pack); reparseUnread(Db.get(ctx), pack, KeepRaw.get(ctx)) }; onReload() }
            }) { Text(stringResource(R.string.rulepack_reset)) }
            Text(packStatus, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
        }

        Section(stringResource(R.string.backup_title))
        Text(stringResource(R.string.backup_help), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(passphrase, { passphrase = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.passphrase)) }, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        Row {
            // Export is only meaningful while raw bodies exist — see Export.kt.
            TextButton(enabled = passphrase.length >= 8 && keepRaw, onClick = { exportTo.launch("tidybox-backup.tdb") }) { Text(stringResource(R.string.export)) }
            TextButton(enabled = passphrase.length >= 8, onClick = { importFrom.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.import_file)) }
            Text(backupStatus, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
        }
        if (!keepRaw) Text(stringResource(R.string.backup_needs_raw), style = MaterialTheme.typography.bodySmall)

        Section(stringResource(R.string.import_history))
        Row {
            for ((label, since) in listOf(R.string.range_1m to now - 30 * DAY, R.string.range_3m to now - 90 * DAY, R.string.range_12m to now - 365 * DAY, R.string.range_all to 0L)) {
                TextButton(enabled = granted, onClick = { onImport(since) }) { Text(stringResource(label)) }
            }
        }
        Text(status, style = MaterialTheme.typography.bodySmall)
        val clipboard = LocalClipboardManager.current
        var copied by remember { mutableStateOf(false) }
        Text(stringResource(R.string.unreadable_help), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = {
            scope.launch {
                val sample = withContext(Dispatchers.IO) { unreadableSample(Db.get(ctx)) }
                clipboard.setText(AnnotatedString(sample)); copied = sample.isNotEmpty()
            }
        }) { Text(stringResource(if (copied) R.string.unreadable_copied else R.string.unreadable_copy)) }

        Section(stringResource(R.string.senders_title))
        Text(stringResource(R.string.senders_help), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth()) {
            OutlinedTextField(newSender, { newSender = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.sender_add)) }, singleLine = true)
            TextButton(enabled = newSender.isNotBlank(), onClick = { allow(newSender.trim()); newSender = "" }) { Text(stringResource(R.string.add)) }
        }
        TextButton(enabled = granted, onClick = { scope.launch { seen = withContext(Dispatchers.IO) { seenSenders(ctx) } } }) { Text(stringResource(R.string.scan_senders)) }
        seen?.let { ids ->
            Text(stringResource(if (ids.isEmpty()) R.string.scan_none else R.string.scan_help), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                for (id in ids) TextButton(onClick = { allow(id) }) { Text("+ $id") }
            }
        }
        for (s in senders) Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(s, Modifier.weight(1f).padding(top = 12.dp))
            TextButton(onClick = { Senders.set(ctx, Senders.get(ctx) - s); senders = Senders.get(ctx).sorted() }) { Text(stringResource(R.string.remove)) }
        }
    }
}
