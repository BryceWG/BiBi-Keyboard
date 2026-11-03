/**
 * IME 识别历史面板列表适配器。
 *
 * 归属模块：ime
 */
package com.brycewg.asrkb.ime

import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.AsrHistoryStore
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.BibiViewThemes
import com.brycewg.asrkb.ui.history.AsrHistoryFailDisplay

internal class AsrHistoryPanelAdapter(
    private val onItemClick: (AsrHistoryStore.AsrHistoryRecord) -> Unit
) : ListAdapter<AsrHistoryStore.AsrHistoryRecord, AsrHistoryPanelAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AsrHistoryStore.AsrHistoryRecord>() {
            override fun areItemsTheSame(
                oldItem: AsrHistoryStore.AsrHistoryRecord,
                newItem: AsrHistoryStore.AsrHistoryRecord
            ): Boolean = oldItem.id == newItem.id

            override fun areContentsTheSame(
                oldItem: AsrHistoryStore.AsrHistoryRecord,
                newItem: AsrHistoryStore.AsrHistoryRecord
            ): Boolean = oldItem.text == newItem.text &&
                oldItem.rawText == newItem.rawText &&
                oldItem.timestamp == newItem.timestamp &&
                oldItem.status == newItem.status &&
                oldItem.failStage == newItem.failStage &&
                oldItem.failReasonCode == newItem.failReasonCode
        }
    }

    private var overlay: ItemOverlay? = null

    fun setOverlay(id: String, text: String, error: Boolean) {
        val previousId = overlay?.id
        overlay = ItemOverlay(id = id, text = text, error = error)
        if (previousId != null && previousId != id) notifyById(previousId)
        notifyById(id)
    }

    fun clearOverlay() {
        val previousId = overlay?.id ?: return
        overlay = null
        notifyById(previousId)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH = VH(createItemView(parent))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val record = getItem(position)
        val itemOverlay = overlay.takeIf { it?.id == record.id }
        holder.bind(
            record = record,
            overlayText = itemOverlay?.text,
            overlayError = itemOverlay?.error == true,
            onClick = onItemClick
        )
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tv: TextView = itemView.findViewById(R.id.asr_hist_tvEntry)

        fun bind(
            record: AsrHistoryStore.AsrHistoryRecord,
            overlayText: String?,
            overlayError: Boolean,
            onClick: (AsrHistoryStore.AsrHistoryRecord) -> Unit
        ) {
            val context = itemView.context
            val theme = BibiViewThemes.resolve(context, Prefs(context))
            val working = overlayText != null && !overlayError
            tv.text = clipboardTextPreview(
                overlayText ?: AsrHistoryFailDisplay.cardText(context, record)
            )
            tv.setTextColor(
                when {
                    overlayError || (overlayText == null && record.isUnsuccessful && !AsrHistoryFailDisplay.isSkipped(record)) -> theme.error
                    working -> theme.panelSummary
                    else -> theme.keyContent
                }
            )
            if (working) {
                itemView.isClickable = false
                itemView.setOnClickListener(null)
            } else {
                itemView.isClickable = true
                itemView.setOnClickListener { onClick(record) }
            }
        }
    }

    private fun notifyById(id: String) {
        val index = currentList.indexOfFirst { it.id == id }
        if (index >= 0) notifyItemChanged(index)
    }

    private data class ItemOverlay(
        val id: String,
        val text: String,
        val error: Boolean
    )

    private fun createItemView(parent: ViewGroup): View {
        val context = parent.context
        val theme = BibiViewThemes.resolve(context, Prefs(context))
        val radiusDp = ClipboardPanelMetrics.itemRadiusDp(theme.panelRadiusDp)

        val item = ConstraintLayout(context).apply {
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
            background = BibiViewThemes.roundedRipple(
                context,
                theme.keyBackground,
                theme.ripple,
                radiusDp,
                insetDp = 0
            )
            clipToOutline = true
            isClickable = true
            isFocusable = true
            minHeight = dp(context, 44)
            setPadding(dp(context, 14), dp(context, 11), dp(context, 14), dp(context, 11))
        }

        val tv = TextView(context).apply {
            id = R.id.asr_hist_tvEntry
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            includeFontPadding = false
            maxLines = 2
            setTextColor(theme.keyContent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = ConstraintLayout.LayoutParams(0, ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                matchConstraintDefaultWidth = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_SPREAD
            }
        }
        item.addView(tv)
        return item
    }

    private fun dp(context: android.content.Context, value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
