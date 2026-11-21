package dev.stagelab.nativehook.xposed

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Official iPadOS Stage Manager blurred-wallpaper stage backdrop inside `com.android.launcher`.
 *
 * Because this view lives inside the Launcher Activity's DecorView:
 * - It sits ABOVE all desktop icons & widgets (hiding home screen clutter when Stage Manager is active).
 * - It sits NATURALLY BELOW all ColorOS native Flexible Windows and the native glass Dock,
 *   so it NEVER intercepts app window touches or interferes with native Z-order.
 */
object LauncherBackdropHook {

    const val ACTION_STAGE_BACKDROP = "dev.stagelab.nativehook.ACTION_STAGE_BACKDROP"
    private const val LAUNCHER_CLASS = "com.android.launcher.Launcher"
    private const val BACKDROP_TAG = "StageLabBlurBackdrop"

    private var launcherRef: WeakReference<Activity>? = null
    private val receiverRegistered = AtomicBoolean(false)

    @Volatile
    private var backdropActive = false

    fun install(classLoader: ClassLoader) {
        val cls = try {
            XposedHelpers.findClass(LAUNCHER_CLASS, classLoader)
        } catch (t: Throwable) {
            HookGuard.warn("LauncherBackdropHook: class not found: $LAUNCHER_CLASS", t)
            return
        }

        try {
            val resumeHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    HookGuard.safeRun("LauncherBackdropHook.lifecycle") {
                        val activity = param.thisObject as? Activity ?: return@safeRun
                        launcherRef = WeakReference(activity)
                        registerReceiverIfNeeded(activity.applicationContext)
                        applyBackdropState(activity, backdropActive)
                    }
                }
            }
            XposedBridge.hookAllMethods(cls, "onStart", resumeHook)
            XposedBridge.hookAllMethods(cls, "onResume", resumeHook)
            HookGuard.info("LauncherBackdropHook: hooked $LAUNCHER_CLASS#onStart & onResume")
        } catch (t: Throwable) {
            HookGuard.warn("LauncherBackdropHook: failed to hook lifecycle", t)
        }
    }

    private fun registerReceiverIfNeeded(appContext: Context) {
        if (!receiverRegistered.compareAndSet(false, true)) return
        try {
            val filter = IntentFilter(ACTION_STAGE_BACKDROP)
            appContext.registerReceiver(backdropReceiver, filter, Context.RECEIVER_EXPORTED)
            HookGuard.info("LauncherBackdropHook: registered backdrop receiver")
        } catch (t: Throwable) {
            receiverRegistered.set(false)
            HookGuard.warn("LauncherBackdropHook: failed to register receiver", t)
        }
    }

    private val backdropReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_STAGE_BACKDROP) return
            HookGuard.safeRun("LauncherBackdropReceiver.onReceive") {
                val visible = intent.getBooleanExtra("visible", false)
                backdropActive = visible
                val activity = launcherRef?.get() ?: return@safeRun
                activity.runOnUiThread {
                    applyBackdropState(activity, visible)
                }
            }
        }
    }

    private fun applyBackdropState(activity: Activity, visible: Boolean) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        var backdrop = decor.findViewWithTag<FrameLayout>(BACKDROP_TAG)

        if (!visible || !HookGuard.isActive()) {
            backdrop?.animate()
                ?.alpha(0f)
                ?.setDuration(180L)
                ?.withEndAction { backdrop.visibility = View.GONE }
                ?.start()
            return
        }

        if (backdrop == null) {
            backdrop = createBackdropView(activity)
            decor.addView(
                backdrop,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        // Refresh ColorOS native blurred wallpaper bitmap
        val img = backdrop.getChildAt(0) as? ImageView
        fetchColorOsBlurWallpaper()?.let { bmp ->
            img?.setImageBitmap(bmp)
        }

        backdrop.bringToFront()
        if (backdrop.visibility != View.VISIBLE || backdrop.alpha < 0.99f) {
            backdrop.visibility = View.VISIBLE
            backdrop.animate().alpha(1f).setDuration(220L).start()
        }
    }

    private fun createBackdropView(context: Context): FrameLayout {
        val container = FrameLayout(context).apply {
            tag = BACKDROP_TAG
            alpha = 0f
            // Absorb stray taps on the empty Stage canvas so hidden desktop icons underneath can never be clicked.
            isClickable = true
            isFocusable = false
            setBackgroundColor(Color.argb(190, 22, 20, 32))
        }

        val wallpaperView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRenderEffect(RenderEffect.createBlurEffect(68f, 68f, Shader.TileMode.CLAMP))
            }
        }
        container.addView(
            wallpaperView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        // Subtle dark atmospheric veil matching iPadOS Stage Manager stage tone
        val scrim = View(context).apply {
            setBackgroundColor(Color.argb(54, 14, 12, 24))
        }
        container.addView(
            scrim,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        return container
    }

    private fun fetchColorOsBlurWallpaper(): Bitmap? {
        return runCatching {
            val fwmCls = Class.forName("com.oplus.flexiblewindow.FlexibleWindowManager")
            val fwm = XposedHelpers.callStaticMethod(fwmCls, "getInstance")
            XposedHelpers.callMethod(fwm, "getCurrentBlurWallpaper") as? Bitmap
        }.onFailure {
            HookGuard.warn("LauncherBackdropHook: getCurrentBlurWallpaper failed", it)
        }.getOrNull()
    }
}
