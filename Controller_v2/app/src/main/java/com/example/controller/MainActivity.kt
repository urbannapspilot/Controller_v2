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

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val viewModel: MainViewModel by viewModels()

    private lateinit var prefs: SharedPreferences

    // Secret gestures tracking
    private val tapTimestamps = ArrayDeque<Long>()
    private val exitTapTimestamps = ArrayDeque<Long>()

    // Kiosk state
    @Volatile private var inLockTask = false
    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    // Screen Dimming
    private val inactivityHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val dimRunnable = Runnable { setScreenBrightness(Config.BRIGHTNESS_DIM) }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Config.ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    viewModel.handleUsbPermission(granted)
                    
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

        prefs = getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)
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
                    Toast.makeText(this@MainActivity, "Use top-right corner gesture to exit", Toast.LENGTH_SHORT).show()
                }
            }
        })

        window.decorView.post {
            KioskUtils.applyImmersiveMode(this)
            inLockTask = KioskUtils.enterKioskMode(this, adminComponent)
            resetInactivityTimer() // Start the timer
        }
    }

    private fun resetInactivityTimer() {
        inactivityHandler.removeCallbacks(dimRunnable)
        if (window.attributes.screenBrightness == Config.BRIGHTNESS_DIM) {
            // If we were dimmed, reload the UI on "wake" to ensure it's fresh
            // (Optional: remove this if your UI state is too complex to reset)
            // webView.reload() 
        }
        setScreenBrightness(Config.BRIGHTNESS_FULL)
        inactivityHandler.postDelayed(dimRunnable, Config.INACTIVITY_DIM_MS)
    }

    private fun setScreenBrightness(level: Float) {
        val params = window.attributes
        params.screenBrightness = level
        window.attributes = params
    }

    private fun setupObservers() {
        viewModel.uiHtml.observe(this) { html ->
            if (html != null) {
                loadHtmlString(html)
            } else {
                loadBundledAsset()
            }
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
            when (event) {
                Config.EVENT_PROMPT_URL_FIRST_RUN -> promptForUrl(isFirstRun = true) { newUrl ->
                    viewModel.onUrlEntered(newUrl, true)
                }
                Config.EVENT_PROMPT_URL_UPDATE -> promptForUrl(isFirstRun = false) { newUrl ->
                    viewModel.onUrlEntered(newUrl, false)
                }
                Config.EVENT_USB_ERROR, Config.EVENT_USB_CONNECTED, Config.EVENT_USB_DISCONNECTED, 
                Config.EVENT_USB_DEVICE_ATTACHED, Config.EVENT_USB_PERMISSION_DENIED -> {
                    if (event.isNotEmpty()) {
                        notifyWeb(event)
                        viewModel.usbEventConsumed()
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) KioskUtils.applyImmersiveMode(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        try {
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.removeJavascriptInterface(Config.JS_BRIDGE_NAME)
            webView.destroy()
        } catch (_: Exception) {}
    }

    private fun prepareWebView() {
        webView = findViewById(R.id.webView)
        // Enable full hardware acceleration.
        // On modern Android/WebView versions, LAYER_TYPE_HARDWARE is the default and 
        // provides the best performance for animations and CSS transitions.
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.setBackgroundColor(android.graphics.Color.parseColor(Config.WEBVIEW_BG_COLOR))
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
            
            // Performance optimizations
            databaseEnabled = true
            setRenderPriority(WebSettings.RenderPriority.HIGH)
            // Enable hardware-accelerated features
            setGeolocationEnabled(false)
            setNeedInitialFocus(false)
            // Some hardware chips benefit from disabling safe browsing in kiosk context
            safeBrowsingEnabled = false
        }
        
        // Ensure no flicker during transitions
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.addJavascriptInterface(ESP32Bridge(), Config.JS_BRIDGE_NAME)
        
        webView.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                Log.e(Config.TAG, "WebView render process gone. Reloading...")
                viewModel.bootUiLoad()
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Optimize rendering when page starts
                view?.setLayerType(View.LAYER_TYPE_HARDWARE, null)
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

    private fun promptForUrl(isFirstRun: Boolean, onUrl: (String) -> Unit) {
        val padPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics
        ).toInt()

        val currentUrl = prefs.getString(Config.PREF_UI_URL, "") ?: ""

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
                val raw = input.text.toString().trim()
                val url = normalizeUrl(raw)
                if (url == null) {
                    Toast.makeText(this, Config.TOAST_INVALID_URL,
                        Toast.LENGTH_LONG).show()
                    if (isFirstRun) viewModel.onFirstRunSkipped()
                } else {
                    onUrl(url)
                }
            }

        if (isFirstRun) {
            builder.setNegativeButton("Use Built-in") { _, _ ->
                viewModel.onFirstRunSkipped()
            }
        } else {
            builder.setNegativeButton("Cancel") { d, _ -> d.dismiss() }
        }

        builder.show()
    }

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

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        resetInactivityTimer() // Reset timer on any touch
        if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
            val zonePx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                Config.SECRET_TAP_ZONE_DP.toFloat(),
                resources.displayMetrics
            )
            val w = window.decorView.width

            if (ev.y <= zonePx) {
                if (ev.x <= zonePx) {
                    onSecretTap()
                } else if (w > 0 && ev.x >= (w / 2) - (zonePx / 2) && ev.x <= (w / 2) + (zonePx / 2)) {
                    onExitSecretTap()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun onSecretTap() {
        val now = System.currentTimeMillis()
        while (tapTimestamps.isNotEmpty() &&
            now - tapTimestamps.first() > Config.SECRET_TAP_WINDOW_MS) {
            tapTimestamps.removeFirst()
        }
        tapTimestamps.addLast(now)

        Log.i(Config.TAG, "URL tap ${tapTimestamps.size}/${Config.SECRET_TAP_COUNT}")
        Toast.makeText(this,
            "Tap ${tapTimestamps.size}/${Config.SECRET_TAP_COUNT}",
            Toast.LENGTH_SHORT).show()

        if (tapTimestamps.size >= Config.SECRET_TAP_COUNT) {
            tapTimestamps.clear()
            promptForUrl(isFirstRun = false) { newUrl ->
                viewModel.onUrlEntered(newUrl, false)
            }
        }
    }

    private fun onExitSecretTap() {
        val now = System.currentTimeMillis()
        while (exitTapTimestamps.isNotEmpty() &&
            now - exitTapTimestamps.first() > Config.SECRET_TAP_WINDOW_MS) {
            exitTapTimestamps.removeFirst()
        }
        exitTapTimestamps.addLast(now)

        Log.i(Config.TAG, "Exit tap ${exitTapTimestamps.size}/${Config.SECRET_TAP_COUNT}")
        Toast.makeText(this,
            "Exit tap ${exitTapTimestamps.size}/${Config.SECRET_TAP_COUNT}",
            Toast.LENGTH_SHORT).show()

        if (exitTapTimestamps.size >= Config.SECRET_TAP_COUNT) {
            exitTapTimestamps.clear()
            showExitKioskDialog()
        }
    }

    private fun showExitKioskDialog() {
        AlertDialog.Builder(this)
            .setTitle("Exit Kiosk Mode?")
            .setMessage(
                "This will unlock the device and exit the controller app. " +
                        "Use this for maintenance only.\n\n" +
                        "To return to kiosk mode, simply relaunch the app or reboot."
            )
            .setPositiveButton("Exit Kiosk") { _, _ ->
                // Restore status bar before exiting
                val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                if (dpm.isDeviceOwnerApp(packageName)) {
                    dpm.setStatusBarDisabled(adminComponent, false)
                }

                if (KioskUtils.exitKioskMode(this)) {
                    inLockTask = false
                }
                Toast.makeText(this, "Kiosk exited", Toast.LENGTH_SHORT).show()
                moveTaskToBack(true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun jsLogToWebView(msg: String) {
        runOnUiThread {
            val escaped = msg.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
            webView.evaluateJavascript("if(typeof connLog==='function') connLog('$escaped')", null)
        }
    }

    private fun notifyWeb(status: String) {
        runOnUiThread {
            webView.evaluateJavascript(
                "if(typeof onUSBStatus==='function') onUSBStatus('$status')", null
            )
        }
    }

    override fun onUserLeaveHint() {
        if (inLockTask) {
            Log.d(Config.TAG, "onUserLeaveHint (consumed)")
            return
        }
        super.onUserLeaveHint()
    }

    inner class ESP32Bridge {

        @JavascriptInterface
        fun connect(): String {
            val manager = getSystemService(Context.USB_SERVICE) as UsbManager
            val driver = viewModel.findDriver() ?: return "error:no_devices"

            if (!manager.hasPermission(driver.device)) {
                viewModel.logToJs("Requesting USB permission...")

                runOnUiThread {
                    if (inLockTask) {
                        viewModel.logToJs("Temporarily exiting Lock Task for permission dialog")
                        if (KioskUtils.exitKioskMode(this@MainActivity)) {
                            inLockTask = false
                        }
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
            
            val success = viewModel.openPortSync(driver)
            return if (success) Config.EVENT_USB_CONNECTED else "error:connection_failed"
        }

        @JavascriptInterface
        fun send(command: String): String {
            return viewModel.sendCommand(command)
        }

        @JavascriptInterface
        fun receive(): String {
            return viewModel.receiveData()
        }

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
