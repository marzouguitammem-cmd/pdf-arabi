package com.tammem.pdfarabi.ui

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.tammem.pdfarabi.Fonts
import com.tammem.pdfarabi.R
import com.tammem.pdfarabi.model.ALIGN_CENTER
import com.tammem.pdfarabi.model.ALIGN_LEFT
import com.tammem.pdfarabi.model.ALIGN_RIGHT
import com.tammem.pdfarabi.model.TextAnnot
import com.tammem.pdfarabi.render.TextBlocks
import java.util.Locale

/** Bottom sheet to type / edit text, with a live preview on the page. */
class TextEditSheet(
    private val activity: AppCompatActivity,
    private val base: TextAnnot,
    private val mode: Mode,
    private val onPreview: (TextAnnot, Boolean) -> Unit,
    private val onDone: (TextAnnot, Boolean) -> Unit,
    private val onCancel: () -> Unit,
    private val allowRemove: Boolean = true,
    private val extras: List<Pair<String, () -> Unit>> = emptyList()
) {
    enum class Mode { ADD, EDIT, REPLACE }

    private var current = base
    private var text = base.text
    private var font = base.font
    private var size = base.size
    private var color = base.color
    private var bold = base.bold
    private var align = base.align
    private var bg = base.bg
    private var border = base.border
    private var removeOriginal = true
    private var alignTouched = mode != Mode.ADD
    private var finished = false

    fun show(removeOriginalDefault: Boolean = true) {
        removeOriginal = removeOriginalDefault
        val dialog = BottomSheetDialog(activity)
        val v = LayoutInflater.from(dialog.context).inflate(R.layout.sheet_text, null)
        dialog.setContentView(v)
        dialog.window?.setDimAmount(0.12f)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        val title = v.findViewById<TextView>(R.id.title)
        title.text = when (mode) {
            Mode.ADD -> "نص جديد"
            Mode.EDIT -> "تعديل النص"
            Mode.REPLACE -> "تبديل الكلام"
        }
        if (extras.isNotEmpty()) {
            val content = (v as android.view.ViewGroup).getChildAt(0) as LinearLayout
            val row = ChipGroup(dialog.context).apply { isSingleLine = true }
            for ((label, action) in extras) {
                row.addView(Chip(dialog.context).apply {
                    this.text = label
                    typeface = Fonts.get("tajawal").bold
                    setOnClickListener {
                        finished = true
                        dialog.dismiss()
                        action()
                    }
                })
            }
            content.addView(row, 1)
        }

        val input = v.findViewById<TextInputEditText>(R.id.input)
        input.setText(text)
        input.setSelection(text.length)

        val fonts = v.findViewById<ChipGroup>(R.id.fonts)
        Fonts.all.forEach { f ->
            val chip = Chip(dialog.context).apply {
                id = View.generateViewId()
                this.text = f.label
                isCheckable = true
                isCheckedIconVisible = false
                typeface = f.regular
                isChecked = f.id == font
                setOnClickListener { font = f.id; update() }
            }
            fonts.addView(chip)
        }

        val slider = v.findViewById<Slider>(R.id.size)
        val sizeLabel = v.findViewById<TextView>(R.id.sizeLabel)
        fun setSize(s: Float) {
            size = (Math.round(s * 2f) / 2f).coerceIn(slider.valueFrom, slider.valueTo)
            if (slider.value != size) slider.value = size
            sizeLabel.text = String.format(Locale.US, if (size % 1f == 0f) "%.0f" else "%.1f", size)
        }
        setSize(size)
        slider.addOnChangeListener { _, value, fromUser -> if (fromUser) { setSize(value); update() } }
        v.findViewById<MaterialButton>(R.id.sizeMinus).setOnClickListener { setSize(size - 0.5f); update() }
        v.findViewById<MaterialButton>(R.id.sizePlus).setOnClickListener { setSize(size + 0.5f); update() }

        val colors = v.findViewById<LinearLayout>(R.id.colors)
        val palette = if (Palette.text.any { (it and 0xFFFFFF) == (color and 0xFFFFFF) }) Palette.text
        else intArrayOf(color) + Palette.text
        colors.addSwatches(palette, color) { color = it; update() }

        val alignGroup = v.findViewById<MaterialButtonToggleGroup>(R.id.alignGroup)
        fun checkAlign() {
            alignGroup.check(
                when (align) {
                    ALIGN_RIGHT -> R.id.alignRight
                    ALIGN_LEFT -> R.id.alignLeft
                    else -> R.id.alignCenter
                }
            )
        }
        checkAlign()
        alignGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val a = when (id) {
                R.id.alignRight -> ALIGN_RIGHT
                R.id.alignLeft -> ALIGN_LEFT
                else -> ALIGN_CENTER
            }
            if (a != align) { align = a; alignTouched = true; update() }
        }

        val boldBtn = v.findViewById<MaterialButton>(R.id.bold)
        boldBtn.isChecked = bold
        boldBtn.addOnCheckedChangeListener { _, c -> bold = c; update() }
        val bgBtn = v.findViewById<MaterialButton>(R.id.bg)
        bgBtn.isChecked = bg != 0
        bgBtn.addOnCheckedChangeListener { _, c -> bg = if (c) 0xFFFFFFFF.toInt() else 0; update() }
        val borderBtn = v.findViewById<MaterialButton>(R.id.border)
        borderBtn.isChecked = border
        borderBtn.addOnCheckedChangeListener { _, c -> border = c; update() }

        val sw = v.findViewById<MaterialSwitch>(R.id.removeOriginal)
        val hint = v.findViewById<TextView>(R.id.hint)
        if (mode == Mode.REPLACE) {
            bgBtn.visibility = View.GONE
            borderBtn.visibility = View.GONE
            sw.visibility = if (allowRemove) View.VISIBLE else View.GONE
            sw.isChecked = removeOriginal
            sw.setOnCheckedChangeListener { _, c -> removeOriginal = c }
            hint.visibility = View.VISIBLE
            hint.text = "اللون والحجم والخط اخترتهم وحدي باش يجيو كيف الكلام الأصلي. إذا فرّغت الخانة، الكلام القديم يتفسخ برك."
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                text = s?.toString() ?: ""
                if (!alignTouched) {
                    val rtl = TextBlocks.isRtl(text)
                    if (rtl != null) {
                        val a = if (rtl) ALIGN_RIGHT else ALIGN_LEFT
                        if (a != align) { align = a; checkAlign(); alignTouched = false }
                    }
                }
                update()
            }
        })

        v.findViewById<MaterialButton>(R.id.done).setOnClickListener {
            finished = true
            activity.hideKeyboard(input)
            dialog.dismiss()
            onDone(build(), removeOriginal)
        }
        v.findViewById<MaterialButton>(R.id.cancel).setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { if (!finished) onCancel() }
        dialog.setOnShowListener { showKeyboard(input) }
        dialog.show()
        update()
    }

    private fun build(): TextAnnot {
        val newer = base.copy(
            text = text, font = font, size = size, color = color, bold = bold,
            align = align, bg = bg, border = border
        )
        current = base.reanchored(newer)
        return current
    }

    private fun update() {
        onPreview(build(), removeOriginal)
    }
}
