/**
 * 非流式识别前的 VAD 空音频检测与静音片段过滤。
 *
 * 归属模块：asr
 */
package com.brycewg.asrkb.asr

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.BuildConfig
import com.brycewg.asrkb.store.Prefs
import java.io.ByteArrayOutputStream

internal data class RecordedVoiceFilterEnergy(
    val maxAbs: Int,
    val sumSquares: Double,
    val countAbove30: Int,
    val sampleCount: Int
)

internal fun measureRecordedVoiceFilterEnergy(pcm: ByteArray, len: Int): RecordedVoiceFilterEnergy {
    val stats = computeFrameStats16le(pcm, len, threshold = 30)
    return RecordedVoiceFilterEnergy(
        maxAbs = stats.maxAbs,
        sumSquares = stats.sumSquares.toDouble(),
        countAbove30 = stats.countAboveThreshold,
        sampleCount = stats.sampleCount
    )
}

/**
 * 对一段 PCM 音频做识别前处理。
 *
 * 输入必须是 16-bit little-endian mono PCM，采样率与 [sampleRate] 一致。
 */
object RecordedAudioVoiceFilter {
    private const val TAG = "RecordedAudioVoiceFilter"
    private const val BYTES_PER_SAMPLE = 2
    private const val PRE_ROLL_MS = 400
    private const val POST_ROLL_MS = 150
    private const val MIN_SILENT_RUN_TO_TRIM_MS = 800
    private const val MIN_SILENT_RUN_TO_SPLIT_MS = 300
    private const val NEAR_SILENCE_MAX_ABS_THRESHOLD = 220
    private const val NEAR_SILENCE_RMS_THRESHOLD = 90
    private const val VAD_DOMINANT_MAX_ABS_THRESHOLD = 1_500
    private const val VAD_DOMINANT_RMS_THRESHOLD = 650
    internal fun isEnabled(prefs: Prefs, emptyAudioHandledBySession: Boolean = false): Boolean = (prefs.autoCancelEmptyAudioInputEnabled && !emptyAudioHandledBySession) ||
        prefs.autoFilterSilentAudioSegmentsEnabled ||
        prefs.recordingAutoStopMode == Prefs.RecordingAutoStopMode.SILENCE

    private data class ChunkMark(
        val offset: Int,
        val length: Int,
        val durationMs: Int,
        val isSpeech: Boolean,
        val maxAbs: Int,
        val sumSquares: Double,
        val sampleCount: Int
    )

    private data class FilterOutput(
        val pcm: ByteArray,
        val candidateRunCount: Int,
        val trimmedRunCount: Int,
        val trimmedMs: Int,
        val ranges: List<IntRange>
    )

    data class Result(
        val pcm: ByteArray,
        val hasSpeech: Boolean,
        val droppedAsEmptyAudio: Boolean,
        val originalDurationMs: Long,
        val outputDurationMs: Long,
        val probabilities: VadProbabilities? = null
    ) {
        internal fun denoiseIfEnabled(context: Context, prefs: Prefs, sampleRate: Int): Result {
            val denoised = OfflineSpeechDenoiserManager.denoiseIfEnabled(context, prefs, pcm, sampleRate)
            // GTCRN 的居中 STFT/ISTFT 抵消首部填充，输出是同一起点、按 hop 截短的前缀。
            return copy(
                pcm = denoised,
                probabilities = probabilities?.slice(0, denoised.size.toLong()),
                outputDurationMs = durationMs(denoised.size, sampleRate)
            )
        }
    }

    data class SilenceAnalysis(
        val silentPercent: Int
    )

    fun analyzeSilence(
        context: Context,
        prefs: Prefs,
        pcm: ByteArray,
        sampleRate: Int,
        probabilities: VadProbabilities? = null
    ): SilenceAnalysis? {
        if (pcm.isEmpty() || sampleRate <= 0) return null

        val analysis = try {
            probabilities ?: VadProbabilities.analyze(context, pcm, sampleRate)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to create VAD analyzer", t)
            return null
        }

        return try {
            val endpoint = VadEndpoint(
                VadTuning.tuningForLevel(prefs.autoStopSilenceSensitivity),
                prefs.autoStopSilenceWindowMs
            )
            var speechSamples = 0L
            var previousEnd = 0L
            var lastSpeech = false
            for (frame in analysis.frames) {
                lastSpeech = endpoint.accept(
                    frame.probability,
                    allowStop = false,
                    frameEndMs = (frame.endSample * 1000 / sampleRate).toInt()
                )
                if (lastSpeech) speechSamples += frame.endSample - previousEnd
                previousEnd = frame.endSample
            }
            // 不足一个推理帧的尾部沿用最后状态，不另跑模型或把整块 200 ms 算成同一状态。
            if (lastSpeech) speechSamples += analysis.sampleCount - previousEnd
            SilenceAnalysis(
                silentPercent = ((analysis.sampleCount - speechSamples) * 100 / analysis.sampleCount).toInt()
            )
        } catch (t: Throwable) {
            Log.w(TAG, "VAD silence analysis failed", t)
            null
        }
    }

    internal fun findSilenceRangesForLocalOfflineChunking(
        context: Context,
        prefs: Prefs,
        pcm: ByteArray,
        sampleRate: Int,
        chunkMillis: Int = 100,
        probabilities: VadProbabilities? = null
    ): List<IntRange> {
        if (pcm.isEmpty() || sampleRate <= 0) return emptyList()
        val analysis = try {
            probabilities ?: VadProbabilities.analyze(context, pcm, sampleRate)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to create VAD chunk analyzer", t)
            return emptyList()
        }

        return try {
            val marks = buildChunkMarks(
                probabilities = analysis,
                pcm = pcm,
                sampleRate = sampleRate,
                chunkMillis = chunkMillis.coerceAtLeast(20),
                classifier = VadSpeechClassifier(VadTuning.SentenceBoundary)
            )
            buildList {
                var index = 0
                while (index < marks.size) {
                    if (marks[index].isSpeech) {
                        index++
                        continue
                    }
                    val start = index
                    var durationMs = 0
                    while (index < marks.size && !marks[index].isSpeech) {
                        durationMs += marks[index].durationMs
                        index++
                    }
                    if (durationMs >= MIN_SILENT_RUN_TO_SPLIT_MS) {
                        val first = marks[start]
                        val last = marks[index - 1]
                        add(first.offset until (last.offset + last.length))
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "VAD chunk analysis failed", t)
            emptyList()
        }
    }

    fun processIfEnabled(
        context: Context,
        prefs: Prefs,
        pcm: ByteArray,
        sampleRate: Int,
        chunkMillis: Int,
        probabilities: VadProbabilities? = null,
        emptyAudioHandledBySession: Boolean = false
    ): Result {
        val cancelEmpty = prefs.autoCancelEmptyAudioInputEnabled && !emptyAudioHandledBySession
        val filterSilent = prefs.autoFilterSilentAudioSegmentsEnabled
        val trimBounds = prefs.recordingAutoStopMode == Prefs.RecordingAutoStopMode.SILENCE
        val originalDurationMs = durationMs(pcm.size, sampleRate)
        if (pcm.isEmpty() || sampleRate <= 0 || (!cancelEmpty && !filterSilent && !trimBounds)) {
            return Result(
                pcm = pcm,
                hasSpeech = pcm.isNotEmpty(),
                droppedAsEmptyAudio = false,
                originalDurationMs = originalDurationMs,
                outputDurationMs = originalDurationMs,
                probabilities = probabilities
            )
        }

        val analysis = try {
            probabilities ?: VadProbabilities.analyze(context, pcm, sampleRate)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to create VAD filter, keeping original audio", t)
            return Result(
                pcm = pcm,
                hasSpeech = true,
                droppedAsEmptyAudio = false,
                originalDurationMs = originalDurationMs,
                outputDurationMs = originalDurationMs,
                probabilities = probabilities
            )
        }

        return try {
            processWithProbabilities(
                probabilities = analysis,
                pcm = pcm,
                sampleRate = sampleRate,
                chunkMillis = 32,
                cancelEmpty = cancelEmpty,
                emptyAudioTuning = EmptyAudioTuning.forStopSensitivity(prefs.autoStopSilenceSensitivity),
                filterSilent = filterSilent,
                prefsTrimBounds = trimBounds,
                originalDurationMs = originalDurationMs
            )
        } catch (t: Throwable) {
            Log.w(TAG, "VAD filter failed, keeping original audio", t)
            Result(
                pcm = pcm,
                hasSpeech = true,
                droppedAsEmptyAudio = false,
                originalDurationMs = originalDurationMs,
                outputDurationMs = originalDurationMs,
                probabilities = probabilities
            )
        }
    }

    private fun processWithProbabilities(
        probabilities: VadProbabilities,
        pcm: ByteArray,
        sampleRate: Int,
        chunkMillis: Int,
        cancelEmpty: Boolean,
        emptyAudioTuning: EmptyAudioTuning,
        filterSilent: Boolean,
        prefsTrimBounds: Boolean,
        originalDurationMs: Long
    ): Result {
        val classifier = VadSpeechClassifier(VadTuning.SilenceFilter)
        val emptyClassifier = EmptyAudioDetector(emptyAudioTuning)
        val boundsClassifier = VadSpeechClassifier(VadTuning.SpeechBounds)
        val marks = buildChunkMarks(probabilities, pcm, sampleRate, chunkMillis, classifier, emptyClassifier, boundsClassifier)

        val hasContent = marks.any { it.shouldKeepAsContent() }
        val shouldDrop = cancelEmpty && !emptyClassifier.hasSpeech
        val filterOutput = if (filterSilent && hasContent) {
            filterLongNonContentRuns(pcm, marks)
        } else {
            null
        }
        val bounds = boundsClassifier.speechBounds(pcm.size, sampleRate)
        val out = if (!filterSilent && prefsTrimBounds) {
            pcm.copyOfRange(bounds.first, bounds.last + 1)
        } else if (filterOutput != null) {
            filterOutput.pcm.takeIf { it.isNotEmpty() } ?: pcm
        } else {
            pcm
        }
        val outputDurationMs = durationMs(out.size, sampleRate)
        logDebugSummary(
            cancelEmpty = cancelEmpty,
            filterSilent = filterSilent,
            sampleRate = sampleRate,
            chunkMillis = chunkMillis,
            marks = marks,
            hasContent = hasContent,
            droppedAsEmptyAudio = shouldDrop,
            originalDurationMs = originalDurationMs,
            outputDurationMs = outputDurationMs,
            filterOutput = filterOutput
        )
        return Result(
            pcm = out,
            hasSpeech = emptyClassifier.hasSpeech,
            droppedAsEmptyAudio = shouldDrop,
            originalDurationMs = originalDurationMs,
            outputDurationMs = outputDurationMs,
            probabilities = when {
                !filterSilent && prefsTrimBounds -> probabilities.slice(bounds.first.toLong(), bounds.last.toLong() + 1)
                filterOutput != null && filterOutput.pcm.isNotEmpty() -> probabilities.select(filterOutput.ranges)
                else -> probabilities
            }
        )
    }

    private fun buildChunkMarks(
        probabilities: VadProbabilities,
        pcm: ByteArray,
        sampleRate: Int,
        chunkMillis: Int,
        classifier: VadSpeechClassifier = VadSpeechClassifier(),
        emptyClassifier: EmptyAudioDetector? = null,
        boundsClassifier: VadSpeechClassifier? = null
    ): List<ChunkMark> {
        val chunkBytes = (((sampleRate * chunkMillis) / 1000) * BYTES_PER_SAMPLE)
            .coerceAtLeast(BYTES_PER_SAMPLE)
        val leveler = VadInputLevelerBranch(sampleRate = sampleRate)
        val marks = ArrayList<ChunkMark>()
        var offset = 0
        while (offset < pcm.size) {
            val len = minOf(chunkBytes, pcm.size - offset)
            val chunk = pcm.copyOfRange(offset, offset + len)
            val frameMs = durationMs(len, sampleRate).toInt().coerceAtLeast(1)
            val leveled = leveler.process(chunk)
            val leveledPcm = leveled.leveledPcm
            val chunkProbabilities = probabilities.slice(offset.toLong(), (offset + len).toLong())
            val isSpeech = classifier.accept(chunkProbabilities)
            boundsClassifier?.accept(chunkProbabilities)
            val energy = measureEnergy(leveledPcm, leveledPcm.size)
            if (emptyClassifier != null) {
                for (frame in chunkProbabilities.frames) {
                    emptyClassifier.accept(frame.probability)
                }
            }
            marks.add(
                ChunkMark(
                    offset = offset,
                    length = len,
                    durationMs = frameMs,
                    isSpeech = isSpeech,
                    maxAbs = energy.maxAbs,
                    sumSquares = energy.sumSquares,
                    sampleCount = energy.sampleCount
                )
            )

            offset += len
        }
        return marks
    }

    private fun measureEnergy(pcm: ByteArray, len: Int): RecordedVoiceFilterEnergy = measureRecordedVoiceFilterEnergy(pcm, len)

    private fun filterLongNonContentRuns(pcm: ByteArray, marks: List<ChunkMark>): FilterOutput {
        val keep = BooleanArray(marks.size)
        marks.forEachIndexed { index, mark ->
            if (mark.shouldKeepAsContent()) keep[index] = true
        }

        var index = 0
        var hasContentBefore = false
        var candidateRunCount = 0
        var trimmedRunCount = 0
        var trimmedMs = 0
        while (index < marks.size) {
            if (keep[index]) {
                hasContentBefore = true
                index++
                continue
            }

            val runStart = index
            var runDurationMs = 0
            while (index < marks.size && !keep[index]) {
                runDurationMs += marks[index].durationMs
                index++
            }
            val runEndExclusive = index
            val hasContentAfter = runEndExclusive < marks.size
            candidateRunCount++

            if (runDurationMs <= MIN_SILENT_RUN_TO_TRIM_MS) {
                for (i in runStart until runEndExclusive) keep[i] = true
                continue
            }

            var elapsedFromStartMs = 0
            for (i in runStart until runEndExclusive) {
                val mark = marks[i]
                val remainingToEndMs = runDurationMs - elapsedFromStartMs
                keep[i] = (hasContentBefore && elapsedFromStartMs < POST_ROLL_MS) ||
                    (hasContentAfter && remainingToEndMs <= PRE_ROLL_MS)
                elapsedFromStartMs += mark.durationMs
            }
            val keptRunMs = (runStart until runEndExclusive)
                .filter { keep[it] }
                .sumOf { marks[it].durationMs }
            val trimmedRunMs = runDurationMs - keptRunMs
            if (trimmedRunMs > 0) {
                trimmedRunCount++
                trimmedMs += trimmedRunMs
            }
        }

        val out = ByteArrayOutputStream(pcm.size)
        marks.forEachIndexed { i, mark ->
            if (keep[i]) out.write(pcm, mark.offset, mark.length)
        }
        return FilterOutput(
            pcm = out.toByteArray(),
            candidateRunCount = candidateRunCount,
            trimmedRunCount = trimmedRunCount,
            trimmedMs = trimmedMs,
            ranges = marks.indices.filter { keep[it] }.map { marks[it].offset until (marks[it].offset + marks[it].length) }
        )
    }

    private fun logDebugSummary(
        cancelEmpty: Boolean,
        filterSilent: Boolean,
        sampleRate: Int,
        chunkMillis: Int,
        marks: List<ChunkMark>,
        hasContent: Boolean,
        droppedAsEmptyAudio: Boolean,
        originalDurationMs: Long,
        outputDurationMs: Long,
        filterOutput: FilterOutput?
    ) {
        if (!BuildConfig.DEBUG) return

        var totalSquares = 0.0
        var totalSamples = 0
        var maxAbs = 0
        var maxRms = 0.0
        var vadSpeechCount = 0
        var vadDominantCount = 0
        var vadDominantSpeechCount = 0
        var nearSilenceCount = 0
        var contentCount = 0
        var lowVolumeSoundCount = 0
        marks.forEach { mark ->
            totalSquares += mark.sumSquares
            totalSamples += mark.sampleCount
            if (mark.maxAbs > maxAbs) maxAbs = mark.maxAbs
            val rms = mark.rms()
            if (rms > maxRms) maxRms = rms
            val isVadDominant = mark.isVadDominantVolume()
            val isNearSilence = mark.isNearSilence()
            val keepAsContent = mark.shouldKeepAsContent()
            if (mark.isSpeech) vadSpeechCount++
            if (isVadDominant) vadDominantCount++
            if (isVadDominant && mark.isSpeech) vadDominantSpeechCount++
            if (isNearSilence) nearSilenceCount++
            if (keepAsContent) contentCount++
            if (!isVadDominant && !isNearSilence) lowVolumeSoundCount++
        }
        val avgRms = if (totalSamples > 0) {
            kotlin.math.sqrt(totalSquares / totalSamples)
        } else {
            0.0
        }

        Log.d(
            TAG,
            "voice_filter_summary " +
                "cancelEmpty=$cancelEmpty filterSilent=$filterSilent " +
                "sr=$sampleRate chunkMs=$chunkMillis chunks=${marks.size} " +
                "durationMs=$originalDurationMs->$outputDurationMs " +
                "hasContent=$hasContent dropped=$droppedAsEmptyAudio " +
                "vadSpeech=$vadSpeechCount content=$contentCount " +
                "nearSilence=$nearSilenceCount lowVolumeSound=$lowVolumeSoundCount " +
                "vadDominant=$vadDominantCount vadDominantSpeech=$vadDominantSpeechCount " +
                "maxAbs=$maxAbs avgRms=${avgRms.toInt()} maxRms=${maxRms.toInt()} " +
                "trimCandidateRuns=${filterOutput?.candidateRunCount ?: 0} " +
                "trimmedRuns=${filterOutput?.trimmedRunCount ?: 0} " +
                "trimmedMs=${filterOutput?.trimmedMs ?: 0} " +
                "thresholds={nearMaxAbs=$NEAR_SILENCE_MAX_ABS_THRESHOLD," +
                "nearRms=$NEAR_SILENCE_RMS_THRESHOLD," +
                "vadMaxAbs=$VAD_DOMINANT_MAX_ABS_THRESHOLD," +
                "vadRms=$VAD_DOMINANT_RMS_THRESHOLD," +
                "minRunMs=$MIN_SILENT_RUN_TO_TRIM_MS," +
                "preMs=$PRE_ROLL_MS,postMs=$POST_ROLL_MS," +
                "vadTuning=${VadTuning.SilenceFilter.name}}"
        )
    }

    private fun ChunkMark.isNearSilence(): Boolean {
        if (sampleCount <= 0) return false
        val rms = rms()
        return maxAbs <= NEAR_SILENCE_MAX_ABS_THRESHOLD &&
            rms <= NEAR_SILENCE_RMS_THRESHOLD
    }

    private fun ChunkMark.shouldKeepAsContent(): Boolean {
        if (sampleCount <= 0) return false
        return if (isVadDominantVolume()) {
            isSpeech
        } else {
            !isNearSilence()
        }
    }

    private fun ChunkMark.isVadDominantVolume(): Boolean {
        if (sampleCount <= 0) return false
        val rms = rms()
        return maxAbs >= VAD_DOMINANT_MAX_ABS_THRESHOLD ||
            rms >= VAD_DOMINANT_RMS_THRESHOLD
    }

    private fun ChunkMark.rms(): Double = kotlin.math.sqrt(sumSquares / sampleCount)

    private fun durationMs(byteCount: Int, sampleRate: Int): Long {
        if (sampleRate <= 0) return 0L
        return (byteCount / BYTES_PER_SAMPLE) * 1_000L / sampleRate
    }
}
