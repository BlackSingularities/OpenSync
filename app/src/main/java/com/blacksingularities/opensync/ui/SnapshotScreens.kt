package com.blacksingularities.opensync.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blacksingularities.opensync.repo.FileEntry
import com.blacksingularities.opensync.repo.SnapshotRef
import com.blacksingularities.opensync.ui.components.ActionRow
import com.blacksingularities.opensync.ui.components.Divider
import com.blacksingularities.opensync.ui.components.Field
import com.blacksingularities.opensync.ui.components.Hint
import com.blacksingularities.opensync.ui.components.HomeAction
import com.blacksingularities.opensync.ui.components.IconButtonRound
import com.blacksingularities.opensync.ui.components.Pill
import com.blacksingularities.opensync.ui.components.PrimaryButton
import com.blacksingularities.opensync.ui.components.ScreenScaffold
import com.blacksingularities.opensync.ui.components.Section
import com.blacksingularities.opensync.ui.components.SectionHeader
import com.blacksingularities.opensync.ui.theme.Palette
import com.blacksingularities.opensync.work.BackupWorker

@Composable
fun SnapshotsScreen(vm: AppViewModel, jobId: String) {
    val job = vm.store.job(jobId) ?: run { vm.back(); return }
    LaunchedEffect(jobId) { vm.loadSnapshots(jobId) }
    var restoreAll by remember { mutableStateOf<String?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        val id = restoreAll
        if (uri != null && id != null) vm.restore(jobId, id, null, uri)
        restoreAll = null
    }
    val ownId = job.id.filter { it.isLetterOrDigit() }
    val list = if (vm.showAllJobs) vm.snapshots else vm.snapshots.filter { it.jobId == ownId }

    ScreenScaffold(job.name, { vm.back() }, actions = {
        IconButtonRound(Icons.Rounded.Refresh, "Odśwież", { vm.loadSnapshots(jobId) })
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeAction(Icons.Rounded.PlayArrow, "Kopia teraz", Modifier.weight(1f)) { vm.runNow(jobId) }
            HomeAction(Icons.Rounded.VerifiedUser, "Weryfikuj", Modifier.weight(1f)) { vm.runNow(jobId, BackupWorker.OP_CHECK) }
            HomeAction(Icons.Rounded.CleaningServices, "Uporządkuj", Modifier.weight(1f)) { vm.runNow(jobId, BackupWorker.OP_PRUNE) }
        }
        vm.repoInfo?.let { Text(it, color = Palette.TextFaint, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp)) }

        when {
            vm.snapshotsLoading && vm.snapshots.isEmpty() -> Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = Color.White)
            }
            vm.snapshotsError != null -> Hint(vm.snapshotsError!!, Palette.TextDim)
            else -> {
                if (vm.snapshots.any { it.jobId != ownId }) {
                    com.blacksingularities.opensync.ui.components.ChoiceChips(listOf(false, true), vm.showAllJobs, { if (it) "Całe repozytorium" else "To zadanie" }) { vm.showAllJobs = it }
                }
                if (list.isEmpty()) Hint("Brak kopii. Pierwsza pojawi się po uruchomieniu zadania.")
                list.groupBy { formatDate(it.time) }.forEach { (day, items) ->
                    SectionHeader(day)
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.PanelHigh).padding(vertical = 4.dp)) {
                        items.forEachIndexed { i, ref ->
                            if (i > 0) Divider()
                            SnapshotRow(
                                vm, jobId, ref, pinned = ref.id in vm.pins,
                                previous = list.getOrNull(list.indexOf(ref) + 1)?.takeIf { it.jobId == ref.jobId },
                                onRestoreAll = { restoreAll = ref.id; restoreLauncher.launch(null) },
                                onDelete = { deleteId = ref.id },
                            )
                        }
                    }
                }
                if (list.isNotEmpty()) {
                    Hint("Każda kopia jest pełna: przywrócisz z niej wszystko albo pojedyncze pliki. Niezmienione dane są współdzielone między kopiami, więc zajmują miejsce tylko raz.")
                }
            }
        }
    }

    deleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text("Usunąć tę kopię?") },
            text = { Text("Pliki obecne także w innych kopiach zostaną. Tej operacji nie można cofnąć.") },
            confirmButton = { TextButton({ vm.deleteSnapshot(jobId, id); deleteId = null }) { Text("Usuń", color = Palette.Record) } },
            dismissButton = { TextButton({ deleteId = null }) { Text("Anuluj") } },
            containerColor = Palette.PanelHigh,
        )
    }
}

@Composable
private fun SnapshotRow(vm: AppViewModel, jobId: String, ref: SnapshotRef, pinned: Boolean, previous: SnapshotRef?, onRestoreAll: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { vm.go(Screen.Browse(jobId, ref.id)) }.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(ref.time), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                if (pinned) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Rounded.PushPin, "Przypięta", tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
            Text(relative(ref.time), color = Palette.TextDim, fontSize = 11.sp)
        }
        Box {
            IconButtonRound(Icons.Rounded.MoreVert, "Więcej", { menu = true }, Palette.TextDim)
            DropdownMenu(menu, { menu = false }, containerColor = Palette.PanelHigh) {
                DropdownMenuItem({ Text("Przeglądaj pliki") }, { menu = false; vm.go(Screen.Browse(jobId, ref.id)) }, leadingIcon = { Icon(Icons.Rounded.Folder, null) })
                DropdownMenuItem({ Text("Przywróć wszystko…") }, { menu = false; onRestoreAll() }, leadingIcon = { Icon(Icons.Rounded.Restore, null) })
                if (previous != null) {
                    DropdownMenuItem({ Text("Zmiany od poprzedniej") }, { menu = false; vm.go(Screen.Diff(jobId, previous.id, ref.id)) }, leadingIcon = { Icon(Icons.Rounded.CompareArrows, null) })
                }
                DropdownMenuItem({ Text(if (pinned) "Odepnij" else "Przypnij (nie usuwaj)") }, { menu = false; vm.setPinned(jobId, ref.id, !pinned) }, leadingIcon = { Icon(Icons.Rounded.PushPin, null) })
                DropdownMenuItem({ Text("Usuń kopię", color = Palette.Record) }, { menu = false; onDelete() }, leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = Palette.Record) })
            }
        }
    }
}

private fun fileIcon(name: String) = when (mimeOf(name).substringBefore('/')) {
    "image" -> Icons.Rounded.Image
    "video" -> Icons.Rounded.Movie
    "audio" -> Icons.Rounded.MusicNote
    else -> Icons.Rounded.Description
}

/** Przeglądanie plików kopii jak w menedżerze plików: foldery, wyszukiwanie, zaznaczanie, przywracanie. */
@Composable
fun BrowseScreen(vm: AppViewModel, jobId: String, snapshotId: String) {
    LaunchedEffect(snapshotId) { vm.openSnapshot(jobId, snapshotId) }
    val snap = vm.snapshotLoaded?.takeIf { it.id == snapshotId }
    var dir by remember(snapshotId) { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val selected = remember(snapshotId) { androidx.compose.runtime.mutableStateListOf<String>() }
    var fileMenu by remember { mutableStateOf<FileEntry?>(null) }
    var pendingRestore by remember { mutableStateOf<Set<String>?>(null) }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        val p = pendingRestore
        if (uri != null && p != null) vm.restore(jobId, snapshotId, p.ifEmpty { null }, uri)
        pendingRestore = null
        selected.clear()
    }

    BackHandlerFor(dir.isNotEmpty() && !searching) { dir = dir.substringBeforeLast('/', "") }

    ScreenScaffold(
        title = if (snap != null) formatDateTime(snap.time) else "Kopia",
        onBack = { if (dir.isNotEmpty() && !searching) dir = dir.substringBeforeLast('/', "") else vm.back() },
        actions = { IconButtonRound(Icons.Rounded.Search, "Szukaj", { searching = !searching; query = "" }) },
        bottom = if (snap == null) null else ({
            val n = selected.size
            PrimaryButton(
                if (n > 0) "Przywróć zaznaczone ($n)…" else if (dir.isEmpty()) "Przywróć całą kopię…" else "Przywróć ten folder…",
                Modifier.fillMaxWidth(), Icons.Rounded.Restore,
            ) {
                pendingRestore = when {
                    n > 0 -> selected.toSet()
                    dir.isEmpty() -> emptySet()
                    else -> setOf(dir)
                }
                restoreLauncher.launch(null)
            }
        }),
    ) {
        if (snap == null) {
            Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(color = Color.White) }
            return@ScreenScaffold
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(plural(snap.files.size, "plik", "pliki", "plików"))
            Pill(formatBytes(snap.totalBytes))
            Pill(snap.device)
            if (snap.durationMs > 0) Pill(formatDuration(snap.durationMs))
        }
        if (searching) {
            Field("Szukaj w kopii", query, { query = it }, placeholder = "nazwa pliku lub fragment ścieżki")
        } else if (dir.isNotEmpty()) {
            Text("/ " + dir.replace("/", " / "), color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp))
        }

        data class Node(val name: String, val path: String, val isDir: Boolean, val size: Long, val count: Int, val entry: FileEntry?)
        val nodes: List<Node> = remember(snap, dir, query, searching) {
            if (searching && query.length >= 2) {
                snap.files.filter { it.path.contains(query, ignoreCase = true) }.take(300).map { Node(it.path.substringAfterLast('/'), it.path, false, it.size, 1, it) }
            } else if (searching) {
                emptyList()
            } else {
                val prefix = if (dir.isEmpty()) "" else "$dir/"
                val dirs = LinkedHashMap<String, Pair<Long, Int>>()
                val files = ArrayList<Node>()
                for (f in snap.files) {
                    if (!f.path.startsWith(prefix)) continue
                    val rest = f.path.removePrefix(prefix)
                    val slash = rest.indexOf('/')
                    if (slash < 0) files += Node(rest, f.path, false, f.size, 1, f)
                    else {
                        val name = rest.substring(0, slash)
                        val (s, c) = dirs[name] ?: (0L to 0)
                        dirs[name] = (s + f.size) to (c + 1)
                    }
                }
                dirs.entries.sortedBy { it.key.lowercase() }.map { Node(it.key, prefix + it.key, true, it.value.first, it.value.second, null) } +
                    files.sortedBy { it.name.lowercase() }
            }
        }

        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.PanelHigh).padding(vertical = 4.dp)) {
            if (nodes.isEmpty()) {
                Text(if (searching) "Wpisz co najmniej 2 znaki" else "Pusty folder", color = Palette.TextDim, modifier = Modifier.padding(16.dp))
            }
            nodes.forEachIndexed { i, n ->
                if (i > 0) Divider()
                val checked = n.path in selected
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (n.isDir) dir = n.path else fileMenu = n.entry
                    }.padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButtonRound(if (checked) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank, "Zaznacz", {
                        if (checked) selected.remove(n.path) else selected.add(n.path)
                    }, if (checked) Color.White else Palette.TextFaint)
                    Icon(if (n.isDir) Icons.Rounded.Folder else fileIcon(n.name), null, tint = if (n.isDir) Color.White else Palette.TextDim, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(n.name, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (n.isDir) "${plural(n.count, "plik", "pliki", "plików")} · ${formatBytes(n.size)}"
                            else formatBytes(n.size) + (if (searching) " · " + n.path.substringBeforeLast('/', "") else "") + (n.entry?.mtime?.takeIf { it > 0 }?.let { " · " + formatDateTime(it) } ?: ""),
                            color = Palette.TextFaint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (vm.extracting == n.path) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
        }
    }

    fileMenu?.let { f ->
        AlertDialog(
            onDismissRequest = { fileMenu = null },
            title = { Text(f.path.substringAfterLast('/'), maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    Text("${formatBytes(f.size)} · ${plural(f.chunks.size, "fragment", "fragmenty", "fragmentów")}", color = Palette.TextDim)
                    Text(f.path, color = Palette.TextFaint, fontSize = 11.sp)
                    when {
                        f.path.endsWith(".vcf") -> Text("Otwórz, aby zaimportować kontakty.", color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        f.path.endsWith(".ics") -> Text("Otwórz, aby zaimportować wydarzenia do kalendarza.", color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        f.path.endsWith("sms.json") -> Text("Udostępnij do OpenSMS albo przywróć do folderu i zaimportuj w OpenSMS (Ustawienia → Kopia).", color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton({ vm.openFile(jobId, f, share = true); fileMenu = null }) { Icon(Icons.Rounded.Share, null); Spacer(Modifier.width(6.dp)); Text("Udostępnij") }
                    TextButton({ vm.openFile(jobId, f); fileMenu = null }) { Icon(Icons.Rounded.OpenInNew, null); Spacer(Modifier.width(6.dp)); Text("Otwórz") }
                }
            },
            dismissButton = {
                TextButton({ pendingRestore = setOf(f.path); fileMenu = null; restoreLauncher.launch(null) }) { Text("Przywróć…") }
            },
            containerColor = Palette.PanelHigh,
        )
    }
}

@Composable
private fun BackHandlerFor(enabled: Boolean, onBack: () -> Unit) = androidx.activity.compose.BackHandler(enabled, onBack)

@Composable
fun DiffScreen(vm: AppViewModel, jobId: String, olderId: String, newerId: String) {
    LaunchedEffect(olderId, newerId) { vm.loadDiff(jobId, olderId, newerId) }
    val d = vm.diff
    val older = SnapshotRef.parse(olderId)
    val newer = SnapshotRef.parse(newerId)
    ScreenScaffold("Zmiany", { vm.back() }) {
        if (older != null && newer != null) {
            Text("${formatDateTime(older.time)} → ${formatDateTime(newer.time)}", color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp))
        }
        if (d == null) {
            Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(color = Color.White) }
            return@ScreenScaffold
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("+${d.added.size} nowe", Palette.Ok.copy(alpha = 0.2f))
            Pill("~${d.changed.size} zmienione", Palette.Warn.copy(alpha = 0.2f))
            Pill("−${d.removed.size} usunięte", Palette.Record.copy(alpha = 0.2f))
        }
        if (d.added.isEmpty() && d.changed.isEmpty() && d.removed.isEmpty()) Hint("Brak różnic – kopie są identyczne.")
        DiffList("Nowe", d.added, Palette.Ok)
        DiffList("Zmienione", d.changed, Palette.Warn)
        DiffList("Usunięte", d.removed, Palette.Record)
    }
}

@Composable
private fun DiffList(title: String, items: List<FileEntry>, color: Color) {
    if (items.isEmpty()) return
    Section("$title (${items.size})") {
        items.take(500).forEachIndexed { i, f ->
            if (i > 0) Divider()
            ActionRow(null, f.path.substringAfterLast('/'), f.path.substringBeforeLast('/', "") + " · " + formatBytes(f.size), trailing = {
                com.blacksingularities.opensync.ui.components.StatusDot(color)
            })
        }
        if (items.size > 500) Text("…i ${items.size - 500} więcej", color = Palette.TextFaint, modifier = Modifier.padding(14.dp))
    }
}
