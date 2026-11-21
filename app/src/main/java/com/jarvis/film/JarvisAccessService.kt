package com.jarvis.film

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class JarvisAccessService : AccessibilityService() {

    companion object {
        @Volatile var instance: JarvisAccessService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun foregroundPackage(): String? = rootInActiveWindow?.packageName?.toString()

    fun screenText(): String? {
        val root = rootInActiveWindow ?: return null
        val sb = StringBuilder()
        collect(root, sb, 0)
        return sb.toString()
    }

    private fun collect(n: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (n == null || depth > 25 || sb.length > 3000) return
        if (!n.isPassword) {
            val t = n.text?.toString() ?: n.contentDescription?.toString()
            if (!t.isNullOrBlank()) sb.append(t).append(". ")
        }
        for (i in 0 until n.childCount) collect(n.getChild(i), sb, depth + 1)
    }

    fun clickText(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val pkg = root.packageName?.toString() ?: ""
        if (Block.bank(pkg)) return false
        val nodes = root.findAccessibilityNodeInfosByText(label) ?: return false
        for (node in nodes) {
            var x: AccessibilityNodeInfo? = node
            var depth = 0
            while (x != null && depth < 6) {
                if (x.isClickable) return x.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                x = x.parent
                depth++
            }
        }
        return false
    }
}
