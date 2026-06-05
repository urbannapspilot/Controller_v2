package com.example.controller

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController

object KioskUtils {
    private const val TAG = "KioskUtils"

    fun applyImmersiveMode(activity: Activity) {
        val window = activity.window
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

    fun enterKioskMode(activity: Activity, adminComponent: ComponentName): Boolean {
        val dpm = activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val packageName = activity.packageName

        if (dpm.isDeviceOwnerApp(packageName)) {
            try {
                dpm.setLockTaskPackages(adminComponent, arrayOf(packageName))
                Log.i(TAG, "Set as Device Owner — entering Lock Task Mode")
            } catch (e: Exception) {
                Log.w(TAG, "setLockTaskPackages failed: ${e.message}")
            }
        }

        return try {
            activity.startLockTask()
            true
        } catch (e: Exception) {
            Log.w(TAG, "startLockTask failed: ${e.message}")
            false
        }
    }

    fun exitKioskMode(activity: Activity): Boolean {
        return try {
            activity.stopLockTask()
            true
        } catch (e: Exception) {
            Log.w(TAG, "stopLockTask failed: ${e.message}")
            false
        }
    }
}
