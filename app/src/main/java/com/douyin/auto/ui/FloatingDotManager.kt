package com.douyin.auto.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * 悬浮操作按钮（类似 iPhone 小白点 / AssistiveTouch）
 *
 * 通过无障碍覆盖层 [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY] 添加到窗口，
 * 无需 SYSTEM_ALERT_WINDOW 权限（仅限无障碍服务内部使用）。
 * 圆点可拖拽；点击圆点展开菜单，再次点击收起。
 *
 * @param context 运行环境（无障碍服务本身）
 * @param actions 菜单项列表（标签 + 点击回调），便于后续扩展更多功能
 */
class FloatingDotManager(
    private val context: Context,
    private val actions: List<FloatingAction>
) {
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val dm = context.resources.displayMetrics

    private val dotSize = 56.dp
    private var dotView: View? = null
    private var menuView: View? = null
    private var dotParams: WindowManager.LayoutParams? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var isMenuOpen = false

    // 拖拽 / 点击判定
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var moved = false

    /** 显示悬浮圆点 */
    fun show() {
        if (dotView != null) return
        val dot = createDotView()
        dotView = dot

        dotParams = createBaseParams(dotSize, dotSize).apply {
            x = dm.widthPixels - dotSize - 16.dp
            y = dm.heightPixels / 2
        }
        windowManager.addView(dot, dotParams)
    }

    /** 移除悬浮圆点与菜单 */
    fun dismiss() {
        hideMenu()
        dotView?.let { windowManager.removeView(it) }
        dotView = null
        menuView = null
    }

    private fun createBaseParams(w: Int, h: Int): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        return WindowManager.LayoutParams(
            w, h, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }

    private fun createDotView(): View {
        val dot = FrameLayout(context)
        dot.background = createCircleDrawable()
        dot.setOnTouchListener { _, event -> handleDotTouch(event) }
        return dot
    }

    private fun handleDotTouch(event: MotionEvent): Boolean {
        val params = dotParams ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                moved = false
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                if (abs(dx) > 6 || abs(dy) > 6) {
                    moved = true
                    hideMenu()
                }
                params.x = (initialX + dx).toInt()
                params.y = (initialY + dy).toInt()
                windowManager.updateViewLayout(dotView, params)
                true
            }

            MotionEvent.ACTION_UP -> {
                if (!moved) toggleMenu()
                true
            }

            else -> false
        }

        return false
    }

    private fun toggleMenu() {
        if (isMenuOpen) hideMenu() else showMenu()
    }

    private fun showMenu() {
        if (menuView != null) return
        val menu = createMenuView()
        menuView = menu
        val menuWidth = 180.dp
        menuParams = createBaseParams(menuWidth, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            val toLeft = (dotParams!!.x + dotSize) > dm.widthPixels / 2
            x = if (toLeft) {
                maxOf(0, dotParams!!.x - menuWidth)
            } else {
                minOf(dm.widthPixels - menuWidth, dotParams!!.x + dotSize)
            }
            y = (dotParams!!.y - 8.dp)
                .coerceAtLeast(0)
                .coerceAtMost(dm.heightPixels - 240.dp)
        }
        windowManager.addView(menu, menuParams)
        isMenuOpen = true
    }

    private fun hideMenu() {
        menuView?.let { windowManager.removeView(it) }
        menuView = null
        isMenuOpen = false
    }

    private fun createMenuView(): View {
        val container = FrameLayout(context)
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = createPanelDrawable()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        actions.forEach { action ->
            val btn = TextView(context).apply {
                text = action.label
                setTextColor(Color.parseColor("#FF1C1B1F"))
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(16.dp, 14.dp, 16.dp, 14.dp)
                isClickable = true
                setOnClickListener {
                    action.onClick()
                    hideMenu()
                }
            }
            panel.addView(btn)
        }
        container.addView(panel)
        return container
    }

    private fun createCircleDrawable(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#D9FFFFFF")) // 半透明白色
            setStroke(2.dp, Color.parseColor("#FFBDBDBD"))
        }
    }

    private fun createPanelDrawable(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14.dp.toFloat()
            setColor(Color.parseColor("#F2FFFFFF"))
            setStroke(1.dp, Color.parseColor("#FFE0E0E0"))
        }
    }

    private val Int.dp: Int
        get() = (this * dm.density).toInt()
}

/**
 * 悬浮菜单项
 *
 * @param label 菜单显示的文本
 * @param onClick 点击后的执行逻辑
 */
data class FloatingAction(
    val label: String,
    val onClick: () -> Unit
)
