package dev.stagelab.android.windows

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Full-screen translucent overlay rendered above all real freeform tasks for:
 *  - App Exposé ("Show All Windows" for a single app across all groups, P6)
 *  - Stage Overview (all workspaces and their windows, P7)
 *
 * Rendered via TYPE_APPLICATION_OVERLAY so active freeform tasks never cover the overview UI.
 */
class StageExposeOverlay(private val context: Context) {

    data class WindowCard(
        val windowId: String,
        val taskId: Int,
        val title: String,
        val groupTitle: String,
        val inActiveGroup: Boolean,
        val focused: Boolean,
        val onSelect: () -> Unit,
        val onBringToCurrentGroup: (() -> Unit)? = null,
        val onClose: () -> Unit
    )

    data class GroupCard(
        val groupId: String,
        val title: String,
        val active: Boolean,
        val windowSummary: List<String>,
        val onSelect: () -> Unit
    )

    private val wm: WindowManager = context.getSystemService(WindowManager::class.java)
    private var rootView: View? = null
    private val density: Float get() = context.resources.displayMetrics.density
    private fun px(dp: Float): Int = (dp * density).roundToInt()

    val isShowing: Boolean get() = rootView != null

    /** Shows App Exposé for a single application: all its open windows + optional New Window button. */
    @SuppressLint("SetTextI18n")
    fun showAppExpose(
        appLabel: String,
        windows: List<WindowCard>,
        canCreateNewWindow: Boolean,
        onNewWindow: () -> Unit
    ) {
        dismiss()
        val root = buildBackdrop { dismiss() }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(32f), px(48f), px(32f), px(40f))
        }

        // Header row
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = TextView(context).apply {
            text = "$appLabel · 所有窗口 (${windows.size})"
            setTextColor(Color.rgb(240, 244, 252))
            textSize = 20f
            paint.isFakeBoldText = true
        }
        header.addView(titleView)

        if (canCreateNewWindow) {
            val newBtn = pillButton("＋ 新建窗口", accent = true) {
                dismiss()
                onNewWindow()
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = px(18f) }
            header.addView(newBtn, lp)
        }

        val closeBtn = pillButton("完成", accent = false) { dismiss() }
        val closeLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = px(12f) }
        header.addView(closeBtn, closeLp)

        content.addView(header)

        // Horizontal card strip
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, px(28f), 0, px(16f))
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        if (windows.isEmpty()) {
            val empty = TextView(context).apply {
                text = "当前没有打开的窗口"
                setTextColor(Color.argb(180, 190, 200, 220))
                textSize = 15f
                setPadding(px(24f), px(36f), px(24f), px(36f))
            }
            row.addView(empty)
        } else {
            windows.forEach { card ->
                row.addView(buildWindowCard(card))
            }
        }
        scroll.addView(row)
        content.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            content,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        attach(root)
    }

    /** Shows Stage Overview: all workspaces and their window summaries (P7). */
    @SuppressLint("SetTextI18n")
    fun showStageOverview(
        groups: List<GroupCard>,
        onNewGroup: () -> Unit
    ) {
        dismiss()
        val root = buildBackdrop { dismiss() }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(32f), px(48f), px(32f), px(40f))
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = "所有工作台 (${groups.size})"
            setTextColor(Color.rgb(240, 244, 252))
            textSize = 20f
            paint.isFakeBoldText = true
        })
        header.addView(
            pillButton("＋ 新建工作台", accent = true) {
                dismiss()
                onNewGroup()
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = px(18f) }
        )
        header.addView(
            pillButton("完成", accent = false) { dismiss() },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = px(12f) }
        )
        content.addView(header)

        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, px(28f), 0, px(16f))
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        groups.forEach { group ->
            row.addView(buildGroupCard(group))
        }
        scroll.addView(row)
        content.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            content,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        attach(root)
    }

    @SuppressLint("SetTextI18n")
    private fun buildWindowCard(card: WindowCard): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(240, 28, 34, 46))
                cornerRadius = px(22f).toFloat()
                setStroke(
                    px(if (card.focused) 2f else 1f),
                    if (card.focused) Color.rgb(138, 168, 255) else Color.argb(46, 255, 255, 255)
                )
            }
            setPadding(px(18f), px(16f), px(18f), px(16f))
            setOnClickListener {
                dismiss()
                card.onSelect()
            }
        }
        val lp = LinearLayout.LayoutParams(px(248f), px(188f)).apply {
            marginEnd = px(16f)
        }
        box.layoutParams = lp

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = card.title
            setTextColor(Color.rgb(240, 244, 252))
            textSize = 15f
            paint.isFakeBoldText = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        topRow.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val closeDot = TextView(context).apply {
            text = "×"
            setTextColor(Color.rgb(255, 130, 124))
            textSize = 16f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.argb(45, 255, 100, 96))
                cornerRadius = px(14f).toFloat()
            }
            setOnClickListener {
                dismiss()
                card.onClose()
            }
        }
        topRow.addView(closeDot, LinearLayout.LayoutParams(px(28f), px(28f)))
        box.addView(topRow)

        val meta = TextView(context).apply {
            text = "${card.groupTitle} · Task #${card.taskId}"
            setTextColor(Color.argb(185, 172, 186, 210))
            textSize = 12f
            setPadding(0, px(8f), 0, px(12f))
        }
        box.addView(meta)

        val status = TextView(context).apply {
            text = when {
                card.focused -> "当前焦点窗口"
                card.inActiveGroup -> "位于当前工作台"
                else -> "位于后台工作台 · 点击切换"
            }
            setTextColor(if (card.inActiveGroup) Color.rgb(148, 178, 255) else Color.argb(175, 190, 200, 220))
            textSize = 11f
        }
        box.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        if (card.onBringToCurrentGroup != null) {
            val bringBtn = pillButton("拉入当前工作台", accent = true) {
                dismiss()
                card.onBringToCurrentGroup.invoke()
            }
            box.addView(bringBtn)
        }
        return box
    }

    @SuppressLint("SetTextI18n")
    private fun buildGroupCard(group: GroupCard): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(240, 28, 34, 46))
                cornerRadius = px(22f).toFloat()
                setStroke(
                    px(if (group.active) 2f else 1f),
                    if (group.active) Color.rgb(138, 168, 255) else Color.argb(46, 255, 255, 255)
                )
            }
            setPadding(px(18f), px(16f), px(18f), px(16f))
            setOnClickListener {
                dismiss()
                group.onSelect()
            }
        }
        box.layoutParams = LinearLayout.LayoutParams(px(236f), px(178f)).apply {
            marginEnd = px(16f)
        }

        box.addView(TextView(context).apply {
            text = group.title + if (group.active) " (当前)" else ""
            setTextColor(Color.rgb(240, 244, 252))
            textSize = 15f
            paint.isFakeBoldText = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })

        val summaryText = if (group.windowSummary.isEmpty()) {
            "空工作台"
        } else {
            group.windowSummary.joinToString("\n• ", prefix = "• ")
        }
        box.addView(TextView(context).apply {
            text = summaryText
            setTextColor(Color.argb(195, 188, 198, 218))
            textSize = 12f
            setPadding(0, px(12f), 0, 0)
            maxLines = 5
            ellipsize = TextUtils.TruncateAt.END
        })
        return box
    }

    private fun pillButton(label: String, accent: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 13f
            setTextColor(if (accent) Color.rgb(16, 22, 34) else Color.rgb(234, 240, 250))
            paint.isFakeBoldText = accent
            setPadding(px(14f), px(7f), px(14f), px(7f))
            background = GradientDrawable().apply {
                setColor(if (accent) Color.rgb(138, 168, 255) else Color.argb(38, 255, 255, 255))
                cornerRadius = px(18f).toFloat()
            }
            setOnClickListener { onClick() }
        }

    private fun buildBackdrop(onOutsideTap: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(206, 10, 14, 22))
            setOnClickListener { onOutsideTap() }
        }

    private fun attach(view: View) {
        val metrics = context.resources.displayMetrics
        val params = WindowManager.LayoutParams(
            metrics.widthPixels,
            metrics.heightPixels,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        runCatching { wm.addView(view, params) }
            .onSuccess { rootView = view }
            .onFailure { Log.w(TAG, "expose overlay add failed: ${it.message}") }
    }

    fun dismiss() {
        rootView?.let { runCatching { wm.removeView(it) } }
        rootView = null
    }

    private companion object {
        const val TAG = "StageExpose"
    }
}
