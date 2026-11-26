package hu.fokusz

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import kotlin.math.roundToInt

internal data class SocialUnlockDraft(
    var minutes: String = "",
    var reason: SocialUnlockReason? = null,
    var emergency: Boolean = false,
    var approved: Boolean = false,
    var joke: String? = null,
) {
    fun wholeMinutes(): Int? = minutes.takeIf { text -> text.all { it in '0'..'9' } }?.toIntOrNull()
}

private const val UNLOCK_BG = 0xff0b1120.toInt()
private const val UNLOCK_CARD = 0xff18233b.toInt()
private const val UNLOCK_WHITE = 0xfff6f8ff.toInt()
private const val UNLOCK_MUTED = 0xffb4c3d8.toInt()
private const val UNLOCK_MINT = 0xff65e2c8.toInt()
private const val UNLOCK_SELECTED = 0xff193933.toInt()
private const val UNLOCK_OUTLINE = 0xff566782.toInt()
private const val HOLD_MILLIS = 800L

private fun View.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

private fun View.rounded(color: Int, border: Int = color, radius: Int = 16) =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), border)
    }

private fun View.ripple(base: Int, outline: Int = base) = RippleDrawable(
    ColorStateList.valueOf(0x24ffffff), rounded(base, outline), rounded(-1)
)

private class BlockingUnlockWindow(context: Context, private val onBack: () -> Unit) : FrameLayout(context) {
    private var unregisterBack: (() -> Unit)? = null

    init {
        setBackgroundColor(UNLOCK_BG)
        isClickable = true
        isFocusableInTouchMode = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT >= 33) {
            findOnBackInvokedDispatcher()?.let { dispatcher ->
                val callback = OnBackInvokedCallback { onBack() }
                dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
                unregisterBack = { dispatcher.unregisterOnBackInvokedCallback(callback) }
            }
        }
    }

    override fun onDetachedFromWindow() {
        unregisterBack?.invoke()
        unregisterBack = null
        super.onDetachedFromWindow()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBack()
        return true
    }
}

internal class SocialUnlockOverlay(
    private val context: Context,
    private val app: SocialUnlockApp,
    private val draft: SocialUnlockDraft,
    private val expiredMinutes: Int?,
    private val onExit: () -> Unit,
    private val onRetry: () -> Unit,
    private val onConfirm: (Int, SocialUnlockReason, Boolean, Boolean) -> Boolean,
    private val onAlternative: () -> Unit,
    private val onFinished: () -> Unit,
) {
    private val root = BlockingUnlockWindow(context) { if (!success) onExit() }
    val view: View get() = root
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(12), dp(20), dp(16))
    }
    private var success = false
    private var available = 0
    private var emergencyAvailable = 0
    private var emergencyToggle: TextView? = null
    private var approvedAvailable = 0
    private var approvedToggle: TextView? = null
    private var hint: TextView? = null
    private var hold: UnlockHoldButton? = null
    private val reasonCards = mutableMapOf<SocialUnlockReason, Pair<TextView, TextView>>()

    init {
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                content.setPadding(root.dp(20) + bars.left, root.dp(12) + bars.top, root.dp(20) + bars.right,
                    root.dp(16) + bars.bottom)
            }
            insets
        }
        root.isFocusableInTouchMode = true
        root.requestFocus()
        val scrollBody = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        content.addView(ScrollView(context).apply {
            isFillViewport = false
            addView(scrollBody, ViewGroup.LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        scrollBody.addView(action("‹", "Vissza", click = onExit).apply {
            textSize = 30f
            background = null
            minWidth = dp(48)
        }, LinearLayout.LayoutParams(root.dp(48), root.dp(44)))
        app.icon?.let { icon ->
            scrollBody.addView(ImageView(context).apply {
                setImageDrawable(icon)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(root.dp(48), root.dp(48)))
        }
        val title = if (expiredMinutes == null) "${app.name} feloldása"
            else "Lejárt a $expiredMinutes perc ${app.name}-időd."
        scrollBody.addView(text(title, 24f, bold = true), vertical(10))
        if (SocialUnlockStore.jokes(context)) {
            val joke = draft.joke ?: SocialUnlockJokes.next(app.packageName, expiredMinutes).also { draft.joke = it }
            scrollBody.addView(text(joke, 16f).apply { setTextColor(UNLOCK_MUTED) }, vertical(8))
        }
        if (expiredMinutes != null) {
            content.addView(action("Kilépek", click = onExit), vertical(8))
            content.addView(action("Új időt kérek", primary = true, click = onRetry), vertical(8))
        } else {
            addForm(scrollBody)
        }
    }

    fun enter() {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        content.alpha = 0f
        content.translationY = root.dp(16).toFloat()
        content.animate().alpha(1f).translationY(0f).setDuration(220).start()
    }

    fun updateAvailability(minutes: Int, emergencyMinutes: Int, approvedMinutes: Int = 0) {
        available = minutes
        emergencyAvailable = emergencyMinutes
        approvedAvailable = approvedMinutes
        refreshButton()
    }

    private fun refreshButton() {
        if (success) return
        val amount = draft.wholeMinutes()
        val limit = when {
            draft.emergency -> emergencyAvailable
            draft.approved -> approvedAvailable
            else -> available
        }
        val valid = amount != null && amount in 1..limit && draft.reason != null
        hint?.text = if (amount != null && amount > limit) "Most $limit percet tudsz felhasználni."
            else when {
                draft.emergency -> "Vészkeret: $limit perc"
                draft.approved -> "Jóváhagyott pluszidő: $limit perc"
                else -> "Elérhető: $limit perc"
            }
        emergencyToggle?.apply {
            visibility = if (emergencyAvailable > 0 || draft.emergency) View.VISIBLE else View.GONE
            text = if (draft.emergency) "Vissza a rendes időkerethez" else "Napi vészkeret · legfeljebb 3 perc"
        }
        approvedToggle?.apply {
            visibility = if (approvedAvailable > 0 || draft.approved) View.VISIBLE else View.GONE
            text = if (draft.approved) "Vissza a rendes időkerethez" else "Jóváhagyott pluszidő használata"
        }
        hold?.update(if (valid) "Tartsd nyomva · ${app.name} · $amount perc" else "Tartsd nyomva a feloldáshoz", valid)
    }

    private fun text(value: String, size: Float, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(UNLOCK_WHITE)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun vertical(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = root.dp(top) }

    private fun action(label: String, description: String = label, primary: Boolean = false, click: () -> Unit) =
        text(label, 16f, true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setTextColor(if (primary) UNLOCK_BG else UNLOCK_WHITE)
            background = if (primary) ripple(UNLOCK_MINT) else ripple(UNLOCK_CARD, UNLOCK_OUTLINE)
            contentDescription = description
            setOnClickListener { if (!success) click() }
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> shrink(v, .97f)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> shrink(v, 1f)
                }
                false
            }
        }

    private fun shrink(view: View, scale: Float) {
        if (ValueAnimator.areAnimatorsEnabled()) view.animate().scaleX(scale).scaleY(scale).setDuration(90).start()
    }

    private fun addForm(body: LinearLayout) {
        val durationQuestion = text("Hány percre szeretnéd megnyitni?", 17f, true)
        body.addView(durationQuestion, vertical(18))
        val field = EditText(context).apply {
            id = View.generateViewId()
            setText(draft.minutes)
            setTextColor(UNLOCK_WHITE)
            textSize = 36f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(4), dp(13), dp(4))
            setSingleLine(true)
            background = rounded(UNLOCK_CARD, UNLOCK_OUTLINE)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            imeOptions = EditorInfo.IME_ACTION_DONE
            filters = arrayOf(InputFilter.LengthFilter(9))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId != EditorInfo.IME_ACTION_DONE) false else {
                    context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
                    clearFocus()
                    root.requestFocus()
                    true
                }
            }
        }
        durationQuestion.labelFor = field.id
        body.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(field, LinearLayout.LayoutParams(0, dp(62), 1f))
            addView(text("perc", 16f).apply { setPadding(dp(12), 0, 0, 0) })
        }, vertical(6))

        val quick = LinearLayout(context)
        for ((index, minutes) in listOf(1, 3, 5, 10).withIndex()) {
            quick.addView(action("$minutes perc") {
                field.setText(minutes.toString())
                field.setSelection(field.text.length)
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index != 0) marginStart = root.dp(8) })
        }
        body.addView(quick, vertical(8))
        hint = text("", 15f).apply { setTextColor(UNLOCK_MUTED) }
        body.addView(hint, vertical(8))
        body.addView(text("Miért szeretnéd megnyitni?", 17f, true), vertical(16))

        val config = context.resources.configuration
        val columns = if (config.screenWidthDp < 350 || config.fontScale > 1.20f) 1 else 2
        for (group in SocialUnlockReason.entries.chunked(columns)) {
            val row = LinearLayout(context)
            for ((index, reason) in group.withIndex()) {
                val checkmark = text("", 15f, true).apply {
                    gravity = Gravity.END
                    setTextColor(UNLOCK_MINT)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                val card = action("${reason.icon}  ${reason.label}") {
                    draft.reason = reason
                    refreshReasons(animate = true)
                    refreshButton()
                }.apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(10), dp(24), dp(10))
                    minHeight = dp(62)
                }
                row.addView(FrameLayout(context).apply {
                    addView(card, FrameLayout.LayoutParams(-1, -2))
                    addView(checkmark, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                        topMargin = dp(6)
                        marginEnd = dp(7)
                    })
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index != 0) marginStart = root.dp(8) })
                reasonCards[reason] = card to checkmark
            }
            body.addView(row, vertical(8))
        }

        emergencyToggle = action("") {
            draft.emergency = !draft.emergency
            draft.approved = false
            refreshButton()
        }.apply { contentDescription = null }
        body.addView(emergencyToggle, vertical(12))
        approvedToggle = action("") {
            draft.approved = !draft.approved
            draft.emergency = false
            refreshButton()
        }.apply { contentDescription = null }
        body.addView(approvedToggle, vertical(8))
        body.addView(action("Inkább csinálok mást", click = onAlternative), vertical(8))
        hold = UnlockHoldButton(context, ::confirm)
        content.addView(hold, vertical(12))
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                draft.minutes = s?.toString().orEmpty()
                refreshButton()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        refreshReasons(animate = false)
        refreshButton()
    }

    private fun refreshReasons(animate: Boolean) {
        for ((reason, pair) in reasonCards) {
            val (card, mark) = pair
            val selected = reason == draft.reason
            val changed = card.isSelected != selected
            card.isSelected = selected
            card.background = if (selected) card.ripple(UNLOCK_SELECTED, UNLOCK_MINT) else card.ripple(UNLOCK_CARD)
            mark.text = if (selected) "✓" else ""
            if (selected && changed && animate && ValueAnimator.areAnimatorsEnabled()) {
                mark.alpha = 0f
                mark.animate().alpha(1f).setDuration(150).start()
                ValueAnimator.ofObject(ArgbEvaluator(), UNLOCK_CARD, UNLOCK_SELECTED).apply {
                    duration = 150L
                    addUpdateListener { frame ->
                        if (card.isSelected) card.background = card.ripple(frame.animatedValue as Int, UNLOCK_MINT)
                    }
                }.start()
            } else mark.alpha = 1f
        }
    }

    private fun confirm() {
        if (success) return
        val reason = draft.reason ?: return
        val limit = when {
            draft.emergency -> emergencyAvailable
            draft.approved -> approvedAvailable
            else -> available
        }
        val amount = draft.wholeMinutes()?.takeIf { it in 1..limit } ?: return
        if (!onConfirm(amount, reason, draft.emergency, draft.approved)) return
        success = true
        hold?.update("✓ Feloldva", false)
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(root.windowToken, 0)
        root.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.LONG_PRESS)
        if (ValueAnimator.areAnimatorsEnabled()) {
            content.animate().alpha(0f).setDuration(180).withEndAction(onFinished).start()
        } else onFinished()
    }
}

private class UnlockHoldButton(context: Context, private val onConfirm: () -> Unit) : FrameLayout(context) {
    private val label = TextView(context).apply {
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(6), dp(10), dp(6))
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff42b49e.toInt() }
    private var progress = 0f
    private var holding = false
    private var track: ValueAnimator? = null
    private var shade: ValueAnimator? = null
    private var currentColor = UNLOCK_CARD
    private val done = Runnable {
        if (holding && isEnabled) {
            stopHold()
            onConfirm()
        }
    }

    init {
        minimumHeight = dp(56)
        addView(label, LayoutParams(-1, -2, Gravity.CENTER))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        label.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = true
        isClickable = true
        clipToOutline = true
        setWillNotDraw(false)
        background = rounded(currentColor)
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Button"
                info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
                if (isEnabled) info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACCESS_CONFIRM, "Feloldás jóváhagyása"))
            }

            override fun performAccessibilityAction(host: View, action: Int, args: android.os.Bundle?): Boolean {
                if (action != ACCESS_CONFIRM || !isEnabled) return super.performAccessibilityAction(host, action, args)
                stopHold()
                onConfirm()
                return true
            }
        }
    }

    fun update(title: String, enabled: Boolean) {
        if (label.text.toString() != title) {
            label.text = title
            contentDescription = title
        }
        label.setTextColor(if (enabled) UNLOCK_BG else UNLOCK_MUTED)
        if (isEnabled == enabled) return
        isEnabled = enabled
        if (!enabled) stopHold()
        val color = if (enabled) UNLOCK_MINT else UNLOCK_CARD
        shade?.cancel()
        if (ValueAnimator.areAnimatorsEnabled()) {
            shade = ValueAnimator.ofObject(ArgbEvaluator(), currentColor, color).apply {
                duration = 180L
                addUpdateListener { frame ->
                    currentColor = frame.animatedValue as Int
                    background = rounded(currentColor)
                }
                start()
            }
        } else {
            currentColor = color
            background = rounded(color)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (holding) canvas.drawRect(0f, 0f, width * progress, height.toFloat(), paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                holding = true
                postDelayed(done, HOLD_MILLIS)
                if (ValueAnimator.areAnimatorsEnabled()) {
                    track = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = HOLD_MILLIS
                        interpolator = LinearInterpolator()
                        addUpdateListener { frame -> progress = frame.animatedValue as Float; invalidate() }
                        start()
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> if (event.x !in 0f..width.toFloat() || event.y !in 0f..height.toFloat()) stopHold()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> stopHold()
        }
        return true
    }

    override fun onDetachedFromWindow() {
        stopHold()
        shade?.cancel()
        super.onDetachedFromWindow()
    }

    private fun stopHold() {
        removeCallbacks(done)
        track?.cancel()
        track = null
        holding = false
        progress = 0f
        invalidate()
    }

    companion object { private const val ACCESS_CONFIRM = 0x00f04001 }
}
