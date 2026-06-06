package com.example.controller

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.hoho.android.usbserial.driver.UsbSerialDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Holds all non-UI state and business logic for the kiosk controller.
 *
 * Bugs fixed vs. previous version:
 *   #3  – handleUsbPermission dispatches USB port open to Dispatchers.IO so
 *          the main thread is never blocked by USB hardware I/O (ANR fix).
 *   #7  – No longer implements SerialInputOutputManager.Listener. UsbSerialManager
 *          now owns its listener internally; ViewModel just wires the error callback.
 *   #13 – Added getSavedUrl() so the Activity never needs its own SharedPreferences
 *          handle (MVVM: single source of truth for persisted state).
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs: SharedPreferences =
        application.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)

    // Wire the error callback on construction. The callback is invoked from
    // the serial-IO thread, so closePort() is dispatched to Dispatchers.Main
    // (not IO) to avoid the potential deadlock described in UsbSerialManager.
    private val usbSerialManager = UsbSerialManager(application).also { mgr ->
        mgr.onRunError = { e ->
            logToJs("Serial run error: ${e.message}")
            // Dispatch to main so ioManager.stop() is NOT called from the
            // serial-IO thread that raised the error (deadlock risk).
            viewModelScope.launch(Dispatchers.Main) {
                mgr.closePort()
                _isConnected.value = false
                _usbEvent.value = Config.EVENT_USB_DISCONNECTED
            }
        }
    }

    // ── Observable state ─────────────────────────────────────────────────

    private val _isConnected = MutableLiveData(false)
    val isConnected: LiveData<Boolean> = _isConnected

    private val _currentUiSource = MutableLiveData(UiSource.NONE)
    val currentUiSource: LiveData<UiSource> = _currentUiSource

    /**
     * Non-null  → load this HTML string into the WebView.
     * null      → load the bundled asset (file:///android_asset/index.html).
     */
    private val _uiHtml = MutableLiveData<String?>()
    val uiHtml: LiveData<String?> = _uiHtml

    /** Single-shot toast; consume via [toastMessageConsumed]. */
    private val _toastMessage = MutableLiveData<String?>()
    val toastMessage: LiveData<String?> = _toastMessage

    /** Single-shot JS log line; observed by Activity to forward to WebView. */
    private val _jsLog = MutableLiveData<String>()
    val jsLog: LiveData<String> = _jsLog

    /**
     * Single-shot USB / navigation event; consume via [usbEventConsumed].
     * Empty string "" is the consumed/idle sentinel — observers must check
     * event.isNotEmpty() before acting.
     */
    private val _usbEvent = MutableLiveData<String>()
    val usbEvent: LiveData<String> = _usbEvent

    enum class UiSource { NONE, BUNDLED, CACHE, NETWORK }

    // ── UI loading ───────────────────────────────────────────────────────

    /**
     * Boot-time source decision tree (runs once per Activity creation):
     *
     *  savedUrl?  online?   action
     *  ─────────  ───────   ────────────────────────────────────────────
     *  no         yes       emit PROMPT_URL_FIRST_RUN → user enters URL
     *  no         no        load bundled asset
     *  yes        yes       fetch → cache → load  (fallback: cache/bundled)
     *  yes        no        load cache → bundled
     */
    fun bootUiLoad() {
        val savedUrl = prefs.getString(Config.PREF_UI_URL, null)
        val online = NetworkUtils.isOnline(getApplication())
        Log.i(Config.VM_TAG, "bootUiLoad: savedUrl=$savedUrl online=$online")

        if (savedUrl.isNullOrBlank()) {
            if (online) {
                _usbEvent.value = Config.EVENT_PROMPT_URL_FIRST_RUN
            } else {
                Log.i(Config.VM_TAG, "No URL + offline — using bundled asset")
                _toastMessage.value = Config.TOAST_OFFLINE_FALLBACK
                loadBundledAsset()
            }
        } else {
            if (online) fetchUrlOrFallback(savedUrl) else loadCachedOrBundled()
        }
    }

    /** Persists [newUrl] and immediately fetches it. */
    fun onUrlEntered(newUrl: String, @Suppress("UNUSED_PARAMETER") isFirstRun: Boolean) {
        prefs.edit().putString(Config.PREF_UI_URL, newUrl).apply()
        fetchUrlOrFallback(newUrl)
    }

    /** Called when the user skips the first-run URL prompt. */
    fun onFirstRunSkipped() = loadBundledAsset()

    /**
     * Returns the persisted UI URL for pre-filling the URL dialog.
     * Bug #13: Activity no longer needs its own SharedPreferences handle.
     */
    fun getSavedUrl(): String = prefs.getString(Config.PREF_UI_URL, "") ?: ""

    private fun fetchUrlOrFallback(url: String) {
        _toastMessage.value = Config.TOAST_FETCHING_UI
        viewModelScope.launch(Dispatchers.IO) {
            val html = try {
                NetworkUtils.downloadString(url, Config.FETCH_TIMEOUT_MS)
            } catch (e: Exception) {
                Log.w(Config.VM_TAG, "Fetch failed: ${e.message}")
                null
            }
            withContext(Dispatchers.Main) {
                if (html != null) {
                    Log.i(Config.VM_TAG, "Fetch OK (${html.length} chars) — caching")
                    writeCache(html)
                    _uiHtml.value = html
                    _currentUiSource.value = UiSource.NETWORK
                } else {
                    Log.w(Config.VM_TAG, "Fetch failed — falling back to cache")
                    _toastMessage.value = Config.TOAST_FETCH_FAILED_FALLBACK
                    loadCachedOrBundled()
                }
            }
        }
    }

    private fun loadCachedOrBundled() {
        viewModelScope.launch(Dispatchers.IO) {
            val cached = readCache()
            withContext(Dispatchers.Main) {
                if (cached != null) {
                    Log.i(Config.VM_TAG, "Loading from cache (${cached.length} chars)")
                    _uiHtml.value = cached
                    _currentUiSource.value = UiSource.CACHE
                } else {
                    Log.i(Config.VM_TAG, "No cache — loading bundled asset")
                    loadBundledAsset()
                }
            }
        }
    }

    private fun loadBundledAsset() {
        _uiHtml.value = null   // null signals Activity to load bundled asset
        _currentUiSource.value = UiSource.BUNDLED
    }

    // ── Cache I/O ────────────────────────────────────────────────────────

    private fun cacheFile() = File(getApplication<Application>().filesDir, Config.CACHE_FILENAME)

    private fun writeCache(html: String) {
        try { cacheFile().writeText(html, Charsets.UTF_8) }
        catch (e: Exception) { Log.w(Config.VM_TAG, "Cache write failed: ${e.message}") }
    }

    private fun readCache(): String? {
        val f = cacheFile()
        return if (f.exists()) try { f.readText(Charsets.UTF_8) } catch (_: Exception) { null }
        else null
    }

    // ── USB / serial ─────────────────────────────────────────────────────

    fun findDriver() = usbSerialManager.findDriver()
    fun hasPermission(driver: UsbSerialDriver) = usbSerialManager.hasPermission(driver)

    /**
     * Opens the serial port and updates LiveData state.
     * MUST be called from a background thread — delegates to UsbSerialManager
     * which performs blocking USB I/O.
     */
    fun openPortSync(driver: UsbSerialDriver): Boolean {
        val success = usbSerialManager.openPort(driver)
        if (success) {
            _isConnected.postValue(true)
            logToJs("✓ Port opened at ${Config.BAUD_RATE} 8N1, DTR+RTS asserted")
            _usbEvent.postValue(Config.EVENT_USB_CONNECTED)
        } else {
            _usbEvent.postValue(Config.EVENT_USB_ERROR)
        }
        return success
    }

    /** Closes the serial port. Safe to call from any thread. */
    fun closePort() {
        usbSerialManager.closePort()
        _isConnected.postValue(false)
    }

    /**
     * Called from the BroadcastReceiver (main thread) after the USB-permission
     * dialog is dismissed.
     *
     * Bug #3 fixed: port open is dispatched to Dispatchers.IO so USB hardware
     * I/O never runs on the main thread (no ANR risk).
     */
    fun handleUsbPermission(granted: Boolean) {
        if (granted) {
            logToJs("Permission granted, opening port...")
            val driver = findDriver()
            if (driver != null) {
                viewModelScope.launch(Dispatchers.IO) {
                    openPortSync(driver)
                }
            } else {
                logToJs("ERROR: no driver after permission grant")
                _usbEvent.postValue(Config.EVENT_USB_ERROR)
            }
        } else {
            logToJs("Permission DENIED by user")
            _usbEvent.postValue(Config.EVENT_USB_PERMISSION_DENIED)
        }
    }

    fun sendCommand(command: String): String = usbSerialManager.sendCommand(command)
    fun receiveData(): String = usbSerialManager.receiveData()
    fun isUsbConnected(): Boolean = usbSerialManager.isConnected

    // ── JS logging ───────────────────────────────────────────────────────

    fun logToJs(msg: String) {
        Log.i(Config.VM_TAG, msg)
        _jsLog.postValue(msg)   // postValue: safe from any thread
    }

    // ── Single-shot event consumption ────────────────────────────────────

    fun toastMessageConsumed() { _toastMessage.value = null }
    fun usbEventConsumed()     { _usbEvent.value = "" }

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        // Dispatch to IO: ioManager.stop() can block briefly and should not
        // run on the main thread during ViewModel teardown.
        viewModelScope.launch(Dispatchers.IO) {
            usbSerialManager.closePort()
        }
    }
}
