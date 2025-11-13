package com.brycewg.asrkb.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import androidx.annotation.StringRes
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 功能说明弹窗工具类
 *
 * 用于在用户首次点击某个功能开关时，向用户解释该功能开启前后的作用，
 * 降低用户的使用门槛，提升用户体验。
 *
 * 特性：
 * - Material3 设计风格
 * - 支持"不再提示"选项
 * - 自动记录是否已提示过
 * - 支持确认/取消回调
 * - Builder 模式，易于使用
 *
 * 使用示例：
 * ```kotlin
 * FeatureExplainerDialog.Builder(context)
 *     .setTitle(R.string.feature_title)
 *     .setOffDescription(R.string.feature_off_desc)
 *     .setOnDescription(R.string.feature_on_desc)
 *     .setCurrentState(false) // 当前状态：关闭
 *     .setPreferenceKey("feature_xyz_explained")
 *     .setOnConfirm {
 *         // 用户确认后的回调
 *         switchFeature.isChecked = true
 *         prefs.featureEnabled = true
 *     }
 *     .showIfNeeded() // 仅在首次点击时显示
 * ```
 */
class FeatureExplainerDialog private constructor(
    private val context: Context,
    private val title: String,
    private val offDescription: String,
    private val onDescription: String,
    private val currentState: Boolean,
    private val preferenceKey: String?,
    private val onConfirm: (() -> Unit)?,
    private val onCancel: (() -> Unit)?
) {

    /**
     * 显示弹窗
     */
    fun show() {
        // 使用自定义布局
        val inflater = LayoutInflater.from(context)
        val customView = inflater.inflate(R.layout.dialog_feature_explainer, null)

        val tvTitle = customView.findViewById<TextView>(R.id.tvDialogTitle)
        val tvCurrentState = customView.findViewById<TextView>(R.id.tvCurrentState)
        val tvFromDescription = customView.findViewById<TextView>(R.id.tvFromDescription)
        val tvToDescription = customView.findViewById<TextView>(R.id.tvToDescription)
        val cbDontShowAgain = customView.findViewById<CheckBox>(R.id.cbDontShowAgain)

        // 设置内容
        tvTitle.text = title

        // 根据当前状态显示不同的说明
        if (currentState) {
            // 当前是开启状态，点击后会关闭
            tvCurrentState.text = context.getString(R.string.dialog_feature_explainer_turn_off)
            tvFromDescription.text = onDescription
            tvToDescription.text = offDescription
        } else {
            // 当前是关闭状态，点击后会开启
            tvCurrentState.text = context.getString(R.string.dialog_feature_explainer_turn_on)
            tvFromDescription.text = offDescription
            tvToDescription.text = onDescription
        }

        // 只有设置了 preferenceKey 才显示"不再提示"选项
        if (preferenceKey.isNullOrBlank()) {
            cbDontShowAgain.visibility = View.GONE
        } else {
            cbDontShowAgain.visibility = View.VISIBLE
        }

        MaterialAlertDialogBuilder(context)
            .setView(customView)
            .setPositiveButton(R.string.btn_confirm) { _, _ ->
                // 如果勾选了"不再提示"，记录到偏好设置
                if (cbDontShowAgain.isChecked && !preferenceKey.isNullOrBlank()) {
                    saveExplainedFlag(preferenceKey)
                }
                onConfirm?.invoke()
            }
            .setNegativeButton(R.string.btn_cancel) { _, _ ->
                // 即使取消，如果勾选了"不再提示"也记录
                if (cbDontShowAgain.isChecked && !preferenceKey.isNullOrBlank()) {
                    saveExplainedFlag(preferenceKey)
                }
                onCancel?.invoke()
            }
            .setCancelable(true)
            .setOnCancelListener {
                // 点击外部关闭也视为取消
                onCancel?.invoke()
            }
            .show()
    }

    /**
     * 仅在首次点击时显示弹窗
     * 如果用户已经看过说明（通过偏好设置记录），则直接执行确认回调
     */
    fun showIfNeeded() {
        if (preferenceKey.isNullOrBlank()) {
            // 没有设置 preferenceKey，每次都显示
            show()
            return
        }

        if (hasExplained(preferenceKey)) {
            // 已经解释过，直接执行确认回调
            onConfirm?.invoke()
        } else {
            // 首次点击，显示弹窗
            show()
        }
    }

    /**
     * 检查是否已经显示过说明
     */
    private fun hasExplained(key: String): Boolean {
        val prefs = context.getSharedPreferences("feature_explainer", Context.MODE_PRIVATE)
        return prefs.getBoolean(key, false)
    }

    /**
     * 保存"已解释"标记
     */
    private fun saveExplainedFlag(key: String) {
        val prefs = context.getSharedPreferences("feature_explainer", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(key, true).apply()
    }

    /**
     * Builder 类，用于构建 FeatureExplainerDialog
     */
    class Builder(private val context: Context) {
        private var title: String = ""
        private var offDescription: String = ""
        private var onDescription: String = ""
        private var currentState: Boolean = false
        private var preferenceKey: String? = null
        private var onConfirm: (() -> Unit)? = null
        private var onCancel: (() -> Unit)? = null

        /**
         * 设置弹窗标题（功能名称）
         */
        fun setTitle(title: String): Builder {
            this.title = title
            return this
        }

        /**
         * 设置弹窗标题（功能名称）
         */
        fun setTitle(@StringRes titleRes: Int): Builder {
            this.title = context.getString(titleRes)
            return this
        }

        /**
         * 设置功能关闭时的说明
         */
        fun setOffDescription(description: String): Builder {
            this.offDescription = description
            return this
        }

        /**
         * 设置功能关闭时的说明
         */
        fun setOffDescription(@StringRes descRes: Int): Builder {
            this.offDescription = context.getString(descRes)
            return this
        }

        /**
         * 设置功能开启时的说明
         */
        fun setOnDescription(description: String): Builder {
            this.onDescription = description
            return this
        }

        /**
         * 设置功能开启时的说明
         */
        fun setOnDescription(@StringRes descRes: Int): Builder {
            this.onDescription = context.getString(descRes)
            return this
        }

        /**
         * 设置当前状态（开启或关闭）
         * @param state true 表示当前已开启，false 表示当前已关闭
         */
        fun setCurrentState(state: Boolean): Builder {
            this.currentState = state
            return this
        }

        /**
         * 设置偏好设置键名，用于记录是否已提示过
         * 如果不设置，每次都会显示弹窗
         */
        fun setPreferenceKey(key: String): Builder {
            this.preferenceKey = key
            return this
        }

        /**
         * 设置用户确认后的回调
         */
        fun setOnConfirm(callback: () -> Unit): Builder {
            this.onConfirm = callback
            return this
        }

        /**
         * 设置用户取消后的回调
         */
        fun setOnCancel(callback: () -> Unit): Builder {
            this.onCancel = callback
            return this
        }

        /**
         * 构建并显示弹窗
         */
        fun show(): FeatureExplainerDialog {
            val dialog = build()
            dialog.show()
            return dialog
        }

        /**
         * 构建并在需要时显示弹窗（仅首次点击时显示）
         */
        fun showIfNeeded(): FeatureExplainerDialog {
            val dialog = build()
            dialog.showIfNeeded()
            return dialog
        }

        /**
         * 仅构建，不显示
         */
        fun build(): FeatureExplainerDialog {
            require(title.isNotBlank()) { "Title must not be blank" }
            require(offDescription.isNotBlank()) { "Off description must not be blank" }
            require(onDescription.isNotBlank()) { "On description must not be blank" }

            return FeatureExplainerDialog(
                context = context,
                title = title,
                offDescription = offDescription,
                onDescription = onDescription,
                currentState = currentState,
                preferenceKey = preferenceKey,
                onConfirm = onConfirm,
                onCancel = onCancel
            )
        }
    }
}
