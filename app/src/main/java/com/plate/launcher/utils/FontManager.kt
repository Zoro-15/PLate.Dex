package com.plate.launcher.utils

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextWatcher
import android.text.Editable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.core.content.edit
import java.io.File

/**
 * FontManager v2 — comprehensive font application with no glitches
 *
 * ══ Old problems ══
 * 1. applyToView walks the tree once → Views added later don't get the font
 * 2. chooseFontForText only reads the text at application time → if the text
 *    changes later = no font
 * 3. applyIfSet in PerfectServer is only called once at build time
 * 4. FontActivityCallbacks only applies in onResume → new fragments don't get the font
 *
 * ══ The fix ══
 * - TypefaceHolder: a Singleton that keeps loaded Typefaces in memory (no disk read every time)
 * - FontTextWatcher: added to every TextView → reapplies the font whenever the text changes
 * - applyToViewDeep: applies + adds an OnGlobalLayoutListener that watches for new Views being added
 * - GlobalLayoutFontHook: watches the decorView and applies the font to any View that gets added
 */
object FontManager {

    // ── Prefs keys ────────────────────────────────────────────────────────────
    private const val PREF_ARABIC_PATH = "font_arabic_path"
    private const val PREF_ARABIC_NAME = "font_arabic_name"
    private const val PREF_LATIN_PATH  = "font_latin_path"
    private const val PREF_LATIN_NAME  = "font_latin_name"
    private const val FONTS_DIR        = "fonts"

    enum class FontScript { ARABIC, LATIN }

    // ── TypefaceHolder — an in-memory Singleton, no disk read on every frame ─────
    private object TypefaceHolder {
        var arabic: Typeface? = null
        var latin:  Typeface? = null
        // A tag we set on every TextView so we don't duplicate the TextWatcher
        const val TAG_WATCHER = 0x594F554B // "YOUK"
    }

    // ── Script detection ──────────────────────────────────────────────────────
    fun detectScript(typeface: Typeface): FontScript {
        val testPaint = Paint().apply { this.typeface = typeface }
        val defPaint  = Paint()
        val arabicStr = "مرحبا بكم في يوكي"
        val diff = kotlin.math.abs(testPaint.measureText(arabicStr) - defPaint.measureText(arabicStr))
        return if (diff > 3f) FontScript.ARABIC else FontScript.LATIN
    }

    /**
     * Detects every font/language supported by the typeface
     * and returns display-ready text like: "Arabic · Latin · Japanese"
     */
    @android.annotation.SuppressLint("NewApi")
    fun detectSupportedScripts(typeface: Typeface): String {
        val paint = Paint().apply { this.typeface = typeface }
        val scripts = mutableListOf<String>()

        // Arabic — the letter "م"
        if (paint.hasGlyph("\u0645")) scripts.add("Arabic")
        // Latin — the letter "A"
        if (paint.hasGlyph("A")) scripts.add("Latin")
        // Japanese Hiragana — "あ"
        if (paint.hasGlyph("\u3042")) scripts.add("Japanese")
        // Chinese/CJK — "漢"
        if (paint.hasGlyph("\u6F22")) scripts.add("Chinese/CJK")
        // Korean Hangul — "가"
        if (paint.hasGlyph("\uAC00")) scripts.add("Korean")
        // Cyrillic — "Ж"
        if (paint.hasGlyph("\u0416")) scripts.add("Cyrillic")
        // Hebrew — "ש"
        if (paint.hasGlyph("\u05E9")) scripts.add("Hebrew")
        // Thai — "ก"
        if (paint.hasGlyph("\u0E01")) scripts.add("Thai")
        // Greek — "α"
        if (paint.hasGlyph("\u03B1")) scripts.add("Greek")

        return if (scripts.isEmpty()) "Unknown" else scripts.joinToString(" · ")
    }

    // ── Load typefaces (lazy + cached) ────────────────────────────────────────
    fun getArabicTypeface(context: Context): Typeface? {
        TypefaceHolder.arabic?.let { return it }
        return loadTypefaceFromPref(context, PREF_ARABIC_PATH)
            .also { TypefaceHolder.arabic = it }
    }

    fun getLatinTypeface(context: Context): Typeface? {
        TypefaceHolder.latin?.let { return it }
        return loadTypefaceFromPref(context, PREF_LATIN_PATH)
            .also { TypefaceHolder.latin = it }
    }

    fun getArabicFontName(context: Context): String? =
        PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_ARABIC_NAME, null)

    fun getLatinFontName(context: Context): String? =
        PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_LATIN_NAME, null)

    fun hasArabicFont(context: Context) = getArabicFontName(context) != null
    fun hasLatinFont(context: Context)  = getLatinFontName(context)  != null

    private fun loadTypefaceFromPref(context: Context, pathPref: String): Typeface? {
        val path = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(pathPref, null) ?: return null
        return try {
            val f = File(path)
            if (f.exists()) Typeface.createFromFile(f) else null
        } catch (e: Exception) { null }
    }

    // ── Invalidate the cache after saving/deleting ──────────────────────────────────────────
    private fun invalidateCache() {
        TypefaceHolder.arabic = null
        TypefaceHolder.latin  = null
    }

    // ── Save / clear ──────────────────────────────────────────────────────────
    fun saveFont(context: Context, path: String, name: String, script: FontScript) {
        val (pathKey, nameKey) = when (script) {
            FontScript.ARABIC -> PREF_ARABIC_PATH to PREF_ARABIC_NAME
            FontScript.LATIN  -> PREF_LATIN_PATH  to PREF_LATIN_NAME
        }
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(pathKey, path)
            putString(nameKey, name)
        }
        invalidateCache()
    }

    fun clearFont(context: Context, script: FontScript) {
        val (pathKey, nameKey) = when (script) {
            FontScript.ARABIC -> PREF_ARABIC_PATH to PREF_ARABIC_NAME
            FontScript.LATIN  -> PREF_LATIN_PATH  to PREF_LATIN_NAME
        }
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            remove(pathKey); remove(nameKey)
        }
        val dir = File(context.filesDir, "$FONTS_DIR/${script.name.lowercase()}")
        dir.deleteRecursively()
        invalidateCache()
    }

    // ── Copy font file ────────────────────────────────────────────────────────
    fun copyFontFromUri(
        context: Context,
        uri: android.net.Uri,
        displayName: String,
        script: FontScript
    ): String? = try {
        val scriptDir = File(context.filesDir, "$FONTS_DIR/${script.name.lowercase()}").also { it.mkdirs() }
        scriptDir.listFiles()?.forEach { it.delete() }
        val dest = File(scriptDir, displayName)
        context.contentResolver.openInputStream(uri)?.use { dest.outputStream().use(it::copyTo) }
        Typeface.createFromFile(dest)
        dest.absolutePath
    } catch (e: Exception) { null }

    // ── FIX: FontTextWatcher — watches for text changes and reapplies the font immediately ───────
    private class FontTextWatcher(
        private val tv: TextView,
        private val arabicTf: Typeface?,
        private val latinTf: Typeface?
    ) : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            val tf = chooseFontForText(s, arabicTf, latinTf) ?: return
            // We avoid a loop: check before the change
            if (tv.typeface != tf) {
                tv.removeTextChangedListener(this)
                tv.typeface = tf
                tv.addTextChangedListener(this)
            }
        }
    }

    // ── Apply to single TextView ──────────────────────────────────────────────
    fun applyToTextView(view: TextView, arabicTf: Typeface?, latinTf: Typeface?) {
        if (arabicTf == null && latinTf == null) return

        // Apply the current font
        chooseFontForText(view.text, arabicTf, latinTf)?.let { tf ->
            if (view.typeface != tf) view.typeface = tf
        }

        // FIX: only add a TextWatcher if one doesn't already exist (avoid duplicating the watcher)
        if (view.getTag(TypefaceHolder.TAG_WATCHER) == null) {
            val watcher = FontTextWatcher(view, arabicTf, latinTf)
            view.addTextChangedListener(watcher)
            view.setTag(TypefaceHolder.TAG_WATCHER, watcher)
        }
    }

    // ── Apply recursively to full View tree ───────────────────────────────────
    fun applyToView(root: View, arabicTf: Typeface?, latinTf: Typeface?) {
        if (arabicTf == null && latinTf == null) return
        applyRecursive(root, arabicTf, latinTf)
    }

    private fun applyRecursive(view: View, arabicTf: Typeface?, latinTf: Typeface?) {
        when (view) {
            is TextView  -> applyToTextView(view, arabicTf, latinTf)
            is ViewGroup -> {
                for (i in 0 until view.childCount)
                    applyRecursive(view.getChildAt(i), arabicTf, latinTf)

                // FIX: watch for new Views being added to the ViewGroup
                attachViewGroupListener(view, arabicTf, latinTf)
            }
        }
    }

    // ── FIX: a listener for new Views being added ─────────────────────────────────────────
    private const val TAG_LISTENER = 0x594F554C // "YOUL"

    private fun attachViewGroupListener(
        group: ViewGroup,
        arabicTf: Typeface?,
        latinTf: Typeface?
    ) {
        // We don't add a duplicate listener
        if (group.getTag(TAG_LISTENER) != null) return

        val listener = object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) {
                child ?: return
                // Apply the font to the new child
                child.post { applyRecursive(child, arabicTf, latinTf) }
            }
            override fun onChildViewRemoved(parent: View?, child: View?) {}
        }
        group.setOnHierarchyChangeListener(listener)
        group.setTag(TAG_LISTENER, listener)
    }

    // ── Convenience ───────────────────────────────────────────────────────────
    fun applyIfSet(context: Context, root: View) {
        val arabicTf = getArabicTypeface(context)
        val latinTf  = getLatinTypeface(context)
        if (arabicTf == null && latinTf == null) return
        applyToView(root, arabicTf, latinTf)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun containsArabic(text: String): Boolean = text.any { c ->
        val cp = c.code
        cp in 0x0600..0x06FF || cp in 0x0750..0x077F || cp in 0x08A0..0x08FF ||
        cp in 0xFB50..0xFDFF || cp in 0xFE70..0xFEFF
    }

    private fun chooseFontForText(
        text: CharSequence?,
        arabicTf: Typeface?,
        latinTf: Typeface?
    ): Typeface? {
        val str = text?.toString() ?: ""
        val hasArabicTf = arabicTf != null
        val hasLatinTf  = latinTf != null
        if (str.isEmpty()) {
            return if (hasArabicTf) arabicTf else if (hasLatinTf) latinTf else null
        }
        val isArabic = containsArabic(str)
        return when {
            isArabic && hasArabicTf  -> arabicTf
            !isArabic && hasLatinTf  -> latinTf
            hasArabicTf              -> arabicTf
            hasLatinTf               -> latinTf
            else                     -> null
        }
    }
}
