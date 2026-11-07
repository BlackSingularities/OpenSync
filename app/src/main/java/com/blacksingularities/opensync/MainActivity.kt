package com.blacksingularities.opensync

import com.blacksingularities.opensync.feedback.Feedback
import com.blacksingularities.opensync.feedback.CrashReportPrompt
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.blacksingularities.opensync.ui.AppRoot
import com.blacksingularities.opensync.ui.AppViewModel
import com.blacksingularities.opensync.ui.theme.OpenSyncTheme
import com.blacksingularities.opensync.update.UpdateManager

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Feedback.install(this, "opensync")
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        UpdateManager.clearUpdatedNotification(this)
        setContent { OpenSyncTheme { CrashReportPrompt(); AppRoot(vm) } }
    }
}
