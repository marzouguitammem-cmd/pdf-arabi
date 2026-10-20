package com.tammem.pdfarabi.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.tammem.pdfarabi.R
import kotlin.math.min

fun Context.dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
fun View.dp(v: Float): Int = context.dp(v)

fun Context.toast(msg: String, long: Boolean = false) =
    Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

fun Context.attrColor(attr: Int): Int = MaterialColors.getColor(this, attr, Color.GRAY)

object Palette {
    val text = intArrayOf(
        0xFF000000.toInt(), 0xFF1A237E.toInt(), 0xFF1565C0.toInt(), 0xFFD32F2F.toInt(),
        0xFF2E7D32.toInt(), 0xFF6A1B9A.toInt(), 0xFFEF6C00.toInt(), 0xFF5D4037.toInt(),
        0xFF616161.toInt(), 0xFFFFFFFF.toInt()
    )
    val pen = intArrayOf(
        0xFFD32F2F.toInt(), 0xFF1565C0.toInt(), 0xFF000000.toInt(), 0xFF2E7D32.toInt(),
        0xFF6A1B9A.toInt(), 0xFFEF6C00.toInt(), 0xFFE91E63.toInt(), 0xFF00897B.toInt()
    )
    val highlight = intArrayOf(
        0x66FFEB3B, 0x5576FF03, 0x5500E5FF, 0x55FF4081, 0x55FFAB40, 0x55B388FF
    )
}

/** A round colour button. */
class SwatchView(context: Context, val color: Int) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@SwatchView.color or 0xFF000000.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2.5f).toFloat()
        this.color = context.attrColor(androidx.appcompat.R.attr.colorPrimary)
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1f).toFloat()
        this.color = 0x33000000
    }

    init {
        val s = context.dp(40f)
        layoutParams = LinearLayout.LayoutParams(s, s)
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.color)
    }

    override fun setSelected(selected: Boolean) {
        super.setSelected(selected)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - dp(7f)
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, edge)
        if (isSelected) canvas.drawCircle(cx, cy, r + dp(4f), ring)
    }
}

fun LinearLayout.addSwatches(colors: IntArray, selected: Int, onPick: (Int) -> Unit): List<SwatchView> {
    val views = colors.map { c -> SwatchView(context, c) }
    views.forEach { v ->
        v.isSelected = (v.color and 0x00FFFFFF) == (selected and 0x00FFFFFF)
        v.setOnClickListener {
            views.forEach { it.isSelected = it === v }
            onPick(v.color)
        }
        addView(v)
    }
    return views
}

/** Icon + label button used in the editor's tool bar. */
fun makeToolButton(ctx: Context, @DrawableRes icon: Int, label: String, onClick: () -> Unit): LinearLayout {
    val box = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        minimumWidth = ctx.dp(64f)
        setPadding(ctx.dp(8f), ctx.dp(6f), ctx.dp(8f), ctx.dp(6f))
        isClickable = true
        isFocusable = true
        val tv = TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        foreground = ContextCompat.getDrawable(ctx, tv.resourceId)
        setOnClickListener { onClick() }
        contentDescription = label
    }
    val iv = ImageView(ctx).apply {
        setImageResource(icon)
        layoutParams = LinearLayout.LayoutParams(ctx.dp(24f), ctx.dp(24f))
        imageTintList = ColorStateList.valueOf(ctx.attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        tag = "icon"
    }
    val tv = TextView(ctx).apply {
        text = label
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall)
        typeface = androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.tajawal)
        setTextColor(ctx.attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        gravity = Gravity.CENTER
        maxLines = 1
        setPadding(0, ctx.dp(3f), 0, 0)
        tag = "label"
    }
    box.addView(iv)
    box.addView(tv)
    return box
}

fun LinearLayout.setToolSelected(selected: Boolean) {
    val ctx = context
    background = if (selected) ContextCompat.getDrawable(ctx, R.drawable.bg_tool_selected) else null
    val color = if (selected) ctx.attrColor(com.google.android.material.R.attr.colorOnPrimaryContainer)
    else ctx.attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
    (findViewWithTag<ImageView>("icon"))?.imageTintList = ColorStateList.valueOf(color)
    (findViewWithTag<TextView>("label"))?.setTextColor(color)
}

/** Small icon button for the options strip. */
fun makeIconChip(ctx: Context, @DrawableRes icon: Int, desc: String, onClick: () -> Unit): ImageView =
    ImageView(ctx).apply {
        setImageResource(icon)
        val s = ctx.dp(42f)
        layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = ctx.dp(2f) }
        setPadding(ctx.dp(9f), ctx.dp(9f), ctx.dp(9f), ctx.dp(9f))
        contentDescription = desc
        imageTintList = ColorStateList.valueOf(ctx.attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        isClickable = true
        isFocusable = true
        val tv = TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        foreground = ContextCompat.getDrawable(ctx, tv.resourceId)
        setOnClickListener { onClick() }
    }

fun ImageView.setChipSelected(selected: Boolean) {
    background = if (selected) ContextCompat.getDrawable(context, R.drawable.bg_tool_selected) else null
    imageTintList = ColorStateList.valueOf(
        context.attrColor(
            if (selected) com.google.android.material.R.attr.colorOnPrimaryContainer
            else com.google.android.material.R.attr.colorOnSurfaceVariant
        )
    )
}

fun makeTextChip(ctx: Context, text: String, onClick: () -> Unit): TextView =
    TextView(ctx).apply {
        this.text = text
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
        typeface = androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.tajawal)
        setTextColor(ctx.attrColor(com.google.android.material.R.attr.colorOnSurface))
        gravity = Gravity.CENTER
        minHeight = ctx.dp(38f)
        minWidth = ctx.dp(42f)
        setPadding(ctx.dp(12f), 0, ctx.dp(12f), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ctx.dp(38f)).apply { marginEnd = ctx.dp(4f) }
        background = ContextCompat.getDrawable(ctx, R.drawable.bg_round_surface)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

fun TextView.setTextChipSelected(selected: Boolean) {
    background = ContextCompat.getDrawable(context, if (selected) R.drawable.bg_tool_selected else R.drawable.bg_round_surface)
    setTextColor(
        context.attrColor(
            if (selected) com.google.android.material.R.attr.colorOnPrimaryContainer
            else com.google.android.material.R.attr.colorOnSurface
        )
    )
}

fun spacer(ctx: Context, widthDp: Float = 10f): View = View(ctx).apply {
    layoutParams = LinearLayout.LayoutParams(ctx.dp(widthDp), 1)
}

fun divider(ctx: Context): View = View(ctx).apply {
    layoutParams = LinearLayout.LayoutParams(ctx.dp(1f), ctx.dp(26f)).apply {
        marginStart = ctx.dp(6f); marginEnd = ctx.dp(6f)
    }
    setBackgroundColor(ctx.attrColor(com.google.android.material.R.attr.colorOutlineVariant))
}

fun Activity.hideKeyboard(v: View? = currentFocus) {
    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.hideSoftInputFromWindow((v ?: window.decorView).windowToken, 0)
}

fun showKeyboard(v: View) {
    v.requestFocus()
    v.postDelayed({
        val imm = v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
    }, 150)
}

/** Text input dialog. */
fun Context.askText(
    title: String,
    initial: String = "",
    hint: String = "",
    password: Boolean = false,
    confirm: String? = null,
    onOk: (String) -> Unit
) {
    val layout = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
        this.hint = hint
        if (password) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
    }
    val input = TextInputEditText(layout.context).apply {
        setText(initial)
        setSelection(initial.length)
        isSingleLine = true
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT
    }
    layout.addView(input)
    val wrap = FrameLayout(this).apply {
        setPadding(dp(22f), dp(8f), dp(22f), 0)
        addView(layout)
    }
    val d = MaterialAlertDialogBuilder(this)
        .setTitle(title)
        .setView(wrap)
        .setPositiveButton(confirm ?: getString(R.string.done)) { _, _ -> onOk(input.text?.toString() ?: "") }
        .setNegativeButton(getString(R.string.cancel), null)
        .create()
    d.setOnShowListener { showKeyboard(input) }
    d.show()
}

fun Context.confirm(title: String, msg: String, yes: String, no: String? = null, onYes: () -> Unit) {
    MaterialAlertDialogBuilder(this)
        .setTitle(title)
        .setMessage(msg)
        .setPositiveButton(yes) { _, _ -> onYes() }
        .setNegativeButton(no ?: getString(R.string.cancel), null)
        .show()
}

fun EditText.textString(): String = text?.toString() ?: ""
