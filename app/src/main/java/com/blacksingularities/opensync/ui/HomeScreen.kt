package com.blacksingularities.opensync.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddTask
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.blacksingularities.opensync.data.Job
import com.blacksingularities.opensync.data.RunStatus
import com.blacksingularities.opensync.engine.Operations
import com.blacksingularities.opensync.engine.Progress
import com.blacksingularities.opensync.ui.components.BigAction
import com.blacksingularities.opensync.ui.components.HomeAction
import com.blacksingularities.opensync.ui.components.BrandMark
import com.blacksingularities.opensync.ui.components.IconButtonRound
import com.blacksingularities.opensync.ui.components.SectionHeader
import com.blacksingularities.opensync.ui.components.StatusDot
import com.blacksingularities.opensync.ui.theme.MonoDigits
import com.blacksingularities.opensync.ui.theme.Palette

@Composable
fun HomeScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val jobs by vm.store.jobs.collectAsState()
    val destinations by vm.store.destinations.collectAsState()
    val history by vm.store.history.collectAsState()
    val running by Operations.running.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val needsNotif = Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    val pm = context.getSystemService(PowerManager::class.java)
    val batteryRestricted = remember(refresh) { !pm.isIgnoringBatteryOptimizations(context.packageName) }

    Box(Modifier.fillMaxSize().background(Palette.Ink)) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark()
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("OpenSync", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Color.White)
                    Text("Szyfrowane kopie w sieci domowej", fontSize = 13.sp, color = Palette.TextDim)
                }
                Box {
                    IconButtonRound(Icons.Rounded.Settings, "Ustawienia", { vm.go(Screen.Settings) })
                    if (vm.updatePending) StatusDot(Color.White, Modifier.align(Alignment.TopEnd).padding(8.dp))
                }
            }

            UpdateBanner(vm)

            val activeJobs = jobs.filter { it.enabled }
            when {
                destinations.isEmpty() -> BigAction(Icons.Rounded.Storage, "Dodaj miejsce kopii", "NAS (SMB), serwer SFTP, WebDAV albo dysk USB") {
                    vm.go(Screen.EditDestination(null))
                }
                jobs.isEmpty() -> BigAction(Icons.Rounded.AddTask, "Utwórz zadanie kopii", "Wybierz, co kopiować, kiedy i ile wersji trzymać") {
                    vm.go(Screen.EditJob(null))
                }
                running.isNotEmpty() -> BigAction(Icons.Rounded.CloudUpload, "Kopia w toku", running.values.first().phase, enabled = false) {}
                else -> {
                    val last = jobs.mapNotNull { vm.store.lastSuccess(it.id) }.maxByOrNull { it.end }
                    BigAction(
                        Icons.Rounded.CloudUpload, "Zrób kopię teraz",
                        if (last != null) "Ostatnia udana: ${relative(last.end)} · ${plural(activeJobs.size, "zadanie", "zadania", "zadań")}"
                        else "Pierwsza kopia może potrwać – kolejne wyślą tylko zmiany",
                        enabled = activeJobs.isNotEmpty(),
                    ) { activeJobs.forEach { vm.runNow(it.id) } }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeAction(Icons.Rounded.AddTask, "Nowe zadanie", Modifier.weight(1f)) {
                    if (destinations.isEmpty()) vm.go(Screen.EditDestination(null)) else vm.go(Screen.EditJob(null))
                }
                HomeAction(Icons.Rounded.Lan, "Miejsca", Modifier.weight(1f), badge = destinations.size.takeIf { it > 0 }?.toString()) { vm.go(Screen.Destinations) }
                HomeAction(Icons.Rounded.History, "Historia", Modifier.weight(1f)) { vm.go(Screen.History) }
            }

            if (needsNotif) {
                Notice(Icons.Rounded.NotificationsOff, "Włącz powiadomienia", "Postęp kopii i informacja, gdy coś pójdzie nie tak") {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            if (batteryRestricted && jobs.any { it.enabled }) {
                Notice(Icons.Rounded.BatteryAlert, "Zezwól na pracę w tle", "Bez tego system może wstrzymywać zaplanowane kopie") {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName)))
                    }
                    refresh++
                }
            }

            if (jobs.isNotEmpty()) {
                SectionHeader("Zadania", Icons.Rounded.Inventory2)
                jobs.forEach { job ->
                    JobCard(vm, job, running[job.id], history.firstOrNull { it.jobId == job.id && it.kind == com.blacksingularities.opensync.data.RunKind.BACKUP })
                }
            }

            if (jobs.isEmpty() && destinations.isEmpty()) {
                SectionHeader("Jak to działa", Icons.Rounded.Lock)
                Explainer()
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Notice(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.PanelHigh).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Palette.Warn, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Palette.TextDim, fontSize = 11.sp)
        }
    }
}

@Composable
private fun JobCard(vm: AppViewModel, job: Job, progress: Progress?, last: com.blacksingularities.opensync.data.RunRecord?) {
    val dest = vm.store.destination(job.destinationId)
    val next = remember(job, last) { vm.nextRun(job.id) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Palette.PanelHigh).clickable { vm.go(Screen.EditJob(job.id)) }.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(
                when {
                    progress != null -> Color.White
                    !job.enabled -> Palette.TextFaint
                    last == null -> Palette.TextFaint
                    else -> statusColor(last.status)
                },
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(job.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (dest?.name?.ifBlank { dest.describe() } ?: "brak miejsca") + " · " + job.schedule.describe() + if (!job.enabled) " · wyłączone" else "",
                    color = Palette.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (progress != null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(progress.phase, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (progress.fraction >= 0) Text("${(progress.fraction * 100).toInt()}%", style = MonoDigits, color = Color.White)
                }
                if (progress.fraction >= 0) {
                    LinearProgressIndicator(progress = { progress.fraction }, color = Color.White, trackColor = Color.White.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(color = Color.White, trackColor = Color.White.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth())
                }
                if (progress.filesTotal > 0) {
                    Text(
                        "${progress.filesDone}/${progress.filesTotal} plików · ${formatBytes(progress.bytesDone)} z ${formatBytes(progress.bytesTotal)}" +
                            (if (progress.uploaded > 0) " · wysłano ${formatBytes(progress.uploaded)}" else "") +
                            (if (progress.bytesPerSec > 0) " · ${formatBytes(progress.bytesPerSec)}/s" else ""),
                        color = Palette.TextDim, fontSize = 11.sp,
                    )
                }
                if (progress.detail.isNotBlank()) Text(progress.detail, color = Palette.TextFaint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("Ostatnia", last?.let { relative(it.end) } ?: "—", Modifier.weight(1f))
                Stat("Rozmiar", last?.takeIf { it.bytesTotal > 0 }?.let { formatBytes(it.bytesTotal) } ?: "—", Modifier.weight(1f))
                Stat("Następna", next?.let { relative(it) } ?: if (job.enabled) "ręcznie" else "—", Modifier.weight(1f))
            }
            if (last != null && last.status != RunStatus.OK) {
                Text(
                    last.status.label + (if (last.message.isNotBlank()) ": ${last.message}" else if (last.warnings.isNotEmpty()) ": ${last.warnings.first()}" else ""),
                    color = statusColor(last.status), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            } else if (last != null) {
                Text(
                    "${plural(last.files, "plik", "pliki", "plików")} · nowe dane ${formatBytes(last.bytesNew)}" +
                        if (last.removedSnapshots > 0) " · usunięto ${plural(last.removedSnapshots, "starą kopię", "stare kopie", "starych kopii")}" else "",
                    color = Palette.TextDim, fontSize = 12.sp,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (progress != null) {
                CardButton(Icons.Rounded.Close, "Przerwij", Modifier.weight(1f)) { vm.cancel(job.id) }
            } else {
                CardButton(Icons.Rounded.PlayArrow, "Kopia teraz", Modifier.weight(1f), primary = true) { vm.runNow(job.id) }
            }
            CardButton(Icons.Rounded.History, "Kopie i przywracanie", Modifier.weight(1.4f)) { vm.go(Screen.Snapshots(job.id)) }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.06f)).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(label, color = Palette.TextFaint, fontSize = 10.sp)
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CardButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, primary: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(if (primary) Color.White else Color.White.copy(alpha = 0.08f)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = if (primary) Palette.Ink else Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (primary) Palette.Ink else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Explainer() {
    val steps = listOf(
        "1" to "Dodaj miejsce: udział SMB na NAS-ie, serwer SFTP, WebDAV albo dysk USB.",
        "2" to "Utwórz zadanie: zaznacz, co kopiować, ustal harmonogram i liczbę wersji.",
        "3" to "Ustal hasło. Dane są szyfrowane AES-256 w telefonie – NAS widzi tylko szyfrogram.",
        "4" to "Kolejne kopie wysyłają tylko zmiany. Każdą wersję przywrócisz w całości lub pojedynczo.",
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.forEach { (n, t) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Text(n, color = Palette.Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Spacer(Modifier.width(12.dp))
                Text(t, color = Palette.TextDim, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}

