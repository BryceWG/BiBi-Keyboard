/**
 * 设置页 Compose 布局度量参数。
 *
 * 归属模块：ui/settings/compose/core
 */
package com.brycewg.asrkb.ui.settings.compose.core

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp

internal object SettingsLayoutMetrics {
    val PageHorizontalPadding = 12.dp
    val PageVerticalPadding = 12.dp
    val SectionSpacing = 12.dp
    val DetailSectionTitleHorizontalPadding = 20.dp
    val DetailSectionTitleVerticalPadding = 8.dp
    val SectionContainerVerticalPadding = 0.dp
    val MaterialSectionItemSpacing = 2.dp
    val MaterialSectionElevation = 1.dp
    val SettingsPreferenceMinHeight = 56.dp
    val SettingsPreferenceIconSize = 24.dp
    val SettingsPreferenceTrailingMaxWidth = 140.dp
    val MaterialSectionShape = 28.dp
    val MaterialSectionInnerShape = 4.dp
    val SettingsPreferenceHorizontalPadding = 16.dp
    val SettingsPreferenceVerticalPadding = 16.dp
    val SettingsPreferenceIconSpacing = 16.dp
    val SettingsPreferenceTrailingSpacing = 16.dp
    val TextFieldCorner = 16.dp
    val TextFieldHorizontalPadding = 20.dp
    val TextFieldVerticalPadding = 6.dp
    val TextFieldLooseVerticalPadding = 8.dp
    val TextFieldEdgePadding = TextFieldHorizontalPadding
    val ActionButtonCorner = 16.dp
    val ActionButtonMinHeight = 40.dp
    val ActionButtonInsideHorizontalPadding = 16.dp
    val ActionButtonInsideVerticalPadding = 13.dp
    val ActionButtonRowHorizontalPadding = 20.dp
    val ActionButtonRowTopPadding = 4.dp
    val ActionButtonRowBottomPadding = 20.dp
    val ActionButtonSpacing = 8.dp
    val ActionButtonIconSize = 18.dp
    val ActionButtonIconSpacing = 8.dp
    val ControlLabelHorizontalPadding = SettingsPreferenceHorizontalPadding
    val ControlLabelVerticalPadding = 16.dp
    val ControlLabelSpacing = 16.dp
    val SliderHorizontalPadding = SettingsPreferenceHorizontalPadding
    val SliderBottomPadding = 8.dp
    val SliderLastItemBottomPadding = 20.dp
    val SheetHorizontalPadding = 20.dp
    val SheetBottomPadding = 16.dp
    val SheetTitleBottomPadding = 12.dp

    /** Miuix 选择器自绘标题顶部留白。 */
    val SheetDialogTitleTopPadding = 16.dp
    val SheetContentMaxHeight = 520.dp
    val SheetTopCorner = 28.dp
    val SheetActionButtonSpacing = 20.dp
    val SheetGroupHeaderHorizontalPadding = 20.dp
    val SheetGroupHeaderVerticalPadding = 8.dp
    val SheetSupportingTextHorizontalPadding = 20.dp
    val SheetSupportingTextVerticalPadding = 8.dp
    val DownloadSourceInsideVerticalPadding = 12.dp
    val DialogMaxWidth = 360.dp
    val DialogContentMaxHeight = 420.dp
    val DialogCorner = 28.dp
    val DialogActionButtonSpacing = 20.dp
    val DialogPaneHorizontalPadding = 24.dp
    val DialogContentBottomPadding = 16.dp
    val DialogProgressMaterialSpacing = 16.dp
    val DialogProgressMiuixSpacing = 12.dp
    val FeatureExplainerSectionSpacing = 12.dp
    val FeatureExplainerDontShowSpacing = 16.dp
    val FeatureExplainerIconVerticalPadding = 4.dp
    val FeatureExplainerLabelSpacing = 8.dp
    val TimingBarHeight = 10.dp
    val TimingBarCorner = 6.dp
    val TimingLegendSwatchSize = 10.dp
    val ChoiceTagTopPadding = 6.dp
    val ChoiceTagSpacing = 6.dp
    val ChoiceTagHorizontalPadding = 8.dp
    val ChoiceTagVerticalPadding = 2.dp
    val ProDialogElevation = 6.dp
    val ProDialogContentPadding = 24.dp
    val ProDialogTinySpacing = 4.dp
    val ProDialogSmallSpacing = 8.dp
    val ProDialogMediumSpacing = 16.dp
    val ProDialogSectionSpacing = 20.dp
    val ProDialogActionSpacing = 8.dp
    val PaymentQrRowSpacing = 16.dp
    val PaymentQrImageSize = 132.dp
    val PaymentQrCardCorner = 18.dp
    val PaymentQrImagePadding = 6.dp
    val PaymentEmailCardCorner = 20.dp
    val PaymentEmailPadding = 12.dp
    val PaymentSaveIconSize = 16.dp
    val SearchTopPadding = 12.dp
    val SearchListVerticalPadding = 12.dp
    val SearchResultSpacing = 12.dp
    val SearchResultContentPadding = 16.dp
    val SearchResultTextSpacing = 4.dp
    val SearchEmptyHorizontalPadding = 20.dp
    val SearchResultElevation = 1.dp

    /** Material3 SearchBar 风格首页入口高度与圆角。 */
    val HomeSearchBarMinHeight = 56.dp
    val HomeSearchBarCorner = MaterialSectionShape
    val HomeSearchBarElevation = MaterialSectionElevation
    val HomeSearchBarHorizontalPadding = 16.dp
    val HomeSearchBarIconSpacing = 12.dp
    val BackupSectionContentPadding = 16.dp
    val BackupSectionContentSpacing = 12.dp
    val AboutSectionContentTopPadding = 20.dp
    val AboutSectionContentBottomPadding = AboutSectionContentTopPadding
    val BottomBarElevation = 3.dp
    val TopBarElevation = BottomBarElevation
    val BottomBarTopCorner = 28.dp
    val BottomBarMinHeight = 64.dp

    val PageContentPadding: PaddingValues
        get() = PaddingValues(
            horizontal = PageHorizontalPadding,
            vertical = PageVerticalPadding
        )

    @Composable
    fun pageContentPadding(scaffoldPadding: PaddingValues): PaddingValues {
        val layoutDirection = LocalLayoutDirection.current
        return PaddingValues(
            start = scaffoldPadding.calculateStartPadding(layoutDirection) + PageHorizontalPadding,
            top = scaffoldPadding.calculateTopPadding() + PageVerticalPadding,
            end = scaffoldPadding.calculateEndPadding(layoutDirection) + PageHorizontalPadding,
            bottom = scaffoldPadding.calculateBottomPadding() + PageVerticalPadding
        )
    }
}
