package co.raseed

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import co.raseed.db.RecentTx
import co.raseed.engine.KNOWN_CATEGORIES
import co.raseed.sms.backfill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
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
    var picking by remember { mutableStateOf<RecentTx?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = it.values.all { v -> v } }

    fun reload() { scope.launch { rows = withContext(Dispatchers.IO) { Db.get(ctx).raseedQueries.recentTx().executeAsList() } } }
    LaunchedEffect(Unit) { reload() }
    fun import(sinceMillis: Long) {
        scope.launch {
            val n = withContext(Dispatchers.IO) { backfill(ctx, sinceMillis) { c -> scope.launch { status = ctx.getString(R.string.importing, c) } } }
            status = ctx.getString(R.string.imported, n)
            reload()
        }
    }

    picking?.let { t ->
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text(stringResource(R.string.pick_category, t.merchant ?: "")) },
            text = {
                LazyColumn {
                    items(KNOWN_CATEGORIES) { cat ->
                        TextButton(onClick = {
                            picking = null
                            scope.launch { withContext(Dispatchers.IO) { correct(Db.get(ctx), t.merchant_key!!, cat) }; reload() }
                        }, Modifier.fillMaxWidth()) { Text(cat) }
                    }
                }
            },
            confirmButton = {},
        )
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(if (settings) R.string.settings else R.string.app_name)) },
            actions = { TextButton(onClick = { settings = !settings }) { Text(stringResource(if (settings) R.string.inbox else R.string.settings)) } },
        )
    }) { pad ->
        if (settings) Settings(Modifier.padding(pad).padding(12.dp), granted, status, ::import, onReload = ::reload)
        else Inbox(Modifier.padding(pad).padding(12.dp), granted, rows, onAsk = { ask.launch(PERMS) }, onPick = { picking = it })
    }
}

@Composable
private fun Inbox(modifier: Modifier, granted: Boolean, rows: List<RecentTx>, onAsk: () -> Unit, onPick: (RecentTx) -> Unit) {
    val uncategorized = stringResource(R.string.uncategorized)
    Column(modifier) {
        if (!granted) {
            Text(stringResource(R.string.privacy_line))
            Button(onClick = onAsk) { Text(stringResource(R.string.allow_sms)) }
            Text(stringResource(R.string.android15_note), style = MaterialTheme.typography.bodySmall)
        }
        if (rows.isEmpty()) Text(stringResource(R.string.empty), Modifier.padding(top = 24.dp))
        LazyColumn {
            rows.groupBy { it.month() }.toSortedMap(reverseOrder()).forEach { (month, txs) ->
                item(key = "m-$month") { MonthHeader(month, txs, uncategorized) }
                items(txs, key = { it.id }) { t ->
                    Column(Modifier.fillMaxWidth().clickable(enabled = t.merchant_key != null) { onPick(t) }.padding(vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(t.merchant ?: t.type.lowercase().replace('_', ' '), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Text("%.2f %s".format(t.amount, t.currency), style = MaterialTheme.typography.bodyLarge)
                        }
                        Text("${t.category ?: uncategorized} · ${t.category_reason.lowercase()} · ${t.occurred_at ?: ""}", style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** Month totals in SAR only. FX rows are listed, never converted or summed (design §7). */
@Composable
private fun MonthHeader(month: String, txs: List<RecentTx>, uncategorized: String) {
    val sar = txs.filter { it.currency == "SAR" }
    val spent = sar.filter { it.type in EXPENSE }.sumOf { it.amount }
    val received = sar.filter { it.type in INCOME }.sumOf { it.amount }
    val byCat = sar.filter { it.type in EXPENSE }.groupBy { it.category ?: uncategorized }
        .mapValues { it.value.sumOf { t -> t.amount } }.entries.sortedByDescending { it.value }.take(4)
    val fx = txs.size - sar.size
    Column(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 6.dp)) {
        Text(month, style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth()) {
            Text("${stringResource(R.string.spent)} %.0f".format(spent), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text("${stringResource(R.string.received)} %.0f".format(received), style = MaterialTheme.typography.titleMedium)
        }
        Text(byCat.joinToString("  ·  ") { "${it.key} %.0f".format(it.value) }, style = MaterialTheme.typography.bodySmall)
        // A total that silently omits rows is a wrong total. Say so rather than convert (design §7).
        if (fx > 0) Text(stringResource(R.string.fx_excluded, fx), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Settings(modifier: Modifier, granted: Boolean, status: String, onImport: (Long) -> Unit, onReload: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var senders by remember { mutableStateOf(Senders.get(ctx).sorted()) }
    var newSender by remember { mutableStateOf("") }
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
                    try { importEncrypted(Db.get(ctx), Senders.get(ctx), blob, pw) } finally { pw.fill('\u0000') }
                }
            }.map { (m, r) -> ctx.getString(R.string.imported_backup, m, r) }.getOrElse { ctx.getString(R.string.import_failed) }
            onReload()
        }
    }
    Column(modifier) {
        Text(stringResource(R.string.backup_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.backup_help), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(passphrase, { passphrase = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.passphrase)) }, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        Row {
            TextButton(enabled = passphrase.length >= 8, onClick = { exportTo.launch("raseed-backup.rsd") }) { Text(stringResource(R.string.export)) }
            TextButton(enabled = passphrase.length >= 8, onClick = { importFrom.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.import_file)) }
            Text(backupStatus, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
        }

        Text(stringResource(R.string.import_history), Modifier.padding(top = 24.dp), style = MaterialTheme.typography.titleMedium)
        Row {
            for ((label, since) in listOf(R.string.range_1m to now - 30 * DAY, R.string.range_3m to now - 90 * DAY, R.string.range_12m to now - 365 * DAY, R.string.range_all to 0L)) {
                TextButton(enabled = granted, onClick = { onImport(since) }) { Text(stringResource(label)) }
            }
        }
        Text(status, style = MaterialTheme.typography.bodySmall)

        Text(stringResource(R.string.senders_title), Modifier.padding(top = 24.dp), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.senders_help), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth()) {
            OutlinedTextField(newSender, { newSender = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.sender_add)) }, singleLine = true)
            TextButton(enabled = newSender.isNotBlank(), onClick = {
                Senders.set(ctx, Senders.get(ctx) + newSender.trim()); senders = Senders.get(ctx).sorted(); newSender = ""
            }) { Text(stringResource(R.string.add)) }
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
