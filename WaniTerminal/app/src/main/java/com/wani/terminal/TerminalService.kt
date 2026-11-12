package com.wani.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/** Keeps terminal sessions alive in the background (foreground service). */
class TerminalService : Service(), TerminalSessionClient {

    interface UiListener {
        fun onSessionsChanged()
        fun onScreenChanged(s: TerminalSession)
        fun onExit()
    }

    inner class LocalBinder : Binder() {
        val service: TerminalService get() = this@TerminalService
    }

    val sessions = mutableListOf<TerminalSession>()
    var uiListener: UiListener? = null
    private var wakeLock: PowerManager.WakeLock? = null
    val wakeLockOn: Boolean get() = wakeLock?.isHeld == true

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "Wani Terminal", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val exit = PendingIntent.getService(
            this, 1, Intent(this, TerminalService::class.java).setAction(ACTION_EXIT),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CH)
            .setContentTitle("Wani Terminal")
            .setContentText("Sessions are running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Exit", exit
                ).build()
            )
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_EXIT) {
            sessions.toList().forEach { it.finishIfRunning() }
            setWakeLock(false)
            uiListener?.onExit()
            stopSelf()
        }
        return START_STICKY
    }

    fun newSession(): TerminalSession {
        val l = ProotLauncher(this)
        val s = TerminalSession(l.shell, l.cwd, l.args, l.env, 2000, this)
        sessions.add(s)
        uiListener?.onSessionsChanged()
        return s
    }

    fun setWakeLock(on: Boolean) {
        if (on) {
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wani:terminal")
            }
            if (wakeLock?.isHeld == false) wakeLock?.acquire()
        } else {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
    }

    override fun onDestroy() {
        setWakeLock(false)
        sessions.toList().forEach { it.finishIfRunning() }
        super.onDestroy()
    }

    // ---- TerminalSessionClient ----
    override fun onTextChanged(changedSession: TerminalSession) {
        uiListener?.onScreenChanged(changedSession)
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        uiListener?.onSessionsChanged()
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        sessions.remove(finishedSession)
        uiListener?.onSessionsChanged()
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("terminal", text ?: ""))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val cm = getSystemService(ClipboardManager::class.java)
        val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        session?.emulator?.paste(t)
    }

    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String?, message: String?) { Log.e(tag, message ?: "") }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag, message ?: "") }
    override fun logInfo(tag: String?, message: String?) { Log.i(tag, message ?: "") }
    override fun logDebug(tag: String?, message: String?) { Log.d(tag, message ?: "") }
    override fun logVerbose(tag: String?, message: String?) { Log.v(tag, message ?: "") }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(tag, message ?: "", e)
    }
    override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag, "", e) }

    companion object {
        const val CH = "wani_terminal"
        const val ID = 1
        const val ACTION_EXIT = "com.wani.terminal.EXIT"
    }
}
