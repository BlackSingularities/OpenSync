package com.blacksingularities.opensync

import android.app.Application
import com.blacksingularities.opensync.work.Notifier
import com.blacksingularities.opensync.work.Scheduler
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class OpenSyncApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Android ma okrojonego dostawcę „BC”; SSH (sshj) potrzebuje pełnego BouncyCastle.
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        Notifier.createChannels(this)
        runCatching { Scheduler.reconcile(this) }
    }
}
