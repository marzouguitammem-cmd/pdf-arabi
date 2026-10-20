package com.tammem.pdfarabi.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.tammem.pdfarabi.Fonts
import com.tammem.pdfarabi.Prefs
import com.tammem.pdfarabi.R
import com.tammem.pdfarabi.Signature
import com.tammem.pdfarabi.Stamp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

object StampPresets {
    val RED = 0xFFC62828.toInt()
    val GREEN = 0xFF2E7D32.toInt()
    val BLUE = 0xFF1565C0.toInt()

    val all: List<Stamp> = listOf(
        Stamp("ممتاز", GREEN, true),
        Stamp("أحسنت", GREEN, true),
        Stamp("عمل جيّد", GREEN, true),
        Stamp("تحسّن ملحوظ", GREEN, true),
        Stamp("حسن", BLUE, true),
        Stamp("مقبول", BLUE, true),
        Stamp("انتبه", RED, true),
        Stamp("أعد المحاولة", RED, true),
        Stamp("راجع درسك", RED, true),
        Stamp("اعتنِ بخطّك", RED, true),
        Stamp("العدد: ……… / 20", RED, true, "tajawal", true),
        Stamp("العدد: ……… / 10", RED, true, "tajawal", true),
        Stamp("اطّلع عليه المعلّم{name}\n{date}", BLUE, true, "tajawal", false),
        Stamp("اطّلع عليه الوليّ: ………", BLUE, true, "tajawal", false),
        Stamp("بتاريخ: {date}", BLUE, false, "tajawal", false),
        Stamp("Excellent", GREEN, true, "serif", true),
        Stamp("Très bien", GREEN, true, "serif", true),
        Stamp("Bien", BLUE, true, "serif", true),
        Stamp("Assez bien", BLUE, true, "serif", true),
        Stamp("Passable", BLUE, true, "serif", true),
        Stamp("À revoir", RED, true, "serif", true),
        Stamp("Bravo !", GREEN, true, "serif", true)
    )

    fun everything(): List<Stamp> = all + Prefs.customStamps()

    /** Replaces {date} and {name}. */
    fun resolve(text: String): String {
        val date = SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date())
        val name = Prefs.teacherName.trim()
        return text.replace("{date}", date).replace("{name}", if (name.isEmpty()) "" else ": $name")
    }
}

/** Picker for teacher stamps. */
@SuppressLint("SetTextI18n")
fun showStampsSheet(ctx: Context, onPick: (Stamp) -> Unit) {
    val dialog = BottomSheetDialog(ctx)
    val c = dialog.context
    val scroll = NestedScrollView(c)
    val box = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(c.dp(16f), c.dp(4f), c.dp(16f), c.dp(20f))
    }
    scroll.addView(box)
    box.addView(TextView(c).apply {
        text = c.getString(R.string.stamps)
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
        typeface = Fonts.get("tajawal").bold
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    })
    box.addView(TextView(c).apply {
        text = c.getString(R.string.stamps_hint)
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        typeface = Fonts.get("tajawal").regular
        setTextColor(c.attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        setPadding(0, c.dp(4f), 0, c.dp(8f))
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    })
    val group = ChipGroup(c).apply { chipSpacingHorizontal = c.dp(8f); chipSpacingVertical = c.dp(4f) }
    box.addView(group)

    fun fill() {
        group.removeAllViews()
        val custom = Prefs.customStamps()
        for (s in StampPresets.everything()) {
            val chip = Chip(c).apply {
                text = StampPresets.resolve(s.text).replace('\n', ' ')
                typeface = Fonts.typeface(s.font, s.bold)
                textSize = 17f
                setTextColor(s.color)
                chipStrokeWidth = if (s.border) c.dp(1.5f).toFloat() else 0f
                chipStrokeColor = ColorStateList.valueOf(s.color)
                chipBackgroundColor = ColorStateList.valueOf(c.attrColor(com.google.android.material.R.attr.colorSurfaceContainerLow))
                setOnClickListener { dialog.dismiss(); onPick(s) }
                if (custom.contains(s)) {
                    setOnLongClickListener {
                        c.confirm(c.getString(R.string.delete_stamp_q), StampPresets.resolve(s.text), c.getString(R.string.delete)) {
                            Prefs.saveCustomStamps(Prefs.customStamps().filterNot { it == s })
                            fill()
                        }
                        true
                    }
                }
            }
            group.addView(chip)
        }
        group.addView(Chip(c).apply {
            text = c.getString(R.string.new_stamp_chip)
            typeface = Fonts.get("tajawal").bold
            setOnClickListener { newStampDialog(c) { s -> Prefs.saveCustomStamps(Prefs.customStamps() + s); fill() } }
        })
    }
    fill()
    dialog.setContentView(scroll)
    dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    dialog.show()
}

private fun newStampDialog(ctx: Context, onSave: (Stamp) -> Unit) {
    val box = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(22f), ctx.dp(8f), ctx.dp(22f), 0)
    }
    val til = TextInputLayout(ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { hint = ctx.getString(R.string.stamp_text) }
    val input = TextInputEditText(til.context).apply {
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }
    til.addView(input)
    box.addView(til)
    val colors = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, ctx.dp(10f), 0, 0) }
    var color = StampPresets.RED
    colors.addSwatches(intArrayOf(StampPresets.RED, StampPresets.GREEN, StampPresets.BLUE, Color.BLACK), color) { color = it }
    box.addView(colors)
    val toggle = MaterialButtonToggleGroup(ctx).apply { isSingleSelection = true; isSelectionRequired = true }
    val handBtn = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        id = View.generateViewId(); text = ctx.getString(R.string.hand_font)
    }
    val plainBtn = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        id = View.generateViewId(); text = ctx.getString(R.string.plain_font)
    }
    toggle.addView(handBtn)
    toggle.addView(plainBtn)
    toggle.check(handBtn.id)
    box.addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = ctx.dp(8f)
    })
    MaterialAlertDialogBuilder(ctx)
        .setTitle(ctx.getString(R.string.new_stamp))
        .setView(box)
        .setPositiveButton(ctx.getString(R.string.save_btn)) { _, _ ->
            val t = input.text?.toString()?.trim().orEmpty()
            if (t.isNotEmpty()) {
                val hand = toggle.checkedButtonId == handBtn.id
                onSave(Stamp(t, color, true, if (hand) "ruqaa" else "tajawal", true))
            }
        }
        .setNegativeButton(ctx.getString(R.string.cancel), null)
        .show()
    showKeyboard(input)
}

// ------------------------------------------------------------------
// Signatures
// ------------------------------------------------------------------

/** Where the user draws a signature. */
class SignaturePadView(context: Context) : View(context) {
    private val strokes = ArrayList<ArrayList<Float>>()
    private var current: ArrayList<Float>? = null
    var color = 0xFF0D2A6B.toInt()
        set(v) { field = v; paint.color = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(3.2f).toFloat()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFF0D2A6B.toInt()
    }
    private val guide = Paint().apply { color = 0x33000000; strokeWidth = context.dp(1f).toFloat() }

    val isEmpty: Boolean get() = strokes.isEmpty()

    fun clear() { strokes.clear(); invalidate() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                current = arrayListOf(e.x, e.y).also { strokes += it }
            }
            MotionEvent.ACTION_MOVE -> current?.let { s ->
                for (h in 0 until e.historySize) { s += e.getHistoricalX(h); s += e.getHistoricalY(h) }
                s += e.x; s += e.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> current = null
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val y = height * 0.75f
        canvas.drawLine(width * 0.08f, y, width * 0.92f, y, guide)
        for (s in strokes) {
            val p = Path()
            p.moveTo(s[0], s[1])
            if (s.size == 2) p.lineTo(s[0] + 0.5f, s[1])
            var i = 2
            while (i + 1 < s.size) {
                val px = s[i - 2]; val py = s[i - 1]
                p.quadTo(px, py, (px + s[i]) / 2f, (py + s[i + 1]) / 2f)
                i += 2
            }
            if (s.size >= 4) p.lineTo(s[s.size - 2], s[s.size - 1])
            canvas.drawPath(p, paint)
        }
    }

    /** Normalised signature, or null when empty. */
    fun toSignature(): Signature? {
        if (strokes.isEmpty()) return null
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (s in strokes) {
            var i = 0
            while (i + 1 < s.size) {
                minX = min(minX, s[i]); maxX = max(maxX, s[i])
                minY = min(minY, s[i + 1]); maxY = max(maxY, s[i + 1])
                i += 2
            }
        }
        val pad = paint.strokeWidth
        minX -= pad; minY -= pad; maxX += pad; maxY += pad
        val w = max(1f, maxX - minX)
        val h = max(1f, maxY - minY)
        val out = strokes.map { s ->
            FloatArray(s.size) { k -> if (k % 2 == 0) (s[k] - minX) / w else (s[k] - minY) / h }
        }
        return Signature(System.currentTimeMillis(), w / h, out, color)
    }
}

/** Little preview of a saved signature. */
class SignaturePreview(context: Context, private val sig: Signature) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = sig.color; strokeWidth = context.dp(2f).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        val pad = dp(8f).toFloat()
        val aw = width - 2 * pad
        val ah = height - 2 * pad
        var w = aw
        var h = w / sig.aspect
        if (h > ah) { h = ah; w = h * sig.aspect }
        val ox = pad + (aw - w) / 2
        val oy = pad + (ah - h) / 2
        for (s in sig.strokes) {
            val p = Path()
            var i = 0
            while (i + 1 < s.size) {
                val x = ox + s[i] * w
                val y = oy + s[i + 1] * h
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                i += 2
            }
            canvas.drawPath(p, paint)
        }
    }
}

fun showSignaturePad(ctx: Context, onSaved: (Signature) -> Unit) {
    val box = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(16f), ctx.dp(8f), ctx.dp(16f), 0)
    }
    val card = MaterialCardView(ctx).apply {
        radius = ctx.dp(12f).toFloat()
        strokeWidth = ctx.dp(1f)
        strokeColor = ctx.attrColor(com.google.android.material.R.attr.colorOutlineVariant)
        cardElevation = 0f
    }
    val pad = SignaturePadView(ctx)
    card.addView(pad, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(210f)))
    box.addView(card)
    val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, ctx.dp(8f), 0, 0) }
    row.addSwatches(intArrayOf(0xFF0D2A6B.toInt(), Color.BLACK, 0xFF1565C0.toInt(), StampPresets.RED), pad.color) { pad.color = it }
    row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
    row.addView(MaterialButton(ctx, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
        text = ctx.getString(R.string.clear)
        setOnClickListener { pad.clear() }
    })
    box.addView(row)
    val d = MaterialAlertDialogBuilder(ctx)
        .setTitle(ctx.getString(R.string.sign_here))
        .setView(box)
        .setPositiveButton(ctx.getString(R.string.save_btn), null)
        .setNegativeButton(ctx.getString(R.string.cancel), null)
        .create()
    d.setOnShowListener {
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val sig = pad.toSignature()
            if (sig == null) { ctx.toast(ctx.getString(R.string.sign_first)); return@setOnClickListener }
            Prefs.saveSignatures(Prefs.signatures() + sig)
            d.dismiss()
            onSaved(sig)
        }
    }
    d.show()
}

/** Picker for saved signatures. */
fun showSignaturesSheet(ctx: Context, onPick: (Signature) -> Unit) {
    val dialog = BottomSheetDialog(ctx)
    val c = dialog.context
    val box = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(c.dp(16f), c.dp(4f), c.dp(16f), c.dp(20f))
    }
    box.addView(TextView(c).apply {
        text = c.getString(R.string.signatures)
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
        typeface = Fonts.get("tajawal").bold
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    })
    val list = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    box.addView(list)
    fun fill() {
        list.removeAllViews()
        for (sig in Prefs.signatures().asReversed()) {
            val card = MaterialCardView(c).apply {
                radius = c.dp(12f).toFloat()
                cardElevation = 0f
                strokeWidth = c.dp(1f)
                strokeColor = c.attrColor(com.google.android.material.R.attr.colorOutlineVariant)
                setCardBackgroundColor(Color.WHITE)
                setOnClickListener { dialog.dismiss(); onPick(sig) }
                setOnLongClickListener {
                    c.confirm(c.getString(R.string.delete_sign_q), c.getString(R.string.delete_sign_msg), c.getString(R.string.delete)) {
                        Prefs.saveSignatures(Prefs.signatures().filterNot { it.id == sig.id })
                        fill()
                    }
                    true
                }
            }
            card.addView(SignaturePreview(c, sig), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dp(90f)))
            list.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = c.dp(10f)
            })
        }
    }
    fill()
    box.addView(MaterialButton(c).apply {
        text = c.getString(R.string.new_signature)
        setOnClickListener {
            dialog.dismiss()
            showSignaturePad(ctx) { onPick(it) }
        }
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = c.dp(14f)
    })
    val scroll = NestedScrollView(c)
    scroll.addView(box)
    dialog.setContentView(scroll)
    dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    dialog.show()
}
