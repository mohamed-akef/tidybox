package co.raseed

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import co.raseed.db.RecentTx
import co.raseed.sms.backfill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Inbox() } }
    }
}

private val PERMS = arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Inbox() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(PERMS.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }) }
    var rows by remember { mutableStateOf(emptyList<RecentTx>()) }
    var status by remember { mutableStateOf("") }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = it.values.all { v -> v } }

    fun reload() { scope.launch { rows = withContext(Dispatchers.IO) { Db.get(ctx).raseedQueries.recentTx().executeAsList() } } }
    LaunchedEffect(Unit) { reload() }

    Scaffold(topBar = { TopAppBar(title = { Text("Raseed") }) }) { pad ->
        Column(Modifier.padding(pad).padding(12.dp)) {
            if (!granted) {
                Text("Raseed reads bank SMS on this phone only. Nothing leaves the device.")
                Button(onClick = { ask.launch(PERMS) }) { Text("Allow SMS access") }
            } else {
                Row {
                    Button(onClick = {
                        scope.launch {
                            status = "importing…"
                            val n = withContext(Dispatchers.IO) { backfill(ctx) }
                            status = "imported $n bank messages"
                            reload()
                        }
                    }) { Text("Import history") }
                    Text(status, Modifier.padding(start = 12.dp, top = 12.dp))
                }
            }
            LazyColumn {
                items(rows, key = { it.id }) { t ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(t.merchant ?: t.type.lowercase().replace('_', ' '), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Text("${t.amount} ${t.currency}", style = MaterialTheme.typography.bodyLarge)
                        }
                        Text("${t.category ?: "Uncategorized"} · ${t.category_reason.lowercase()} · ${t.occurred_at ?: ""}", style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
