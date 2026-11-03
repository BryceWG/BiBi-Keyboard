// Shared terminal arbitration seam for ASR primary/backup wrappers.
package com.brycewg.asrkb.asr

import java.util.concurrent.atomic.AtomicBoolean

internal class BackupAsrTerminalCoordinator(
    private val onFinal: (text: String, source: AsrBackupArbitrationSource) -> Unit,
    private val onError: (message: String) -> Unit
) {
    private val delivered = AtomicBoolean(false)

    @Volatile private var arbitrator: AsrBackupArbitrator? = null

    @Volatile private var lastFinalFromBackup: Boolean = false

    val terminalDelivered: Boolean
        get() = delivered.get()

    fun reset(hasPrimary: Boolean, hasBackup: Boolean) {
        delivered.set(false)
        lastFinalFromBackup = false
        arbitrator = AsrBackupArbitrator(
            hasPrimary = hasPrimary,
            hasBackup = hasBackup
        )
    }

    fun markTerminalDelivered() {
        delivered.set(true)
    }

    fun wasLastResultFromBackup(): Boolean = lastFinalFromBackup

    fun dispatch(event: AsrBackupArbitrationEvent) {
        if (delivered.get()) return
        val commands = arbitrator?.onEvent(event).orEmpty()
        commands.forEach { command ->
            when (command) {
                is AsrBackupArbitrationCommand.DeliverFinal -> deliverFinal(
                    command.text,
                    command.source
                )
                is AsrBackupArbitrationCommand.DeliverError -> deliverError(command.message)
            }
        }
    }

    private fun deliverFinal(text: String, source: AsrBackupArbitrationSource) {
        if (!delivered.compareAndSet(false, true)) return
        lastFinalFromBackup = source == AsrBackupArbitrationSource.Backup
        onFinal(text, source)
    }

    internal fun finishWithError(message: String) = deliverError(message)

    private fun deliverError(message: String) {
        if (!delivered.compareAndSet(false, true)) return
        onError(message)
    }
}
