package com.fersaiyan.cyanbridge.media.sync

import android.view.View
import com.fersaiyan.cyanbridge.databinding.AcitivytMainBinding
import com.fersaiyan.cyanbridge.shared.glasses.GlassesDashboardUiState
import com.fersaiyan.cyanbridge.shared.glasses.GlassesSyncFlow
import com.fersaiyan.cyanbridge.shared.glasses.GlassesTransferUiState
import java.util.Locale

/**
 * Owns the media-transfer progress card: the legacy views kept alive behind Compose and the
 * dashboard's transfer state. All calls are expected on the main thread, as before.
 */
internal class TransferProgressPresenter(
    private val bindingProvider: () -> AcitivytMainBinding,
    private val updateDashboardState: ((GlassesDashboardUiState) -> GlassesDashboardUiState) -> Unit,
) {
    private val binding: AcitivytMainBinding
        get() = bindingProvider()

    // UI state for P2P sync progress
    var transferTotalJpg = 0
    var transferTotalMp4 = 0
    var transferTotalOpus = 0
    var transferDoneJpg = 0
    var transferDoneMp4 = 0
    var transferDoneOpus = 0
    var lastFileProgressUiAtMs: Long = 0L

    fun setTransferUiVisible(visible: Boolean) {
        binding.cardTransferProgress.visibility = if (visible) View.VISIBLE else View.GONE
        updateDashboardState { state ->
            state.copy(transfer = state.transfer.copy(isVisible = visible))
        }
    }

    fun resetTransferUiState() {
        transferTotalJpg = 0
        transferTotalMp4 = 0
        transferTotalOpus = 0
        transferDoneJpg = 0
        transferDoneMp4 = 0
        transferDoneOpus = 0
        lastFileProgressUiAtMs = 0L

        binding.tvTransferFlow.text = "Flow: --"
        binding.tvTransferCounts.text = "Photos: --  Videos: --  Audio: --"
        binding.progressTransfer.isIndeterminate = true
        binding.progressTransfer.max = 100
        binding.progressTransfer.progress = 0
        binding.tvTransferDetail.text = "Idle"
        updateDashboardState { state ->
            state.copy(
                transfer = GlassesTransferUiState(
                    isVisible = state.transfer.isVisible,
                ),
            )
        }
    }

    fun setTransferPlan(jpg: Int, mp4: Int, opus: Int) {
        transferTotalJpg = jpg
        transferTotalMp4 = mp4
        transferTotalOpus = opus
        transferDoneJpg = 0
        transferDoneMp4 = 0
        transferDoneOpus = 0
        renderTransferProgress()
    }

    fun onTransferItemDone(type: String) {
        when (type) {
            "jpg" -> transferDoneJpg++
            "mp4" -> transferDoneMp4++
            "opus" -> transferDoneOpus++
        }
        renderTransferProgress()
    }

    fun transferItemSummary(): String {
        val done = transferDoneJpg + transferDoneMp4 + transferDoneOpus
        val total = transferTotalJpg + transferTotalMp4 + transferTotalOpus
        return "$done/$total"
    }

    fun renderTransferProgress() {
        val total = transferTotalJpg + transferTotalMp4 + transferTotalOpus
        val done = transferDoneJpg + transferDoneMp4 + transferDoneOpus

        binding.tvTransferCounts.text =
            "Photos: ${transferDoneJpg}/${transferTotalJpg}  Videos: ${transferDoneMp4}/${transferTotalMp4}  Audio: ${transferDoneOpus}/${transferTotalOpus}"

        if (total <= 0) {
            binding.progressTransfer.isIndeterminate = true
            binding.progressTransfer.max = 100
            binding.progressTransfer.progress = 0
        } else {
            binding.progressTransfer.isIndeterminate = false
            binding.progressTransfer.max = total * 1000
            binding.progressTransfer.progress = (done * 1000).coerceAtMost(binding.progressTransfer.max)
        }
        updateDashboardState { state ->
            state.copy(
                transfer = state.transfer.copy(
                    countsLabel = "Photos: $transferDoneJpg/$transferTotalJpg  Videos: $transferDoneMp4/$transferTotalMp4  Audio: $transferDoneOpus/$transferTotalOpus",
                    progress = if (total > 0) done.toFloat() / total.toFloat() else null,
                ),
            )
        }
    }

    fun setTransferDetail(text: String) {
        binding.tvTransferDetail.text = text
        updateDashboardState { state ->
            state.copy(transfer = state.transfer.copy(detail = text))
        }
    }

    fun setTransferFlowLabel(mode: GlassesSyncFlow) {
        binding.tvTransferFlow.text = "Flow: ${mode.label}"
        updateDashboardState { state ->
            state.copy(transfer = state.transfer.copy(flowLabel = mode.label))
        }
    }

    fun showIndeterminateProgress() {
        binding.progressTransfer.isIndeterminate = true
        updateDashboardState { state ->
            state.copy(transfer = state.transfer.copy(progress = null))
        }
    }

    fun showDeterminateProgressStart() {
        binding.progressTransfer.isIndeterminate = false
        updateDashboardState { state ->
            state.copy(transfer = state.transfer.copy(progress = 0f))
        }
    }

    fun renderFileProgress(detail: String, bytesCopied: Long, totalBytes: Long) {
        setTransferDetail(detail)
        val totalItems = transferTotalJpg + transferTotalMp4 + transferTotalOpus
        val completedItems = transferDoneJpg + transferDoneMp4 + transferDoneOpus
        if (totalItems > 0) {
            val fraction = if (totalBytes > 0L) {
                (bytesCopied.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            binding.progressTransfer.isIndeterminate = false
            binding.progressTransfer.max = totalItems * 1000
            binding.progressTransfer.progress = (completedItems * 1000 + fraction * 1000).toInt()
                .coerceAtMost(binding.progressTransfer.max)
            updateDashboardState { state ->
                state.copy(
                    transfer = state.transfer.copy(
                        progress = ((completedItems + fraction) / totalItems).toFloat(),
                    ),
                )
            }
        }
    }

    companion object {
        fun formatTransferBytes(bytes: Long): String {
            if (bytes <= 0L) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB")
            var value = bytes.toDouble()
            var unitIndex = 0
            while (value >= 1024.0 && unitIndex < units.lastIndex) {
                value /= 1024.0
                unitIndex++
            }
            val pattern = if (value >= 100 || unitIndex == 0) "%.0f" else "%.1f"
            return pattern.format(Locale.US, value) + " " + units[unitIndex]
        }
    }
}
