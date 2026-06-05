package com.example.controller

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Device Admin Receiver — the entry point for Device Owner privileges.
 *
 * The app becomes Device Owner via a one-time ADB command after factory
 * reset (see kiosk setup instructions). Once provisioned, this receiver
 * is the "principal" for all DevicePolicyManager calls.
 *
 * This class doesn't need much code — just existing as a registered
 * receiver is what makes the app eligible to be Device Owner.
 */
class KioskDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i("UrbanNaps.DPC", "Device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i("UrbanNaps.DPC", "Device admin disabled")
    }
}
