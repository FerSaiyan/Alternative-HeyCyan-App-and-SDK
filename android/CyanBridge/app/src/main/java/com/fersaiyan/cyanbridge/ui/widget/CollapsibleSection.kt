package com.fersaiyan.cyanbridge.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.fersaiyan.cyanbridge.R

/**
 * A collapsible card section with a header that can be clicked to expand/collapse.
 * The content animates smoothly between expanded and collapsed states.
 */
class CollapsibleSection @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val headerContainer: LinearLayout
    private val titleText: TextView
    private val expandIcon: ImageView
    private val contentContainer: LinearLayout
    
    private var isExpanded: Boolean = true
    private var contentHeight: Int = 0
    private var animator: ValueAnimator? = null
    
    init {
        orientation = VERTICAL
        
        // Create header
        headerContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(0, 0, 0, 0)
            isClickable = true
            isFocusable = true
            val selectableBackground = context.obtainStyledAttributes(
                intArrayOf(android.R.attr.selectableItemBackground)
            )
            background = try {
                selectableBackground.getDrawable(0)
            } finally {
                selectableBackground.recycle()
            }
        }
        
        titleText = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(context.getColor(R.color.cyan_accent))
            textSize = 12f
            letterSpacing = 0.1f
            setTextColor(resources.getColor(R.color.cyan_accent, null))
        }
        
        expandIcon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            setImageResource(R.drawable.ic_expand_more)
            setColorFilter(resources.getColor(R.color.text_secondary, null))
        }
        
        headerContainer.addView(titleText)
        headerContainer.addView(expandIcon)
        addView(headerContainer)
        
        // Content container
        contentContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        addView(contentContainer)
        
        // Apply custom attrs
        attrs?.let {
            val typedArray = context.obtainStyledAttributes(it, R.styleable.CollapsibleSection)
            val title = typedArray.getString(R.styleable.CollapsibleSection_section_title)
            val initiallyExpanded = typedArray.getBoolean(R.styleable.CollapsibleSection_initially_expanded, true)
            titleText.text = title ?: ""
            isExpanded = initiallyExpanded
            
            // Set initial icon rotation
            expandIcon.rotation = if (isExpanded) 180f else 0f
            
            typedArray.recycle()
        }
        
        // Set initial content visibility
        contentContainer.visibility = if (isExpanded) View.VISIBLE else View.GONE
        
        // Click listener
        headerContainer.setOnClickListener {
            toggle()
        }
    }
    
    fun setTitle(title: String) {
        titleText.text = title
    }
    
    fun setContent(view: View) {
        contentContainer.removeAllViews()
        contentContainer.addView(view)
    }
    
    fun addContent(view: View) {
        contentContainer.addView(view)
    }
    
    fun toggle() {
        if (isExpanded) {
            collapse()
        } else {
            expand()
        }
    }
    
    fun expand() {
        if (isExpanded) return
        isExpanded = true
        animateContent(true)
    }
    
    fun collapse() {
        if (!isExpanded) return
        isExpanded = false
        animateContent(false)
    }
    
    private fun animateContent(expand: Boolean) {
        // Cancel any existing animation
        animator?.cancel()
        
        if (expand) {
            contentContainer.visibility = View.VISIBLE
        }
        
        // Measure content height if needed
        contentContainer.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        contentHeight = contentContainer.measuredHeight
        
        val startHeight = if (expand) 0 else contentHeight
        val endHeight = if (expand) contentHeight else 0
        
        animator = ValueAnimator.ofInt(startHeight, endHeight).apply {
            duration = 300
            interpolator = FastOutSlowInInterpolator()
            
            addUpdateListener { anim ->
                val value = anim.animatedValue as Int
                contentContainer.layoutParams.height = value
                contentContainer.requestLayout()
            }
            
            start()
        }
        
        // Rotate icon
        expandIcon.animate()
            .rotation(if (expand) 180f else 0f)
            .setDuration(300)
            .setInterpolator(FastOutSlowInInterpolator())
            .start()
        
        if (!expand) {
            // Set visibility to GONE after animation
            contentContainer.postDelayed({
                if (!isExpanded) {
                    contentContainer.visibility = View.GONE
                    contentContainer.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
            }, 300)
        } else {
            contentContainer.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
    }
    
    fun isExpandedState(): Boolean = isExpanded
}
