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
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application), SerialInputOutputManager.Listener {

    private val prefs: SharedPreferences = application.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)

    private val usbSerialManager = UsbSerialManager(application, this)

    private val _isConnected = MutableLiveData<Boolean>(false)
    val isConnected: LiveData<Boolean> = _isConnected

    private val _currentUiSource = MutableLiveData<UiSource>(UiSource.NONE)
    val currentUiSource: LiveData<UiSource> = _currentUiSource

    private val _uiHtml = MutableLiveData<String?>()
    val uiHtml: LiveData<String?> = _uiHtml

    private val _toastMessage = MutableLiveData<String?>()
    val toastMessage: LiveData<String?> = _toastMessage

    private val _jsLog = MutableLiveData<String>()
    val jsLog: LiveData<String> = _jsLog

    private val _usbEvent = MutableLiveData<String>()
    val usbEvent: LiveData<String> = _usbEvent

    enum class UiSource { NONE, BUNDLED, CACHE, NETWORK }

    fun bootUiLoad() {
        val savedUrl = prefs.getString(Config.PREF_UI_URL, null)
        val online = NetworkUtils.isOnline(getApplication())
        Log.i(Config.VM_TAG, "bootUiLoad: savedUrl=$savedUrl online=$online")

        if (savedUrl.isNullOrBlank()) {
            if (online) {
                _usbEvent.value = Config.EVENT_PROMPT_URL_FIRST_RUN
            } else {
                Log.i(Config.VM_TAG, "No URL saved + offline — using bundled asset")
                _toastMessage.value = Config.TOAST_OFFLINE_FALLBACK
                loadBundledAsset()
            }
        } else {
            if (online) {
                fetchUrlOrFallback(savedUrl)
            } else {
                Log.i(Config.VM_TAG, "Offline — loading from cache")
                loadCachedOrBundled()
            }
        }
    }

    fun onUrlEntered(newUrl: String, isFirstRun: Boolean) {
        prefs.edit().putString(Config.PREF_UI_URL, newUrl).apply()
        fetchUrlOrFallback(newUrl)
    }

    fun onFirstRunSkipped() {
        loadBundledAsset()
    }

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
                    Log.i(Config.VM_TAG, "Fetch OK (${html.length} chars) — caching and loading")
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
        val cached = readCache()
        if (cached != null) {
            Log.i(Config.VM_TAG, "Loading from cache (${cached.length} chars)")
            _uiHtml.value = cached
            _currentUiSource.value = UiSource.CACHE
        } else {
            Log.i(Config.VM_TAG, "No cache — loading bundled asset")
            loadBundledAsset()
        }
    }

    private fun loadBundledAsset() {
        _uiHtml.value = null // Signal to load from file:///android_asset/index.html
        _currentUiSource.value = UiSource.BUNDLED
    }

    private fun cacheFile(): File = File(getApplication<Application>().filesDir, Config.CACHE_FILENAME)

    private fun writeCache(html: String) {
        try {
            cacheFile().writeText(html, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(Config.VM_TAG, "Cache write failed: ${e.message}")
        }
    }

    private fun readCache(): String? {
        val f = cacheFile()
        return if (f.exists()) {
            try { f.readText(Charsets.UTF_8) } catch (e: Exception) { null }
        } else null
    }

    fun findDriver(): UsbSerialDriver? = usbSerialManager.findDriver()

    fun openPort(driver: UsbSerialDriver) {
        if (usbSerialManager.openPort(driver)) {
            _isConnected.value = true
            logToJs("✓ Port opened at ${Config.BAUD_RATE} 8N1, DTR+RTS asserted")
            _usbEvent.value = Config.EVENT_USB_CONNECTED
        } else {
            _usbEvent.value = Config.EVENT_USB_ERROR
        }
    }

    fun closePort() {
        usbSerialManager.closePort()
        _isConnected.value = false
    }

    override fun onNewData(data: ByteArray) {
        usbSerialManager.onNewData(data)
    }

    override fun onRunError(e: Exception) {
        logToJs("Serial run error: ${e.message}")
        viewModelScope.launch(Dispatchers.Main) {
            closePort()
            _usbEvent.value = Config.EVENT_USB_DISCONNECTED
        }
    }

    fun logToJs(msg: String) {
        Log.i(Config.VM_TAG, msg)
        _jsLog.postValue(msg)
    }

    fun sendCommand(command: String): String = usbSerialManager.sendCommand(command)

    fun receiveData(): String = usbSerialManager.receiveData()

    fun toastMessageConsumed() {
        _toastMessage.value = null
    }

    fun usbEventConsumed() {
        _usbEvent.value = ""
    }

    override fun onCleared() {
        super.onCleared()
        closePort()
    }

    fun handleUsbPermission(granted: Boolean) {
        if (granted) {
            logToJs("Permission granted, opening port...")
            val driver = findDriver()
            if (driver != null) {
                openPort(driver)
            } else {
                logToJs("ERROR: no driver after permission grant")
                _usbEvent.value = Config.EVENT_USB_ERROR
            }
        } else {
            logToJs("Permission DENIED by user")
            _usbEvent.value = Config.EVENT_USB_PERMISSION_DENIED
        }
    }
}
