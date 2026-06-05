package com.example.controller

import android.app.AlertDialog
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.*
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), SerialInputOutputManager.Listener {

    private lateinit var webView: WebView
    private var usbManager: UsbManager? = null
    private var serialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private val rxQueue = ConcurrentLinkedQueue<String>()
    // O(1) size tracking so we can bound the queue without ConcurrentLinkedQueue.size() (O(n)).
    private val rxCount = AtomicInteger(0)

    // Outbound serial writes are pushed onto this queue and drained by a
    // dedicated writer thread, so a slow/blocking port.write() never freezes
    // the WebView's JS-bridge thread (the source of tap-to-action lag).
    private val txQueue = LinkedBlockingQueue<ByteArray>(2048)
    @Volatile private var writerRunning = false
    private var writerThread: Thread? = null

    // Shared background executor for UI fetches (reused instead of spawning a
    // new single-thread executor per fetch, which leaked threads).
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile private var isConnected = false

    private lateinit var prefs: SharedPreferences

    // Tracks which UI source is currently loaded.
    private enum class UiSource { NONE, BUNDLED, CACHE, NETWORK }
    @Volatile private var currentUiSource: UiSource = UiSource.NONE

    // Hidden gesture: 7 taps in the top-left corner within 3 seconds re-opens
    // the URL prompt so kiosk-deployed devices can have their URL changed
    // without clearing app data.
    private val tapTimestamps = ArrayDeque<Long>()
    private val SECRET_TAP_COUNT = 7
    private val SECRET_TAP_WINDOW_MS = 3000L
    private val SECRET_TAP_ZONE_DP = 80   // size of the corner sniff zone

    // Secret EXIT gesture: 7 taps in the top-RIGHT corner within 3s shows
    // a dialog to exit kiosk mode (for maintenance / reconfiguration).
    private val exitTapTimestamps = ArrayDeque<Long>()

    // Kiosk state
    @Volatile private var inLockTask = false
    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    companion object {
        const val TAG = "UrbanNaps"
        const val ACTION_USB_PERMISSION = "com.urbannaps.controller.USB_PERMISSION"
        const val BAUD_RATE = 115200

        // ── UI source / caching ──────────────────────────────
        const val PREFS_NAME       = "controller_prefs"
        const val PREF_UI_URL      = "ui_url"
        const val CACHE_FILENAME   = "ui_cache.html"
        const val FETCH_TIMEOUT_MS = 8000   // give the server 8s to respond
        const val BASE_URL_ORIGIN  = "https://controller.local/"   // stable origin for cached/fetched HTML

        // Cap buffered inbound serial chunks so a UI that stops polling
        // receive() can't grow the queue unbounded → OOM on a 24/7 kiosk.
        const val RX_MAX_CHUNKS    = 4096
        const val WRITE_TIMEOUT_MS = 1000
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            jsLog("Permission granted, opening port...")
                            openPort()
                        } else {
                            jsLog("Permission DENIED by user")
                            notifyWeb("permission_denied")
                        }
                        // Re-enter Lock Task Mode after the user has responded
                        // (either grant or deny). We left it briefly to let the
                        // system dialog render.
                        if (!inLockTask && dpm.isDeviceOwnerApp(packageName)) {
                            try {
                                startLockTask()
                                inLockTask = true
                                jsLog("Re-entered Lock Task Mode")
                            } catch (e: Exception) {
                                Log.w(TAG, "Re-enter Lock Task failed: ${e.message}")
                            }
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    jsLog("USB device attached!")
                    notifyWeb("device_attached")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    jsLog("USB device detached!")
                    closePort()
                    notifyWeb("disconnected")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep screen on while the controller is open (it's a kiosk-style app)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Show on lock screen if device wakes from idle.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        setContentView(R.layout.activity_main)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Device Policy Manager — used to enter/exit Lock Task Mode.
        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, KioskDeviceAdminReceiver::class.java)

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        prepareWebView()
        bootUiLoad()

        // Enter kiosk after layout settles so UI rendering isn't interrupted.
        window.decorView.post { enterKiosk() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        closePort()
        ioExecutor.shutdownNow()
        // Detach and destroy the WebView so its native resources are released
        // instead of leaking with the (singleInstance) activity.
        try {
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.removeJavascriptInterface("ESP32")
            webView.destroy()
        } catch (_: Exception) {}
    }

    // ════════════════════════════════════════════════════════════════════
    //  UI LOADING — fetch fresh when online, fall back to cache, then
    //  fall back to bundled asset.
    // ════════════════════════════════════════════════════════════════════

    private fun prepareWebView() {
        webView = findViewById(R.id.webView)
        // Rendering layer choice — this is the crux of the smooth-vs-glitchy
        // trade-off on kiosk tablet GPUs:
        //   • LAYER_TYPE_SOFTWARE  → CPU paints everything: no glitch but slow/laggy.
        //   • LAYER_TYPE_HARDWARE  → whole WebView in ONE giant GPU texture:
        //                            fast but corrupts ("pixels"/ghosted text) on
        //                            many tablet GPUs.
        //   • LAYER_TYPE_NONE (default) → WebView's own GPU-accelerated TILED
        //                            renderer (same engine the browser uses):
        //                            fast AND no giant-texture corruption.
        // We want NONE. The window itself is hardware-accelerated (see manifest),
        // so the tiled renderer runs on the GPU.
        webView.setLayerType(View.LAYER_TYPE_NONE, null)
        // Opaque background matching the UI's dark theme. A transparent/white
        // WebView surface composited over the host layout is a common source of
        // ghosting/pixel-trail artifacts because stale pixels aren't cleared.
        webView.setBackgroundColor(android.graphics.Color.parseColor("#06080c"))
        // Disable overscroll. Android 12+ replaced the edge-glow with a
        // RenderEffect-based "stretch" shader that produces stray colored
        // pixels ("red dots") on some tablet GPUs. A kiosk UI never needs to
        // overscroll, so turn it off entirely.
        webView.overScrollMode = View.OVER_SCROLL_NEVER

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            setSupportZoom(false)
            // Cache aggressively so cached/bundled resources don't re-fetch.
            cacheMode = WebSettings.LOAD_DEFAULT
            // Don't block image autoload — avoids extra reflow passes.
            blockNetworkImage = false
            loadsImagesAutomatically = true
            // Allow mixed content if your URL serves http — change to NEVER_ALLOW for https only.
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.addJavascriptInterface(ESP32Bridge(), "ESP32")
        // Remote debugging + per-message console logging add overhead and are
        // only useful while developing. Gate them on debug builds so production
        // kiosks don't pay for every console.log the UI emits.
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
            webView.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                    msg?.let { Log.d(TAG, "JS: ${it.message()} @ line ${it.lineNumber()}") }
                    return true
                }
            }
        } else {
            webView.webChromeClient = WebChromeClient()
        }
    }

    /**
     * Boot-time UI source decision tree. Runs once per app start.
     *
     *  saved URL?      online?     action
     *  ---------       -------     ---------------------------------
     *  no              yes         prompt → save → fetch → cache → load
     *  no              no          load bundled asset (no prompt yet)
     *  yes             yes         fetch → on success: cache + load
     *                              on fail: load cache → or bundled
     *  yes             no          load cache → or bundled
     */
    private fun bootUiLoad() {
        val savedUrl = prefs.getString(PREF_UI_URL, null)
        val online = isOnline()
        Log.i(TAG, "bootUiLoad: savedUrl=$savedUrl online=$online")

        if (savedUrl.isNullOrBlank()) {
            if (online) {
                promptForUrl(isFirstRun = true) { newUrl ->
                    prefs.edit().putString(PREF_UI_URL, newUrl).apply()
                    fetchUrlOrFallback(newUrl)
                }
            } else {
                Log.i(TAG, "No URL saved + offline — using bundled asset")
                Toast.makeText(this,
                    "Offline — loading built-in UI. Connect to internet to set custom URL.",
                    Toast.LENGTH_LONG).show()
                loadBundledAsset()
            }
        } else {
            if (online) {
                fetchUrlOrFallback(savedUrl)
            } else {
                Log.i(TAG, "Offline — loading from cache")
                loadCachedOrBundled()
            }
        }
    }

    /**
     * Try to fetch the HTML from [url]. On success, cache it and load it.
     * On failure, fall back to cache → bundled asset.
     */
    private fun fetchUrlOrFallback(url: String) {
        Toast.makeText(this, "Fetching latest UI...", Toast.LENGTH_SHORT).show()

        ioExecutor.execute {
            val html = try {
                downloadString(url)
            } catch (e: Exception) {
                Log.w(TAG, "Fetch failed: ${e.message}")
                null
            }

            Handler(Looper.getMainLooper()).post {
                if (html != null) {
                    Log.i(TAG, "Fetch OK (${html.length} chars) — caching and loading")
                    writeCache(html)
                    loadHtmlString(html)
                    currentUiSource = UiSource.NETWORK
                } else {
                    Log.w(TAG, "Fetch failed — falling back to cache")
                    Toast.makeText(this, "Couldn't reach server — loading cached UI",
                        Toast.LENGTH_SHORT).show()
                    loadCachedOrBundled()
                }
            }
        }
    }

    /** Loads from disk cache if it exists, otherwise bundled asset. */
    private fun loadCachedOrBundled() {
        val cached = readCache()
        if (cached != null) {
            Log.i(TAG, "Loading from cache (${cached.length} chars)")
            loadHtmlString(cached)
            currentUiSource = UiSource.CACHE
        } else {
            Log.i(TAG, "No cache — loading bundled asset")
            loadBundledAsset()
        }
    }

    /**
     * Load the bundled `assets/index.html` as the canonical offline fallback.
     */
    private fun loadBundledAsset() {
        webView.loadUrl("file:///android_asset/index.html")
        currentUiSource = UiSource.BUNDLED
    }

    /**
     * Load HTML string into the WebView with a fixed base URL so the WebView
     * keeps a stable origin across cache/network loads. This keeps cookies,
     * localStorage, and the JS bridge consistent.
     */
    private fun loadHtmlString(html: String) {
        webView.loadDataWithBaseURL(
            BASE_URL_ORIGIN,
            html,
            "text/html",
            "UTF-8",
            null
        )
    }

    // ── Network helpers ──────────────────────────────────────────────────

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    /** Blocking HTTP GET. Caller must run on a background thread. */
    private fun downloadString(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = FETCH_TIMEOUT_MS
            readTimeout = FETCH_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "text/html,*/*")
            setRequestProperty("User-Agent", "UrbanNapsController/1.0")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw RuntimeException("HTTP $code")
            }
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ── Cache helpers ────────────────────────────────────────────────────

    private fun cacheFile(): File = File(filesDir, CACHE_FILENAME)

    private fun writeCache(html: String) {
        try {
            cacheFile().writeText(html, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Cache write failed: ${e.message}")
        }
    }

    private fun readCache(): String? {
        val f = cacheFile()
        return if (f.exists()) {
            try { f.readText(Charsets.UTF_8) } catch (e: Exception) { null }
        } else null
    }

    // ── URL prompt ───────────────────────────────────────────────────────

    /**
     * Show the URL prompt dialog.
     *
     * @param isFirstRun  When true (first-launch flow), there's no current URL
     *                    and tapping cancel falls back to bundled UI without
     *                    saving anything. When false (kiosk secret-gesture
     *                    flow), cancel just dismisses the dialog and leaves
     *                    the current URL/UI as-is.
     * @param onUrl       Callback with the validated URL on confirm.
     */
    private fun promptForUrl(isFirstRun: Boolean, onUrl: (String) -> Unit) {
        // Convert dp → px for consistent padding across screen densities.
        val padPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics
        ).toInt()

        // Pre-fill with the currently saved URL on subsequent edits.
        val currentUrl = prefs.getString(PREF_UI_URL, "") ?: ""

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "https://example.com/controller/index.html"
            setSingleLine(true)
            setText(currentUrl)
            // Layout params for width=match_parent inside the container below
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }

        // Wrap the EditText in a LinearLayout so it picks up our padding
        // (AlertDialog's default setView places content flush against the
        // left edge — wrapping gives us full control over alignment).
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padPx, padPx / 2, padPx, 0)
            addView(input)
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("Enter User-Interface URL")
            .setMessage(
                if (isFirstRun)
                    "Provide the URL where the controller UI is hosted. " +
                            "The app will download it now and cache it for offline use."
                else
                    "Update the controller UI URL. The new URL will be saved " +
                            "and the latest UI will be fetched immediately."
            )
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("Load") { _, _ ->
                val raw = input.text.toString().trim()
                val url = normalizeUrl(raw)
                if (url == null) {
                    Toast.makeText(this, "Invalid URL — keeping current UI",
                        Toast.LENGTH_LONG).show()
                    if (isFirstRun) loadBundledAsset()
                } else {
                    onUrl(url)
                }
            }

        if (isFirstRun) {
            builder.setNegativeButton("Use Built-in") { _, _ ->
                // First-run skip: load bundled but DON'T save anything,
                // so they'll be prompted again next launch.
                loadBundledAsset()
            }
        } else {
            builder.setNegativeButton("Cancel") { d, _ -> d.dismiss() }
        }

        builder.show()
    }

    /** Returns a usable http/https URL, or null if input is unusable. */
    private fun normalizeUrl(raw: String): String? {
        if (raw.isBlank()) return null
        val withScheme = if (raw.startsWith("http://", ignoreCase = true) ||
            raw.startsWith("https://", ignoreCase = true)) {
            raw
        } else {
            "https://$raw"
        }
        return try {
            URL(withScheme).toString()
        } catch (_: Exception) {
            null
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  SECRET GESTURES — implemented via dispatchTouchEvent so they run
    //  BEFORE the WebView's touch handling, sniffing taps without consuming
    //  them (WebView still works normally).
    //
    //  Top-LEFT corner, N taps    → opens URL prompt
    //  Top-RIGHT corner, N taps   → opens "Exit kiosk?" dialog
    // ════════════════════════════════════════════════════════════════════
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
            val zonePx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                SECRET_TAP_ZONE_DP.toFloat(),
                resources.displayMetrics
            )
            val w = window.decorView.width

            if (ev.y <= zonePx) {
                if (ev.x <= zonePx) {
                    onSecretTap()
                } else if (w > 0 && ev.x >= w - zonePx) {
                    onExitSecretTap()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun onSecretTap() {
        val now = System.currentTimeMillis()
        while (tapTimestamps.isNotEmpty() &&
            now - tapTimestamps.first() > SECRET_TAP_WINDOW_MS) {
            tapTimestamps.removeFirst()
        }
        tapTimestamps.addLast(now)

        Log.i(TAG, "URL tap ${tapTimestamps.size}/$SECRET_TAP_COUNT")
        Toast.makeText(this,
            "Tap ${tapTimestamps.size}/$SECRET_TAP_COUNT",
            Toast.LENGTH_SHORT).show()

        if (tapTimestamps.size >= SECRET_TAP_COUNT) {
            tapTimestamps.clear()
            promptForUrl(isFirstRun = false) { newUrl ->
                prefs.edit().putString(PREF_UI_URL, newUrl).apply()
                fetchUrlOrFallback(newUrl)
            }
        }
    }

    private fun onExitSecretTap() {
        val now = System.currentTimeMillis()
        while (exitTapTimestamps.isNotEmpty() &&
            now - exitTapTimestamps.first() > SECRET_TAP_WINDOW_MS) {
            exitTapTimestamps.removeFirst()
        }
        exitTapTimestamps.addLast(now)

        Log.i(TAG, "Exit tap ${exitTapTimestamps.size}/$SECRET_TAP_COUNT")
        Toast.makeText(this,
            "Exit tap ${exitTapTimestamps.size}/$SECRET_TAP_COUNT",
            Toast.LENGTH_SHORT).show()

        if (exitTapTimestamps.size >= SECRET_TAP_COUNT) {
            exitTapTimestamps.clear()
            showExitKioskDialog()
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  KIOSK MODE — Lock Task + immersive fullscreen + back blocked.
    //  Requires the app to be set as Device Owner (one-time ADB command).
    // ════════════════════════════════════════════════════════════════════

    /**
     * Enter Lock Task Mode if we're permitted to. Permitted means EITHER:
     *  - We are Device Owner (set via ADB), OR
     *  - Our package is on the Device Owner's lock-task allowlist (we are
     *    our own Device Owner, so we set ourselves).
     *
     * If not permitted, this silently does nothing — the app still runs,
     * just without true lockdown. Useful for development on tablets that
     * haven't been provisioned yet.
     */
    private fun enterKiosk() {
        applyImmersive()

        if (dpm.isDeviceOwnerApp(packageName)) {
            try {
                // Whitelist ourselves for Lock Task. Without this, startLockTask
                // would show a "screen pinning" notification asking permission.
                dpm.setLockTaskPackages(adminComponent, arrayOf(packageName))
                Log.i(TAG, "Set as Device Owner — entering Lock Task Mode")
            } catch (e: Exception) {
                Log.w(TAG, "setLockTaskPackages failed: ${e.message}")
            }
        } else {
            Log.w(TAG, "NOT Device Owner — Lock Task Mode unavailable. " +
                    "Run the ADB provisioning command (see setup docs).")
        }

        try {
            startLockTask()
            inLockTask = true
            Log.i(TAG, "Lock Task Mode active")
        } catch (e: Exception) {
            Log.w(TAG, "startLockTask failed: ${e.message}")
        }
    }

    private fun exitKiosk() {
        try {
            if (inLockTask) {
                stopLockTask()
                inLockTask = false
                Log.i(TAG, "Exited Lock Task Mode")
            }
        } catch (e: Exception) {
            Log.w(TAG, "stopLockTask failed: ${e.message}")
        }
    }

    /**
     * Confirms with the user that they want to exit kiosk mode. Used by
     * the top-right secret gesture for maintenance access.
     */
    private fun showExitKioskDialog() {
        AlertDialog.Builder(this)
            .setTitle("Exit Kiosk Mode?")
            .setMessage(
                "This will unlock the device and exit the controller app. " +
                        "Use this for maintenance only.\n\n" +
                        "To return to kiosk mode, simply relaunch the app or reboot."
            )
            .setPositiveButton("Exit Kiosk") { _, _ ->
                exitKiosk()
                Toast.makeText(this, "Kiosk exited", Toast.LENGTH_SHORT).show()
                // Move app to background so the system home appears.
                moveTaskToBack(true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Apply immersive fullscreen flags. Hides status bar and nav bar.
     * On Android 11+ (API 30) uses WindowInsetsController; older versions
     * fall back to the deprecated systemUiVisibility flags.
     */
    private fun applyImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val controller = window.insetsController ?: return
            controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  Everything below this line is UNCHANGED from the previous version.
    // ════════════════════════════════════════════════════════════════════

    private fun jsLog(msg: String) {
        Log.i(TAG, msg)
        runOnUiThread {
            val escaped = msg.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
            webView.evaluateJavascript("if(typeof connLog==='function') connLog('$escaped')", null)
        }
    }

    private fun findDriver(): UsbSerialDriver? {
        val manager = usbManager ?: return null
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        jsLog("Found ${drivers.size} USB serial driver(s)")
        for (d in drivers) {
            val dev = d.device
            jsLog("  ${dev.deviceName} VID=0x${"%04X".format(dev.vendorId)} " +
                    "PID=0x${"%04X".format(dev.productId)} " +
                    "ports=${d.ports.size} driver=${d.javaClass.simpleName}")
        }
        return drivers.firstOrNull()
    }

    private fun openPort() {
        val manager = usbManager ?: return
        val driver = findDriver() ?: run {
            jsLog("ERROR: no driver after permission grant")
            notifyWeb("error")
            return
        }
        val connection = manager.openDevice(driver.device) ?: run {
            jsLog("ERROR: openDevice returned null")
            notifyWeb("error")
            return
        }
        val port = driver.ports.first()
        try {
            port.open(connection)
            port.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port.dtr = true
            port.rts = true
        } catch (e: Exception) {
            jsLog("ERROR opening port: ${e.message}")
            try { port.close() } catch (_: Exception) {}
            notifyWeb("error")
            return
        }

        serialPort = port
        ioManager = SerialInputOutputManager(port, this).also { it.start() }
        startWriter(port)
        isConnected = true
        jsLog("✓ Port opened at $BAUD_RATE 8N1, DTR+RTS asserted")
        notifyWeb("connected")
    }

    private fun closePort() {
        isConnected = false
        stopWriter()
        try { ioManager?.stop() } catch (_: Exception) {}
        try { serialPort?.close() } catch (_: Exception) {}
        ioManager = null
        serialPort = null
        rxQueue.clear()
        rxCount.set(0)
    }

    /** Drains [txQueue] on a dedicated thread so writes never block the caller. */
    private fun startWriter(port: UsbSerialPort) {
        txQueue.clear()
        writerRunning = true
        writerThread = thread(name = "serial-writer", isDaemon = true) {
            while (writerRunning) {
                val data = try {
                    txQueue.poll(200, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    break
                } ?: continue
                try {
                    port.write(data, WRITE_TIMEOUT_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Serial write failed: ${e.message}")
                }
            }
        }
    }

    private fun stopWriter() {
        writerRunning = false
        writerThread?.interrupt()
        writerThread = null
        txQueue.clear()
    }

    override fun onNewData(data: ByteArray) {
        rxQueue.add(String(data, Charsets.UTF_8))
        // Bound the buffer: if nobody is polling receive(), drop oldest chunks.
        if (rxCount.incrementAndGet() > RX_MAX_CHUNKS) {
            if (rxQueue.poll() != null) rxCount.decrementAndGet()
        }
    }

    override fun onRunError(e: Exception) {
        jsLog("Serial run error: ${e.message}")
        runOnUiThread {
            closePort()
            notifyWeb("disconnected")
        }
    }

    private fun notifyWeb(status: String) {
        runOnUiThread {
            // Guard against the page not having defined the handler yet
            // (e.g. status arrives mid-load) to avoid a silent JS ReferenceError.
            webView.evaluateJavascript(
                "if(typeof onUSBStatus==='function')onUSBStatus('$status')", null
            )
        }
    }

    @Deprecated("Use OnBackPressedCallback")
    override fun onBackPressed() {
        // Inside kiosk: swallow Back so user can't navigate away.
        if (inLockTask) {
            Log.d(TAG, "Back pressed (consumed by kiosk)")
            return
        }
        // Outside kiosk: hint at the exit gesture but don't actually leave.
        Toast.makeText(this,
            "Use top-right corner gesture to exit",
            Toast.LENGTH_SHORT).show()
    }

    override fun onUserLeaveHint() {
        // Fired when user presses Home or Recents. In Lock Task mode this
        // won't normally be reachable, but we override defensively.
        if (inLockTask) {
            Log.d(TAG, "onUserLeaveHint (consumed)")
            return
        }
        super.onUserLeaveHint()
    }

    inner class ESP32Bridge {

        @JavascriptInterface
        fun connect(): String {
            val manager = usbManager ?: return "error:no_usb_manager"
            val driver = findDriver() ?: return "error:no_devices"

            if (!manager.hasPermission(driver.device)) {
                jsLog("Requesting USB permission...")

                // stopLockTask() and requestPermission() touch Activity/window
                // state and MUST run on the main thread. @JavascriptInterface
                // methods run on the JS-bridge thread, so marshal both onto the
                // UI thread (and keep their order: exit lock task → show dialog).
                runOnUiThread {
                    // Briefly exit Lock Task Mode so the system permission dialog
                    // can render over the app. Some OEMs suppress system dialogs
                    // over pinned/locked apps, making the popup never appear.
                    // The usbReceiver re-enters Lock Task after the user responds.
                    if (inLockTask) {
                        jsLog("Temporarily exiting Lock Task for permission dialog")
                        try {
                            stopLockTask()
                            inLockTask = false
                        } catch (e: Exception) {
                            Log.w(TAG, "stopLockTask before permission failed: ${e.message}")
                        }
                    }

                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        PendingIntent.FLAG_MUTABLE else 0
                    val permIntent = PendingIntent.getBroadcast(
                        this@MainActivity, 0,
                        Intent(ACTION_USB_PERMISSION).setPackage(packageName),
                        flags
                    )
                    manager.requestPermission(driver.device, permIntent)
                }
                return "requesting_permission"
            }
            openPort()
            return if (isConnected) "connected" else "error:connection_failed"
        }

        @JavascriptInterface
        fun send(command: String): String {
            if (!isConnected || serialPort == null) return "error:not_connected"
            // Enqueue and return immediately — the writer thread performs the
            // actual (potentially blocking) USB write. This keeps the WebView
            // responsive even under rapid command bursts.
            val data = (command + "\n").toByteArray(Charsets.UTF_8)
            return if (txQueue.offer(data)) {
                Log.i(TAG, "Queued '$command' (${data.size}B)")
                "ok:${data.size}"
            } else {
                Log.w(TAG, "TX queue full — dropped '$command'")
                "error:queue_full"
            }
        }

        @JavascriptInterface
        fun receive(): String {
            val sb = StringBuilder()
            while (true) {
                val chunk = rxQueue.poll() ?: break
                rxCount.decrementAndGet()
                sb.append(chunk)
            }
            return sb.toString()
        }

        @JavascriptInterface
        fun disconnect(): String {
            closePort()
            return "disconnected"
        }

        @JavascriptInterface
        fun isConnected(): Boolean = isConnected

        @JavascriptInterface
        fun getDeviceList(): String {
            val manager = usbManager ?: return "no_usb_manager"
            val sb = StringBuilder()
            for ((name, device) in manager.deviceList) {
                sb.append("$name VID=0x${"%04X".format(device.vendorId)} ")
                sb.append("PID=0x${"%04X".format(device.productId)} ")
                sb.append("${device.productName ?: ""}\n")
            }
            return if (sb.isEmpty()) "no_devices" else sb.toString()
        }
    }
}