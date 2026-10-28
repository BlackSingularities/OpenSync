package com.blacksingularities.opensync.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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
import com.blacksingularities.opensync.backend.WakeOnLan
import com.blacksingularities.opensync.data.DestType
import com.blacksingularities.opensync.data.Destination
import com.blacksingularities.opensync.data.Secrets
import com.blacksingularities.opensync.ui.components.ActionRow
import com.blacksingularities.opensync.ui.components.BigAction
import com.blacksingularities.opensync.ui.components.ChoiceChips
import com.blacksingularities.opensync.ui.components.Divider
import com.blacksingularities.opensync.ui.components.Field
import com.blacksingularities.opensync.ui.components.Hint
import com.blacksingularities.opensync.ui.components.PrimaryButton
import com.blacksingularities.opensync.ui.components.ScreenScaffold
import com.blacksingularities.opensync.ui.components.SecondaryButton
import com.blacksingularities.opensync.ui.components.Section
import com.blacksingularities.opensync.ui.components.StepperRow
import com.blacksingularities.opensync.ui.components.ToggleRow
import com.blacksingularities.opensync.ui.theme.Palette

@Composable
fun DestinationsScreen(vm: AppViewModel) {
    val destinations by vm.store.destinations.collectAsState()
    val jobs by vm.store.jobs.collectAsState()
    ScreenScaffold("Miejsca kopii", { vm.back() }) {
        BigAction(Icons.Rounded.Add, "Dodaj miejsce", "NAS, serwer, chmura domowa albo dysk USB") { vm.go(Screen.EditDestination(null)) }
        if (destinations.isNotEmpty()) {
            Section("Zapisane") {
                destinations.forEachIndexed { i, d ->
                    if (i > 0) Divider()
                    val used = jobs.count { it.destinationId == d.id }
                    ActionRow(d.type.icon(), d.name.ifBlank { d.type.label }, d.describe() + " · " + plural(used, "zadanie", "zadania", "zadań") + if (d.wolMac.isNotBlank()) " · Wake-on-LAN" else "") {
                        vm.go(Screen.EditDestination(d.id))
                    }
                }
            }
        }
        Hint("Dane są szyfrowane w telefonie, zanim trafią do celu. Serwer przechowuje wyłącznie szyfrogram, więc może to być nawet dysk współdzielony z innymi domownikami.")
    }
}

@Composable
fun DestinationEditor(vm: AppViewModel, destId: String?) {
    val context = LocalContext.current
    val original = remember(destId) { destId?.let { vm.store.destination(it) } }
    var d by remember(destId) { mutableStateOf(original ?: Destination(name = "NAS")) }
    var password by remember(destId) { mutableStateOf(Secrets.reveal(d.password)) }
    var privateKey by remember(destId) { mutableStateOf(Secrets.reveal(d.privateKey)) }
    var portText by remember(destId) { mutableStateOf(if (d.port > 0) d.port.toString() else "") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            d = d.copy(folderUri = uri.toString())
        }
    }

    fun current() = d.copy(
        name = d.name.trim(),
        port = portText.toIntOrNull() ?: 0,
        password = Secrets.protect(password),
        privateKey = Secrets.protect(privateKey.trim()),
    )

    fun validate(x: Destination): String? = when (x.type) {
        DestType.SMB -> when {
            x.host.isBlank() -> "Podaj adres serwera"
            x.share.isBlank() -> "Podaj nazwę udziału"
            else -> null
        }
        DestType.SFTP -> when {
            x.host.isBlank() -> "Podaj adres serwera"
            x.user.isBlank() -> "Podaj nazwę użytkownika"
            else -> null
        }
        DestType.WEBDAV -> if (!x.url.startsWith("http://") && !x.url.startsWith("https://")) "Adres musi zaczynać się od http:// lub https://" else null
        DestType.FOLDER -> if (x.folderUri.isBlank()) "Wybierz folder" else null
    } ?: if (x.wolMac.isNotBlank() && WakeOnLan.parseMac(x.wolMac) == null) "Nieprawidłowy adres MAC" else null

    ScreenScaffold(
        title = if (original == null) "Nowe miejsce" else d.name.ifBlank { "Miejsce" },
        onBack = { vm.back() },
        bottom = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                error?.let { Text(it, color = Palette.Record) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(if (vm.testing) "Sprawdzam…" else "Testuj", Modifier.weight(1f), Icons.Rounded.NetworkCheck) {
                        val x = current()
                        error = validate(x)
                        if (error == null && !vm.testing) vm.testDestination(x) { key -> d = d.copy(hostKey = key) }
                    }
                    PrimaryButton("Zapisz", Modifier.weight(1f), Icons.Rounded.Check) {
                        val x = current()
                        error = validate(x)
                        if (error == null) {
                            vm.saveDestination(x)
                            vm.toast = "Zapisano miejsce"
                            vm.back()
                        }
                    }
                }
            }
        },
    ) {
        Section {
            ChoiceChips(DestType.entries, d.type, { it.label.substringBefore(" (") }) { d = d.copy(type = it); vm.testResult = null }
            Field("Nazwa", d.name, { d = d.copy(name = it) }, placeholder = "np. Synology w salonie")
        }

        vm.testResult?.let { (ok, msg) ->
            Section {
                ActionRow(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Error, if (ok) "Działa" else "Nie działa", msg, danger = !ok)
            }
        }
        if (vm.testing) Row { CircularProgressIndicator(color = Color.White) }

        when (d.type) {
            DestType.SMB -> Section("Udział SMB", footer = "SMB 2/3 – Synology, QNAP, TrueNAS, Unraid, Samba, Windows. Ścieżka to podfolder w udziale (opcjonalnie).") {
                Field("Serwer (adres IP lub nazwa)", d.host, { d = d.copy(host = it.trim()) }, placeholder = "192.168.1.10")
                Field("Udział", d.share, { d = d.copy(share = it) }, placeholder = "backup")
                Field("Ścieżka w udziale", d.path, { d = d.copy(path = it) }, placeholder = "telefony")
                Field("Użytkownik", d.user, { d = d.copy(user = it) }, supporting = "Puste = dostęp anonimowy, „guest” = gość")
                Field("Hasło", password, { password = it }, password = true)
                Field("Domena (opcjonalnie)", d.domain, { d = d.copy(domain = it) })
                Field("Port", portText, { portText = it.filter(Char::isDigit) }, placeholder = "445", keyboard = KeyboardType.Number)
            }
            DestType.SFTP -> Section("Serwer SFTP", footer = "Klucz serwera jest zapamiętywany przy pierwszym połączeniu. Zmiana klucza zablokuje kopię – to ochrona przed podszyciem się pod serwer.") {
                Field("Serwer", d.host, { d = d.copy(host = it.trim()) }, placeholder = "192.168.1.10")
                Field("Port", portText, { portText = it.filter(Char::isDigit) }, placeholder = "22", keyboard = KeyboardType.Number)
                Field("Użytkownik", d.user, { d = d.copy(user = it) })
                Field(if (privateKey.isBlank()) "Hasło" else "Hasło klucza (jeśli jest)", password, { password = it }, password = true)
                Field("Klucz prywatny (opcjonalnie)", privateKey, { privateKey = it }, singleLine = false, minLines = 3, placeholder = "-----BEGIN OPENSSH PRIVATE KEY-----")
                Field("Katalog", d.path, { d = d.copy(path = it) }, placeholder = "/volume1/backup")
                if (d.hostKey.isNotBlank()) {
                    Divider()
                    ActionRow(Icons.Rounded.Fingerprint, "Zapamiętany klucz serwera", d.hostKey, trailing = {
                        SecondaryButton("Zapomnij") { d = d.copy(hostKey = "") }
                    })
                }
            }
            DestType.WEBDAV -> Section("WebDAV", footer = "Nextcloud: https://serwer/remote.php/dav/files/UŻYTKOWNIK/. Zalecane hasło aplikacji zamiast głównego.") {
                Field("Adres", d.url, { d = d.copy(url = it.trim()) }, placeholder = "https://nas.local:5006/backup", keyboard = KeyboardType.Uri)
                Field("Użytkownik", d.user, { d = d.copy(user = it) })
                Field("Hasło", password, { password = it }, password = true)
                ToggleRow(null, "Akceptuj certyfikat samopodpisany", "Tylko w zaufanej sieci domowej. Dane i tak są zaszyfrowane.", d.insecureTls) { d = d.copy(insecureTls = it) }
            }
            DestType.FOLDER -> Section("Folder", footer = "Pamięć telefonu, karta SD albo dysk USB podłączony przez OTG. Dysk USB musi być podłączony w czasie kopii.") {
                ActionRow(Icons.Rounded.FolderOpen, if (d.folderUri.isBlank()) "Wybierz folder" else "Zmień folder", d.folderUri.takeIf { it.isNotBlank() }?.let { d.describe() }) {
                    folderLauncher.launch(null)
                }
            }
        }

        if (d.type != DestType.FOLDER) {
            Section("Wyszukaj w sieci", footer = "Serwery ogłaszające się przez Bonjour/mDNS (SMB, SSH, WebDAV).") {
                ActionRow(Icons.Rounded.Radar, if (vm.discovering) "Szukam…" else "Znajdź serwery w sieci", null) { vm.discover() }
                vm.found.forEach { f ->
                    Divider()
                    ActionRow(f.type.icon(), f.name, "${f.host}:${f.port} · ${f.type.label.substringBefore(" (")}") {
                        d = when (f.type) {
                            DestType.WEBDAV -> d.copy(type = f.type, url = "http://${f.host}:${f.port}/", name = d.name.ifBlank { f.name })
                            else -> d.copy(type = f.type, host = f.host, name = if (d.name.isBlank() || d.name == "NAS") f.name else d.name)
                        }
                        portText = if (f.port != d.defaultPort) f.port.toString() else ""
                    }
                }
            }

            Section("Wake-on-LAN", footer = "Jeśli NAS usypia się w nocy, OpenSync wyśle „magiczny pakiet” i poczeka, aż się obudzi.") {
                Field("Adres MAC serwera", d.wolMac, { d = d.copy(wolMac = it.trim()) }, placeholder = "00:11:32:AA:BB:CC")
                Field("Adres rozgłoszeniowy (opcjonalnie)", d.wolBroadcast, { d = d.copy(wolBroadcast = it.trim()) }, placeholder = "automatycznie")
                StepperRow("Czekaj na wybudzenie", null, d.wolWaitSec, 10..600, 10, { "$it s" }) { d = d.copy(wolWaitSec = it) }
                if (d.wolMac.isNotBlank()) {
                    Divider()
                    ActionRow(Icons.Rounded.PowerSettingsNew, "Obudź teraz") { vm.wake(d) }
                }
            }
        }

        if (original != null) {
            SecondaryButton("Usuń miejsce", Modifier.fillMaxWidth(), Icons.Rounded.Delete, danger = true) { confirmDelete = true }
        }
    }

    if (confirmDelete && original != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Usunąć miejsce?") },
            text = { Text("Usuwane są tylko ustawienia połączenia. Pliki na serwerze zostają.") },
            confirmButton = { androidx.compose.material3.TextButton({ confirmDelete = false; if (vm.deleteDestination(original.id)) vm.back() }) { Text("Usuń", color = Palette.Record) } },
            dismissButton = { androidx.compose.material3.TextButton({ confirmDelete = false }) { Text("Anuluj") } },
            containerColor = Palette.PanelHigh,
        )
    }
}
