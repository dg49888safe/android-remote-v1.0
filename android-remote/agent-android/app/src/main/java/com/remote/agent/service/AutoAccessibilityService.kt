package com.remote.agent.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务：支持不需要 root 的手势模拟和界面交互。
 * 需要用户在「设置 → 无障碍」中手动开启本服务。
 */
class AutoAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AutoAccessibilityService? = null
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** 模拟点击屏幕坐标 */
    fun performTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /** 模拟滑动 */
    fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 300) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /** 模拟返回键 */
    fun pressBack() = performGlobalAction(GLOBAL_ACTION_BACK)

    /** 模拟 Home 键 */
    fun pressHome() = performGlobalAction(GLOBAL_ACTION_HOME)

    /** 展开通知栏 */
    fun openNotifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
}
