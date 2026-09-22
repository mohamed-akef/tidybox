package app.tidybox

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
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
        setContent {
            val dark = isSystemInDarkTheme()
            val scheme = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) { App() }
        }
    }
}

private val PERMS = arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
private val EXPENSE = setOf("PURCHASE", "BILL", "GOV", "ATM_OUT", "TRANSFER_OUT", "INVESTMENT")
private val INCOME = setOf("SALARY", "DEPOSIT", "TRANSFER_IN", "REFUND")
private const val DAY = 86_400_000L

private fun RecentTx.month(): String =
    occurred_at?.take(7) ?: SimpleDateFormat("yyyy-MM", Locale.US).format(Date(received_at))

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

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(if (settings) R.string.settings else R.string.app_name)) },
            actions = { TextButton(onClick = { settings = !settings }) { Text(stringResource(if (settings) R.string.inbox else R.string.settings)) } },
        )
    }) { pad ->
        if (settings) Settings(Modifier.padding(pad).padding(12.dp), granted, status, ::import, onReload = ::reload)
        else Inbox(Modifier.padding(pad).padding(12.dp), granted, rows, status, seen, onAsk = { ask.launch(PERMS) }, onPick = { picking = it.id }, onAllow = ::allow)
    }
}

private val CATEGORY_EMOJI = mapOf(
    "Cash" to "💵", "Finance" to "🏦", "Food" to "🍔", "Fuel" to "⛽", "Government" to "🏛️", "Groceries" to "🛒",
    "Health" to "🩺", "Income" to "💼", "Other" to "📦", "Services" to "🛠️", "Shopping" to "🛍️", "Software" to "💻",
    "Telecom" to "📱", "Transfer" to "🔁", "Transport" to "🚕", "Travel" to "✈️",
)
private val MONEY: NumberFormat = NumberFormat.getNumberInstance(Locale.getDefault()).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }
private fun money(v: Double): String = MONEY.format(v)

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
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 32.dp)) {
        if (!granted) item {
            Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.privacy_line), style = MaterialTheme.typography.bodyMedium)
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
        rows.groupBy { it.month() }.toSortedMap(reverseOrder()).forEach { (month, txs) ->
            item(key = "m-$month") { MonthCard(month, txs, uncategorized) }
            txs.groupBy { it.day() }.toSortedMap(reverseOrder()).forEach { (day, dayTxs) ->
                item(key = "d-$day") {
                    val label = when (day) {
                        today -> stringResource(R.string.today)
                        today.minusDays(1) -> stringResource(R.string.yesterday)
                        else -> day.format(dayFmt)
                    }
                    Text(label, Modifier.padding(start = 4.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(dayTxs, key = { it.id }) { t -> TxRow(t, uncategorized, onPick) }
            }
        }
    }
}

@Composable
private fun TxRow(t: RecentTx, uncategorized: String, onPick: (RecentTx) -> Unit) {
    val name = t.merchant ?: t.type.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    val category = t.category ?: uncategorized
    val emoji = CATEGORY_EMOJI[t.category] ?: name.first().uppercase()
    val (sign, color) = when (t.type) {
        in EXPENSE -> "−" to MaterialTheme.colorScheme.error
        in INCOME -> "+" to MaterialTheme.colorScheme.tertiary
        else -> "" to MaterialTheme.colorScheme.onSurface
    }
    ListItem(
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { onPick(t) },
        leadingContent = {
            Surface(Modifier.size(44.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Box(contentAlignment = Alignment.Center) { Text(emoji, style = MaterialTheme.typography.titleMedium) }
            }
        },
        headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = { Text("$category · ${t.time()}", style = MaterialTheme.typography.bodySmall) },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(sign + money(t.amount), style = MaterialTheme.typography.titleMedium, color = color)
                Text(t.currency, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

/**
 * Month totals in the month's main currency (the one most rows carry). Rows in other currencies
 * are listed, never converted or summed (design §7); the card says how many were left out.
 */
@Composable
private fun MonthCard(month: String, txs: List<RecentTx>, uncategorized: String) {
    val main = txs.groupingBy { it.currency }.eachCount().maxByOrNull { it.value }?.key ?: "SAR"
    val inMain = txs.filter { it.currency == main }
    val spent = inMain.filter { it.type in EXPENSE }.sumOf { it.amount }
    val received = inMain.filter { it.type in INCOME }.sumOf { it.amount }
    val byCat = inMain.filter { it.type in EXPENSE }.groupBy { it.category ?: uncategorized }
        .mapValues { it.value.sumOf { t -> t.amount } }.entries.sortedByDescending { it.value }.take(4)
    val fx = txs.size - inMain.size
    val title = runCatching { YearMonth.parse(month).format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())) }.getOrDefault(month)
    Card(
        Modifier.fillMaxWidth().padding(top = 16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.spent), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("−${money(spent)}", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
                    Text(main, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(stringResource(R.string.received), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("+${money(received)}", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.tertiary)
                    Text(main, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            if (byCat.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((cat, sum) in byCat) Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface) {
                    Text("${CATEGORY_EMOJI[cat] ?: "•"} $cat ${money(sum)}", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
            // A total that silently omits rows is a wrong total. Say so rather than convert (design §7).
            if (fx > 0) Text(stringResource(R.string.fx_excluded, fx), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
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
    Column(modifier) {
        Text(stringResource(R.string.privacy_title), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.keep_raw), Modifier.weight(1f).padding(top = 12.dp))
            Switch(checked = keepRaw, onCheckedChange = { on ->
                keepRaw = on
                scope.launch { withContext(Dispatchers.IO) { KeepRaw.set(ctx, on, Db.get(ctx)) } }
            })
        }
        Text(stringResource(R.string.keep_raw_help), style = MaterialTheme.typography.bodySmall)

        Text(stringResource(R.string.rulepack_title), Modifier.padding(top = 24.dp), style = MaterialTheme.typography.titleMedium)
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

        Text(stringResource(R.string.backup_title), Modifier.padding(top = 24.dp), style = MaterialTheme.typography.titleMedium)
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

        Text(stringResource(R.string.import_history), Modifier.padding(top = 24.dp), style = MaterialTheme.typography.titleMedium)
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

        Text(stringResource(R.string.senders_title), Modifier.padding(top = 24.dp), style = MaterialTheme.typography.titleMedium)
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
        LazyColumn {
            items(senders, key = { it }) { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(s, Modifier.weight(1f).padding(top = 12.dp))
                    TextButton(onClick = { Senders.set(ctx, Senders.get(ctx) - s); senders = Senders.get(ctx).sorted() }) { Text(stringResource(R.string.remove)) }
                }
            }
        }
    }
}
