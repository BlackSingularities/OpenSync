package com.blacksingularities.opensync.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.blacksingularities.opensync.data.DestType
import com.blacksingularities.opensync.data.SourceType
import com.blacksingularities.opensync.ui.theme.Palette

@Composable
fun AppRoot(vm: AppViewModel) {
    val context = LocalContext.current
    BackHandler(enabled = vm.canGoBack) { vm.back() }
    LaunchedEffect(vm.toast) {
        vm.toast?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.toast = null }
    }
    Box(Modifier.fillMaxSize().background(Palette.Ink)) {
        AnimatedContent(vm.screen, transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(100)) }, label = "screen") { s ->
            when (s) {
                Screen.Home -> HomeScreen(vm)
                is Screen.EditJob -> JobEditor(vm, s.jobId)
                Screen.Destinations -> DestinationsScreen(vm)
                is Screen.EditDestination -> DestinationEditor(vm, s.destId)
                is Screen.Snapshots -> SnapshotsScreen(vm, s.jobId)
                is Screen.Browse -> BrowseScreen(vm, s.jobId, s.snapshotId)
                is Screen.Diff -> DiffScreen(vm, s.jobId, s.olderId, s.newerId)
                Screen.History -> HistoryScreen(vm)
                Screen.Settings -> SettingsScreen(vm)
            }
        }
    }
}

fun SourceType.icon(): ImageVector = when (this) {
    SourceType.SMS -> Icons.Rounded.Sms
    SourceType.CALLS -> Icons.Rounded.Call
    SourceType.CONTACTS -> Icons.Rounded.Contacts
    SourceType.CALENDAR -> Icons.Rounded.CalendarMonth
    SourceType.PHOTOS -> Icons.Rounded.Photo
    SourceType.VIDEOS -> Icons.Rounded.Movie
    SourceType.AUDIO -> Icons.Rounded.MusicNote
    SourceType.DOWNLOADS -> Icons.Rounded.Download
    SourceType.FOLDERS -> Icons.Rounded.Folder
    SourceType.APPS -> Icons.Rounded.Apps
    SourceType.DEVICE -> Icons.Rounded.PhoneAndroid
}

/** Krótki podpis na kafelku. */
fun SourceType.short(): String = when (this) {
    SourceType.SMS -> "SMS i MMS"
    SourceType.CALLS -> "Połączenia"
    SourceType.CONTACTS -> "Kontakty"
    SourceType.CALENDAR -> "Kalendarz"
    SourceType.PHOTOS -> "Zdjęcia"
    SourceType.VIDEOS -> "Filmy"
    SourceType.AUDIO -> "Audio"
    SourceType.DOWNLOADS -> "Pobrane"
    SourceType.FOLDERS -> "Foldery"
    SourceType.APPS -> "Aplikacje"
    SourceType.DEVICE -> "Urządzenie"
}

fun DestType.icon(): ImageVector = when (this) {
    DestType.SMB -> Icons.Rounded.Lan
    DestType.SFTP -> Icons.Rounded.Terminal
    DestType.WEBDAV -> Icons.Rounded.Cloud
    DestType.FOLDER -> Icons.Rounded.Usb
}
