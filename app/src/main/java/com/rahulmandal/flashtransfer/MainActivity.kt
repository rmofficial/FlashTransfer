package com.rahulmandal.flashtransfer

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.rahulmandal.flashtransfer.databinding.ActivityMainBinding
import com.rahulmandal.flashtransfer.databinding.DialogAboutBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var treeUri: Uri? = null

    private val handler = Handler(Looper.getMainLooper())
    private var addrIndex = 0
    private var currentUrl: String? = null
    private var lastQrUrl: String? = null
    private var lastBytes = 0L
    private var lastTime = 0L
    private var smoothSpeed = 0.0

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val folderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            treeUri = uri
            prefs.edit().putString("tree", uri.toString()).apply()
            updateFolderLabel()
            toast("Folder selected")
        }

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyWindowInsets()

        prefs = getSharedPreferences("flash", MODE_PRIVATE)
        treeUri = prefs.getString("tree", null)?.let { Uri.parse(it) }
        binding.etPort.setText(prefs.getString("port", "8181"))
        binding.etPass.setText(prefs.getString("pass", ""))
        updateFolderLabel()

        binding.btnStart.setOnClickListener { if (ServerState.running) stopServer() else startServer() }
        binding.btnFolder.setOnClickListener { chooseFolder() }
        binding.btnAbout.setOnClickListener { showAbout() }
        binding.tvFooter.setOnClickListener { showAbout() }

        binding.tvUrl.setOnClickListener { cycleAddress() }
        binding.ivQr.setOnClickListener { cycleAddress() }
        binding.btnCopy.setOnClickListener {
            currentUrl?.let {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Flash Transfer link", it))
                toast("Link copied")
            }
        }
        binding.btnShare.setOnClickListener {
            currentUrl?.let {
                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, it)
                startActivity(Intent.createChooser(i, "Share link"))
            }
        }
        binding.btnOpen.setOnClickListener {
            currentUrl?.let { safeStart(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
        }
        binding.btnHotspot.setOnClickListener { openHotspotSettings() }
        binding.btnBattery.setOnClickListener {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                toast("Background running is already allowed")
            } else {
                safeStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                toast("Find Flash Transfer and choose “Don’t optimize”")
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        updateBatteryButton()
        ticker.run()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    // ------------------------------------------------------------ start / stop

    private fun startServer() {
        val root = treeUri
        if (root == null || !hasStorageAccess()) {
            toast("Choose a folder to share first")
            chooseFolder()
            return
        }
        val port = binding.etPort.text.toString().trim().toIntOrNull()
        if (port == null || port < 1024 || port > 65535) {
            toast("Port must be between 1024 and 65535")
            return
        }
        val pass = binding.etPass.text.toString()
        prefs.edit()
            .putString("port", port.toString())
            .putString("pass", pass)
            .apply()

        val i = Intent(this, ServerService::class.java)
            .putExtra(ServerService.EXTRA_PORT, port)
            .putExtra(ServerService.EXTRA_ROOT, root.toString())
            .putExtra(ServerService.EXTRA_PASS, pass)
        ContextCompat.startForegroundService(this, i)
    }

    private fun stopServer() {
        startService(Intent(this, ServerService::class.java).setAction(ServerService.ACTION_STOP))
    }

    // ------------------------------------------------------------ UI refresh

    private fun refresh() {
        val b = binding
        ServerState.error?.let {
            toast(it)
            ServerState.error = null
        }
        val running = ServerState.running

        b.viewDot.setBackgroundResource(if (running) R.drawable.dot_on else R.drawable.dot_off)
        b.tvStatus.text = if (running) "Server online" else "Server offline"
        b.btnStart.text = if (running) "STOP SERVER" else "START SERVER"
        b.btnStart.setBackgroundResource(if (running) R.drawable.bg_btn_stop else R.drawable.bg_btn_primary)
        b.btnStart.setTextColor(if (running) Color.WHITE else 0xFF1A1000.toInt())
        b.etPort.isEnabled = !running
        b.etPass.isEnabled = !running
        b.btnFolder.isEnabled = !running

        val now = SystemClock.elapsedRealtime()
        val bytes = ServerState.bytesSent.get()
        if (lastTime != 0L && now > lastTime) {
            val inst = maxOf(0.0, (bytes - lastBytes) * 1000.0 / (now - lastTime))
            smoothSpeed = smoothSpeed * 0.4 + inst * 0.6
        }
        lastBytes = bytes
        lastTime = now
        if (!running) smoothSpeed = 0.0
        b.tvSpeed.text = Fmt.speed(smoothSpeed)
        b.tvSent.text = Fmt.size(bytes)
        b.tvActive.text = ServerState.active.get().toString()

        if (running) {
            val addrs = NetUtils.list()
            if (addrIndex >= addrs.size) addrIndex = 0
            b.cardQr.visibility = View.VISIBLE
            if (addrs.isEmpty()) {
                b.ivQr.visibility = View.GONE
                b.tvUrl.text = "No network found"
                b.tvIface.text = "Turn on Hotspot, or connect to Wi-Fi"
                currentUrl = null
                lastQrUrl = null
            } else {
                val a = addrs[addrIndex]
                val url = "http://${a.ip}:${ServerState.port}"
                currentUrl = url
                b.ivQr.visibility = View.VISIBLE
                if (url != lastQrUrl) {
                    b.ivQr.setImageBitmap(QrUtil.make(url))
                    lastQrUrl = url
                }
                b.tvUrl.text = url
                b.tvIface.text = a.label +
                    if (addrs.size > 1) "  •  tap to switch (${addrIndex + 1}/${addrs.size})" else ""
            }
        } else {
            b.cardQr.visibility = View.GONE
            currentUrl = null
            lastQrUrl = null
        }
    }

    private fun cycleAddress() {
        addrIndex++
        refresh()
    }

    // ------------------------------------------------------------ screen fit

    private fun applyWindowInsets() {
        val root = binding.root
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        ViewCompat.requestApplyInsets(root)
    }

    // ------------------------------------------------------------ storage access (SAF)

    private fun hasStorageAccess(): Boolean {
        val u = treeUri ?: return false
        return try {
            contentResolver.persistedUriPermissions.any { it.uri == u && it.isReadPermission }
        } catch (_: Exception) {
            false
        }
    }

    private fun updateBatteryButton() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val ok = pm.isIgnoringBatteryOptimizations(packageName)
        binding.btnBattery.text =
            if (ok) "✅  Background running allowed" else "🔋  Allow background running"
    }

    private fun openHotspotSettings() {
        try {
            startActivity(
                Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings")
            )
        } catch (e: Exception) {
            safeStart(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
    }

    // ------------------------------------------------------------ folder picker

    private fun updateFolderLabel() {
        val u = treeUri
        binding.tvFolder.text = if (u == null) "No folder selected — tap Change" else folderLabel(u)
    }

    private fun folderLabel(uri: Uri): String {
        val id = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (_: Exception) {
            return uri.lastPathSegment ?: "Shared folder"
        }
        val path = id.substringAfter(':', id)
        return "📁  " + if (path.isEmpty()) "Root" else path
    }

    private fun chooseFolder() {
        try {
            folderLauncher.launch(null)
        } catch (_: Exception) {
            toast("Could not open folder picker")
        }
    }

    // ------------------------------------------------------------ about

    private fun showAbout() {
        val d = DialogAboutBinding.inflate(layoutInflater)
        d.ivPhoto.setImageResource(R.drawable.dev_photo)
        d.ivPhoto.clipToOutline = true

        d.rowEmail.setOnClickListener {
            safeStart(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:rahulmandal.official@zohomail.in")))
        }
        d.rowPhone.setOnClickListener { safeStart(Intent(Intent.ACTION_DIAL, Uri.parse("tel:7430852038"))) }
        d.rowGithub.setOnClickListener {
            safeStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/rmofficial")))
        }
        d.rowFacebook.setOnClickListener {
            safeStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.facebook.com/share/1FV6HZzVyd/")))
        }

        val dlg = AlertDialog.Builder(this).setView(d.root).create()
        d.btnClose.setOnClickListener { dlg.dismiss() }
        dlg.window?.setWindowAnimations(R.style.Animation_Flash_None)
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dlg.setOnShowListener {
            val dm = resources.displayMetrics
            val width = minOf((dm.widthPixels * 0.92f).toInt(), (440 * dm.density).toInt())
            dlg.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        dlg.show()
    }

    // ------------------------------------------------------------ helpers

    private fun safeStart(i: Intent) {
        try {
            startActivity(i)
        } catch (e: Exception) {
            toast("Could not open")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
