package com.example.controller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Auto-launches MainActivity when the device finishes booting.
 *
 * Triggered by BOOT_COMPLETED (and a few related actions emitted by some
 * vendor ROMs). Without this, the user would have to tap the app icon
 * after every power-on.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i("UrbanNaps.Boot", "BootReceiver fired: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",      // some HTC/Sony
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {  // older HTC
                val launch = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(launch)
            }
        }
    }
}
