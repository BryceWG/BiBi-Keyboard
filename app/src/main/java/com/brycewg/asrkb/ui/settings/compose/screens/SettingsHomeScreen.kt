/**
 * 设置首页三 Tab Compose 页面。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Help
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ime.AsrKeyboardService
import com.brycewg.asrkb.store.ApiLogStore
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.AsrVendorUi
import com.brycewg.asrkb.ui.floating.floatingInputNeedsAccessibility
import com.brycewg.asrkb.ui.settings.compose.components.SettingsHomeSearchEntry
import com.brycewg.asrkb.ui.settings.compose.core.BibiSettingsRoute
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsActionController
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import com.brycewg.asrkb.ui.settings.compose.model.SettingsEntry
import com.brycewg.asrkb.ui.settings.compose.model.SettingsSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SettingsHomeScreen(
    selectedTab: Int,
    uiMode: BibiUiMode,
    hasUpdateAvailable: Boolean,
    onSelectTab: (Int) -> Unit,
    onPushRoute: (BibiSettingsRoute) -> Unit,
    actions: SettingsActionController
) {
    val tabs = remember { settingsHomeTabs() }
    val context = LocalContext.current
    val appContext = context.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember(appContext) { Prefs(appContext) }
    var homeSnapshot by remember(appContext) {
        mutableStateOf(SettingsHomeSnapshot.placeholder(context))
    }
    var hasRecentApiErrors by remember { mutableStateOf(false) }
    var showProPromo by remember { mutableStateOf(!prefs.proPromoShown) }
    var refreshToken by remember { mutableStateOf(0) }
    val pagerState = rememberPagerState(
        initialPage = selectedTab,
        pageCount = { tabs.size }
    )
    val homePagerState = rememberSettingsHomePagerState(pagerState)

    LaunchedEffect(selectedTab) {
        if (pagerState.currentPage == selectedTab) return@LaunchedEffect
        // 点击 Tab 时 animateToPage 已经在跑，这里不要再抢一次滚动。
        if (homePagerState.isNavigating && homePagerState.selectedPage == selectedTab) return@LaunchedEffect
        // 布局完成前 pageSize 为 0，这时滚动不会改页，返回后会停在旧 Tab。
        snapshotFlow { pagerState.layoutInfo.pageSize }.first { it > 0 }
        if (pagerState.currentPage != selectedTab) {
            pagerState.scrollToPage(selectedTab)
        }
        homePagerState.syncPage()
    }
    LaunchedEffect(pagerState.settledPage) {
        if (homePagerState.isNavigating) return@LaunchedEffect
        val settled = pagerState.settledPage
        if (settled != selectedTab) {
            // 指示器还停在旧页、selectedTab 已经指向目标页时，是外部跳转还没跟上，不能写回。
            // 用户滑动时指示器仍等于 selectedTab，pager 先离开，这时才跟随。
            if (homePagerState.selectedPage != selectedTab) return@LaunchedEffect
            homePagerState.syncPage()
            onSelectTab(settled)
            return@LaunchedEffect
        }
        homePagerState.syncPage()
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshToken++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(refreshToken) {
        showProPromo = !prefs.proPromoShown
        val loaded = withContext(Dispatchers.IO) {
            SettingsHomeLoadedState(
                snapshot = SettingsHomeSnapshot.fromPrefs(context, prefs),
                hasRecentApiErrors = hasRecentApiLogErrors()
            )
        }
        if (homeSnapshot != loaded.snapshot) {
            homeSnapshot = loaded.snapshot
        }
        if (hasRecentApiErrors != loaded.hasRecentApiErrors) {
            hasRecentApiErrors = loaded.hasRecentApiErrors
        }
    }

    val inputPageSections = remember(homeSnapshot, actions, onPushRoute) {
        inputSections(homeSnapshot, actions, onPushRoute)
    }
    val apiErrorsSummary = if (hasRecentApiErrors) {
        stringResource(R.string.home_summary_api_log_errors)
    } else {
        null
    }
    val smartPageSections = remember(homeSnapshot, apiErrorsSummary, actions, onPushRoute) {
        smartSections(
            snapshot = homeSnapshot,
            apiErrorsSummary = apiErrorsSummary,
            actions = actions,
            onPushRoute = onPushRoute
        )
    }
    val updateAvailableSummary = if (homeSnapshot.autoUpdateCheckEnabled && hasUpdateAvailable) {
        stringResource(R.string.home_summary_update_available)
    } else {
        null
    }
    val updatesEnabled = actions.updatesEnabled
    val systemPageSections = remember(
        updateAvailableSummary,
        updatesEnabled,
        actions,
        onPushRoute
    ) {
        systemSections(
            updateAvailableSummary = updateAvailableSummary,
            updatesEnabled = updatesEnabled,
            actions = actions,
            onPushRoute = onPushRoute
        )
    }

    SettingsHomeScaffold(
        uiMode = uiMode,
        tabs = tabs,
        selectedTab = homePagerState.selectedPage,
        onSelectTab = { page ->
            homePagerState.animateToPage(page)
            onSelectTab(page)
        }
    ) { innerPadding, scrollModifier ->
        val layoutDirection = LocalLayoutDirection.current
        val searchBarPadding = Modifier.padding(
            start = innerPadding.calculateStartPadding(layoutDirection) +
                SettingsLayoutMetrics.PageHorizontalPadding,
            top = innerPadding.calculateTopPadding() + SettingsLayoutMetrics.SearchTopPadding,
            end = innerPadding.calculateEndPadding(layoutDirection) +
                SettingsLayoutMetrics.PageHorizontalPadding
        )
        val listContentPadding = PaddingValues(
            start = innerPadding.calculateStartPadding(layoutDirection) +
                SettingsLayoutMetrics.PageHorizontalPadding,
            top = SettingsLayoutMetrics.PageVerticalPadding,
            end = innerPadding.calculateEndPadding(layoutDirection) +
                SettingsLayoutMetrics.PageHorizontalPadding,
            bottom = innerPadding.calculateBottomPadding() + SettingsLayoutMetrics.PageVerticalPadding
        )
        Column(modifier = Modifier.fillMaxSize()) {
            SettingsHomeSearchEntry(
                uiMode = uiMode,
                onClick = { onPushRoute(BibiSettingsRoute.Search) },
                modifier = searchBarPadding.fillMaxWidth()
            )
            if (showProPromo) {
                ProPromoHomeCard(
                    uiMode = uiMode,
                    onOpen = {
                        prefs.proPromoShown = true
                        showProPromo = false
                        onPushRoute(BibiSettingsRoute.Paywall)
                    },
                    onDismiss = {
                        prefs.proPromoShown = true
                        showProPromo = false
                    }
                )
            }
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 0,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { page ->
                when (page) {
                    0 -> SettingsSectionList(
                        sections = inputPageSections,
                        uiMode = uiMode,
                        modifier = scrollModifier,
                        contentPadding = listContentPadding
                    )

                    1 -> SettingsSectionList(
                        sections = smartPageSections,
                        uiMode = uiMode,
                        modifier = scrollModifier,
                        contentPadding = listContentPadding
                    )

                    else -> SettingsSectionList(
                        sections = systemPageSections,
                        uiMode = uiMode,
                        modifier = scrollModifier,
                        contentPadding = listContentPadding
                    )
                }
            }
        }
    }
}

@Composable
private fun ProPromoHomeCard(uiMode: BibiUiMode, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val modifier = Modifier
        .fillMaxWidth()
        .padding(
            start = SettingsLayoutMetrics.PageHorizontalPadding,
            top = SettingsLayoutMetrics.PageVerticalPadding,
            end = SettingsLayoutMetrics.PageHorizontalPadding
        )
    when (uiMode) {
        BibiUiMode.Material -> Card(
            onClick = onOpen,
            modifier = modifier,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            ProPromoHomeCardContent(uiMode, onDismiss)
        }

        BibiUiMode.Miuix -> MiuixCard(modifier = modifier) {
            ProPromoHomeCardContent(uiMode, onDismiss, onOpen)
        }
    }
}

@Composable
private fun ProPromoHomeCardContent(
    uiMode: BibiUiMode,
    onDismiss: () -> Unit,
    onOpen: (() -> Unit)? = null
) {
    val dismissLabel = stringResource(R.string.pro_paywall_home_dismiss)
    Row(
        modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            when (uiMode) {
                BibiUiMode.Material -> Icon(Icons.Rounded.WorkspacePremium, contentDescription = null)
                BibiUiMode.Miuix -> MiuixIcon(Icons.Rounded.WorkspacePremium, contentDescription = null)
            }
            Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                when (uiMode) {
                    BibiUiMode.Material -> {
                        Text(
                            stringResource(R.string.pro_paywall_home_title),
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                        )
                        Text(
                            stringResource(R.string.pro_paywall_home_summary),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    BibiUiMode.Miuix -> {
                        MiuixText(
                            stringResource(R.string.pro_paywall_home_title),
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                        )
                        MiuixText(
                            stringResource(R.string.pro_paywall_home_summary),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.footnote1
                        )
                    }
                }
            }
        }
        when (uiMode) {
            BibiUiMode.Material -> IconButton(onClick = onDismiss) {
                Icon(Icons.Rounded.Close, contentDescription = dismissLabel)
            }

            BibiUiMode.Miuix -> MiuixIconButton(onClick = onDismiss) {
                MiuixIcon(Icons.Rounded.Close, contentDescription = dismissLabel)
            }
        }
    }
}

private fun inputSections(
    snapshot: SettingsHomeSnapshot,
    actions: SettingsActionController,
    onPushRoute: (BibiSettingsRoute) -> Unit
): List<SettingsSection> = listOf(
    SettingsSection(
        id = "input_quick",
        entries = listOf(
            SettingsEntry.Action(
                id = "one_click_setup",
                titleRes = R.string.btn_one_click_setup,
                summary = snapshot.oneClickSetupSummary,
                icon = Icons.Rounded.RocketLaunch,
                onClick = actions::startOneClickSetup
            ),
            SettingsEntry.Action(
                id = "test_input",
                titleRes = R.string.btn_test_input,
                icon = Icons.Rounded.TextFields,
                onClick = actions::showTestInput
            ),
            SettingsEntry.Action(
                id = "recording_test",
                titleRes = R.string.title_recording_test,
                icon = Icons.Rounded.Mic,
                onClick = {
                    onPushRoute(BibiSettingsRoute.RecordingTest)
                }
            ),
            SettingsEntry.Action(
                id = "input_settings",
                titleRes = R.string.title_input_settings,
                summary = snapshot.inputControlSummary,
                icon = Icons.Rounded.Keyboard,
                onClick = { onPushRoute(BibiSettingsRoute.Input) }
            ),
            SettingsEntry.Action(
                id = "ui_settings",
                titleRes = R.string.section_ui_settings,
                icon = Icons.Rounded.Dashboard,
                onClick = { onPushRoute(BibiSettingsRoute.UiSettings) }
            ),
            SettingsEntry.Action(
                id = "floating_settings",
                titleRes = R.string.title_floating_settings,
                summary = snapshot.floatingSummary,
                icon = Icons.Rounded.TouchApp,
                onClick = { onPushRoute(BibiSettingsRoute.Floating) }
            )
        )
    )
)

private fun smartSections(
    snapshot: SettingsHomeSnapshot,
    apiErrorsSummary: String?,
    actions: SettingsActionController,
    onPushRoute: (BibiSettingsRoute) -> Unit
): List<SettingsSection> = listOf(
    SettingsSection(
        id = "smart_main",
        entries = listOf(
            SettingsEntry.Action(
                id = "asr_settings",
                titleRes = R.string.title_asr_settings,
                summary = snapshot.asrSummary,
                icon = Icons.Rounded.Mic,
                onClick = { onPushRoute(BibiSettingsRoute.Asr) }
            ),
            SettingsEntry.Action(
                id = "ai_settings",
                titleRes = R.string.title_ai_settings,
                summary = snapshot.aiSummary,
                icon = Icons.Rounded.AutoAwesome,
                onClick = { onPushRoute(BibiSettingsRoute.Ai) }
            ),
            SettingsEntry.Action(
                id = "asr_history",
                titleRes = R.string.btn_open_asr_history,
                summary = apiErrorsSummary,
                icon = Icons.Rounded.History,
                onClick = {
                    onPushRoute(BibiSettingsRoute.History)
                }
            )
        )
    )
)

private fun systemSections(
    updateAvailableSummary: String?,
    updatesEnabled: Boolean,
    actions: SettingsActionController,
    onPushRoute: (BibiSettingsRoute) -> Unit
): List<SettingsSection> = listOf(
    SettingsSection(
        id = "system_more",
        entries = listOf(
            SettingsEntry.Action(
                id = "backup_settings",
                titleRes = R.string.btn_open_backup_settings,
                icon = Icons.Rounded.Backup,
                onClick = { onPushRoute(BibiSettingsRoute.Backup) }
            ),
            SettingsEntry.Action(
                id = "other_settings",
                titleRes = R.string.title_other_settings,
                icon = Icons.Rounded.MoreHoriz,
                onClick = { onPushRoute(BibiSettingsRoute.Other) }
            ),
            SettingsEntry.Action(
                id = "about_stats_title",
                titleRes = R.string.about_stats_title,
                icon = Icons.Rounded.BarChart,
                onClick = { onPushRoute(BibiSettingsRoute.UsageStats) }
            ),
            SettingsEntry.Action(
                id = "check_update",
                titleRes = R.string.btn_check_update,
                icon = Icons.Rounded.SystemUpdate,
                enabled = updatesEnabled,
                onClick = actions::checkForUpdates
            ),
            SettingsEntry.Action(
                id = "guide",
                titleRes = R.string.btn_show_guide,
                icon = Icons.AutoMirrored.Rounded.Help,
                onClick = actions::openOnboardingGuide
            ),
            SettingsEntry.Action(
                id = "about",
                titleRes = R.string.btn_about,
                summary = updateAvailableSummary,
                icon = Icons.Rounded.Info,
                onClick = { onPushRoute(BibiSettingsRoute.About) }
            )
        )
    )
)

private data class SettingsHomeSnapshot(
    val oneClickSetupSummary: String,
    val inputControlSummary: String,
    val floatingSummary: String,
    val asrSummary: String,
    val aiSummary: String,
    val autoUpdateCheckEnabled: Boolean
) {
    companion object {
        fun placeholder(context: Context): SettingsHomeSnapshot = SettingsHomeSnapshot(
            oneClickSetupSummary = "",
            inputControlSummary = "",
            floatingSummary = context.getString(R.string.home_summary_more_input_disabled),
            asrSummary = "",
            aiSummary = "",
            autoUpdateCheckEnabled = false
        )

        fun fromPrefs(context: Context, prefs: Prefs): SettingsHomeSnapshot {
            val floatingEnabled = prefs.floatingAsrEnabled
            val volumeKeyEnabled = prefs.volumeKeyRecordingEnabled
            val shakeRecordingEnabled = prefs.shakeRecordingEnabled
            val imeBridgeEnabled = prefs.floatingImeBridgeEnabled
            val accessibilityMissing = floatingInputNeedsAccessibility(
                floatingEnabled = floatingEnabled,
                volumeKeyEnabled = volumeKeyEnabled,
                imeBridgeEnabled = imeBridgeEnabled,
                shakeRecordingEnabled = shakeRecordingEnabled
            ) &&
                !isAccessibilityServiceEnabled(context)
            return SettingsHomeSnapshot(
                oneClickSetupSummary = oneClickSetupSummary(context, prefs),
                inputControlSummary = inputControlSummary(context, prefs),
                floatingSummary = moreInputSummary(
                    context = context,
                    floatingEnabled = floatingEnabled,
                    volumeKeyEnabled = volumeKeyEnabled,
                    shakeRecordingEnabled = shakeRecordingEnabled,
                    accessibilityMissing = accessibilityMissing
                ),
                asrSummary = asrSummary(context, prefs),
                aiSummary = aiSummary(context, prefs),
                autoUpdateCheckEnabled = prefs.autoUpdateCheckEnabled
            )
        }
    }
}

private data class SettingsHomeLoadedState(
    val snapshot: SettingsHomeSnapshot,
    val hasRecentApiErrors: Boolean
)

private fun enabledSummary(context: Context, enabled: Boolean): String = context.getString(if (enabled) R.string.home_summary_enabled else R.string.home_summary_disabled)

private fun moreInputSummary(
    context: Context,
    floatingEnabled: Boolean,
    volumeKeyEnabled: Boolean,
    shakeRecordingEnabled: Boolean,
    accessibilityMissing: Boolean
): String = context.getString(
    when {
        accessibilityMissing -> R.string.home_summary_more_input_accessibility_missing
        floatingEnabled && (volumeKeyEnabled || shakeRecordingEnabled) -> R.string.home_summary_more_input_both_enabled
        shakeRecordingEnabled -> R.string.home_summary_more_input_shake_enabled
        volumeKeyEnabled -> R.string.home_summary_more_input_volume_key_enabled
        floatingEnabled -> R.string.home_summary_more_input_floating_enabled
        else -> R.string.home_summary_more_input_disabled
    }
)

private fun inputControlSummary(context: Context, prefs: Prefs): String = context.getString(
    if (prefs.micTapToggleEnabled) {
        R.string.home_summary_input_tap_control
    } else {
        R.string.home_summary_input_hold_control
    }
)

private fun asrSummary(context: Context, prefs: Prefs): String = context.getString(
    R.string.home_summary_asr_format,
    enabledSummary(context, prefs.recordingAutoStopMode != Prefs.RecordingAutoStopMode.MANUAL),
    AsrVendorUi.name(context, prefs.asrVendor)
)

private fun aiSummary(context: Context, prefs: Prefs): String {
    val vendor = prefs.llmVendor
    val vendorName = if (vendor == com.brycewg.asrkb.asr.LlmVendor.CUSTOM) {
        prefs.getActiveLlmProvider()?.name?.takeIf { it.isNotBlank() }
            ?: context.getString(vendor.displayNameResId)
    } else {
        context.getString(vendor.displayNameResId)
    }
    val promptName = activePromptPresetTitle(prefs)
    return context.getString(
        R.string.home_summary_ai_format,
        enabledSummary(context, prefs.postProcessEnabled),
        vendorName,
        promptName
    )
}

private fun activePromptPresetTitle(prefs: Prefs): String {
    val presets = prefs.getPromptPresets()
    val activeId = prefs.activePromptId
    return presets.firstOrNull { it.id == activeId }?.title
        ?: presets.firstOrNull()?.title
        ?: ""
}

private fun oneClickSetupSummary(context: Context, prefs: Prefs): String {
    val floatingEnabled = prefs.floatingAsrEnabled
    val volumeKeyEnabled = prefs.volumeKeyRecordingEnabled
    val shakeRecordingEnabled = prefs.shakeRecordingEnabled
    val imeBridgeEnabled = prefs.floatingImeBridgeEnabled
    val checks = buildList {
        add(isOurImeEnabled(context))
        add(isOurImeCurrent(context))
        add(hasMicrophonePermission(context))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(hasNotificationPermission(context))
        }
        if (floatingEnabled) {
            add(Settings.canDrawOverlays(context))
        }
        if (floatingInputNeedsAccessibility(
                floatingEnabled = floatingEnabled,
                volumeKeyEnabled = volumeKeyEnabled,
                imeBridgeEnabled = imeBridgeEnabled,
                shakeRecordingEnabled = shakeRecordingEnabled
            )
        ) {
            add(isAccessibilityServiceEnabled(context))
        }
    }
    val done = checks.count { it }
    return if (done == checks.size) {
        context.getString(R.string.home_summary_setup_done)
    } else {
        context.getString(R.string.home_summary_setup_progress, done, checks.size)
    }
}

private fun hasRecentApiLogErrors(): Boolean = ApiLogStore.listAll()
    .take(10)
    .any { !it.success && !it.canceled }

private fun hasMicrophonePermission(context: Context): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun hasNotificationPermission(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
} else {
    true
}

private fun isOurImeEnabled(context: Context): Boolean {
    val imm = try {
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    } catch (_: Exception) {
        return false
    }
    val enabledList = try {
        imm.enabledInputMethodList
    } catch (_: Exception) {
        null
    }
    if (enabledList?.any { it.packageName == context.packageName } == true) return true
    return try {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_INPUT_METHODS
        )
        val ids = ourImeIdCandidates(context)
        ids.any { enabled?.contains(it) == true } ||
            (enabled?.split(':')?.any { it.startsWith(context.packageName) } == true)
    } catch (_: Exception) {
        false
    }
}

private fun isOurImeCurrent(context: Context): Boolean = try {
    val current = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.DEFAULT_INPUT_METHOD
    )
    current != null && ourImeIdCandidates(context).contains(current)
} catch (_: Exception) {
    false
}

private fun ourImeIdCandidates(context: Context): Set<String> {
    val component = ComponentName(context, AsrKeyboardService::class.java)
    return setOf(component.flattenToShortString(), component.flattenToString())
}
