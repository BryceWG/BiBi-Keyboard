package com.brycewg.asrkb.ime

/**
 * 键盘状态机：使用 sealed class 明确定义各种状态，替代布尔标志位。
 * 状态转换将变得明确且易于跟踪。
 */
sealed class KeyboardState {
    /**
     * 空闲状态：键盘就绪，等待用户操作
     */
    data object Idle : KeyboardState()

    /**
     * 普通听写模式：正在录音
     * @param partialText 当前的中间识别结果（用于实时预览）
     * @param committedStableLen 已提交的稳定长度（分段录音使用）
     */
    data class Listening(
        val partialText: String? = null,
        val committedStableLen: Int = 0,
        val lockedBySwipe: Boolean = false
    ) : KeyboardState()

    /**
     * ASR 识别处理中：录音已停止，等待最终识别结果
     */
    data object Processing : KeyboardState()

    /**
     * AI 后处理中：正在使用 LLM 优化识别结果
     * @param rawText 原始识别文本
     * @param promptTitle 本次润色实际使用的提示词名称；自动选择尚未完成时为空
     * @param selectingPrompt 正在自动选择提示词，信息条先展示选择中
     */
    data class AiProcessing(
        val rawText: String,
        val promptTitle: String? = null,
        val selectingPrompt: Boolean = false
    ) : KeyboardState()

    /**
     * AI 编辑模式：正在录音捕获编辑指令
     * @param targetIsSelection 编辑目标是否为选中文本
     * @param targetText 编辑目标文本（选中文本或最后一次 ASR 提交）
     * @param instruction 当前录音的编辑指令（部分结果）
     */
    data class AiEditListening(
        val targetIsSelection: Boolean,
        val targetText: String,
        val instruction: String? = null
    ) : KeyboardState()

    /**
     * AI 编辑执行中：正在使用 LLM 执行编辑指令
     * @param targetIsSelection 编辑目标是否为选中文本
     * @param targetText 原始文本
     * @param instruction 编辑指令
     */
    data class AiEditProcessing(
        val targetIsSelection: Boolean,
        val targetText: String,
        val instruction: String
    ) : KeyboardState()
}

/** 诊断日志使用的稳定标签，不依赖混淆后的类名。 */
internal val KeyboardState.diagnosticName: String
    get() = when (this) {
        KeyboardState.Idle -> "Idle"
        is KeyboardState.Listening -> "Listening"
        KeyboardState.Processing -> "Processing"
        is KeyboardState.AiProcessing -> "AiProcessing"
        is KeyboardState.AiEditListening -> "AiEditListening"
        is KeyboardState.AiEditProcessing -> "AiEditProcessing"
    }

/**
 * 撤销快照：记录操作前后的文本状态，用于全局撤销
 */
data class UndoSnapshot(val beforeCursor: CharSequence, val afterCursor: CharSequence)

/**
 * AI 后处理提交记录：记录原始识别结果和 AI 优化后的结果，用于撤销
 */
data class PostprocCommit(val processed: String, val raw: String, val translated: Boolean = false)

/**
 * 剪贴板预览类型：区分文本与文件。
 */
enum class ClipboardPreviewType {
    TEXT,
    FILE
}

/**
 * 剪贴板预览状态
 */
data class ClipboardPreview(
    val fullText: String,
    val displaySnippet: String,
    val type: ClipboardPreviewType = ClipboardPreviewType.TEXT,
    val fileEntryId: String? = null
)

/**
 * 键盘会话上下文：保存会话相关的数据
 */
data class KeyboardSessionContext(
    val lastAsrCommitText: String? = null,
    val lastPostprocCommit: PostprocCommit? = null,
    val undoSnapshot: UndoSnapshot? = null,
    val lastRequestDurationMs: Long? = null,
    val clipboardPreview: ClipboardPreview? = null,
    // 写入失效时待用户主动复制的结果；保留在会话上下文中，键盘重新显示后可再次展示。
    val pendingCopyResult: String? = null
)
