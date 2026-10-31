/**
 * 自定义键盘布局的默认值、JSON 读写与旧扩展按钮迁移。
 *
 * 归属模块：ime/layout
 */
package com.brycewg.asrkb.ime.layout

import com.brycewg.asrkb.ime.ExtensionButtonAction
import com.brycewg.asrkb.store.Prefs
import org.json.JSONArray
import org.json.JSONObject

object KeyboardLayoutStore {
    private const val VERSION = 1

    fun load(prefs: Prefs): KeyboardLayoutBundle {
        val stored = prefs.customKeyboardLayoutsJson
        val parsed = parseBundle(stored)
        val initialBundle = parsed ?: defaultBundle(
            mainExtensionActions = legacyMainExtensionActions(prefs)
        )
        return normalizeBundle(prefs, initialBundle)
    }

    fun save(prefs: Prefs, bundle: KeyboardLayoutBundle) {
        prefs.customKeyboardLayoutsJson = encodeBundle(bundle)
    }

    @Suppress("UNUSED_PARAMETER")
    fun defaultBundle(prefs: Prefs): KeyboardLayoutBundle = defaultBundle(
        mainExtensionActions = ExtensionButtonAction.getDefaults()
    )

    private fun defaultBundle(
        mainExtensionActions: List<ExtensionButtonAction>
    ): KeyboardLayoutBundle {
        val now = System.currentTimeMillis()
        return KeyboardLayoutBundle(
            main = KeyboardLayout(
                id = "main_default",
                name = "Main",
                panel = KeyboardLayoutPanel.Main,
                gridSize = GridSize(7, 4),
                blocks = defaultMainBlocks(mainExtensionActions),
                createdAt = now,
                updatedAt = now
            ),
            aiEdit = KeyboardLayout(
                id = "ai_edit_default",
                name = "AI Edit",
                panel = KeyboardLayoutPanel.AiEdit,
                gridSize = GridSize(7, 4),
                blocks = defaultAiEditBlocks(),
                createdAt = now,
                updatedAt = now
            ),
            recording = KeyboardLayout(
                id = "recording_default",
                name = "Recording",
                panel = KeyboardLayoutPanel.Recording,
                gridSize = GridSize(7, 4),
                blocks = defaultRecordingBlocks(),
                createdAt = now,
                updatedAt = now
            )
        )
    }

    fun defaultLayout(prefs: Prefs, panel: KeyboardLayoutPanel): KeyboardLayout = defaultBundle(prefs).layoutFor(panel)

    fun normalizeBundle(prefs: Prefs, bundle: KeyboardLayoutBundle): KeyboardLayoutBundle = migrateRequiredBlocks(prefs, bundle.withMigratedDefIds().withPanelAllowedBlocks())

    fun encodeBundle(bundle: KeyboardLayoutBundle): String {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put(
            "layouts",
            JSONArray().apply {
                put(encodeLayout(bundle.main))
                put(encodeLayout(bundle.aiEdit))
                put(encodeLayout(bundle.recording))
            }
        )
        return root.toString()
    }

    fun parseBundle(json: String?): KeyboardLayoutBundle? {
        if (json.isNullOrBlank()) return null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val layoutsArray = root.optJSONArray("layouts") ?: return null
        val layouts = (0 until layoutsArray.length())
            .mapNotNull { index -> layoutsArray.optJSONObject(index)?.let(::parseLayout) }
        val byPanel = layouts.associateBy { it.panel }
        val now = System.currentTimeMillis()
        return KeyboardLayoutBundle(
            main = byPanel[KeyboardLayoutPanel.Main] ?: KeyboardLayout(
                "main_default",
                "Main",
                KeyboardLayoutPanel.Main,
                GridSize(7, 4),
                defaultMainBlocks(ExtensionButtonAction.getDefaults()),
                now,
                now
            ),
            aiEdit = byPanel[KeyboardLayoutPanel.AiEdit] ?: KeyboardLayout(
                "ai_edit_default",
                "AI Edit",
                KeyboardLayoutPanel.AiEdit,
                GridSize(7, 4),
                defaultAiEditBlocks(),
                now,
                now
            ),
            recording = byPanel[KeyboardLayoutPanel.Recording] ?: KeyboardLayout(
                "recording_default",
                "Recording",
                KeyboardLayoutPanel.Recording,
                GridSize(7, 4),
                defaultRecordingBlocks(),
                now,
                now
            )
        )
    }

    private fun defaultMainBlocks(actions: List<ExtensionButtonAction>): List<BlockInstance> {
        val normalizedActions = actions
            .take(4)
            .map { if (it == ExtensionButtonAction.NONE) null else it }
        val placements = listOf(
            BlockPlacement(0f, 0f, 1f, 1f),
            BlockPlacement(1f, 0f, 1f, 1f),
            BlockPlacement(5f, 0f, 1f, 1f),
            BlockPlacement(6f, 0f, 1f, 1f)
        )
        return normalizedActions.mapIndexedNotNull { index, action ->
            action?.let { BlockInstance(BlockDefRegistry.extensionDefId(it), placements[index]) }
        } + fixedDefaultMainBlocks()
    }

    private fun legacyMainExtensionActions(prefs: Prefs): List<ExtensionButtonAction> = listOf(prefs.extBtn1, prefs.extBtn2, prefs.extBtn3, prefs.extBtn4)

    private fun fixedDefaultMainBlocks(): List<BlockInstance> = listOf(
        BlockInstance("status", BlockPlacement(2f, 0f, 3f, 1f)),
        BlockInstance("ai_edit", BlockPlacement(0f, 1f, 1f, 1f)),
        BlockInstance("postproc", BlockPlacement(1f, 1f, 1f, 1f)),
        BlockInstance("mic", BlockPlacement(2.5f, 1f, 2f, 2f)),
        BlockInstance("clipboard", BlockPlacement(5f, 1f, 1f, 1f)),
        BlockInstance("backspace", BlockPlacement(6f, 1f, 1f, 1f)),
        BlockInstance("settings", BlockPlacement(0f, 2f, 1f, 1f)),
        BlockInstance("prompt_picker", BlockPlacement(1f, 2f, 1f, 1f)),
        BlockInstance("switch_ime", BlockPlacement(5f, 2f, 1f, 1f)),
        BlockInstance("enter", BlockPlacement(6f, 2f, 1f, 1f)),
        BlockInstance("numpad", BlockPlacement(0f, 3f, 1f, 1f)),
        BlockInstance("punct_left", BlockPlacement(1f, 3f, 1f, 1f)),
        BlockInstance("space", BlockPlacement(2f, 3f, 3f, 1f)),
        BlockInstance("punct_right", BlockPlacement(5f, 3f, 1f, 1f)),
        BlockInstance("vendor_picker", BlockPlacement(6f, 3f, 1f, 1f))
    )

    private fun defaultAiEditBlocks(): List<BlockInstance> = listOf(
        BlockInstance("ai_info", BlockPlacement(1f, 0f, 5f, 1f)),
        BlockInstance("ai_back", BlockPlacement(0f, 1f, 1f, 1f)),
        BlockInstance("ai_apply", BlockPlacement(1f, 1f, 1f, 1f)),
        BlockInstance("ai_mic", BlockPlacement(2.5f, 1f, 2f, 2f)),
        BlockInstance("ai_select_all", BlockPlacement(5f, 1f, 1f, 1f)),
        BlockInstance("ai_delete", BlockPlacement(6f, 1f, 1f, 1f)),
        BlockInstance("ai_cursor_left", BlockPlacement(0f, 2f, 1f, 1f)),
        BlockInstance("ai_cursor_right", BlockPlacement(1f, 2f, 1f, 1f)),
        BlockInstance("ai_copy", BlockPlacement(5f, 2f, 1f, 1f)),
        BlockInstance("ai_paste", BlockPlacement(6f, 2f, 1f, 1f)),
        BlockInstance("ai_numpad", BlockPlacement(0f, 3f, 1f, 1f)),
        BlockInstance("ai_select", BlockPlacement(1f, 3f, 1f, 1f)),
        BlockInstance("ai_space", BlockPlacement(2f, 3f, 3f, 1f)),
        BlockInstance("ai_move_start", BlockPlacement(5f, 3f, 1f, 1f)),
        BlockInstance("ai_move_end", BlockPlacement(6f, 3f, 1f, 1f))
    )

    private fun defaultRecordingBlocks(): List<BlockInstance> = listOf(
        BlockInstance("gesture_cancel", BlockPlacement(0f, 1f, 2f, 2f)),
        BlockInstance("gesture_send", BlockPlacement(5f, 1f, 2f, 2f))
    )

    private fun encodeLayout(layout: KeyboardLayout): JSONObject = JSONObject().apply {
        put("id", layout.id)
        put("name", layout.name)
        put("panel", layout.panel.id)
        put(
            "grid",
            JSONObject().apply {
                put("cols", layout.gridSize.cols)
                put("rows", layout.gridSize.rows)
            }
        )
        put(
            "blocks",
            JSONArray().apply {
                layout.blocks.forEach { put(encodeBlock(it)) }
            }
        )
        put("createdAt", layout.createdAt)
        put("updatedAt", layout.updatedAt)
    }

    private fun encodeBlock(block: BlockInstance): JSONObject = JSONObject().apply {
        put("defId", block.defId)
        put("col", block.placement.col.toDouble())
        put("row", block.placement.row.toDouble())
        put("width", block.placement.width.toDouble())
        put("height", block.placement.height.toDouble())
        if (block.config.isNotEmpty()) {
            put("config", JSONObject(block.config))
        }
    }

    private fun parseLayout(o: JSONObject): KeyboardLayout {
        val panel = KeyboardLayoutPanel.fromId(o.optString("panel"))
        val grid = o.optJSONObject("grid")
        val blocksJson = o.optJSONArray("blocks") ?: JSONArray()
        val now = System.currentTimeMillis()
        return KeyboardLayout(
            id = o.optString("id").ifBlank { "${panel.id}_custom" },
            name = o.optString("name").ifBlank { panel.id },
            panel = panel,
            gridSize = GridSize(
                cols = grid?.optInt("cols", 7) ?: 7,
                rows = grid?.optInt("rows", 4) ?: 4
            ).coerceForPanel(panel),
            blocks = (0 until blocksJson.length()).mapNotNull { index ->
                blocksJson.optJSONObject(index)?.let(::parseBlock)
            },
            createdAt = o.optLong("createdAt", now),
            updatedAt = o.optLong("updatedAt", now)
        )
    }

    private fun parseBlock(o: JSONObject): BlockInstance = BlockInstance(
        defId = o.optString("defId"),
        placement = BlockPlacement(
            col = o.optDouble("col", 0.0).toFloat(),
            row = o.optDouble("row", 0.0).toFloat(),
            width = o.optDouble("width", 1.0).toFloat(),
            height = o.optDouble("height", 1.0).toFloat()
        ).snapped(),
        config = o.optJSONObject("config")?.let { config ->
            config.keys().asSequence().associateWith { key -> config.optString(key) }
        }.orEmpty()
    )

    private fun migrateRequiredBlocks(prefs: Prefs, bundle: KeyboardLayoutBundle): KeyboardLayoutBundle {
        val defaults = defaultBundle(prefs)
        return KeyboardLayoutBundle(
            main = bundle.main.withRequiredBlocks(defaults.main),
            aiEdit = bundle.aiEdit.withRequiredBlocks(defaults.aiEdit),
            recording = bundle.recording.withRequiredBlocks(defaults.recording)
        )
    }

    private fun KeyboardLayoutBundle.withMigratedDefIds(): KeyboardLayoutBundle = KeyboardLayoutBundle(
        main = main.withMigratedDefIds(),
        aiEdit = aiEdit.withMigratedDefIds(),
        recording = recording.withMigratedDefIds()
    )

    private fun KeyboardLayout.withMigratedDefIds(): KeyboardLayout {
        var changed = false
        val nextBlocks = blocks.map { block ->
            val migratedId = LEGACY_DEF_IDS[block.defId] ?: return@map block
            changed = true
            block.copy(defId = migratedId)
        }
        return if (changed) copy(blocks = nextBlocks) else this
    }

    private fun KeyboardLayoutBundle.withPanelAllowedBlocks(): KeyboardLayoutBundle = KeyboardLayoutBundle(
        main = main.withPanelAllowedBlocks(),
        aiEdit = aiEdit.withPanelAllowedBlocks(),
        recording = recording.withPanelAllowedBlocks()
    )

    private fun KeyboardLayout.withPanelAllowedBlocks(): KeyboardLayout {
        val nextBlocks = when (panel) {
            KeyboardLayoutPanel.Recording -> blocks.filter { it.defId in recordingGestureDefIds }
            KeyboardLayoutPanel.Main,
            KeyboardLayoutPanel.AiEdit -> blocks.filterNot { it.defId in recordingGestureDefIds }
        }
        return if (nextBlocks.size == blocks.size) this else copy(blocks = nextBlocks)
    }

    private fun KeyboardLayout.withRequiredBlocks(defaultLayout: KeyboardLayout): KeyboardLayout {
        val requiredDefs = BlockDefRegistry.default.defsFor(panel).filter { it.required }
        if (requiredDefs.isEmpty()) return this
        val nextBlocks = blocks.toMutableList()
        var nextGrid = gridSize
        requiredDefs.forEach { def ->
            if (nextBlocks.any { it.defId == def.id }) return@forEach
            val defaultBlock = defaultLayout.blocks.firstOrNull { it.defId == def.id } ?: return@forEach
            val placement = nextBlocks.findRequiredPlacement(
                preferred = defaultBlock.placement,
                grid = nextGrid,
                size = def.defaultSize
            ) ?: run {
                nextGrid = GridSize(
                    cols = maxOf(nextGrid.cols, defaultLayout.gridSize.cols),
                    rows = maxOf(nextGrid.rows, defaultLayout.gridSize.rows)
                ).coerceForPanel(panel)
                nextBlocks.findRequiredPlacement(
                    preferred = defaultBlock.placement,
                    grid = nextGrid,
                    size = def.defaultSize
                )
            } ?: return@forEach
            nextBlocks += defaultBlock.copy(placement = placement)
        }
        return copy(gridSize = nextGrid, blocks = nextBlocks)
    }

    private fun List<BlockInstance>.findRequiredPlacement(
        preferred: BlockPlacement,
        grid: GridSize,
        size: BlockSize
    ): BlockPlacement? {
        val preferredPlacement = preferred.snapped()
        if (preferredPlacement.withinGrid(grid) && none { preferredPlacement.overlaps(it.placement.snapped()) }) {
            return preferredPlacement
        }
        var row = 0f
        while (row + size.height <= grid.rows) {
            var col = 0f
            while (col + size.width <= grid.cols) {
                val placement = BlockPlacement(col, row, size.width, size.height)
                if (none { placement.overlaps(it.placement.snapped()) }) return placement
                col += 0.5f
            }
            row += 0.5f
        }
        return null
    }

    private val recordingGestureDefIds = RecordingLayoutButtons.defIds

    private val LEGACY_DEF_IDS = mapOf(
        "ext_copy_last_asr" to BlockDefRegistry.extensionDefId(ExtensionButtonAction.ASR_HISTORY)
    )
}
