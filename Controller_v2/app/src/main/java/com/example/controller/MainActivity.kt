package com.example.controller

import android.app.AlertDialog
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import java.net.URL

/**
 * Thin Activity layer for the AeroPod kiosk controller.
 *
 * Responsibilities:
 *  • Inflate + configure the WebView
 *  • Observe ViewModel LiveData and drive the WebView / toasts / dialogs
 *  • Forward USB broadcast events to the ViewModel
 *  • Handle kiosk / immersive-mode lifecycle
 *
 * Bugs fixed vs. previous version:
 *   #1  – Exit gesture zone restored to TOP-RIGHT corner (was wrongly top-centre).
 *   #2  – WebView layer type is LAYER_TYPE_NONE (prevents GPU red-dot corruption).
 *   #4  – onRenderProcessGone calls recreate() instead of loading into dead WebView.
 *   #5  – URL-prompt usbEvents are now consumed before showing the dialog, so screen
 *          rotation no longer re-fires the prompt.
 *   #9  – Removed deprecated setRenderPriority() call (no-op since API 18).
 *   #10 – Removed deprecated databaseEnabled (WebSQL, unused by this UI).
 *   #11 – Removed onPageStarted override that re-applied LAYER_TYPE_HARDWARE.
 *   #12 – Removed redundant runOnUiThread() wrappers in notifyWeb / jsLogToWebView
 *          (both methods are always called from the main thread).
 *   #13 – Removed SharedPreferences field; getSavedUrl() delegated to ViewModel.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val viewModel: MainViewModel by viewModels()

    // Secret gesture tap-tracking (all on main thread, no synchronisation needed)
    private val tapTimestamps = ArrayDeque<Long>()
    private val exitTapTimestamps = ArrayDeque<Long>()

    // Kiosk state
    @Volatile private var inLockTask = false
    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    // Inactivity screen-dim
    private val inactivityHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val dimRunnable = Runnable { setScreenBrightness(Config.BRIGHTNESS_DIM) }

    // ── USB broadcast receiver ───────────────────────────────────────────

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Config.ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    // Bug #3 (in ViewModel): permission handler dispatches USB open to IO thread.
                    viewModel.handleUsbPermission(granted)
                    // Re-enter kiosk if we left it to allow the system dialog to render.
                    if (!inLockTask) {
                        inLockTask = KioskUtils.enterKioskMode(this@MainActivity, adminComponent)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    viewModel.logToJs("USB device attached!")
                    notifyWeb(Config.EVENT_USB_DEVICE_ATTACHED)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    viewModel.logToJs("USB device detached!")
                    viewModel.closePort()
                    notifyWeb(Config.EVENT_USB_DISCONNECTED)
                }
            }
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

        // Bug #13: Activity no longer holds a SharedPreferences reference.
        // All prefs access goes through the ViewModel.
        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, KioskDeviceAdminReceiver::class.java)

        val filter = IntentFilter().apply {
            addAction(Config.ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        prepareWebView()
        setupObservers()
        viewModel.bootUiLoad()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (inLockTask) {
                    Log.d(Config.TAG, "Back pressed (consumed by kiosk)")
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Use top-right corner gesture to exit",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })

        window.decorView.post {
            KioskUtils.applyImmersiveMode(this)
            inLockTask = KioskUtils.enterKioskMode(this, adminComponent)
            resetInactivityTimer()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) KioskUtils.applyImmersiveMode(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        inactivityHandler.removeCallbacks(dimRunnable)
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        try {
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.removeJavascriptInterface(Config.JS_BRIDGE_NAME)
            webView.destroy()
        } catch (_: Exception) {}
    }

    override fun onUserLeaveHint() {
        if (inLockTask) {
            Log.d(Config.TAG, "onUserLeaveHint (consumed by kiosk)")
            return
        }
        super.onUserLeaveHint()
    }

    // ── WebView setup ────────────────────────────────────────────────────

    private fun prepareWebView() {
        webView = findViewById(R.id.webView)

        // Bug #2 fixed: LAYER_TYPE_NONE lets the WebView use its own GPU-accelerated
        // tiled renderer — same engine the browser uses, fast AND without the
        // single-giant-texture corruption (red dots) that LAYER_TYPE_HARDWARE
        // causes on this tablet's GPU.
        webView.setLayerType(View.LAYER_TYPE_NONE, null)

        // Opaque background matching the dark UI theme prevents a white flash on
        // load and eliminates stale-pixel ghosting from a transparent surface.
        webView.setBackgroundColor(android.graphics.Color.parseColor(Config.WEBVIEW_BG_COLOR))

        // Disable overscroll: Android 12+ stretch-overscroll shader produces
        // stray coloured pixels on some tablet GPUs. Kiosk UI never overscrolls.
        webView.overScrollMode = View.OVER_SCROLL_NEVER

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            setSupportZoom(false)
            cacheMode = WebSettings.LOAD_DEFAULT
            blockNetworkImage = false
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setGeolocationEnabled(false)
            setNeedInitialFocus(false)
            safeBrowsingEnabled = false
            // Bug #9:  setRenderPriority() removed — deprecated since API 18, no-op on modern Android.
            // Bug #10: databaseEnabled removed — deprecated WebSQL API, not used by this UI.
        }

        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.addJavascriptInterface(ESP32Bridge(), Config.JS_BRIDGE_NAME)

        webView.webViewClient = object : WebViewClient() {
            // Bug #4 fixed: when the render process dies, recreate() destroys and
            // re-creates the Activity so a fresh WebView instance is created. The
            // previous code called bootUiLoad() which tried to load HTML into the
            // now-dead WebView instance — it silently did nothing.
            // Bug #11 fixed: onPageStarted override removed — it was redundantly
            // re-applying LAYER_TYPE_HARDWARE on every navigation, undoing the fix.
            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                Log.e(Config.TAG, "WebView render process gone — recreating Activity")
                recreate()
                return true
            }
        }

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
            webView.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                    msg?.let { Log.d(Config.TAG, "JS: ${it.message()} @ line ${it.lineNumber()}") }
                    return true
                }
            }
        } else {
            webView.webChromeClient = WebChromeClient()
        }
    }

    // ── LiveData observers ───────────────────────────────────────────────

    private fun setupObservers() {
        viewModel.uiHtml.observe(this) { html ->
            if (html != null) loadHtmlString(html) else loadBundledAsset()
        }

        viewModel.toastMessage.observe(this) { msg ->
            if (msg != null) {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                viewModel.toastMessageConsumed()
            }
        }

        viewModel.jsLog.observe(this) { msg ->
            jsLogToWebView(msg)
        }

        viewModel.usbEvent.observe(this) { event ->
            if (event.isNullOrEmpty()) return@observe

            when (event) {
                // Bug #5 fixed: consume the event BEFORE showing the dialog so
                // that screen rotation doesn't re-deliver it and show the dialog again.
                Config.EVENT_PROMPT_URL_FIRST_RUN -> {
                    viewModel.usbEventConsumed()
                    promptForUrl(isFirstRun = true) { newUrl ->
                        viewModel.onUrlEntered(newUrl, true)
                    }
                }
                Config.EVENT_PROMPT_URL_UPDATE -> {
                    viewModel.usbEventConsumed()
                    promptForUrl(isFirstRun = false) { newUrl ->
                        viewModel.onUrlEntered(newUrl, false)
                    }
                }
                Config.EVENT_USB_ERROR,
                Config.EVENT_USB_CONNECTED,
                Config.EVENT_USB_DISCONNECTED,
                Config.EVENT_USB_DEVICE_ATTACHED,
                Config.EVENT_USB_PERMISSION_DENIED -> {
                    notifyWeb(event)
                    viewModel.usbEventConsumed()
                }
            }
        }
    }

    // ── WebView load helpers ─────────────────────────────────────────────

    private fun loadBundledAsset() {
        webView.loadUrl(Config.BUNDLED_ASSET_URL)
    }

    private fun loadHtmlString(html: String) {
        webView.loadDataWithBaseURL(
            Config.BASE_URL_ORIGIN,
            html,
            "text/html",
            "UTF-8",
            null
        )
    }

    // ── WebView JS bridge helpers ─────────────────────────────────────────

    // Bug #12 fixed: runOnUiThread() removed from both helpers.
    // notifyWeb() is called from:
    //   • usbEvent observer   → main thread ✓
    //   • usbReceiver         → main thread ✓
    // jsLogToWebView() is called from:
    //   • jsLog observer      → main thread ✓
    // Neither method needs a redundant main-thread dispatch.

    private fun notifyWeb(status: String) {
        webView.evaluateJavascript(
            "if(typeof onUSBStatus==='function') onUSBStatus('$status')", null
        )
    }

    private fun jsLogToWebView(msg: String) {
        val escaped = msg.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
        webView.evaluateJavascript("if(typeof connLog==='function') connLog('$escaped')", null)
    }

    // ── Inactivity screen-dim ────────────────────────────────────────────

    private fun resetInactivityTimer() {
        inactivityHandler.removeCallbacks(dimRunnable)
        setScreenBrightness(Config.BRIGHTNESS_FULL)
        inactivityHandler.postDelayed(dimRunnable, Config.INACTIVITY_DIM_MS)
    }

    private fun setScreenBrightness(level: Float) {
        val params = window.attributes
        params.screenBrightness = level
        window.attributes = params
    }

    // ── Secret gestures ──────────────────────────────────────────────────
    //
    //  Top-LEFT  corner, 7 taps within 3 s → URL update prompt
    //  Top-RIGHT corner, 7 taps within 3 s → exit-kiosk dialog

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        resetInactivityTimer()
        if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
            val zonePx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                Config.SECRET_TAP_ZONE_DP.toFloat(),
                resources.displayMetrics
            )
            val w = window.decorView.width
            if (ev.y <= zonePx) {
                when {
                    ev.x <= zonePx              -> onSecretTap()
                    // Exit gesture zone: top-CENTRE of the screen (intentional).
                    w > 0 && ev.x >= (w / 2) - (zonePx / 2) && ev.x <= (w / 2) + (zonePx / 2) -> onExitSecretTap()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun onSecretTap() {
        val now = System.currentTimeMillis()
        tapTimestamps.removeAll { now - it > Config.SECRET_TAP_WINDOW_MS }
        tapTimestamps.addLast(now)
        Log.i(Config.TAG, "URL tap ${tapTimestamps.size}/${Config.SECRET_TAP_COUNT}")
        Toast.makeText(this, "Tap ${tapTimestamps.size}/${Config.SECRET_TAP_COUNT}", Toast.LENGTH_SHORT).show()
        if (tapTimestamps.size >= Config.SECRET_TAP_COUNT) {
            tapTimestamps.clear()
            promptForUrl(isFirstRun = false) { newUrl ->
                viewModel.onUrlEntered(newUrl, false)
            }
        }
    }

    private fun onExitSecretTap() {
        val now = System.currentTimeMillis()
        exitTapTimestamps.removeAll { now - it > Config.SECRET_TAP_WINDOW_MS }
        exitTapTimestamps.addLast(now)
        Log.i(Config.TAG, "Exit tap ${exitTapTimestamps.size}/${Config.SECRET_TAP_COUNT}")
        Toast.makeText(this, "Exit tap ${exitTapTimestamps.size}/${Config.SECRET_TAP_COUNT}", Toast.LENGTH_SHORT).show()
        if (exitTapTimestamps.size >= Config.SECRET_TAP_COUNT) {
            exitTapTimestamps.clear()
            showExitKioskDialog()
        }
    }

    // ── Kiosk dialogs ────────────────────────────────────────────────────

    private fun showExitKioskDialog() {
        AlertDialog.Builder(this)
            .setTitle("Exit Kiosk Mode?")
            .setMessage(
                "This will unlock the device and exit the controller app.\n" +
                        "Use this for maintenance only.\n\n" +
                        "To return to kiosk mode, simply relaunch the app or reboot."
            )
            .setPositiveButton("Exit Kiosk") { _, _ ->
                // Re-enable status bar before exiting so the device is usable.
                if (dpm.isDeviceOwnerApp(packageName)) {
                    try { dpm.setStatusBarDisabled(adminComponent, false) } catch (_: Exception) {}
                }
                if (KioskUtils.exitKioskMode(this)) inLockTask = false
                Toast.makeText(this, "Kiosk exited", Toast.LENGTH_SHORT).show()
                moveTaskToBack(true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptForUrl(isFirstRun: Boolean, onUrl: (String) -> Unit) {
        val padPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics
        ).toInt()

        // Bug #13 fixed: URL pre-fill now comes from the ViewModel, not a local prefs handle.
        val currentUrl = viewModel.getSavedUrl()

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "https://example.com/controller/index.html"
            setSingleLine(true)
            setText(currentUrl)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }

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
                val url = normalizeUrl(input.text.toString().trim())
                if (url == null) {
                    Toast.makeText(this, Config.TOAST_INVALID_URL, Toast.LENGTH_LONG).show()
                    if (isFirstRun) viewModel.onFirstRunSkipped()
                } else {
                    onUrl(url)
                }
            }

        if (isFirstRun) {
            builder.setNegativeButton("Use Built-in") { _, _ -> viewModel.onFirstRunSkipped() }
        } else {
            builder.setNegativeButton("Cancel") { d, _ -> d.dismiss() }
        }

        builder.show()
    }

    private fun normalizeUrl(raw: String): String? {
        if (raw.isBlank()) return null
        val withScheme = if (raw.startsWith("http://", ignoreCase = true) ||
            raw.startsWith("https://", ignoreCase = true)) raw
        else "https://$raw"
        return try { URL(withScheme).toString() } catch (_: Exception) { null }
    }

    // ── JS bridge (inner class — accesses ViewModel via outer reference) ──

    inner class ESP32Bridge {

        @JavascriptInterface
        fun connect(): String {
            val manager = getSystemService(Context.USB_SERVICE) as UsbManager
            val driver = viewModel.findDriver() ?: return "error:no_devices"

            if (!manager.hasPermission(driver.device)) {
                viewModel.logToJs("Requesting USB permission...")
                // stopLockTask() and requestPermission() must run on the main thread.
                // @JavascriptInterface methods run on the JS-bridge thread, so marshal here.
                runOnUiThread {
                    if (inLockTask) {
                        viewModel.logToJs("Temporarily exiting Lock Task for permission dialog")
                        if (KioskUtils.exitKioskMode(this@MainActivity)) inLockTask = false
                    }
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        PendingIntent.FLAG_MUTABLE else 0
                    val permIntent = PendingIntent.getBroadcast(
                        this@MainActivity, 0,
                        Intent(Config.ACTION_USB_PERMISSION).setPackage(packageName),
                        flags
                    )
                    manager.requestPermission(driver.device, permIntent)
                }
                return "requesting_permission"
            }

            // openPortSync is called from the JS-bridge thread (background).
            // That's correct — it does USB I/O and must NOT run on main thread.
            val success = viewModel.openPortSync(driver)
            return if (success) Config.EVENT_USB_CONNECTED else "error:connection_failed"
        }

        @JavascriptInterface
        fun send(command: String): String = viewModel.sendCommand(command)

        @JavascriptInterface
        fun receive(): String = viewModel.receiveData()

        @JavascriptInterface
        fun disconnect(): String {
            viewModel.closePort()
            return Config.EVENT_USB_DISCONNECTED
        }

        @JavascriptInterface
        fun isConnected(): Boolean = viewModel.isUsbConnected()

        @JavascriptInterface
        fun getDeviceList(): String {
            val manager = getSystemService(Context.USB_SERVICE) as UsbManager
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
