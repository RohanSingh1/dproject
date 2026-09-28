package com.dproject.remote.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service that replays taps, swipes, and text input
 * received from the remote browser session.
 *
 * The signalling layer calls the companion object methods which
 * delegate to the running instance.
 */
class RemoteInputService : AccessibilityService() {

    companion object {
        private const val TAG = "RemoteInputService"
        private var instance: RemoteInputService? = null

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            return enabled?.contains("com.dproject.remote/.service.RemoteInputService") == true
        }

        fun performTap(x: Float, y: Float) {
            instance?.dispatchTap(x, y) ?: Log.w(TAG, "Service not running")
        }

        fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
            instance?.dispatchSwipe(x1, y1, x2, y2, durationMs) ?: Log.w(TAG, "Service not running")
        }

        fun performNav(action: String) {
            instance?.dispatchNav(action) ?: Log.w(TAG, "Service not running")
        }

        fun performText(text: String) {
            instance?.dispatchText(text) ?: Log.w(TAG, "Service not running")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    // ── Gesture dispatch ──────────────────────────────────────────────────────

    private fun dispatchTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
        Log.d(TAG, "Tap dispatched: $x, $y")
    }

    private fun dispatchSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(50, 2000))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
        Log.d(TAG, "Swipe dispatched: ($x1,$y1) → ($x2,$y2)")
    }

    private fun dispatchNav(action: String) {
        when (action) {
            "back"    -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home"    -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        }
        Log.d(TAG, "Nav dispatched: $action")
    }

    private fun dispatchText(text: String) {
        val root = rootInActiveWindow ?: return
        val focused = findFocusedInput(root)
        if (focused != null) {
            val args = android.os.Bundle().apply {
                putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.d(TAG, "Text set: $text")
        } else {
            // Fallback: try clipboard paste
            Log.w(TAG, "No focused input found for text: $text")
        }
    }

    private fun findFocusedInput(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFocusedInput(child)
            if (result != null) return result
        }
        return null
    }
}
