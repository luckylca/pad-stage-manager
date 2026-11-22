package dev.stagelab.nativehook.ui.dock

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.stagelab.nativehook.xposed.HookGuard

/**
 * Floating frosted-glass App Search & App Library sheet launched from the Dock.
 * - Real-time search across all installed launchable apps (by label or package name).
 * - Tap app -> S1 (launch new widescreen Stage or switch to owning Stage).
 * - Long-press app -> S3 (merge into current Active Stage / S4 overflow).
 */
class AppSearchOverlayController(
    private val onAppSelectedLaunch: (packageName: String) -> Unit,
    private val onAppSelectedMerge: (packageName: String) -> Unit,
) {
    private data class LaunchableApp(
        val packageName: String,
        val label: String,
        val icon: Drawable,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    // Gentle spring-overshoot curve so opening from the Dock feels unmistakably silky & tactile
    private val openInterpolator = PathInterpolator(0.22f, 1.08f, 0.34f, 1.0f)
    private val scrimInterpolator = PathInterpolator(0.25f, 0.1f, 0.25f, 1.0f)
    private val closeInterpolator = PathInterpolator(0.36f, 0.0f, 0.66f, 1.0f)
    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var panelView: View? = null
    private var gridContainer: GridLayout? = null
    private var isDismissing = false
    private var allApps: List<LaunchableApp> = emptyList()

    val isShowing: Boolean get() = rootView != null

    fun toggle(context: Context) {
        if (isShowing) {
            dismiss()
        } else {
            show(context)
        }
    }

    fun show(context: Context) {
        mainHandler.post {
            if (isShowing || !HookGuard.isActive()) return@post
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
            windowManager = wm

            allApps = loadLaunchableApps(context)

            val scrim = FrameLayout(context).apply {
                setBackgroundColor(Color.argb(115, 16, 14, 24))
                setOnClickListener { dismiss() }
                isFocusableInTouchMode = true
                setOnKeyListener { _, keyCode, event ->
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                        dismiss()
                        true
                    } else {
                        false
                    }
                }
            }

            val panel = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 22), dp(context, 20), dp(context, 22), dp(context, 20))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(context, 28).toFloat()
                    setColor(Color.argb(242, 248, 244, 255))
                    setStroke(dp(context, 1), Color.argb(160, 255, 255, 255))
                }
                elevation = dp(context, 16).toFloat()
                // Absorb clicks inside panel so scrim doesn't dismiss
                setOnClickListener { }
            }

            // Header row: Search input + Close pill
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val searchInput = EditText(context).apply {
                hint = "搜索应用（点击打开 · 长按加入当前 Stage）"
                setHintTextColor(Color.argb(145, 73, 69, 79))
                setTextColor(Color.rgb(28, 27, 31))
                textSize = 15f
                isSingleLine = true
                setPadding(dp(context, 18), dp(context, 12), dp(context, 18), dp(context, 12))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(context, 999).toFloat()
                    setColor(Color.rgb(231, 224, 236))
                }
            }

            val closeBtn = TextView(context).apply {
                text = "关闭"
                setTextColor(Color.rgb(103, 80, 164))
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(context, 999).toFloat()
                    setColor(Color.rgb(232, 222, 248))
                }
                setOnClickListener { dismiss() }
            }

            header.addView(
                searchInput,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            header.addView(
                closeBtn,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { leftMargin = dp(context, 12) },
            )
            panel.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

            // Scrollable 5-column app grid
            val grid = GridLayout(context).apply {
                columnCount = 5
                setPadding(0, dp(context, 16), 0, dp(context, 8))
            }
            gridContainer = grid

            val scroll = ScrollView(context).apply {
                addView(grid, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            }
            panel.addView(
                scroll,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 360)).apply {
                    topMargin = dp(context, 8)
                },
            )

            searchInput.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    populateGrid(context, s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })

            populateGrid(context, "")

            scrim.addView(
                panel,
                FrameLayout.LayoutParams(dp(context, 560), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
            )

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    fitInsetsTypes = 0
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
                title = "StageLabAppSearch"
            }

            // Prepare smooth iPadOS spring-bloom open animation rising from the Dock
            scrim.alpha = 0f
            panel.alpha = 0f
            panel.scaleX = 0.72f
            panel.scaleY = 0.72f
            panel.translationY = dp(context, 120).toFloat()

            runCatching {
                wm.addView(scrim, lp)
                rootView = scrim
                panelView = panel
                isDismissing = false

                // Wait for layout + 58-icon grid upload BEFORE frame 0 of the animation starts!
                scrim.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        scrim.viewTreeObserver.removeOnPreDrawListener(this)
                        if (rootView !== scrim || isDismissing) return true
                        scrim.animate()
                            .alpha(1f)
                            .setDuration(260L)
                            .setInterpolator(scrimInterpolator)
                            .start()
                        panel.animate()
                            .withLayer()
                            .alpha(1f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .translationY(0f)
                            .setDuration(380L)
                            .setInterpolator(openInterpolator)
                            .start()
                        return true
                    }
                })

                HookGuard.info("APP_SEARCH opened (${allApps.size} apps)")
            }.onFailure {
                HookGuard.warn("APP_SEARCH failed to open", it)
            }
        }
    }

    fun dismiss() {
        mainHandler.post {
            val wm = windowManager
            val v = rootView
            val p = panelView
            if (wm == null || v == null || isDismissing) return@post
            isDismissing = true

            v.animate().cancel()
            p?.animate()?.cancel()

            p?.animate()
                ?.withLayer()
                ?.alpha(0f)
                ?.scaleX(0.78f)
                ?.scaleY(0.78f)
                ?.translationY(dp(v.context, 90).toFloat())
                ?.setDuration(240L)
                ?.setInterpolator(closeInterpolator)
                ?.start()

            v.animate()
                .alpha(0f)
                .setDuration(240L)
                .setInterpolator(closeInterpolator)
                .withEndAction {
                    runCatching { wm.removeViewImmediate(v) }
                    if (rootView === v) {
                        rootView = null
                        panelView = null
                        gridContainer = null
                        isDismissing = false
                    }
                }
                .start()
        }
    }

    private fun populateGrid(context: Context, query: String) {
        val grid = gridContainer ?: return
        grid.removeAllViews()
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) {
            allApps
        } else {
            allApps.filter { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
        }

        val itemWidth = dp(context, 98)
        for (app in filtered.take(60)) {
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(context, 6), dp(context, 10), dp(context, 6), dp(context, 10))
                setOnClickListener {
                    dismiss()
                    onAppSelectedLaunch(app.packageName)
                }
                setOnLongClickListener {
                    dismiss()
                    onAppSelectedMerge(app.packageName)
                    true
                }
            }

            val iconView = ImageView(context).apply {
                setImageDrawable(app.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                val r = dp(context, 14).toFloat()
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, r)
                    }
                }
                clipToOutline = true
            }
            val labelView = TextView(context).apply {
                text = app.label
                setTextColor(Color.rgb(28, 27, 31))
                textSize = 12f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
            }

            cell.addView(iconView, LinearLayout.LayoutParams(dp(context, 50), dp(context, 50)))
            cell.addView(
                labelView,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(context, 6)
                },
            )
            grid.addView(
                cell,
                GridLayout.LayoutParams().apply {
                    width = itemWidth
                    height = GridLayout.LayoutParams.WRAP_CONTENT
                },
            )
        }
    }

    private fun loadLaunchableApps(context: Context): List<LaunchableApp> {
        return runCatching {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(intent, 0)
            val seen = HashSet<String>()
            resolved.mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                if (!seen.add(pkg) || pkg == "dev.stagelab.nativehook") return@mapNotNull null
                val label = ri.loadLabel(pm).toString().ifBlank { pkg }
                val icon = ri.loadIcon(pm)
                LaunchableApp(pkg, label, icon)
            }.sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
    }

    private fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics,
        ).toInt()
}
