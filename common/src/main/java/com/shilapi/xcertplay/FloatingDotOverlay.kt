package com.shilapi.xcertplay

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import com.shilapi.xcertplay.compat.realSdkInt
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * Issue #119: 悬浮小白点。部分车机无法在桌面添加快捷方式，
 * 开启后屏幕上显示一个可拖动的小圆点，点击即打开 DiPlay。
 * 优化：仅在退出 CarPlay / DiPlay 回到车机桌面等后台状态时显示，
 * 在当前 CarPlay / DiPlay 前台界面时不展示小白点；图标动态使用当前应用自身图标。
 */
object FloatingDotOverlay {
    private const val TAG = "FloatingDot"
    private const val PREFS = "diplay_floating_dot"
    private const val KEY_X = "dot_x"
    private const val KEY_Y = "dot_y"

    private var dotView: View? = null
    private var windowManager: WindowManager? = null
    private var activityCount = 0
    private var appRegistered = false

    fun init(app: Application) {
        if (appRegistered) return
        appRegistered = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                activityCount++
                updateVisibility(activity.applicationContext)
            }
            override fun onActivityResumed(activity: Activity) {
                updateVisibility(activity.applicationContext)
            }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                activityCount = (activityCount - 1).coerceAtLeast(0)
                updateVisibility(activity.applicationContext)
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun updateVisibility(context: Context) {
        if (activityCount > 0) {
            hide()
        } else {
            if (isEnabled(context) && permitted(context)) {
                show(context)
            } else {
                hide()
            }
        }
    }

    fun permitted(context: Context): Boolean {
        return if (realSdkInt() >= 23) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    fun isShowing(): Boolean = dotView != null

    fun setEnabled(context: Context, enabled: Boolean) {
        AirPlayPersistence.saveFloatingDot(context, enabled)
        val app = context.applicationContext as? Application
        if (app != null) init(app)
        updateVisibility(context.applicationContext)
    }

    fun isEnabled(context: Context): Boolean = AirPlayPersistence.loadFloatingDot(context)

    /** App 启动时调用，按用户设置恢复小白点。 */
    fun restoreIfEnabled(context: Context) {
        val app = context.applicationContext as? Application
        if (app != null) init(app)
        updateVisibility(context.applicationContext)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context) {
        if (dotView != null) return
        if (!permitted(context)) {
            Log.w(TAG, "overlay permission not granted")
            return
        }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val density = context.resources.displayMetrics.density
        val size = (48 * density).toInt()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val type = if (realSdkInt() >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val metrics = context.resources.displayMetrics
            x = prefs.getInt(KEY_X, metrics.widthPixels - size - (16 * density).toInt())
                .coerceIn(0, (metrics.widthPixels - size).coerceAtLeast(0))
            y = prefs.getInt(KEY_Y, (metrics.heightPixels / 3))
                .coerceIn(0, (metrics.heightPixels - size).coerceAtLeast(0))
            title = "DiPlay dot"
        }

        // 动态使用当前应用的自身图标
        val dot = android.widget.ImageView(context).apply {
            val appIcon = try {
                context.packageManager.getApplicationIcon(context.packageName)
            } catch (_: Throwable) {
                null
            }
            if (appIcon != null) {
                setImageDrawable(appIcon)
            } else {
                setImageResource(com.shilapi.xcertplay.shared.R.mipmap.ic_launcher_round)
            }
        }

        var downX = 0f
        var downY = 0f
        var moved = false
        dot.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (dx * dx + dy * dy > (8 * density).toInt().let { it * it }) {
                        moved = true
                    }
                    if (moved) {
                        params.x = (params.x + dx).coerceIn(
                            0, (context.resources.displayMetrics.widthPixels - size).coerceAtLeast(0))
                        params.y = (params.y + dy).coerceIn(
                            0, (context.resources.displayMetrics.heightPixels - size).coerceAtLeast(0))
                        runCatching { wm.updateViewLayout(v, params) }
                        downX = event.rawX
                        downY = event.rawY
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        prefs.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
                    } else {
                        v.performClick()
                    }
                    true
                }
                else -> false
            }
        }
        dot.setOnClickListener {
            // Issue #167: 以前直接启动全屏视频宿主，无会话时就是黑屏且只能进设置。
            // 改为走桌面图标同样的入口：有会话会自动回到投屏画面，无会话则显示带连接按钮的主页。
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(context, DiPlayActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            runCatching { context.startActivity(launch) }
                .onFailure { e -> Log.w(TAG, "launch failed", e) }
        }

        runCatching { wm.addView(dot, params) }
            .onSuccess {
                dotView = dot
                windowManager = wm
                Log.i(TAG, "floating dot shown")
            }
            .onFailure { e -> Log.w(TAG, "addView failed", e) }
    }

    fun hide() {
        val view = dotView ?: return
        runCatching { windowManager?.removeView(view) }
        dotView = null
        windowManager = null
    }
}
