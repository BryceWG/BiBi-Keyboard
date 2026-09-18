// Builds the top-level ParallelAsrEngine wrapper when backup ASR policy allows it.
package com.brycewg.asrkb.asr

import android.content.Context
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope

internal data class AsrParallelEngineRequest(
    val context: Context,
    val scope: CoroutineScope,
    val prefs: Prefs,
    val listener: StreamingAsrEngine.Listener,
    val primaryVendor: AsrVendor,
    val backupVendor: AsrVendor,
    val externalPcmInput: Boolean = false,
    val modePreferences: AsrEngineModePreferences = prefs.asrEngineModePreferencesSnapshot(),
    val onPrimaryRequestDuration: ((Long) -> Unit)? = null,
    val onBackupRequestDuration: ((Long) -> Unit)? = null,
    val modelOverride: AsrRequestModelOverride = AsrRequestModelOverride()
)

internal data class AsrParallelEnginePlan(
    val decision: AsrParallelEngineDecision,
    val primaryVendor: AsrVendor,
    val backupVendor: AsrVendor,
    val externalPcmInput: Boolean
) {
    val shouldUseParallel: Boolean
        get() = decision == AsrParallelEngineDecision.UseParallel

    val shouldUseLazyLocalBackup: Boolean
        get() = decision == AsrParallelEngineDecision.UseLazyLocalBackup

    val shouldUseBackupWrapper: Boolean
        get() = decision != AsrParallelEngineDecision.UsePrimaryOnly

    val engineClassName: String?
        get() = when (decision) {
            AsrParallelEngineDecision.UseParallel -> "ParallelAsrEngine"
            AsrParallelEngineDecision.UseLazyLocalBackup -> "LazyLocalBackupAsrEngine"
            AsrParallelEngineDecision.UsePrimaryOnly -> null
        }
}

enum class AsrParallelEngineDecision {
    UseParallel,
    UsePrimaryOnly,
    UseLazyLocalBackup
}

internal class AsrParallelEngineFactory(
    private val constructors: AsrParallelEngineConstructorTable =
        RealAsrParallelEngineConstructorTable
) {
    fun createOrNull(request: AsrParallelEngineRequest): StreamingAsrEngine? {
        val plan = resolvePlan(request)
        return createPlanned(plan) {
            when (plan.decision) {
                AsrParallelEngineDecision.UseParallel -> ParallelAsrEngine(
                    context = request.context,
                    scope = request.scope,
                    prefs = request.prefs,
                    listener = request.listener,
                    primaryVendor = plan.primaryVendor,
                    backupVendor = plan.backupVendor,
                    onPrimaryRequestDuration = request.onPrimaryRequestDuration,
                    onBackupRequestDuration = request.onBackupRequestDuration,
                    externalPcmInput = plan.externalPcmInput
                ).also {
                    it.modePreferencesOverride = request.modePreferences
                    it.modelOverride = request.modelOverride
                }
                AsrParallelEngineDecision.UseLazyLocalBackup -> LazyLocalBackupAsrEngine(
                    context = request.context,
                    scope = request.scope,
                    prefs = request.prefs,
                    listener = request.listener,
                    primaryVendor = plan.primaryVendor,
                    backupVendor = plan.backupVendor,
                    onPrimaryRequestDuration = request.onPrimaryRequestDuration,
                    onBackupRequestDuration = request.onBackupRequestDuration,
                    externalPcmInput = plan.externalPcmInput,
                    modePreferences = request.modePreferences,
                    modelOverride = request.modelOverride
                )
                AsrParallelEngineDecision.UsePrimaryOnly ->
                    error("Primary-only plan cannot construct a backup wrapper")
            }
        }
    }

    fun createOrNull(
        context: Context,
        scope: CoroutineScope,
        prefs: Prefs,
        listener: StreamingAsrEngine.Listener,
        primaryVendor: AsrVendor = prefs.asrVendor,
        backupVendor: AsrVendor = prefs.backupAsrVendor,
        externalPcmInput: Boolean = false,
        modePreferences: AsrEngineModePreferences = prefs.asrEngineModePreferencesSnapshot(),
        onPrimaryRequestDuration: ((Long) -> Unit)? = null,
        onBackupRequestDuration: ((Long) -> Unit)? = null,
        modelOverride: AsrRequestModelOverride = AsrRequestModelOverride()
    ): StreamingAsrEngine? = createOrNull(
        AsrParallelEngineRequest(
            context = context,
            scope = scope,
            prefs = prefs,
            listener = listener,
            primaryVendor = primaryVendor,
            backupVendor = backupVendor,
            externalPcmInput = externalPcmInput,
            modePreferences = modePreferences,
            onPrimaryRequestDuration = onPrimaryRequestDuration,
            onBackupRequestDuration = onBackupRequestDuration,
            modelOverride = modelOverride
        )
    )

    fun createPlanned(
        plan: AsrParallelEnginePlan,
        engineFactory: () -> StreamingAsrEngine
    ): StreamingAsrEngine? {
        if (!plan.shouldUseBackupWrapper) return null
        return constructors.create(plan, engineFactory)
    }

    fun resolvePlan(request: AsrParallelEngineRequest): AsrParallelEnginePlan = resolvePlan(
        context = request.context,
        prefs = request.prefs,
        primaryVendor = request.primaryVendor,
        backupVendor = request.backupVendor,
        externalPcmInput = request.externalPcmInput
    )

    fun resolvePlan(
        context: Context,
        prefs: Prefs,
        primaryVendor: AsrVendor,
        backupVendor: AsrVendor,
        externalPcmInput: Boolean = false
    ): AsrParallelEnginePlan = planFor(
        primaryVendor = primaryVendor,
        backupVendor = backupVendor,
        externalPcmInput = externalPcmInput,
        decision = resolveBackupAsrDecision(
            context = context,
            prefs = prefs,
            primaryVendor = primaryVendor,
            backupVendor = backupVendor
        )
    )

    fun resolvePlan(
        backupPolicyInput: AsrBackupPolicyInput,
        externalPcmInput: Boolean = false
    ): AsrParallelEnginePlan = planFor(
        primaryVendor = backupPolicyInput.primaryVendor,
        backupVendor = backupPolicyInput.backupVendor,
        externalPcmInput = externalPcmInput,
        decision = resolveBackupAsrDecision(backupPolicyInput)
    )

    private fun planFor(
        primaryVendor: AsrVendor,
        backupVendor: AsrVendor,
        externalPcmInput: Boolean,
        decision: AsrBackupPolicyDecision
    ): AsrParallelEnginePlan = AsrParallelEnginePlan(
        decision = decision.toParallelEngineDecision(),
        primaryVendor = primaryVendor,
        backupVendor = backupVendor,
        externalPcmInput = externalPcmInput
    )
}

private fun AsrBackupPolicyDecision.toParallelEngineDecision(): AsrParallelEngineDecision = when (this) {
    AsrBackupPolicyDecision.UsePrimaryOnly -> AsrParallelEngineDecision.UsePrimaryOnly
    AsrBackupPolicyDecision.UseParallel -> AsrParallelEngineDecision.UseParallel
    AsrBackupPolicyDecision.UseLazyLocalBackup -> AsrParallelEngineDecision.UseLazyLocalBackup
}

internal fun interface AsrParallelEngineConstructorTable {
    fun create(
        plan: AsrParallelEnginePlan,
        engineFactory: () -> StreamingAsrEngine
    ): StreamingAsrEngine
}

internal object RealAsrParallelEngineConstructorTable : AsrParallelEngineConstructorTable {
    override fun create(
        plan: AsrParallelEnginePlan,
        engineFactory: () -> StreamingAsrEngine
    ): StreamingAsrEngine {
        require(plan.shouldUseBackupWrapper) {
            "Backup ASR wrapper can only be constructed from an approved backup plan"
        }
        return engineFactory()
    }
}
