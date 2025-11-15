package com.brycewg.asrkb

/**
 * 应用颜色令牌层：将业务语义映射到 Material3 主题角色
 *
 * 统一管理所有 UI 组件的颜色语义，确保与动态取色（Monet）和主题系统的一致性。
 * 所有颜色应通过此令牌层访问，避免直接硬编码颜色值或分散引用主题属性。
 */
object UiColorTokens {

    // ==================== 面板与容器 ====================

    /** 面板背景色（主要容器） */
    val panelBg = com.google.android.material.R.attr.colorSurface

    /** 面板前景色（主要文本/图标） */
    val panelFg = com.google.android.material.R.attr.colorOnSurface

    /** 面板前景色（次要文本/图标） */
    val panelFgVariant = com.google.android.material.R.attr.colorOnSurfaceVariant

    /** 容器背景色（卡片、芯片等） */
    val containerBg = com.google.android.material.R.attr.colorSurfaceVariant

    /** 容器前景色 */
    val containerFg = com.google.android.material.R.attr.colorOnSurfaceVariant


    // ==================== 键盘相关 ====================

    /** 键盘按键背景 */
    val kbdKeyBg = com.google.android.material.R.attr.colorSurfaceVariant

    /** 键盘按键文本/图标 */
    val kbdKeyFg = com.google.android.material.R.attr.colorOnSurfaceVariant

    /** 键盘容器背景 */
    val kbdContainerBg = com.google.android.material.R.attr.colorSurfaceVariant


    // ==================== 强调与状态色 ====================

    /** 主强调色（主要操作按钮等） */
    val primary = android.R.attr.colorPrimary

    /** 主强调容器色 */
    val primaryContainer = com.google.android.material.R.attr.colorPrimaryContainer

    /** 主强调容器前景色 */
    val onPrimaryContainer = com.google.android.material.R.attr.colorOnPrimaryContainer

    /** 次要强调色 */
    val secondary = com.google.android.material.R.attr.colorSecondary

    /** 次要强调容器色 */
    val secondaryContainer = com.google.android.material.R.attr.colorSecondaryContainer

    /** 次要强调容器前景色 */
    val onSecondaryContainer = com.google.android.material.R.attr.colorOnSecondaryContainer

    /** 第三强调色 */
    val tertiary = com.google.android.material.R.attr.colorTertiary

    /** 错误/警告色 */
    val error = android.R.attr.colorError

    /** 错误容器色 */
    val errorContainer = com.google.android.material.R.attr.colorErrorContainer

    /** 错误容器前景色 */
    val onErrorContainer = com.google.android.material.R.attr.colorOnErrorContainer


    // ==================== 选中与高亮 ====================

    /** 选中项背景色 */
    val selectedBg = com.google.android.material.R.attr.colorSecondaryContainer

    /** 选中项前景色 */
    val selectedFg = com.google.android.material.R.attr.colorOnSecondaryContainer

    /** 波纹/高亮效果色 */
    val ripple = android.R.attr.colorControlHighlight


    // ==================== 边框与分割线 ====================

    /** 主要边框色 */
    val outline = com.google.android.material.R.attr.colorOutline

    /** 次要边框色（更淡） */
    val outlineVariant = com.google.android.material.R.attr.colorOutlineVariant


    // ==================== 悬浮球相关 ====================

    /** 悬浮球容器背景 */
    val floatingBallBg = com.google.android.material.R.attr.colorSurface

    /** 悬浮球图标色（使用次要强调色） */
    val floatingIcon = com.google.android.material.R.attr.colorSecondary

    /** 悬浮球错误状态色 */
    val floatingError = android.R.attr.colorError


    // ==================== 状态芯片 ====================

    /** 芯片背景色 */
    val chipBg = com.google.android.material.R.attr.colorSurfaceVariant

    /** 芯片文本色 */
    val chipFg = com.google.android.material.R.attr.colorOnSurfaceVariant
}
