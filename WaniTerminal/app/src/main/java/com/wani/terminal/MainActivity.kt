package com.wani.terminal

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

class MainActivity : Activity(), TerminalService.UiListener {

    private var service: TerminalService? = null
    private var current: TerminalSession? = null

    private lateinit var terminalView: TerminalView
    private lateinit var tabBar: LinearLayout
    private lateinit var setupBox: LinearLayout
    private lateinit var setupText: TextView
    private lateinit var setupBar: ProgressBar
    private lateinit var ctrlBtn: Button
    private lateinit var altBtn: Button
    private lateinit var prefs: android.content.SharedPreferences

    private var ctrl = false
    private var alt = false
    private var fontPx = 0

    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------- lifecycle ----------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("wani", Context.MODE_PRIVATE)
        fontPx = prefs.getInt("font_px", dp(14))
        buildUi()
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (RootfsInstaller.isInstalled(this)) startTerminal() else runSetup()
    }

    override fun onDestroy() {
        service?.uiListener = null
        if (service != null) {
            try { unbindService(conn) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    // ---------------- setup (rootfs download) ----------------
    private fun runSetup() {
        setupBox.visibility = View.VISIBLE
        setupText.text = "Preparing Alpine…"
        setupText.setOnClickListener(null)
        Thread {
            try {
                RootfsInstaller.install(this) { msg, pct ->
                    runOnUiThread { setupText.text = msg; setupBar.progress = pct }
                }
                runOnUiThread { setupBox.visibility = View.GONE; startTerminal() }
            } catch (e: Exception) {
                runOnUiThread {
                    setupText.text = "Setup failed: ${e.message}\n\nTap to retry"
                    setupText.setOnClickListener { runSetup() }
                }
            }
        }.start()
    }

    private fun startTerminal() {
        if (!ProotLauncher(this).isReady()) {
            Toast.makeText(this, "libproot.so missing in jniLibs/arm64-v8a (see README)", Toast.LENGTH_LONG).show()
            return
        }
        val i = Intent(this, TerminalService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        bindService(i, conn, Context.BIND_AUTO_CREATE)
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            val s = (b as TerminalService.LocalBinder).service
            service = s
            s.uiListener = this@MainActivity
            if (s.sessions.isEmpty()) s.newSession()
            select(current?.takeIf { s.sessions.contains(it) } ?: s.sessions.last())
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    // ---------------- TerminalService.UiListener ----------------
    override fun onSessionsChanged() {
        val s = service ?: return
        if (current == null || !s.sessions.contains(current)) {
            if (s.sessions.isEmpty()) { select(s.newSession()); return }
            select(s.sessions.last())
        } else refreshTabs()
    }

    override fun onScreenChanged(s: TerminalSession) {
        if (s === current) terminalView.onScreenUpdated()
    }

    override fun onExit() { finish() }

    // ---------------- sessions / tabs ----------------
    private fun select(s: TerminalSession) {
        current = s
        terminalView.attachSession(s)
        terminalView.setTextSize(fontPx)
        terminalView.requestFocus()
        refreshTabs()
    }

    private fun refreshTabs() {
        val svc = service ?: return
        tabBar.removeAllViews()
        svc.sessions.forEachIndexed { idx, s ->
            val label = s.title?.takeIf { it.isNotBlank() }?.take(14) ?: "sh ${idx + 1}"
            val b = btn(label) { select(s) }
            b.setBackgroundColor(if (s === current) 0xFF3D7AED.toInt() else 0xFF2B2B2B.toInt())
            b.setOnLongClickListener { s.finishIfRunning(); true }   // long-press closes the tab
            tabBar.addView(b)
        }
        tabBar.addView(btn("+") { service?.let { select(it.newSession()) } })
        tabBar.addView(btn("pkg") { showPackageDialog() })
        tabBar.addView(btn("A-") { setFont(fontPx - dp(1)) })
        tabBar.addView(btn("A+") { setFont(fontPx + dp(1)) })
        val wl = btn(if (svc.wakeLockOn) "wake:on" else "wake:off") {
            svc.setWakeLock(!svc.wakeLockOn); refreshTabs()
        }
        tabBar.addView(wl)
    }

    private fun setFont(px: Int) {
        fontPx = px.coerceIn(dp(8), dp(32))
        prefs.edit().putInt("font_px", fontPx).apply()
        terminalView.setTextSize(fontPx)
        terminalView.onScreenUpdated()
    }

    private fun showPackageDialog() {
        val input = EditText(this).apply { setText("bash nano curl git") }
        AlertDialog.Builder(this)
            .setTitle("apk add")
            .setView(input)
            .setPositiveButton("Install") { _, _ ->
                current?.write("apk add ${input.text}\n")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------- UI ----------------
    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        setBackgroundColor(0xFF2B2B2B.toInt())
        minWidth = 0; minimumWidth = dp(44)
        setPadding(dp(10), 0, dp(10), 0)
        layoutParams = LinearLayout.LayoutParams(WRAP, dp(40)).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
        setOnClickListener { onClick() }
    }

    private fun send(s: String) { current?.write(s) }

    private fun updateMods() {
        ctrlBtn.setBackgroundColor(if (ctrl) 0xFF3D7AED.toInt() else 0xFF2B2B2B.toInt())
        altBtn.setBackgroundColor(if (alt) 0xFF3D7AED.toInt() else 0xFF2B2B2B.toInt())
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        tabBar = LinearLayout(this)
        col.addView(HorizontalScrollView(this).apply { addView(tabBar) }, LinearLayout.LayoutParams(MATCH, WRAP))

        terminalView = TerminalView(this, null).apply {
            setTerminalViewClient(viewClient)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        col.addView(terminalView, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val keys = LinearLayout(this)
        fun key(label: String, seq: String) = keys.addView(btn(label) { send(seq) })
        key("ESC", "\u001b"); key("TAB", "\t")
        ctrlBtn = btn("CTRL") { ctrl = !ctrl; updateMods() }
        altBtn = btn("ALT") { alt = !alt; updateMods() }
        keys.addView(ctrlBtn); keys.addView(altBtn)
        key("←", "\u001b[D"); key("↓", "\u001b[B"); key("↑", "\u001b[A"); key("→", "\u001b[C")
        key("-", "-"); key("/", "/"); key("|", "|"); key("~", "~"); key("\\", "\\")
        col.addView(HorizontalScrollView(this).apply { addView(keys) }, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        setupBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        setupText = TextView(this).apply { setTextColor(Color.WHITE); gravity = Gravity.CENTER; textSize = 16f }
        setupBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), 0, dp(32), 0)
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            addView(TextView(context).apply {
                text = "Wani Terminal"; setTextColor(Color.WHITE); textSize = 24f; gravity = Gravity.CENTER
            })
            addView(setupText, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) })
            addView(setupBar, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) })
        }
        root.addView(setupBox, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
    }

    // ---------------- TerminalView callbacks ----------------
    private val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            if (scale < 0.9f || scale > 1.1f) {
                setFont(fontPx + if (scale > 1f) dp(1) else -dp(1))
                return 1.0f
            }
            return scale
        }

        override fun onSingleTapUp(e: MotionEvent?) {
            getSystemService(InputMethodManager::class.java).showSoftInput(terminalView, 0)
        }

        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = true
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?) = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
        override fun onLongPress(event: MotionEvent?) = false
        override fun readControlKey() = ctrl
        override fun readAltKey() = alt
        override fun readShiftKey() = false
        override fun readFnKey() = false

        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
            if (session == null) return false
            if (ctrlDown) {
                val c = Character.toLowerCase(codePoint)
                val code = when (c) {
                    in 'a'.code..'z'.code -> c - 'a'.code + 1
                    '['.code -> 27
                    '\\'.code -> 28
                    ']'.code -> 29
                    '^'.code -> 30
                    '_'.code -> 31
                    ' '.code -> 0
                    else -> return false
                }
                session.write(String(charArrayOf(code.toChar())))
                ctrl = false; updateMods()
                return true
            }
            if (alt) {
                session.write("\u001b" + String(Character.toChars(codePoint)))
                alt = false; updateMods()
                return true
            }
            return false
        }

        override fun onEmulatorSet() {}

        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }
}
