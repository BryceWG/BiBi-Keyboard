/**
 * ASR 识别历史 Compose 页面。
 *
 * 归属模块：ui/history/compose/history
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.history.compose.history

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.LocaleHelper
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrRecordedAudioRouteDecision
import com.brycewg.asrkb.asr.AsrRecordedAudioRouteKind
import com.brycewg.asrkb.asr.AsrRecordedAudioRouteResolver
import com.brycewg.asrkb.asr.LlmVendor
import com.brycewg.asrkb.store.AsrHistoryStore
import com.brycewg.asrkb.store.AsrHistoryTimingOrigin
import com.brycewg.asrkb.store.AsrHistoryTimingStage
import com.brycewg.asrkb.store.JevClassifierProvider
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.PromptSelectionFailReason
import com.brycewg.asrkb.store.PromptSelectionStatus
import com.brycewg.asrkb.ui.history.AsrHistoryFailDisplay
import com.brycewg.asrkb.ui.history.AsrHistoryRerunErrorMessages
import com.brycewg.asrkb.ui.settings.compose.components.MaterialSettingsAlertDialog
import com.brycewg.asrkb.ui.settings.compose.components.MaterialSettingsDialogAction
import com.brycewg.asrkb.ui.settings.compose.components.MaterialSettingsDialogButtonRow
import com.brycewg.asrkb.ui.settings.compose.components.MaterialSettingsDialogExitEffect
import com.brycewg.asrkb.ui.settings.compose.components.SettingsAssistChip
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDialogAction
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDialogActionRow
import com.brycewg.asrkb.ui.settings.compose.components.SettingsFilterChip
import com.brycewg.asrkb.ui.settings.compose.components.SettingsNoticeDialog
import com.brycewg.asrkb.ui.settings.compose.components.SettingsNoticeDialogState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsSearchField
import com.brycewg.asrkb.ui.settings.compose.components.TimingBarInterval
import com.brycewg.asrkb.ui.settings.compose.components.TimingIntervalBar
import com.brycewg.asrkb.ui.settings.compose.components.TimingLegendRow
import com.brycewg.asrkb.ui.settings.compose.components.animateSettingsDialogExitAlpha
import com.brycewg.asrkb.ui.settings.compose.components.hasFeatureExplainerFlag
import com.brycewg.asrkb.ui.settings.compose.components.rememberSettingsDialogExitController
import com.brycewg.asrkb.ui.settings.compose.components.saveFeatureExplainerFlag
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.CardDefaults as MiuixCardDefaults
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AsrHistoryScreen(
    uiMode: BibiUiMode,
    records: List<AsrHistoryStore.AsrHistoryRecord>,
    query: String,
    filterState: HistoryFilterState,
    selectedIds: Set<String>,
    displayLimit: Int,
    pageSize: Int,
    vendorOptions: List<HistoryVendorOption>,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onFilterChange: (HistoryFilterState) -> Unit,
    onSelectionChange: (Set<String>) -> Unit,
    onSelectAll: (Set<String>) -> Unit,
    onClearSelection: () -> Unit,
    onLoadMore: () -> Unit,
    onCopy: (String) -> Unit,
    audioRecordIds: Set<String>,
    llmAvailable: Boolean,
    onReRecognize: suspend (AsrHistoryStore.AsrHistoryRecord) -> AsrHistoryStore.AsrHistoryRecord,
    onReprocess: suspend (AsrHistoryStore.AsrHistoryRecord) -> AsrHistoryStore.AsrHistoryRecord,
    onRecordUpdated: (AsrHistoryStore.AsrHistoryRecord) -> Unit,
    onDeleteSelected: (Set<String>) -> Unit,
    onOpenApiLog: () -> Unit,
    hasRecentApiErrors: Boolean,
    onHapticTap: () -> Unit
) {
    val context = LocalContext.current
    val filteredRecords = remember(records, query, filterState, context) {
        filterHistoryRecords(
            records,
            query,
            filterState,
            failDisplayText = { AsrHistoryFailDisplay.format(context, it) }
        )
    }
    val filteredIds = remember(filteredRecords) { filteredRecords.map { it.id }.toSet() }
    val selectedVisibleIds = remember(selectedIds, filteredIds) { selectedIds.intersect(filteredIds) }
    val isSearching = query.trim().isNotEmpty()
    val visibleRecords = remember(filteredRecords, displayLimit, isSearching) {
        if (isSearching) {
            filteredRecords
        } else {
            filteredRecords.take(displayLimit.coerceAtLeast(pageSize))
        }
    }
    val rows = remember(visibleRecords, selectedVisibleIds) {
        buildHistoryRows(visibleRecords, selectedVisibleIds)
    }
    val hasMore = !isSearching && visibleRecords.size < filteredRecords.size
    val listState = rememberLazyListState()
    var showFilterDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var selectedRecord by remember { mutableStateOf<AsrHistoryStore.AsrHistoryRecord?>(null) }
    var rerunJob by remember { mutableStateOf<Job?>(null) }
    var rerunError by remember { mutableStateOf<String?>(null) }
    var rerecognitionNotice by remember { mutableStateOf<SettingsNoticeDialogState?>(null) }
    val prefs = remember(context.applicationContext) { Prefs(context.applicationContext) }
    val scope = rememberCoroutineScope()

    fun launchReRecognize(target: AsrHistoryStore.AsrHistoryRecord) {
        rerunError = null
        rerunJob = scope.launch {
            runCatching { onReRecognize(target) }
                .onSuccess {
                    selectedRecord = it
                    onRecordUpdated(it)
                }
                .onFailure { rerunError = it.message ?: "rerun_failed" }
        }
    }

    fun requestReRecognize(target: AsrHistoryStore.AsrHistoryRecord) {
        if (rerunJob?.isActive == true || rerecognitionNotice != null) return
        val decision = AsrRecordedAudioRouteResolver.resolve(context, prefs)
        val noticeKey = decision.noticeKey.takeIf { it.isNotBlank() }
        if (noticeKey != null && context.hasFeatureExplainerFlag(noticeKey)) {
            if (decision.canContinue) {
                launchReRecognize(target)
            } else {
                rerunError = decision.reasonCode
            }
            return
        }
        rerecognitionNotice = historyRerecognitionNoticeState(
            context = context,
            decision = decision,
            noticeKey = noticeKey,
            onContinue = { launchReRecognize(target) }
        )
    }

    LaunchedEffect(filteredIds, selectedIds) {
        if (selectedVisibleIds != selectedIds) {
            onSelectionChange(selectedVisibleIds)
        }
    }

    LaunchedEffect(listState, hasMore, rows.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (hasMore && lastVisible >= (rows.size - 4).coerceAtLeast(0)) {
                    onLoadMore()
                }
            }
    }

    HistoryScaffold(
        uiMode = uiMode,
        onBack = onBack,
        onOpenApiLog = {
            onHapticTap()
            onOpenApiLog()
        },
        hasRecentApiErrors = hasRecentApiErrors
    ) { innerPadding, scrollModifier ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(horizontal = SettingsLayoutMetrics.PageHorizontalPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = dimensionResource(R.dimen.settings_form_max_width))
            ) {
                HistoryActionBar(
                    uiMode = uiMode,
                    selectedCount = selectedVisibleIds.size,
                    hasData = filteredRecords.isNotEmpty(),
                    filterActive = filterState.vendorIds.isNotEmpty() ||
                        filterState.sources.isNotEmpty() ||
                        filterState.timeFilter != TimeFilter.ALL,
                    onFilter = { showFilterDialog = true },
                    onSelectAll = { onSelectAll(filteredIds) },
                    onClearSelection = onClearSelection,
                    onDeleteSelected = { showDeleteDialog = true }
                )
                HistorySearchField(
                    value = query,
                    onValueChange = onQueryChange,
                    uiMode = uiMode
                )
                if (rows.isEmpty()) {
                    EmptyHistoryState(uiMode = uiMode)
                } else {
                    HistoryList(
                        rows = rows,
                        uiMode = uiMode,
                        vendorOptions = vendorOptions,
                        selectedCount = selectedVisibleIds.size,
                        listState = listState,
                        scrollModifier = scrollModifier,
                        onToggleSelection = { id ->
                            onHapticTap()
                            onSelectionChange(toggleId(selectedVisibleIds, id))
                        },
                        onCopy = {
                            onHapticTap()
                            onCopy(it)
                        },
                        onOpenDetails = { selectedRecord = it }
                    )
                }
            }
        }
    }

    if (showFilterDialog) {
        HistoryFilterDialog(
            uiMode = uiMode,
            vendorOptions = vendorOptions,
            filterState = filterState,
            onDismiss = { showFilterDialog = false },
            onApply = {
                showFilterDialog = false
                onFilterChange(it)
            },
            onReset = {
                showFilterDialog = false
                onFilterChange(HistoryFilterState())
            }
        )
    }
    if (showDeleteDialog) {
        DeleteSelectedDialog(
            uiMode = uiMode,
            selectedCount = selectedVisibleIds.size,
            onDismiss = { showDeleteDialog = false },
            onConfirm = {
                showDeleteDialog = false
                onDeleteSelected(selectedVisibleIds)
            }
        )
    }
    selectedRecord?.let { record ->
        HistoryDetailsDialog(
            record = record,
            uiMode = uiMode,
            hasAudio = record.id in audioRecordIds,
            llmAvailable = llmAvailable,
            working = rerunJob?.isActive == true,
            error = rerunError,
            onDismissStarted = {
                rerunJob?.cancel()
                rerunJob = null
                rerunError = null
                rerecognitionNotice = null
            },
            onDismiss = {
                selectedRecord = null
            },
            onCopy = { onCopy(it) },
            onReRecognize = { requestReRecognize(record) },
            onReprocess = {
                rerunError = null
                rerunJob = scope.launch {
                    runCatching { onReprocess(record) }
                        .onSuccess {
                            selectedRecord = it
                            onRecordUpdated(it)
                        }
                        .onFailure { rerunError = it.message ?: "postprocess_failed" }
                }
            }
        )
    }
    SettingsNoticeDialog(
        state = rerecognitionNotice,
        uiMode = uiMode,
        onDismiss = { rerecognitionNotice = null }
    )
}

@Composable
private fun HistoryScaffold(
    uiMode: BibiUiMode,
    onBack: () -> Unit,
    onOpenApiLog: () -> Unit,
    hasRecentApiErrors: Boolean,
    content: @Composable (PaddingValues, Modifier) -> Unit
) {
    val apiLogLabel = stringResource(R.string.menu_api_log)
    SettingsDetailScaffold(
        uiMode = uiMode,
        titleRes = R.string.title_asr_history,
        onBack = onBack,
        actions = {
            when (uiMode) {
                BibiUiMode.Material -> IconButton(onClick = onOpenApiLog) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Article,
                        contentDescription = apiLogLabel,
                        tint = if (hasRecentApiErrors) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                BibiUiMode.Miuix -> MiuixIconButton(onClick = onOpenApiLog) {
                    MiuixIcon(
                        Icons.AutoMirrored.Rounded.Article,
                        contentDescription = apiLogLabel,
                        tint = if (hasRecentApiErrors) {
                            MiuixTheme.colorScheme.error
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantActions
                        }
                    )
                }
            }
        },
        content = content
    )
}

@Composable
private fun HistoryActionBar(
    uiMode: BibiUiMode,
    selectedCount: Int,
    hasData: Boolean,
    filterActive: Boolean,
    onFilter: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsFilterChip(
            uiMode = uiMode,
            label = stringResource(R.string.menu_filter),
            selected = filterActive,
            onClick = onFilter
        )
        if (selectedCount == 0 && hasData) {
            SettingsFilterChip(
                uiMode = uiMode,
                label = stringResource(R.string.menu_select_all),
                selected = false,
                onClick = onSelectAll
            )
        }
        if (selectedCount > 0) {
            SettingsFilterChip(
                uiMode = uiMode,
                label = stringResource(R.string.menu_clear_selection),
                selected = false,
                onClick = onClearSelection
            )
            SettingsFilterChip(
                uiMode = uiMode,
                label = stringResource(R.string.menu_delete_selected),
                selected = false,
                onClick = onDeleteSelected
            )
            SettingsAssistChip(
                uiMode = uiMode,
                label = selectedCount.toString()
            )
        }
    }
}

@Composable
private fun HistorySearchField(
    value: String,
    onValueChange: (String) -> Unit,
    uiMode: BibiUiMode
) {
    SettingsSearchField(
        value = value,
        onValueChange = onValueChange,
        label = stringResource(R.string.hint_search_history),
        uiMode = uiMode,
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
    )
}

@Composable
private fun HistoryList(
    rows: List<HistoryRow>,
    uiMode: BibiUiMode,
    vendorOptions: List<HistoryVendorOption>,
    selectedCount: Int,
    listState: androidx.compose.foundation.lazy.LazyListState,
    scrollModifier: Modifier,
    onToggleSelection: (String) -> Unit,
    onCopy: (String) -> Unit,
    onOpenDetails: (AsrHistoryStore.AsrHistoryRecord) -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .then(scrollModifier),
        contentPadding = PaddingValues(top = 12.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(
            items = rows,
            key = { row ->
                when (row) {
                    is HistoryRow.Header -> "header-${row.section.name}"
                    is HistoryRow.Item -> row.record.id
                }
            }
        ) { row ->
            when (row) {
                is HistoryRow.Header -> SectionHeader(section = row.section, uiMode = uiMode)
                is HistoryRow.Item -> HistoryItemCard(
                    row = row,
                    uiMode = uiMode,
                    vendorOptions = vendorOptions,
                    selectedCount = selectedCount,
                    onToggleSelection = onToggleSelection,
                    onCopy = onCopy,
                    onOpenDetails = onOpenDetails
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(section: HistorySection, uiMode: BibiUiMode) {
    val title = when (section) {
        HistorySection.WITHIN_2H -> stringResource(R.string.history_section_2h)
        HistorySection.TODAY -> stringResource(R.string.history_section_today)
        HistorySection.LAST_7D -> stringResource(R.string.history_section_7d)
        HistorySection.LAST_30D -> stringResource(R.string.history_section_30d)
        HistorySection.OLDER -> stringResource(R.string.history_section_older)
    }
    HistoryText(
        text = title,
        uiMode = uiMode,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
        header = true
    )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun HistoryItemCard(
    row: HistoryRow.Item,
    uiMode: BibiUiMode,
    vendorOptions: List<HistoryVendorOption>,
    selectedCount: Int,
    onToggleSelection: (String) -> Unit,
    onCopy: (String) -> Unit,
    onOpenDetails: (AsrHistoryStore.AsrHistoryRecord) -> Unit
) {
    val record = row.record
    val onClick = {
        if (selectedCount > 0) onToggleSelection(record.id) else onOpenDetails(record)
    }
    val onLongClick = { onToggleSelection(record.id) }
    when (uiMode) {
        BibiUiMode.Material -> {
            // clickable 必须落在 clip(shape) 之内，否则按压遮罩会画出直角方框。
            val shape = RoundedCornerShape(SettingsLayoutMetrics.MaterialSectionShape)
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick
                    ),
                shape = shape,
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = if (row.selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    }
                )
            ) {
                HistoryItemContent(record, uiMode, vendorOptions, onCopy)
            }
        }

        BibiUiMode.Miuix -> {
            // 使用 Card 自带 onClick/onLongPress，指示器画在圆角 clip 内，避免外层再叠一层遮罩。
            val cornerRadius = MiuixCardDefaults.CornerRadius
            MiuixCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (row.selected) {
                            Modifier.border(
                                width = 1.dp,
                                color = MiuixTheme.colorScheme.primary,
                                shape = RoundedCornerShape(cornerRadius)
                            )
                        } else {
                            Modifier
                        }
                    ),
                cornerRadius = cornerRadius,
                insideMargin = PaddingValues(0.dp),
                onClick = onClick,
                onLongPress = onLongClick,
                showIndication = true
            ) {
                HistoryItemContent(record, uiMode, vendorOptions, onCopy)
            }
        }
    }
}

@Composable
private fun HistoryItemContent(
    record: AsrHistoryStore.AsrHistoryRecord,
    uiMode: BibiUiMode,
    vendorOptions: List<HistoryVendorOption>,
    onCopy: (String) -> Unit
) {
    val context = LocalContext.current
    val formatter = remember(context) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", LocaleHelper.locale(context))
    }
    val timestamp = remember(record.timestamp) { formatter.format(Date(record.timestamp)) }
    val bodyText = AsrHistoryFailDisplay.cardText(context, record)
    val copyText = AsrHistoryFailDisplay.copyText(record)
    // 卡片内边距由内容区统一承担；MiuixCard 默认 insideMargin 会与这里叠加，造成顶部空洞。
    val contentPadding = PaddingValues(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 14.dp)
    Column(
        modifier = Modifier.padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 复制按钮只与时间戳同行，避免与正文并排时在右侧占满一列高度。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HistoryText(
                text = timestamp,
                uiMode = uiMode,
                timestamp = true,
                modifier = Modifier.weight(1f)
            )
            SettingsAssistChip(
                uiMode = uiMode,
                label = stringResource(R.string.btn_copy),
                icon = Icons.Rounded.ContentCopy,
                onClick = copyText?.let { text -> { onCopy(text) } }
            )
        }
        HistoryText(
            text = bodyText,
            uiMode = uiMode,
            emphasized = true,
            error = record.isUnsuccessful,
            maxLines = 4
        )
        HistoryText(
            text = buildMeta(record, vendorOptions),
            uiMode = uiMode,
            compact = true,
            secondary = true,
            maxLines = 3
        )
    }
}

@Composable
private fun buildMeta(
    record: AsrHistoryStore.AsrHistoryRecord,
    vendorOptions: List<HistoryVendorOption>
): String {
    val vendor = vendorOptions.firstOrNull { it.id == record.vendorId }?.label ?: record.vendorId
    val source = when (record.source) {
        "floating" -> stringResource(R.string.source_floating_full)
        "external" -> stringResource(R.string.source_external_full)
        "ime" -> stringResource(R.string.source_ime_full)
        else -> record.source
    }
    val aiStatus = when (record.aiPostStatus) {
        AsrHistoryStore.AiPostStatus.SUCCESS -> {
            val llmVendorId = record.llmVendorId
            if (llmVendorId == null) {
                stringResource(R.string.ai_processed_yes)
            } else {
                val llmVendorName = LlmVendor.allVendors()
                    .firstOrNull { it.id == llmVendorId }
                    ?.let { stringResource(it.displayNameResId) }
                    ?: llmVendorId
                stringResource(R.string.ai_processed_by_vendor, llmVendorName)
            }
        }
        AsrHistoryStore.AiPostStatus.FAILED -> stringResource(R.string.ai_processed_failed)
        AsrHistoryStore.AiPostStatus.NONE -> if (record.aiProcessed) {
            stringResource(R.string.ai_processed_yes)
        } else {
            stringResource(R.string.ai_processed_no)
        }
    }
    val parts = mutableListOf(
        vendor,
        source,
        aiStatus,
        promptSelectionPart(record),
        "${record.charCount}${stringResource(R.string.unit_chars)}"
    )
    if (record.totalElapsedMs > 0) {
        parts.add(stringResource(R.string.meta_total_elapsed_seconds, record.totalElapsedMs / 1000.0))
    }
    parts.add(stringResource(R.string.meta_total_seconds, record.audioMs / 1000.0))
    val recognitionMs = record.timingTrace?.let { trace ->
        trace.stageDurationMs(AsrHistoryTimingStage.RECOGNITION)
    } ?: record.procMs
    if (recognitionMs > 0) {
        parts.add(stringResource(R.string.meta_proc_seconds, recognitionMs / 1000.0))
    }
    if (record.aiPostStatus != AsrHistoryStore.AiPostStatus.NONE || record.aiPostMs > 0) {
        parts.add(stringResource(R.string.meta_ai_postproc_seconds, record.aiPostMs / 1000.0))
    }
    return parts.joinToString("·")
}

@Composable
private fun HistoryDetailsDialog(
    record: AsrHistoryStore.AsrHistoryRecord,
    uiMode: BibiUiMode,
    hasAudio: Boolean,
    llmAvailable: Boolean,
    working: Boolean,
    error: String?,
    onDismissStarted: () -> Unit,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    onReRecognize: () -> Unit,
    onReprocess: () -> Unit
) {
    val exit = rememberSettingsDialogExitController(record.id)
    fun finishDismiss() {
        exit.finish()
    }
    fun dismissDialog() {
        if (!exit.show) return
        onDismissStarted()
        exit.dismiss(onDismiss)
    }

    val title = stringResource(R.string.history_details_title)
    val actions = listOf(
        SettingsDialogAction(
            text = stringResource(R.string.btn_rerecognize),
            onClick = onReRecognize,
            enabled = hasAudio && !working
        ),
        SettingsDialogAction(
            text = stringResource(R.string.btn_reprocess),
            onClick = onReprocess,
            enabled = llmAvailable && !working,
            primary = true
        )
    )
    when (uiMode) {
        BibiUiMode.Material -> {
            val alpha = animateSettingsDialogExitAlpha(
                show = exit.show,
                label = "HistoryDetailsDialogAlpha"
            )
            MaterialSettingsDialogExitEffect(show = exit.show, onFinished = ::finishDismiss)
            MaterialSettingsAlertDialog(
                title = title,
                onDismissRequest = ::dismissDialog,
                modifier = Modifier.graphicsLayer(alpha = alpha),
                text = {
                    HistoryDetailsAdaptiveBody { bounded ->
                        HistoryDetailsSections(
                            record = record,
                            uiMode = uiMode,
                            hasAudio = hasAudio,
                            llmAvailable = llmAvailable,
                            working = working,
                            error = error,
                            onCopy = onCopy,
                            modifier = if (bounded) Modifier.weight(1f, fill = false) else Modifier,
                            constrainResultSections = bounded
                        )
                    }
                },
                buttons = {
                    MaterialSettingsDialogButtonRow(
                        actions.map {
                            MaterialSettingsDialogAction(
                                text = it.text,
                                onClick = it.onClick,
                                enabled = it.enabled,
                                primary = it.primary
                            )
                        }
                    )
                }
            )
        }

        // OverlayDialog 的 title 与 content 同列且不分配 remaining height。
        // 标题改在自适应列内绘制，才能把剩余高度留给正文并钉住底部按钮。
        BibiUiMode.Miuix -> OverlayDialog(
            show = exit.show,
            onDismissRequest = ::dismissDialog,
            onDismissFinished = ::finishDismiss
        ) {
            HistoryDetailsAdaptiveBody { bounded ->
                MiuixText(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    text = title,
                    fontSize = MiuixTheme.textStyles.title4.fontSize,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    color = MiuixTheme.colorScheme.onBackground
                )
                HistoryDetailsSections(
                    record = record,
                    uiMode = uiMode,
                    hasAudio = hasAudio,
                    llmAvailable = llmAvailable,
                    working = working,
                    error = error,
                    onCopy = onCopy,
                    modifier = if (bounded) Modifier.weight(1f, fill = false) else Modifier,
                    constrainResultSections = bounded
                )
                Spacer(modifier = Modifier.height(12.dp))
                SettingsDialogActionRow(uiMode = uiMode, actions = actions)
            }
        }
    }
}

@Composable
private fun HistoryDetailsAdaptiveBody(
    content: @Composable ColumnScope.(bounded: Boolean) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val bounded = maxHeight < Dp.Infinity
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (bounded) Modifier.heightIn(max = maxHeight) else Modifier)
        ) {
            content(bounded)
        }
    }
}

@Composable
private fun HistoryDetailsSections(
    record: AsrHistoryStore.AsrHistoryRecord,
    uiMode: BibiUiMode,
    hasAudio: Boolean,
    llmAvailable: Boolean,
    working: Boolean,
    error: String?,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
    constrainResultSections: Boolean = false
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (record.isUnsuccessful) {
            HistoryText(
                text = AsrHistoryFailDisplay.format(LocalContext.current, record),
                uiMode = uiMode,
                emphasized = true,
                error = true
            )
        }
        HistoryTimingTraceSection(record = record, uiMode = uiMode)
        HistoryResultSection(
            title = stringResource(R.string.history_raw_text),
            value = record.rawText ?: stringResource(R.string.history_raw_unavailable),
            canCopy = !record.rawText.isNullOrBlank(),
            uiMode = uiMode,
            onCopy = { record.rawText?.let(onCopy) },
            modifier = if (constrainResultSections) Modifier.weight(1f, fill = false) else Modifier,
            fillBody = constrainResultSections
        )
        HistoryResultSection(
            title = stringResource(R.string.history_final_text),
            value = record.text.ifBlank {
                if (record.isUnsuccessful) {
                    stringResource(R.string.history_final_unavailable)
                } else {
                    record.text
                }
            },
            canCopy = record.text.isNotBlank(),
            uiMode = uiMode,
            onCopy = { onCopy(record.text) },
            modifier = if (constrainResultSections) Modifier.weight(1f, fill = false) else Modifier,
            fillBody = constrainResultSections
        )
        if (!hasAudio) {
            HistoryText(
                text = stringResource(R.string.history_audio_unavailable),
                uiMode = uiMode,
                compact = true,
                secondary = true
            )
        }
        if (!llmAvailable) {
            HistoryText(
                text = stringResource(R.string.history_llm_unavailable),
                uiMode = uiMode,
                compact = true,
                secondary = true
            )
        }
        if (working) {
            HistoryText(
                text = stringResource(R.string.history_rerun_working),
                uiMode = uiMode,
                secondary = true
            )
        }
        error?.let {
            HistoryText(
                text = AsrHistoryRerunErrorMessages.format(
                    LocalContext.current,
                    it
                ),
                uiMode = uiMode,
                secondary = true
            )
        }
        HistoryPromptSelectionSection(record = record, uiMode = uiMode)
    }
}

/**
 * 详情中的自动选择阶段：状态、实际使用的预设、选择模型与失败原因。
 */
@Composable
private fun HistoryPromptSelectionSection(
    record: AsrHistoryStore.AsrHistoryRecord,
    uiMode: BibiUiMode
) {
    val selection = record.promptSelection
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HistoryText(
            text = stringResource(R.string.history_prompt_selection_section_title),
            uiMode = uiMode,
            compact = true,
            emphasized = true
        )
        HistoryText(
            text = promptSelectionPart(record),
            uiMode = uiMode,
            compact = true,
            secondary = true
        )
        if (selection == null) return@Column
        val modelLabel = promptSelectionModelLabel(selection)
        if (modelLabel != null) {
            HistoryText(
                text = stringResource(R.string.history_prompt_selection_model, modelLabel),
                uiMode = uiMode,
                compact = true,
                secondary = true
            )
        }
        if (selection.requestSent) {
            HistoryText(
                text = stringResource(
                    R.string.history_prompt_selection_elapsed,
                    formatHistoryTimingDuration(selection.elapsedMs)
                ),
                uiMode = uiMode,
                compact = true,
                secondary = true
            )
        }
        selection.failReasonEnum?.let { reason ->
            HistoryText(
                text = stringResource(promptSelectionReasonLabel(reason)),
                uiMode = uiMode,
                compact = true,
                secondary = true,
                error = true
            )
        }
    }
}

/** 卡片与详情共用的选择结果短句。 */
@Composable
private fun promptSelectionPart(record: AsrHistoryStore.AsrHistoryRecord): String {
    val selection = record.promptSelection ?: return stringResource(
        R.string.history_prompt_selection_none
    )
    return when {
        selection.skippedPolish -> stringResource(
            R.string.history_prompt_selection_skipped,
            formatHistoryTimingDuration(selection.elapsedMs)
        )

        selection.ok -> stringResource(
            R.string.history_prompt_selection_auto,
            selection.usedPresetTitle.orEmpty(),
            formatHistoryTimingDuration(selection.elapsedMs)
        )

        else -> stringResource(R.string.history_prompt_selection_failed)
    }
}

/** 选择模型展示：内置供应商用本地化名称，自定义配置用配置 ID。 */
@Composable
private fun promptSelectionModelLabel(
    selection: PromptSelectionStatus
): String? {
    val model = selection.model.orEmpty()
    val vendorId = selection.vendorId
    val providerId = selection.customProviderId
    val target = when {
        providerId != null -> providerId
        vendorId != null -> LlmVendor.allVendors()
            .firstOrNull { it.id == vendorId }
            ?.let { stringResource(it.displayNameResId) }
            ?: JevClassifierProvider.fromId(vendorId)?.displayName()
            ?: vendorId
        else -> null
    }
    return when {
        target != null && model.isNotBlank() -> "$target · $model"
        target != null -> target
        model.isNotBlank() -> model
        else -> null
    }
}

private fun promptSelectionReasonLabel(reason: PromptSelectionFailReason): Int = when (reason) {
    PromptSelectionFailReason.INVALID_CONFIG -> R.string.prompt_selection_reason_invalid_config
    PromptSelectionFailReason.MODEL_UNAVAILABLE -> R.string.prompt_selection_reason_model_unavailable
    PromptSelectionFailReason.TIMEOUT -> R.string.prompt_selection_reason_timeout
    PromptSelectionFailReason.REQUEST_FAILED -> R.string.prompt_selection_reason_request_failed
    PromptSelectionFailReason.INVALID_OUTPUT -> R.string.prompt_selection_reason_invalid_output
    PromptSelectionFailReason.CANCELLED -> R.string.prompt_selection_reason_cancelled
}

@Composable
private fun HistoryTimingTraceSection(
    record: AsrHistoryStore.AsrHistoryRecord,
    uiMode: BibiUiMode
) {
    val trace = record.timingTrace
    if (trace == null) {
        HistoryText(
            text = stringResource(R.string.history_timing_legacy_unavailable),
            uiMode = uiMode,
            compact = true,
            secondary = true
        )
        return
    }

    val stageStyles = historyTimingStageStyles(record.source)
    val stageDurations = trace.intervals
        .map { it.stage }
        .distinct()
        .associateWith { stage -> trace.stageDurationMs(stage) }
    val visibleStages = stageStyles.mapNotNull { style ->
        stageDurations[style.stage]
            ?.takeIf { it > 0L }
            ?.let { durationMs -> style.copy(durationMs = durationMs) }
    }
    val totalDuration = formatHistoryTimingDuration(trace.totalElapsedMs)
    val origin = stringResource(historyTimingOriginLabel(trace.origin))
    var stageDescription = ""
    for (stage in visibleStages) {
        val nextDescription = stringResource(
            R.string.history_timing_stage_description,
            stage.label,
            formatHistoryTimingDuration(stage.durationMs)
        )
        stageDescription = if (stageDescription.isEmpty()) {
            nextDescription
        } else {
            stringResource(
                R.string.history_timing_summary,
                stageDescription,
                nextDescription
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HistoryText(
            text = stringResource(
                R.string.history_timing_summary,
                origin,
                stringResource(R.string.history_timing_total, totalDuration)
            ),
            uiMode = uiMode,
            compact = true,
            secondary = true
        )
        TimingIntervalBar(
            totalElapsedMs = trace.totalElapsedMs,
            intervals = trace.intervals.mapNotNull { interval ->
                stageStyles.firstOrNull { it.stage == interval.stage }?.let { style ->
                    TimingBarInterval(
                        startOffsetMs = interval.startOffsetMs,
                        endOffsetMs = interval.endOffsetMs,
                        color = style.color
                    )
                }
            },
            contentDescription = stringResource(
                R.string.history_timing_bar_description,
                origin,
                totalDuration,
                stageDescription
            ),
            trackColor = historyTimingTrackColor(uiMode)
        )
        HistoryTimingLegendGrid(stages = visibleStages, uiMode = uiMode)
    }
}

@Composable
private fun HistoryTimingLegendGrid(
    stages: List<HistoryTimingStageStyle>,
    uiMode: BibiUiMode
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        stages.forEach { stage ->
            TimingLegendRow(
                label = stage.label,
                value = formatHistoryTimingDuration(stage.durationMs),
                color = stage.color,
                uiMode = uiMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = SettingsLayoutMetrics.ProDialogTinySpacing)
            )
        }
    }
}

@Composable
private fun historyTimingStageStyles(source: String): List<HistoryTimingStageStyle> = listOf(
    HistoryTimingStageStyle(
        stage = AsrHistoryTimingStage.AUDIO_INPUT,
        label = stringResource(R.string.history_timing_audio_input),
        color = colorResource(R.color.history_timing_audio_input)
    ),
    HistoryTimingStageStyle(
        stage = AsrHistoryTimingStage.RECOGNITION,
        label = stringResource(R.string.history_timing_recognition),
        color = colorResource(R.color.history_timing_recognition)
    ),
    HistoryTimingStageStyle(
        stage = AsrHistoryTimingStage.POSTPROCESS,
        label = stringResource(R.string.history_timing_postprocess),
        color = colorResource(R.color.history_timing_postprocess)
    ),
    HistoryTimingStageStyle(
        stage = AsrHistoryTimingStage.PROMPT_SELECTION,
        label = stringResource(R.string.history_timing_prompt_selection),
        color = colorResource(R.color.history_timing_prompt_selection)
    ),
    HistoryTimingStageStyle(
        stage = AsrHistoryTimingStage.AI_POSTPROCESS,
        label = stringResource(R.string.history_timing_ai_postprocess),
        color = colorResource(R.color.history_timing_ai_postprocess)
    ),
    HistoryTimingStageStyle(
        stage = AsrHistoryTimingStage.TEXT_DELIVERY,
        label = stringResource(
            if (source == "external") {
                R.string.history_timing_result_delivery
            } else {
                R.string.history_timing_text_delivery
            }
        ),
        color = colorResource(R.color.history_timing_text_delivery)
    )
)

private fun historyTimingOriginLabel(origin: AsrHistoryTimingOrigin): Int = when (origin) {
    AsrHistoryTimingOrigin.ORIGINAL -> R.string.history_timing_origin_original
    AsrHistoryTimingOrigin.RERECOGNITION -> R.string.history_timing_origin_rerecognition
    AsrHistoryTimingOrigin.REPROCESS -> R.string.history_timing_origin_reprocess
}

@Composable
private fun formatHistoryTimingDuration(durationMs: Long): String {
    val safeDurationMs = durationMs.coerceAtLeast(0L)
    return if (safeDurationMs < 1_000L) {
        stringResource(R.string.history_timing_duration_ms, safeDurationMs)
    } else {
        stringResource(
            R.string.history_timing_duration_seconds,
            safeDurationMs / 1_000.0
        )
    }
}

@Composable
private fun historyTimingTrackColor(uiMode: BibiUiMode): Color = when (uiMode) {
    BibiUiMode.Material -> MaterialTheme.colorScheme.surfaceVariant
    BibiUiMode.Miuix -> MiuixTheme.colorScheme.surfaceVariant
}

private data class HistoryTimingStageStyle(
    val stage: AsrHistoryTimingStage,
    val label: String,
    val color: Color,
    val durationMs: Long = 0L
)

@Composable
private fun HistoryResultSection(
    title: String,
    value: String,
    canCopy: Boolean,
    uiMode: BibiUiMode,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    fillBody: Boolean = false
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            HistoryText(text = title, uiMode = uiMode, header = true)
            SettingsAssistChip(
                uiMode = uiMode,
                label = stringResource(R.string.btn_copy),
                icon = Icons.Rounded.ContentCopy,
                onClick = onCopy.takeIf { canCopy }
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (fillBody) {
                        Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                    } else {
                        Modifier
                    }
                )
        ) {
            SelectionContainer {
                HistoryText(
                    text = value,
                    uiMode = uiMode,
                    maxLines = Int.MAX_VALUE,
                    overflow = TextOverflow.Clip
                )
            }
        }
    }
}

@Composable
private fun EmptyHistoryState(uiMode: BibiUiMode) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        HistoryText(
            text = stringResource(R.string.empty_history),
            uiMode = uiMode,
            secondary = true
        )
    }
}

@Composable
private fun HistoryText(
    text: String,
    uiMode: BibiUiMode,
    modifier: Modifier = Modifier,
    header: Boolean = false,
    timestamp: Boolean = false,
    compact: Boolean = false,
    secondary: Boolean = false,
    emphasized: Boolean = false,
    error: Boolean = false,
    maxLines: Int = 2,
    overflow: TextOverflow = TextOverflow.Ellipsis
) {
    val fontWeight = when {
        header || emphasized -> FontWeight.SemiBold
        else -> FontWeight.Normal
    }
    when (uiMode) {
        BibiUiMode.Material -> Text(
            text = text,
            modifier = modifier,
            // Material 默认正文字号偏小；时间戳与正文分别上调，贴近 Miuix 视觉权重。
            style = when {
                header -> MaterialTheme.typography.titleSmall
                timestamp -> MaterialTheme.typography.bodyMedium
                compact -> MaterialTheme.typography.bodySmall
                else -> MaterialTheme.typography.bodyLarge
            },
            fontWeight = fontWeight,
            color = when {
                error -> MaterialTheme.colorScheme.error
                secondary -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            },
            maxLines = maxLines,
            overflow = overflow
        )

        BibiUiMode.Miuix -> MiuixText(
            text = text,
            modifier = modifier,
            style = when {
                header -> MiuixTheme.textStyles.body2
                timestamp -> MiuixTheme.textStyles.body2
                compact -> MiuixTheme.textStyles.footnote1
                else -> MiuixTheme.textStyles.body1
            },
            fontWeight = fontWeight,
            color = when {
                error -> MiuixTheme.colorScheme.error
                secondary -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                else -> MiuixTheme.colorScheme.onSurface
            },
            maxLines = maxLines,
            overflow = overflow
        )
    }
}

@Composable
private fun DeleteSelectedDialog(
    uiMode: BibiUiMode,
    selectedCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val title = stringResource(R.string.dialog_delete_selected_title)
    val message = stringResource(R.string.dialog_delete_selected_msg, selectedCount)
    val confirm = stringResource(R.string.dialog_filter_ok)
    val cancel = stringResource(R.string.dialog_filter_cancel)
    val exit = rememberSettingsDialogExitController()
    fun finishDismiss() {
        exit.finish()
    }

    when (uiMode) {
        BibiUiMode.Material -> {
            val alpha = animateSettingsDialogExitAlpha(
                show = exit.show,
                label = "DeleteSelectedDialogAlpha"
            )
            MaterialSettingsDialogExitEffect(show = exit.show, onFinished = ::finishDismiss)
            MaterialSettingsAlertDialog(
                onDismissRequest = { exit.dismiss(onDismiss) },
                modifier = Modifier.graphicsLayer(alpha = alpha),
                title = title,
                text = { Text(message) },
                buttons = {
                    MaterialSettingsDialogButtonRow(
                        actions = listOf(
                            MaterialSettingsDialogAction(cancel, onClick = { exit.dismiss(onDismiss) }),
                            MaterialSettingsDialogAction(confirm, onClick = { exit.dismiss(onConfirm) })
                        )
                    )
                }
            )
        }

        BibiUiMode.Miuix -> OverlayDialog(
            show = exit.show,
            title = title,
            summary = message,
            onDismissRequest = { exit.dismiss(onDismiss) },
            onDismissFinished = ::finishDismiss
        ) {
            SettingsDialogActionRow(
                uiMode = BibiUiMode.Miuix,
                actions = listOf(
                    SettingsDialogAction(
                        text = cancel,
                        onClick = { exit.dismiss(onDismiss) }
                    ),
                    SettingsDialogAction(
                        text = confirm,
                        onClick = { exit.dismiss(onConfirm) },
                        primary = true
                    )
                )
            )
        }
    }
}

@Composable
private fun HistoryFilterDialog(
    uiMode: BibiUiMode,
    vendorOptions: List<HistoryVendorOption>,
    filterState: HistoryFilterState,
    onDismiss: () -> Unit,
    onApply: (HistoryFilterState) -> Unit,
    onReset: () -> Unit
) {
    var tempVendorIds by remember(filterState) {
        mutableStateOf(filterState.vendorIds)
    }
    var tempSources by remember(filterState) {
        mutableStateOf(filterState.sources)
    }
    var tempTimeFilter by remember(filterState) {
        mutableStateOf(filterState.timeFilter)
    }
    val title = stringResource(R.string.dialog_filter_title)
    val confirm = stringResource(R.string.dialog_filter_ok)
    val cancel = stringResource(R.string.dialog_filter_cancel)
    val reset = stringResource(R.string.dialog_filter_reset)
    val exit = rememberSettingsDialogExitController(filterState)
    fun finishDismiss() {
        exit.finish()
    }
    fun applyFilter() {
        exit.dismiss {
            onApply(
                HistoryFilterState(
                    vendorIds = tempVendorIds,
                    sources = tempSources,
                    timeFilter = tempTimeFilter
                )
            )
        }
    }

    val content: @Composable () -> Unit = {
        FilterDialogContent(
            uiMode = uiMode,
            vendorOptions = vendorOptions,
            vendorIds = tempVendorIds,
            sources = tempSources,
            timeFilter = tempTimeFilter,
            onVendorIdsChange = { tempVendorIds = it },
            onSourcesChange = { tempSources = it },
            onTimeFilterChange = { tempTimeFilter = it }
        )
    }

    val actionButtons: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SettingsLayoutMetrics.ActionButtonSpacing)
        ) {
            SettingsDialogActionRow(
                uiMode = uiMode,
                actions = listOf(
                    SettingsDialogAction(
                        text = reset,
                        onClick = { exit.dismiss(onReset) }
                    )
                )
            )
            SettingsDialogActionRow(
                uiMode = uiMode,
                actions = listOf(
                    SettingsDialogAction(
                        text = cancel,
                        onClick = { exit.dismiss(onDismiss) }
                    ),
                    SettingsDialogAction(
                        text = confirm,
                        onClick = ::applyFilter,
                        primary = true
                    )
                )
            )
        }
    }

    when (uiMode) {
        BibiUiMode.Material -> {
            val alpha = animateSettingsDialogExitAlpha(
                show = exit.show,
                label = "HistoryFilterDialogAlpha"
            )
            MaterialSettingsDialogExitEffect(show = exit.show, onFinished = ::finishDismiss)
            MaterialSettingsAlertDialog(
                onDismissRequest = { exit.dismiss(onDismiss) },
                modifier = Modifier.graphicsLayer(alpha = alpha),
                title = title,
                text = content,
                buttons = actionButtons
            )
        }

        BibiUiMode.Miuix -> OverlayDialog(
            show = exit.show,
            title = title,
            onDismissRequest = { exit.dismiss(onDismiss) },
            onDismissFinished = ::finishDismiss
        ) {
            content()
            Spacer(modifier = Modifier.height(12.dp))
            actionButtons()
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun FilterDialogContent(
    uiMode: BibiUiMode,
    vendorOptions: List<HistoryVendorOption>,
    vendorIds: Set<String>,
    sources: Set<String>,
    timeFilter: TimeFilter,
    onVendorIdsChange: (Set<String>) -> Unit,
    onSourcesChange: (Set<String>) -> Unit,
    onTimeFilterChange: (TimeFilter) -> Unit
) {
    val sourceOptions = listOf(
        "ime" to stringResource(R.string.source_ime),
        "floating" to stringResource(R.string.source_floating),
        "external" to stringResource(R.string.source_external)
    )
    val timeOptions = listOf(
        TimeFilter.ALL to stringResource(R.string.filter_all),
        TimeFilter.WITHIN_2H to stringResource(R.string.history_section_2h),
        TimeFilter.TODAY to stringResource(R.string.history_section_today),
        TimeFilter.LAST_7D to stringResource(R.string.history_section_7d),
        TimeFilter.LAST_30D to stringResource(R.string.history_section_30d)
    )

    Column(
        modifier = Modifier
            .heightIn(max = 460.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FilterGroupTitle(text = stringResource(R.string.label_vendor), uiMode = uiMode)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterOptionChip(
                label = stringResource(R.string.filter_all),
                selected = vendorIds.isEmpty(),
                uiMode = uiMode,
                onClick = { onVendorIdsChange(emptySet()) }
            )
            vendorOptions.forEach { vendor ->
                FilterOptionChip(
                    label = vendor.label,
                    selected = vendor.id in vendorIds,
                    uiMode = uiMode,
                    onClick = {
                        val next = if (vendor.id in vendorIds) {
                            vendorIds - vendor.id
                        } else {
                            vendorIds + vendor.id
                        }
                        onVendorIdsChange(next)
                    }
                )
            }
        }

        FilterGroupTitle(text = stringResource(R.string.label_source), uiMode = uiMode)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterOptionChip(
                label = stringResource(R.string.filter_all),
                selected = sources.isEmpty(),
                uiMode = uiMode,
                onClick = { onSourcesChange(emptySet()) }
            )
            sourceOptions.forEach { (id, label) ->
                FilterOptionChip(
                    label = label,
                    selected = sources.firstOrNull() == id,
                    uiMode = uiMode,
                    onClick = { onSourcesChange(setOf(id)) }
                )
            }
        }

        FilterGroupTitle(text = stringResource(R.string.label_time), uiMode = uiMode)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            timeOptions.forEach { (filter, label) ->
                FilterOptionChip(
                    label = label,
                    selected = timeFilter == filter,
                    uiMode = uiMode,
                    onClick = { onTimeFilterChange(filter) }
                )
            }
        }
    }
}

@Composable
private fun FilterGroupTitle(text: String, uiMode: BibiUiMode) {
    HistoryText(
        text = text,
        uiMode = uiMode,
        header = true,
        maxLines = 1
    )
}

@Composable
private fun FilterOptionChip(
    label: String,
    selected: Boolean,
    uiMode: BibiUiMode,
    onClick: () -> Unit
) {
    SettingsFilterChip(
        uiMode = uiMode,
        label = label,
        selected = selected,
        onClick = onClick
    )
}

private fun toggleId(ids: Set<String>, id: String): Set<String> = if (id in ids) ids - id else ids + id

private fun historyRerecognitionNoticeState(
    context: Context,
    decision: AsrRecordedAudioRouteDecision,
    noticeKey: String?,
    onContinue: () -> Unit
): SettingsNoticeDialogState {
    val canContinue = decision.canContinue
    return SettingsNoticeDialogState(
        title = context.getString(R.string.history_rerecognition_notice_title),
        paragraphs = listOf(
            context.getString(R.string.history_rerecognition_current_engine, decision.currentEngineLabel),
            historyRerecognitionFallbackLine(context, decision),
            context.getString(
                if (canContinue) {
                    R.string.history_rerecognition_notice_supported_hint
                } else {
                    R.string.history_rerecognition_notice_unsupported_hint
                }
            )
        ),
        dontShowAgainText = noticeKey?.let {
            context.getString(R.string.dialog_feature_explainer_dont_show_again)
        },
        confirmText = if (canContinue) {
            context.getString(R.string.history_rerecognition_btn_continue)
        } else {
            null
        },
        dismissText = if (canContinue) {
            context.getString(R.string.btn_cancel)
        } else {
            context.getString(R.string.history_rerecognition_btn_got_it)
        },
        onDontShowAgain = {
            noticeKey?.let { context.saveFeatureExplainerFlag(it) }
        },
        onConfirm = {
            if (canContinue) onContinue()
        }
    )
}

private fun historyRerecognitionFallbackLine(
    context: Context,
    decision: AsrRecordedAudioRouteDecision
): String {
    if (!decision.canContinue) {
        return context.getString(R.string.history_rerecognition_fallback_unsupported)
    }
    if (decision.kind == AsrRecordedAudioRouteKind.ReplayStream) {
        return context.getString(
            R.string.history_rerecognition_fallback_stream,
            decision.currentEngineLabel
        )
    }
    val fallbackLabel = decision.fallbackEngineLabel?.takeIf { it.isNotBlank() }
        ?: decision.currentEngineLabel
    val useDirectCopy = decision.reasonCode == AsrRecordedAudioRouteResolver.REASON_DIRECT_FILE ||
        decision.fallbackEngineLabel.isNullOrBlank() ||
        decision.fallbackEngineLabel == decision.currentEngineLabel
    return if (useDirectCopy) {
        context.getString(R.string.history_rerecognition_fallback_direct, fallbackLabel)
    } else {
        context.getString(R.string.history_rerecognition_fallback_supported, fallbackLabel)
    }
}
