package app.marlboroadvance.mpvex.driveplayer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Drive Player MPV (fork): UI tambahan halaman riwayat (pencarian + backup). */

/** Top bar pencarian: mengganti top bar biasa selama mencari. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentSearchBar(
  query: String,
  onQueryChange: (String) -> Unit,
  onClose: () -> Unit,
) {
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
  TopAppBar(
    navigationIcon = {
      IconButton(onClick = onClose) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Tutup pencarian")
      }
    },
    title = {
      TextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text("Cari nama file") },
        modifier = Modifier.focusRequester(focus),
        colors = TextFieldDefaults.colors(
          focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
          unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
          focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
          unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
      )
    },
    actions = {
      if (query.isNotEmpty()) {
        IconButton(onClick = { onQueryChange("") }) {
          Icon(Icons.Filled.Close, contentDescription = "Hapus teks")
        }
      }
    },
  )
}

/** Menu titik tiga: backup dan restore data resume + riwayat. */
@Composable
fun RowScope.RecentBackupMenu(
  onBackup: (Uri) -> Unit,
  onRestore: (Uri) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  val backupLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.CreateDocument("application/json"),
  ) { uri -> uri?.let(onBackup) }
  val restoreLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenDocument(),
  ) { uri -> uri?.let(onRestore) }

  IconButton(onClick = { open = true }) {
    Icon(Icons.Filled.MoreVert, contentDescription = "Backup dan restore")
  }
  DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
    DropdownMenuItem(
      text = { Text("Backup posisi & riwayat") },
      onClick = {
        open = false
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        backupLauncher.launch("driveplayer-backup-$stamp.json")
      },
    )
    DropdownMenuItem(
      text = { Text("Restore dari backup") },
      onClick = {
        open = false
        restoreLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*"))
      },
    )
  }
}
