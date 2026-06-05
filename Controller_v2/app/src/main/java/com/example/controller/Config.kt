package com.example.controller

object Config {
    const val TAG = "UrbanNaps"
    const val VM_TAG = "UrbanNapsVM"
    const val USB_TAG = "UsbSerialManager"
    
    const val ACTION_USB_PERMISSION = "com.urbannaps.controller.USB_PERMISSION"
    const val BAUD_RATE = 115200

    const val PREFS_NAME       = "controller_prefs"
    const val PREF_UI_URL      = "ui_url"
    const val CACHE_FILENAME   = "ui_cache.html"
    const val FETCH_TIMEOUT_MS = 8000
    const val BASE_URL_ORIGIN  = "https://controller.local/"

    const val RX_MAX_CHUNKS    = 4096
    const val WRITE_TIMEOUT_MS = 1000
    const val WRITER_THREAD_SLEEP_MS = 200L
    
    const val SECRET_TAP_COUNT = 7
    const val SECRET_TAP_WINDOW_MS = 3000L
    const val SECRET_TAP_ZONE_DP = 80

    const val BUNDLED_ASSET_URL = "file:///android_asset/index.html"
    const val WEBVIEW_BG_COLOR = "#06080c"

    // Event keys
    const val EVENT_PROMPT_URL_FIRST_RUN = "prompt_url_first_run"
    const val EVENT_PROMPT_URL_UPDATE = "prompt_url_update"
    const val EVENT_USB_ERROR = "error"
    const val EVENT_USB_CONNECTED = "connected"
    const val EVENT_USB_DISCONNECTED = "disconnected"
    const val EVENT_USB_DEVICE_ATTACHED = "device_attached"
    const val EVENT_USB_PERMISSION_DENIED = "permission_denied"

    // Toast messages
    const val TOAST_OFFLINE_FALLBACK = "Offline — loading built-in UI. Connect to internet to set custom URL."
    const val TOAST_FETCHING_UI = "Fetching latest UI..."
    const val TOAST_FETCH_FAILED_FALLBACK = "Couldn't reach server — loading cached UI"
    const val TOAST_INVALID_URL = "Invalid URL — keeping current UI"

    // JS Bridge strings
    const val JS_BRIDGE_NAME = "ESP32"
}
