package com.blacksingularities.opensync.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AppSettingsAlt
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blacksingularities.opensync.BuildConfig
import com.blacksingularities.opensync.data.RunRecord
import com.blacksingularities.opensync.update.UpdateState
import com.blacksingularities.opensync.ui.components.About
import com.blacksingularities.opensync.ui.components.ActionRow
import com.blacksingularities.opensync.ui.components.ChoiceChips
import com.blacksingularities.opensync.ui.components.Divider
import com.blacksingularities.opensync.ui.components.Field
import com.blacksingularities.opensync.ui.components.Hint
import com.blacksingularities.opensync.ui.components.IconButtonRound
import com.blacksingularities.opensync.ui.components.ScreenScaffold
import com.blacksingularities.opensync.ui.components.Section
import com.blacksingularities.opensync.ui.components.SectionHeader
import com.blacksingularities.opensync.ui.components.StatusDot
import com.blacksingularities.opensync.ui.components.ToggleRow
import com.blacksingularities.opensync.ui.theme.Palette

@Composable
fun HistoryScreen(vm: AppViewModel) {
    val history by vm.store.history.collectAsState()
    var filter by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    ScreenScaffold("Historia", { vm.back() }, actions = {
        if (history.isNotEmpty()) IconButtonRound(Icons.Rounded.DeleteSweep, "Wyczyść", { confirmClear = true })
    }) {
        val jobs = history.map { it.jobName }.distinct()
        if (jobs.size > 1) ChoiceChips(listOf<String?>(null) + jobs, filter, { it ?: "Wszystkie" }) { filter = it }
        val list = history.filter { filter == null || it.jobName == filter }
        if (list.isEmpty()) Hint("Tu pojawi się przebieg każdej kopii, weryfikacji i przywracania.")
        val ok = list.count { it.status.name == "OK" }
        if (list.isNotEmpty()) {
            Text("Udane: $ok z ${list.size} · wysłano łącznie ${formatBytes(list.sumOf { it.bytesUploaded })}", color = Palette.TextFaint, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp))
        }
        list.groupBy { formatDate(it.start) }.forEach { (day, items) ->
            SectionHeader(day)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.PanelHigh).padding(vertical = 4.dp)) {
                items.forEachIndexed { i, r ->
                    if (i > 0) Divider()
                    HistoryRow(r)
                }
            }
        }
    }
    if (confirmClear) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Wyczyścić historię?") },
            text = { Text("Kopie na serwerze pozostaną bez zmian.") },
            confirmButton = { androidx.compose.material3.TextButton({ vm.store.clearHistory(); confirmClear = false }) { Text("Wyczyść", color = Palette.Record) } },
            dismissButton = { androidx.compose.material3.TextButton({ confirmClear = false }) { Text("Anuluj") } },
            containerColor = Palette.PanelHigh,
        )
    }
}

@Composable
private fun HistoryRow(r: RunRecord) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(statusColor(r.status))
            Spacer(Modifier.width(10.dp))
            Text("${r.kind.label} · ${r.jobName}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(formatTime(r.start), color = Palette.TextDim, fontSize = 12.sp)
        }
        val summary = buildString {
            append(r.status.label)
            if (r.end > r.start) append(" · ").append(formatDuration(r.end - r.start))
            if (r.files > 0) append(" · ").append(plural(r.files, "plik", "pliki", "plików"))
            if (r.bytesTotal > 0) append(" · ").append(formatBytes(r.bytesTotal))
            if (r.bytesUploaded > 0) append(" · wysłano ").append(formatBytes(r.bytesUploaded))
            if (r.removedSnapshots > 0) append(" · usunięto kopii: ").append(r.removedSnapshots)
        }
        Text(summary, color = Palette.TextDim, fontSize = 11.sp, modifier = Modifier.padding(start = 18.dp, top = 2.dp))
        if (r.message.isNotBlank()) Text(r.message, color = statusColor(r.status), fontSize = 12.sp, modifier = Modifier.padding(start = 18.dp, top = 2.dp))
        AnimatedVisibility(open && (r.warnings.isNotEmpty() || r.changedFiles > 0)) {
            Column(Modifier.padding(start = 18.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (r.changedFiles > 0) Text("Zmienione lub nowe pliki: ${r.changedFiles} · nowe dane: ${formatBytes(r.bytesNew)}", color = Palette.TextFaint, fontSize = 11.sp)
                r.warnings.take(50).forEach { Text("• $it", color = Palette.Warn, fontSize = 11.sp) }
                if (r.warnings.size > 50) Text("…i ${r.warnings.size - 50} więcej", color = Palette.TextFaint, fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val context = LocalContext.current
    var deviceName by remember { mutableStateOf(vm.store.deviceName) }
    var notifyFailures by remember { mutableStateOf(vm.store.notifyFailures) }
    var autoUpdate by remember { mutableStateOf(vm.store.autoUpdateCheck) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { vm.exportConfig(it) } }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importConfig(it) } }

    ScreenScaffold("Ustawienia", { vm.store.deviceName = deviceName; vm.back() }) {
        UpdateBanner(vm)
        Section("Urządzenie", icon = Icons.Rounded.PhoneAndroid, footer = "Nazwa widoczna przy kopiach – przydatna, gdy kilka telefonów zapisuje do jednego repozytorium.") {
            Field("Nazwa urządzenia", deviceName, { deviceName = it; vm.store.deviceName = it })
        }
        Section("Powiadomienia i praca w tle", icon = Icons.Rounded.NotificationsActive) {
            ToggleRow(Icons.Rounded.NotificationsActive, "Powiadamiaj o błędach", "Nieudana kopia lub weryfikacja", notifyFailures) { notifyFailures = it; vm.store.notifyFailures = it }
            Divider()
            ActionRow(Icons.Rounded.BatteryAlert, "Optymalizacja baterii", "Wyłącz dla OpenSync, aby zaplanowane kopie nie były wstrzymywane") {
                runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))) }
                    .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
            }
            Divider()
            ActionRow(Icons.Rounded.AppSettingsAlt, "Uprawnienia aplikacji", "SMS, kontakty, kalendarz, multimedia, lokalizacja") {
                runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))) }
            }
        }
        Section("Przeniesienie ustawień", icon = Icons.Rounded.Upload, footer = "Plik zawiera zadania i miejsca kopii, bez haseł. Na nowym telefonie wczytaj go i wpisz hasła ponownie.") {
            ActionRow(Icons.Rounded.Upload, "Eksportuj ustawienia") { exportLauncher.launch("opensync-ustawienia.json") }
            Divider()
            ActionRow(Icons.Rounded.Download, "Importuj ustawienia") { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
        }
        Section("Bezpieczeństwo", icon = Icons.Rounded.Shield) {
            ActionRow(Icons.Rounded.Shield, "Szyfrowanie po stronie telefonu", "AES-256-GCM · PBKDF2-HMAC-SHA256, 600 000 iteracji · identyfikatory HMAC-SHA256 · hasła do serwerów w Android Keystore")
            Divider()
            ActionRow(Icons.Rounded.Laptop, "Odzyskiwanie bez telefonu", "Do każdego wydania dołączony jest plik opensync-restore.py – odczyta kopię na komputerze (Python 3 + pakiet cryptography) z dysku NAS.")
        }
        Section("Aktualizacje", icon = Icons.Rounded.SystemUpdate) {
            ToggleRow(Icons.Rounded.Update, "Sprawdzaj przy starcie", null, autoUpdate) { autoUpdate = it; vm.store.autoUpdateCheck = it }
            Divider()
            ActionRow(
                Icons.Rounded.SystemUpdate, "Sprawdź teraz",
                when (val u = vm.update) {
                    UpdateState.Checking -> "Sprawdzanie…"
                    UpdateState.UpToDate -> "Masz najnowszą wersję ${BuildConfig.VERSION_NAME}"
                    is UpdateState.Failed -> u.message
                    is UpdateState.Available -> "Dostępna wersja ${u.info.version}"
                    else -> "Wersja ${BuildConfig.VERSION_NAME}"
                },
            ) { vm.checkUpdate(silent = false) }
        }
        About(BuildConfig.VERSION_NAME)
    }
}
