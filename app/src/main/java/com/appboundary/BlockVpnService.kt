package com.appboundary

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream

/**
 * Cuts the internet of blocked apps while they are locked. Only those apps are routed into this
 * on-device tunnel and every packet is dropped, nothing is relayed, so all other apps keep their
 * normal connection at full speed. When a blocked app is unlocked it is taken out of the tunnel,
 * so it also runs at full speed; when you leave it, it goes back in.
 */
class BlockVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var current: Set<String> = emptySet()

    @Volatile
    private var reader: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val wanted = wantedPackages(this)
        if (wanted.isEmpty()) {
            closeTunnel()
            stopSelf()
            return START_NOT_STICKY
        }
        if (tun == null || wanted != current) establishTunnel(wanted)
        return START_STICKY
    }

    private fun establishTunnel(wanted: Set<String>) {
        val builder = Builder()
            .setSession("Network")
            .addAddress("10.255.255.1", 32)
            .addRoute("0.0.0.0", 0)
            .addAddress("fd00:255::1", 128)
            .addRoute("::", 0)
            .setBlocking(true)
        var added = 0
        for (pkg in wanted) {
            try {
                builder.addAllowedApplication(pkg)
                added++
            } catch (e: Exception) {
                // Not installed any more.
            }
        }
        if (added == 0) {
            closeTunnel()
            stopSelf()
            return
        }
        val fresh = try {
            builder.establish()
        } catch (e: Exception) {
            null
        }
        if (fresh == null) {
            // No VPN permission (or another VPN took over): nothing to do.
            closeTunnel()
            stopSelf()
            return
        }
        val old = tun
        val oldReader = reader
        tun = fresh
        current = wanted
        startDrain(fresh)
        // The new tunnel replaces the old one; the old one is closed afterwards so there is no gap.
        oldReader?.interrupt()
        try {
            old?.close()
        } catch (e: Exception) {
        }
    }

    /** Reads and throws away everything the blocked apps try to send. */
    private fun startDrain(fd: ParcelFileDescriptor) {
        val t = Thread {
            val buf = ByteArray(32767)
            try {
                val input = FileInputStream(fd.fileDescriptor)
                while (!Thread.currentThread().isInterrupted) {
                    if (input.read(buf) < 0) break
                }
            } catch (e: Exception) {
                // Tunnel closed.
            }
        }
        t.isDaemon = true
        reader = t
        t.start()
    }

    private fun closeTunnel() {
        reader?.interrupt()
        reader = null
        try {
            tun?.close()
        } catch (e: Exception) {
        }
        tun = null
        current = emptySet()
    }

    override fun onRevoke() {
        // The user turned the VPN off in system settings.
        Prefs.setNetBlock(this, false)
        closeTunnel()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        closeTunnel()
        super.onDestroy()
    }

    companion object {
        /** Blocked apps that are locked right now. */
        private fun wantedPackages(context: Context): Set<String> {
            if (!Prefs.netBlock(context) || !Prefs.isEnabled(context)) return emptySet()
            val unlocked = BoundaryAccessibilityService.unlockedPackage
            return Prefs.targets(context).filter { it != unlocked }.toSet()
        }

        /** Safe to call as often as needed: the tunnel is only rebuilt when the set of locked apps changes. */
        fun sync(context: Context) {
            val app = context.applicationContext
            val wanted = wantedPackages(app)
            if (wanted.isEmpty() && !Prefs.netBlock(app)) {
                app.stopService(Intent(app, BlockVpnService::class.java))
                return
            }
            if (VpnService.prepare(app) != null) return // not allowed yet
            try {
                app.startService(Intent(app, BlockVpnService::class.java))
            } catch (e: Exception) {
                // Could not start from here; the next sync will try again.
            }
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            app.stopService(Intent(app, BlockVpnService::class.java))
        }
    }
}
