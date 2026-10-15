package io.github.maximus5.ffnotifyimporter.ui

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ListAdapter
import android.widget.ListView
import android.widget.PopupWindow
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import kotlin.math.min

/** How long typing has to pause before the suggestion list is shown or refreshed. */
private const val SUGGESTION_DELAY_MILLIS = 500L

/** A list longer than [SHORT_LIST] rows is only shown once at least this many characters are typed. */
private const val MIN_CHARS_FOR_LONG_LIST = 2
private const val SHORT_LIST = 3

/** The list shows at most this many rows (the rest scrolls). */
private const val MAX_ROWS = 6

/**
 * [MaterialAutoCompleteTextView] with calmer suggestions:
 *
 * - the list is (re)filtered only after typing has paused for [SUGGESTION_DELAY_MILLIS], and only
 *   if the field still has focus - it does not flash up while the user is still typing;
 * - a list longer than [SHORT_LIST] rows is shown only from [MIN_CHARS_FOR_LONG_LIST] typed
 *   characters on, so a single letter does not drop a long list (a short one is shown at once);
 * - at most [MAX_ROWS] rows, the rest scrolls;
 * - the list opens above or below the field - whichever side has more room - and **keeps that
 *   side** for as long as the field has focus, shrinking to fit instead of jumping to the other
 *   side as the number of matches changes. (The framework's own drop-down chooses the side from the
 *   current list height, which is what made it jump; so this draws its own `PopupWindow`.)
 */
open class SuggestAutoCompleteTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.autoCompleteTextViewStyle,
) : MaterialAutoCompleteTextView(context, attrs, defStyleAttr) {

    /** Called with the text of a suggestion the user tapped (after it was written into the field). */
    var onSuggestionPicked: ((String) -> Unit)? = null

    private var pendingFilter: Runnable? = null
    private var popup: PopupWindow? = null
    private var popupList: ListView? = null

    /** Side chosen for this focus session: true = above the field. Null until the first show. */
    private var showAbove: Boolean? = null

    /** True while a chosen suggestion is being written into the field (must not start a new filtering). */
    private var selecting = false

    private val scrollListener = ViewTreeObserver.OnScrollChangedListener { dismissDropDown() }

    final override fun performFiltering(text: CharSequence?, keyCode: Int) {
        if (selecting) return
        pendingFilter?.let { removeCallbacks(it) }
        // Not dismissDropDown() here: it also stops the framework from acting on the result of this
        // very filtering, so nothing would show up after the pause.
        val filter = Runnable {
            pendingFilter = null
            if (isAttachedToWindow && hasFocus()) filterNow(text, keyCode)
        }
        pendingFilter = filter
        postDelayed(filter, SUGGESTION_DELAY_MILLIS)
    }

    /** The actual filtering, run once typing has paused; subclasses may change what is filtered by. */
    protected open fun filterNow(text: CharSequence?, keyCode: Int) {
        super.performFiltering(text, keyCode)
    }

    /** How many characters of the query count towards [MIN_CHARS_FOR_LONG_LIST]. */
    protected open fun queryLength(): Int = text?.toString()?.trim()?.length ?: 0

    override fun isPopupShowing(): Boolean = popup?.isShowing == true

    override fun showDropDown() {
        val adapter = adapter ?: return
        val count = adapter.count
        if (count == 0 || (count > SHORT_LIST && queryLength() < MIN_CHARS_FOR_LONG_LIST)) {
            dismissDropDown()
            return
        }
        showPopup(adapter)
    }

    override fun dismissDropDown() {
        popup?.dismiss()
        popup = null
        viewTreeObserver.removeOnScrollChangedListener(scrollListener)
        super.dismissDropDown()
    }

    /** Filtering has finished: with no matches the list closes (the framework would leave it open). */
    override fun onFilterComplete(count: Int) {
        if (count == 0) dismissDropDown() else super.onFilterComplete(count)
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        if (!focused) {
            pendingFilter?.let { removeCallbacks(it) }
            pendingFilter = null
            showAbove = null
            dismissDropDown()
        }
    }

    override fun onDetachedFromWindow() {
        pendingFilter?.let { removeCallbacks(it) }
        pendingFilter = null
        dismissDropDown()
        super.onDetachedFromWindow()
    }

    private fun showPopup(adapter: ListAdapter) {
        val density = resources.displayMetrics.density
        val list = popupList ?: ListView(context).apply {
            divider = null
            setOnItemClickListener { _, _, position, _ -> select(position) }
            popupList = this
        }
        if (list.adapter !== adapter) list.adapter = adapter

        val width = width
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        var content = 0
        for (i in 0 until min(adapter.count, MAX_ROWS)) {
            val row = adapter.getView(i, null, list)
            // Same height spec the ListView will give the row: an explicit layout_height (the
            // standard drop-down row has one) is ignored by View.measure on its own.
            val declared = row.layoutParams?.height ?: -1
            val heightSpec = if (declared >= 0) View.MeasureSpec.makeMeasureSpec(declared, View.MeasureSpec.EXACTLY) else unspecified
            row.measure(widthSpec, heightSpec)
            content += row.measuredHeight
        }

        val location = IntArray(2)
        getLocationOnScreen(location)
        // Where this field's window sits on the screen: a popup shown with showAtLocation is placed
        // relative to its parent *window*, which is the whole screen for an activity but a smaller,
        // offset window for a dialog - using screen coordinates there put the list well below the
        // field, over the keyboard.
        val windowOrigin = IntArray(2)
        rootView.getLocationOnScreen(windowOrigin)
        val frame = Rect()
        rootView.getWindowVisibleDisplayFrame(frame)
        // A dialog window does not always report its visible frame without the keyboard, so the
        // IME inset is applied explicitly.
        val imeHeight = ViewCompat.getRootWindowInsets(this)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        val visibleBottom = if (imeHeight > 0) min(frame.bottom, resources.displayMetrics.heightPixels - imeHeight) else frame.bottom
        val spaceBelow = visibleBottom - (location[1] + height)
        val spaceAbove = location[1] - frame.top

        // Side: the one with more room on the first show, then kept; re-chosen only if the kept side
        // has no room left for even one row (the field was scrolled or the keyboard changed).
        val minRoom = content / min(adapter.count, MAX_ROWS).coerceAtLeast(1)
        val keep = showAbove
        val above = when {
            keep == true && spaceAbove >= minRoom -> true
            keep == false && spaceBelow >= minRoom -> false
            else -> spaceAbove > spaceBelow
        }
        showAbove = above

        val popupHeight = min(content, if (above) spaceAbove else spaceBelow)
        val x = location[0] - windowOrigin[0]
        val y = (if (above) location[1] - popupHeight else location[1] + height) - windowOrigin[1]
        val shown = popup
        if (shown != null) {
            shown.update(x, y, width, popupHeight)
        } else {
            val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainer)
            popup = PopupWindow(list, width, popupHeight, false).apply {
                isOutsideTouchable = true
                elevation = 8 * density
                setBackgroundDrawable(GradientDrawable().apply { setColor(surface); cornerRadius = 4 * density })
                showAtLocation(rootView, Gravity.NO_GRAVITY, x, y)
            }
            viewTreeObserver.addOnScrollChangedListener(scrollListener)
        }
    }

    private fun select(position: Int) {
        val item = adapter?.getItem(position) ?: return
        pendingFilter?.let { removeCallbacks(it) }
        pendingFilter = null
        selecting = true
        val picked = convertSelectionToString(item)
        try {
            replaceText(picked)
        } finally {
            selecting = false
        }
        dismissDropDown()
        onSuggestionPicked?.invoke(picked.toString())
    }
}
