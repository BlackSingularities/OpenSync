package com.blacksingularities.opensync.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.blacksingularities.opensync.data.FolderSpec
import com.blacksingularities.opensync.data.Job
import com.blacksingularities.opensync.data.Retention
import com.blacksingularities.opensync.data.Schedule
import com.blacksingularities.opensync.data.ScheduleMode
import com.blacksingularities.opensync.data.Secrets
import com.blacksingularities.opensync.data.SourceType
import com.blacksingularities.opensync.engine.RepoSession
import com.blacksingularities.opensync.sources.Sources
import com.blacksingularities.opensync.ui.components.ActionRow
import com.blacksingularities.opensync.ui.components.ChoiceChips
import com.blacksingularities.opensync.ui.components.Divider
import com.blacksingularities.opensync.ui.components.Field
import com.blacksingularities.opensync.ui.components.Hint
import com.blacksingularities.opensync.ui.components.PrimaryButton
import com.blacksingularities.opensync.ui.components.Section
import com.blacksingularities.opensync.ui.components.SectionHeader
import com.blacksingularities.opensync.ui.components.SecondaryButton
import com.blacksingularities.opensync.ui.components.StepperRow
import com.blacksingularities.opensync.ui.components.Tile
import com.blacksingularities.opensync.ui.components.TileGrid
import com.blacksingularities.opensync.ui.components.ScreenScaffold
import com.blacksingularities.opensync.ui.components.ToggleRow
import com.blacksingularities.opensync.ui.theme.Palette

private enum class RetentionPreset(val label: String, val r: Retention) {
    LEAN("Oszczędnie", Retention(true, 3, 0, 7, 2, 3, 0, 0)),
    BALANCED("Zrównoważony", Retention(true, 10, 0, 7, 4, 12, 3, 0)),
    ARCHIVE("Archiwum", Retention(true, 20, 24, 30, 12, 36, 10, 14)),
    COUNT("Tylko liczba", Retention(true, 10, 0, 0, 0, 0, 0, 0)),
}

@Composable
fun JobEditor(vm: AppViewModel, jobId: String?) {
    val context = LocalContext.current
    val destinations by vm.store.destinations.collectAsState()
    val original = remember(jobId) { jobId?.let { vm.store.job(it) } }
    var job by remember(jobId) {
        mutableStateOf(original ?: Job(destinationId = destinations.firstOrNull()?.id ?: ""))
    }
    val hasPassword = job.repoPassword.isNotEmpty()
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var replacePassword by remember { mutableStateOf(false) }
    var changeDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var excludeText by remember(jobId) { mutableStateOf(job.filters.exclude.joinToString("\n")) }
    var ssidText by remember(jobId) { mutableStateOf(job.conditions.homeSsids.joinToString(", ")) }
    var tagsText by remember(jobId) { mutableStateOf(job.tags.joinToString(", ")) }
    var error by remember { mutableStateOf<String?>(null) }
    var more by remember(jobId) { mutableStateOf(false) }
    var customRetention by remember(jobId) { mutableStateOf(RetentionPreset.entries.none { it.r == job.retention }) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { !it }) vm.toast = "Bez uprawnień część danych nie zostanie skopiowana"
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val label = DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').substringAfterLast('/').ifBlank { "Folder" }
            job = job.copy(folders = job.folders + FolderSpec(uri.toString(), label), sources = job.sources + SourceType.FOLDERS)
        }
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) RepoSession.currentSsid(context)?.let { s -> ssidText = (ssidText.split(',').map { it.trim() }.filter { it.isNotEmpty() } + s).distinct().joinToString(", ") }
            ?: run { vm.toast = "Nie rozpoznano sieci – włącz lokalizację" }
    }

    fun missingPermissions(types: Collection<SourceType>) = Sources.permissionsFor(types)
        .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }

    fun toggleSource(t: SourceType, on: Boolean) {
        job = job.copy(sources = if (on) job.sources + t else job.sources - t)
        if (on) missingPermissions(listOf(t)).takeIf { it.isNotEmpty() }?.let { permLauncher.launch(it.toTypedArray()) }
        if (on && t == SourceType.FOLDERS && job.folders.isEmpty()) folderLauncher.launch(null)
    }

    fun save() {
        error = null
        val d = vm.store.destination(job.destinationId)
        when {
            d == null -> { error = "Wybierz miejsce kopii"; return }
            job.sources.isEmpty() -> { error = "Zaznacz co najmniej jedno źródło"; return }
        }
        var final = job.copy(
            name = job.name.trim().ifEmpty { "Kopia telefonu" },
            repoPath = job.repoPath.trim().trim('/').ifEmpty { "OpenSync" },
            filters = job.filters.copy(exclude = excludeText.lines().map { it.trim() }.filter { it.isNotEmpty() }),
            conditions = job.conditions.copy(homeSsids = ssidText.split(',').map { it.trim() }.filter { it.isNotEmpty() }),
            tags = tagsText.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        )
        if (!hasPassword || replacePassword) {
            when {
                password.length < 8 -> { error = "Hasło musi mieć co najmniej 8 znaków"; return }
                !replacePassword && password != confirm -> { error = "Hasła się różnią"; return }
            }
            final = final.copy(repoPassword = Secrets.protect(password))
        }
        val scheduleChanged = original == null || original.schedule != final.schedule || original.conditions != final.conditions || original.enabled != final.enabled
        vm.saveJob(final, scheduleChanged)
        missingPermissions(final.sources).takeIf { it.isNotEmpty() }?.let { permLauncher.launch(it.toTypedArray()) }
        vm.toast = if (original == null) "Zadanie utworzone" else "Zapisano"
        vm.back()
    }

    ScreenScaffold(
        title = if (original == null) "Nowe zadanie" else job.name.ifBlank { "Zadanie" },
        onBack = { vm.back() },
        bottom = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                error?.let { Text(it, color = Palette.Record) }
                PrimaryButton("Zapisz", Modifier.fillMaxWidth(), Icons.Rounded.Check) { save() }
            }
        },
    ) {
        Section {
            Field("Nazwa zadania", job.name, { job = job.copy(name = it) }, placeholder = "Kopia telefonu")
        }

        // ---- Miejsce ----
        Section("Dokąd", icon = Icons.Rounded.Inventory2, footer = "Kopie trafią do podfolderu „${job.repoPath.ifBlank { "OpenSync" }}”. Kilka telefonów może używać tego samego miejsca i hasła.") {
            destinations.forEachIndexed { i, d ->
                if (i > 0) Divider()
                ActionRow(
                    d.type.icon(), d.name.ifBlank { d.type.label }, d.describe(),
                    trailing = {
                        androidx.compose.material3.Icon(
                            if (d.id == job.destinationId) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked, null,
                            tint = if (d.id == job.destinationId) Color.White else Palette.TextFaint,
                        )
                    },
                ) { job = job.copy(destinationId = d.id) }
            }
            if (destinations.isNotEmpty()) Divider()
            ActionRow(Icons.Rounded.AddCircleOutline, "Dodaj nowe miejsce") { vm.go(Screen.EditDestination(null)) }
            if (more) Field("Folder repozytorium", job.repoPath, { job = job.copy(repoPath = it) }, placeholder = "OpenSync")
        }

        // ---- Szyfrowanie ----
        Section("Szyfrowanie", icon = Icons.Rounded.Lock, footer = "AES-256-GCM, klucz z hasła przez PBKDF2 (600 000 iteracji). Bez hasła nikt – także Ty – nie odczyta kopii. Zapisz je w menedżerze haseł.") {
            if (hasPassword && !replacePassword) {
                ActionRow(Icons.Rounded.Key, "Hasło jest ustawione", "Przechowywane w sprzętowym magazynie kluczy telefonu")
                Divider()
                ActionRow(Icons.Rounded.Refresh, "Zmień hasło repozytorium", "Szybkie – dane nie są szyfrowane ponownie") { changeDialog = true }
                Divider()
                ActionRow(Icons.Rounded.Key, "Podaj inne hasło", "Gdy repozytorium założono na innym urządzeniu") { replacePassword = true }
            } else {
                Field("Hasło szyfrowania", password, { password = it }, password = true, supporting = if (replacePassword) "Hasło istniejącego repozytorium" else "Co najmniej 8 znaków")
                if (!replacePassword) Field("Powtórz hasło", confirm, { confirm = it }, password = true)
            }
        }

        // ---- Źródła ----
        SectionHeader("Co kopiować", Icons.Rounded.FolderOpen)
        TileGrid(SourceType.entries.map { t -> Tile(t.icon(), t.short(), t in job.sources) { toggleSource(t, it) } })
        Text(
            job.sources.sortedBy { it.ordinal }.joinToString(" · ") { it.label }.ifEmpty { "Nic nie zaznaczono" },
            color = Palette.TextFaint, fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp),
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        if (SourceType.FOLDERS in job.sources || job.folders.isNotEmpty()) {
            Section("Wybrane foldery", footer = "Np. eksporty z innych aplikacji, dokumenty, nagrania. Kopiowane w całości z podfolderami.") {
                job.folders.forEachIndexed { i, f ->
                    if (i > 0) Divider()
                    ActionRow(Icons.Rounded.Folder, f.label, Uri.decode(f.uri.substringAfterLast("/tree/")).substringAfter(':', "").ifEmpty { "Folder" }, trailing = {
                        com.blacksingularities.opensync.ui.components.IconButtonRound(Icons.Rounded.Close, "Usuń", { job = job.copy(folders = job.folders - f) }, Palette.TextDim)
                    })
                }
                if (job.folders.isNotEmpty()) Divider()
                ActionRow(Icons.Rounded.Add, "Dodaj folder") { folderLauncher.launch(null) }
            }
        }
        if (SourceType.APPS in job.sources) {
            Section {
                ToggleRow(null, "Kopiuj pliki APK", "Pozwala zainstalować aplikacje bez sklepu. Dane aplikacji Android chroni – kopiowana jest tylko instalacja.", job.filters.includeApks) {
                    job = job.copy(filters = job.filters.copy(includeApks = it))
                }
            }
        }

        // ---- Harmonogram ----
        Section("Kiedy", icon = Icons.Rounded.Schedule, footer = "Android może przesunąć start o kilkanaście minut, aby oszczędzać baterię.") {
            ChoiceChips(ScheduleMode.entries, job.schedule.mode, { it.label }) { job = job.copy(schedule = job.schedule.copy(mode = it)) }
            val s = job.schedule
            when (s.mode) {
                ScheduleMode.INTERVAL -> StepperRow("Co ile godzin", null, s.intervalHours, 1..168, 1, { "$it h" }) { job = job.copy(schedule = s.copy(intervalHours = it)) }
                ScheduleMode.DAILY, ScheduleMode.WEEKLY -> {
                    if (s.mode == ScheduleMode.WEEKLY) {
                        ChoiceChips((1..7).toList(), s.weekday, { Schedule.WEEKDAYS[it - 1].take(3) }) { job = job.copy(schedule = s.copy(weekday = it)) }
                    }
                    StepperRow("Godzina", null, s.hour, 0..23, 1, { "%02d".format(it) }) { job = job.copy(schedule = s.copy(hour = it)) }
                    StepperRow("Minuta", null, s.minute, 0..55, 5, { "%02d".format(it) }) { job = job.copy(schedule = s.copy(minute = it)) }
                }
                ScheduleMode.MANUAL -> {}
            }
            Divider()
            ToggleRow(Icons.Rounded.PhotoCamera, "Po zrobieniu zdjęcia lub filmu", "Kopia kilka minut po pojawieniu się nowych multimediów", job.conditions.onNewMedia) {
                job = job.copy(conditions = job.conditions.copy(onNewMedia = it))
            }
        }

        // ---- Warunki ----
        val c = job.conditions
        Section("Warunki", icon = Icons.Rounded.Wifi) {
            ToggleRow(Icons.Rounded.Wifi, "Tylko Wi-Fi / sieć bez limitu", null, c.unmeteredOnly) { job = job.copy(conditions = c.copy(unmeteredOnly = it)) }
            ToggleRow(Icons.Rounded.BatteryChargingFull, "Tylko podczas ładowania", null, c.chargingOnly) { job = job.copy(conditions = c.copy(chargingOnly = it)) }
            if (more) {
            ToggleRow(Icons.Rounded.BatteryStd, "Nie przy niskiej baterii", null, c.batteryNotLow) { job = job.copy(conditions = c.copy(batteryNotLow = it)) }
            ToggleRow(Icons.Rounded.Bedtime, "Gdy telefon nie jest używany", "Najlepiej w nocy, razem z ładowaniem", c.idleOnly) { job = job.copy(conditions = c.copy(idleOnly = it)) }
            ToggleRow(Icons.Rounded.Refresh, "Ponawiaj, gdy NAS jest niedostępny", "Np. poza domem – kopia wykona się po powrocie", c.retryWhenUnreachable) { job = job.copy(conditions = c.copy(retryWhenUnreachable = it)) }
            Field("Domowe sieci Wi-Fi (opcjonalnie)", ssidText, { ssidText = it }, placeholder = "MojaSiec, MojaSiec-5G", supporting = "Kopia tylko w tych sieciach. Wymaga dostępu do lokalizacji.")
            ActionRow(Icons.Rounded.Add, "Dodaj bieżącą sieć") {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    RepoSession.currentSsid(context)?.let { s -> ssidText = (ssidText.split(',').map { it.trim() }.filter { it.isNotEmpty() } + s).distinct().joinToString(", ") }
                        ?: run { vm.toast = "Nie rozpoznano sieci Wi-Fi" }
                } else {
                    locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }
            }
        }

        // ---- Retencja ----
        val r = job.retention
        Section("Ile kopii trzymać", icon = Icons.Rounded.Inventory2, footer = "Kopia zostaje, jeśli spełnia którąkolwiek regułę. Najnowsza i przypięte zostają zawsze. Teraz: ${r.describe()}.") {
            ToggleRow(null, "Usuwaj nadmiarowe kopie", "Wyłączone = wszystkie wersje zostają na zawsze", r.enabled) { job = job.copy(retention = r.copy(enabled = it)) }
            if (r.enabled) {
                ChoiceChips(RetentionPreset.entries + listOf<RetentionPreset?>(null), if (customRetention) null else RetentionPreset.entries.firstOrNull { it.r == r }, { it?.label ?: "Własne" }) { p ->
                    customRetention = p == null
                    if (p != null) job = job.copy(retention = p.r)
                }
                if (customRetention) {
                StepperRow("Ostatnie kopie", "Liczba najnowszych", r.keepLast, 0..100) { job = job.copy(retention = r.copy(keepLast = it)) }
                StepperRow("Godzinowe", "Po jednej z każdej godziny", r.keepHourly, 0..168) { job = job.copy(retention = r.copy(keepHourly = it)) }
                StepperRow("Dzienne", "Po jednej z każdego dnia", r.keepDaily, 0..365) { job = job.copy(retention = r.copy(keepDaily = it)) }
                StepperRow("Tygodniowe", null, r.keepWeekly, 0..260) { job = job.copy(retention = r.copy(keepWeekly = it)) }
                StepperRow("Miesięczne", null, r.keepMonthly, 0..240) { job = job.copy(retention = r.copy(keepMonthly = it)) }
                StepperRow("Roczne", null, r.keepYearly, 0..50) { job = job.copy(retention = r.copy(keepYearly = it)) }
                StepperRow("Wszystkie z ostatnich dni", null, r.keepWithinDays, 0..365) { job = job.copy(retention = r.copy(keepWithinDays = it)) }
                }
            }
        }

        Section {
            ActionRow(Icons.Rounded.Tune, if (more) "Mniej opcji" else "Więcej opcji", if (more) null else "Filtry, kompresja, weryfikacja, folder repozytorium – domyślne ustawienia są dobre") { more = !more }
        }

        if (more) {
        // ---- Filtry ----
        val f = job.filters
        Section("Filtry", icon = Icons.Rounded.FilterAlt) {
            ToggleRow(null, "Pomijaj ukryte pliki i foldery", "Zaczynające się od kropki", f.skipHidden) { job = job.copy(filters = f.copy(skipHidden = it)) }
            StepperRow("Maks. rozmiar pliku", "0 = bez limitu", f.maxFileMb, 0..65536, if (f.maxFileMb >= 1024) 512 else 50, { if (it == 0) "bez" else if (it >= 1024) "${it / 1024} GB" else "$it MB" }) {
                job = job.copy(filters = f.copy(maxFileMb = it))
            }
            StepperRow("Multimedia z ostatnich", "0 = wszystkie", f.mediaMaxAgeDays, 0..3650, if (f.mediaMaxAgeDays >= 90) 30 else 7, { if (it == 0) "wszystkie" else "$it dni" }) {
                job = job.copy(filters = f.copy(mediaMaxAgeDays = it))
            }
            Field("Wykluczenia (jeden wzorzec w linii)", excludeText, { excludeText = it }, singleLine = false, minLines = 3, supporting = "* dowolny ciąg, ? jeden znak, np. *.tmp, media/WhatsApp/*")
        }

        // ---- Zaawansowane ----
        Section("Zaawansowane", icon = Icons.Rounded.Tune) {
            Text("Kompresja", color = Palette.TextDim, modifier = Modifier.padding(start = 14.dp, top = 8.dp))
            ChoiceChips(listOf(0, 1, 6, 9), job.compression, { when (it) { 0 -> "Wyłączona"; 1 -> "Szybka"; 6 -> "Standardowa"; else -> "Maksymalna" } }) { job = job.copy(compression = it) }
            Text("Wielkość fragmentu", color = Palette.TextDim, modifier = Modifier.padding(start = 14.dp, top = 4.dp))
            ChoiceChips(listOf(1, 4, 8, 16, 32), job.chunkMb, { "$it MB" }) { job = job.copy(chunkMb = it) }
            StepperRow("Weryfikacja co", "Po tylu kopiach sprawdzana jest spójność repozytorium (0 = nigdy)", job.verifyEvery, 0..100, 1, { if (it == 0) "nigdy" else "$it" }) { job = job.copy(verifyEvery = it) }
            StepperRow("Próbka weryfikacji", "Jaka część danych jest pobierana i sprawdzana", job.verifySamplePct, 0..100, 5, { "$it%" }) { job = job.copy(verifySamplePct = it) }
            StepperRow("Limit wysyłania", "0 = bez limitu", job.limitKbps, 0..100_000, if (job.limitKbps >= 1000) 1000 else 100, { if (it == 0) "bez" else if (it >= 1000) "${it / 1000} MB/s" else "$it KB/s" }) {
                job = job.copy(limitKbps = it)
            }
            ToggleRow(Icons.Rounded.NotificationsActive, "Powiadamiaj o udanej kopii", "Błędy są zgłaszane zawsze", job.notifySuccess) { job = job.copy(notifySuccess = it) }
            Field("Etykiety (po przecinku)", tagsText, { tagsText = it }, placeholder = "np. telefon-adama", keyboard = KeyboardType.Text)
            ToggleRow(Icons.Rounded.PowerSettingsNew, "Zadanie włączone", "Wyłączone nie uruchamia się według harmonogramu", job.enabled) { job = job.copy(enabled = it) }
        }
        }

        if (original != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Usuń zadanie", Modifier.weight(1f), Icons.Rounded.Delete, danger = true) { confirmDelete = true }
            }
            Hint("Usunięcie zadania nie kasuje kopii z NAS-a. Możesz je później otworzyć, tworząc zadanie z tym samym miejscem, folderem i hasłem.")
        }
    }

    if (confirmDelete && original != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Usunąć zadanie?") },
            text = { Text("Kopie na serwerze zostaną nienaruszone.") },
            confirmButton = { TextButton({ vm.deleteJob(original.id); confirmDelete = false; vm.back() }) { Text("Usuń", color = Palette.Record) } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Anuluj") } },
            containerColor = Palette.PanelHigh,
        )
    }

    if (changeDialog && original != null) {
        var p1 by remember { mutableStateOf("") }
        var p2 by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!busy) changeDialog = false },
            title = { Text("Nowe hasło repozytorium") },
            text = {
                Column {
                    Field("Nowe hasło", p1, { p1 = it }, password = true)
                    Field("Powtórz", p2, { p2 = it }, password = true)
                    Text("Inne urządzenia korzystające z tego repozytorium też będą potrzebować nowego hasła.", color = Palette.TextDim)
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && p1.length >= 8 && p1 == p2, onClick = {
                    busy = true
                    vm.changeRepoPassword(original, p1) { ok ->
                        busy = false
                        if (ok) { changeDialog = false; job = vm.store.job(original.id) ?: job }
                    }
                }) { Text(if (busy) "Zmieniam…" else "Zmień") }
            },
            dismissButton = { TextButton({ changeDialog = false }, enabled = !busy) { Text("Anuluj") } },
            containerColor = Palette.PanelHigh,
        )
    }
}
