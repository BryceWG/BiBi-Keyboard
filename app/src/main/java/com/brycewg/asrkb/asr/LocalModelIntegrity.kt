/**
 * 本地 ASR 模型文件完整性校验工具。
 *
 * 归属模块：asr
 */
package com.brycewg.asrkb.asr

import android.content.Context
import com.brycewg.asrkb.R
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

private const val INTEGRITY_PREFS_NAME = "local_model_integrity_cache"

internal sealed class LocalModelCheck<out T> {
    data class Ready<T>(val value: T) : LocalModelCheck<T>()
    object Missing : LocalModelCheck<Nothing>()
    data class IntegrityError(val fileName: String) : LocalModelCheck<Nothing>()
}

internal data class LocalModelFileSpec(
    val name: String,
    val sizeBytes: Long,
    val sha256: String
)

internal data class Qwen3AsrVariantFileSpecs(
    val convFrontend: LocalModelFileSpec,
    val encoder: LocalModelFileSpec,
    val decoder: LocalModelFileSpec,
    val merges: LocalModelFileSpec,
    val tokenizerConfig: LocalModelFileSpec,
    val vocab: LocalModelFileSpec
)

internal fun localModelErrorMessage(
    context: Context,
    check: LocalModelCheck<*>,
    missingResId: Int
): String = when (check) {
    is LocalModelCheck.IntegrityError -> context.getString(
        R.string.error_local_model_integrity_failed,
        check.fileName
    )
    LocalModelCheck.Missing -> context.getString(missingResId)
    is LocalModelCheck.Ready<*> -> ""
}

internal fun requireModelFiles(vararg files: Pair<File, LocalModelFileSpec>): LocalModelCheck<Unit> = requireModelFilesInternal(context = null, files = files)

internal fun requireModelFilesCached(
    context: Context,
    vararg files: Pair<File, LocalModelFileSpec>
): LocalModelCheck<Unit> = requireModelFilesInternal(context = context.applicationContext, files = files)

private fun requireModelFilesInternal(
    context: Context?,
    files: Array<out Pair<File, LocalModelFileSpec>>
): LocalModelCheck<Unit> {
    for ((file, _) in files) {
        if (!file.exists() || !file.isFile) return LocalModelCheck.Missing
    }
    for ((file, spec) in files) {
        if (file.length() != spec.sizeBytes) {
            return LocalModelCheck.IntegrityError(file.name)
        }
        if (sha256Hex(context, file, spec) != spec.sha256) {
            return LocalModelCheck.IntegrityError(file.name)
        }
    }
    return LocalModelCheck.Ready(Unit)
}

private data class HashCacheKey(
    val path: String,
    val length: Long,
    val lastModified: Long
)

private val hashCache = ConcurrentHashMap<HashCacheKey, String>()

private fun sha256Hex(context: Context?, file: File, spec: LocalModelFileSpec): String {
    val key = HashCacheKey(
        path = file.absolutePath,
        length = file.length(),
        lastModified = file.lastModified()
    )
    val persistentKey = buildPersistentKey(key, spec)
    val prefs = context?.getSharedPreferences(INTEGRITY_PREFS_NAME, Context.MODE_PRIVATE)
    if (prefs?.getString(persistentKey, null) == spec.sha256) {
        return spec.sha256
    }
    return hashCache.getOrPut(key) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }.also { hash ->
            if (hash == spec.sha256) {
                prefs?.edit()?.putString(persistentKey, hash)?.apply()
            }
        }
    }
}

private fun buildPersistentKey(key: HashCacheKey, spec: LocalModelFileSpec): String = listOf("v1", key.path, key.length, key.lastModified, spec.name, spec.sha256).joinToString("|")

internal object LocalModelSpecs {
    object SenseVoice {
        val tokens = LocalModelFileSpec(
            name = "tokens.txt",
            sizeBytes = 315_894L,
            sha256 = "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"
        )
        val smallInt8 = LocalModelFileSpec(
            name = "model.int8.onnx",
            sizeBytes = 239_233_841L,
            sha256 = "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"
        )
        val smallFull = LocalModelFileSpec(
            name = "model.onnx",
            sizeBytes = 937_617_178L,
            sha256 = "977016bd9c79f9eb343430b5cc305e07ab64d5212dff41b0dcfa1694bee9a8cb"
        )
    }

    object FunAsrNano {
        val tokenizer = LocalModelFileSpec(
            name = "tokenizer.json",
            sizeBytes = 11_422_654L,
            sha256 = "aeb13307a71acd8fe81861d94ad54ab689df773318809eed3cbe794b4492dae4"
        )
        val nanoEmbedding = LocalModelFileSpec(
            name = "embedding.int8.onnx",
            sizeBytes = 155_584_380L,
            sha256 = "95e61cd0c9c3b9543339a4cf973c95c116815e745ccc1e0285cbd81f76d18644"
        )
        val nanoEncoderAdaptor = LocalModelFileSpec(
            name = "encoder_adaptor.int8.onnx",
            sizeBytes = 237_792_748L,
            sha256 = "f36dea2e30fbc33b5db1d7a7265cc976c5e5586c77b042d5adb1ad27c72db422"
        )
        val nanoLlm = LocalModelFileSpec(
            name = "llm.int8.onnx",
            sizeBytes = 600_356_593L,
            sha256 = "dfbf9aa3be41bccc257587f151e15c63fbe1b549f2b517f5ccd5bdce3bf4322a"
        )
        val mltEmbedding = LocalModelFileSpec(
            name = "embedding.int8.onnx",
            sizeBytes = 155_583_106L,
            sha256 = "2eb6f47e9119b0e92cb38caea8d41505d446c7ee829a44f91619498a6ec7594b"
        )
        val mltEncoderAdaptor = LocalModelFileSpec(
            name = "encoder_adaptor.int8.onnx",
            sizeBytes = 237_792_749L,
            sha256 = "0ccd6c75ffe07d724921fd0787389479c3ae8d84e60f55508f43d87c0f02bc85"
        )
        val mltLlm = LocalModelFileSpec(
            name = "llm.int8.onnx",
            sizeBytes = 600_356_593L,
            sha256 = "2e54f5d0e7a7147df785a8318d8680a582678f95fb3dfbb5b9aa2d4e55e6ff6c"
        )
    }

    object Qwen3Asr {
        private val sharedMerges = LocalModelFileSpec(
            name = "merges.txt",
            sizeBytes = 1_671_853L,
            sha256 = "8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5"
        )
        private val sharedTokenizerConfig = LocalModelFileSpec(
            name = "tokenizer_config.json",
            sizeBytes = 12_487L,
            sha256 = "4942d005604266809309cabc9f4e9cb89ce855d59b14681fdc0e1cc62ea26c4c"
        )
        private val sharedVocab = LocalModelFileSpec(
            name = "vocab.json",
            sizeBytes = 2_776_833L,
            sha256 = "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910"
        )

        private val qwen3Asr06bInt8 = Qwen3AsrVariantFileSpecs(
            convFrontend = LocalModelFileSpec(
                name = "conv_frontend.onnx",
                sizeBytes = 44_148_281L,
                sha256 = "d22dc4423e0940e49884e903d2ea2f7e5567c14fc1aed97e4e26d6b8f208ef9e"
            ),
            encoder = LocalModelFileSpec(
                name = "encoder.int8.onnx",
                sizeBytes = 182_491_662L,
                sha256 = "60748d3e6744a57c9c91e1b17424a6c2990567e8adceb0783940c03ed98fa9d9"
            ),
            decoder = LocalModelFileSpec(
                name = "decoder.int8.onnx",
                sizeBytes = 755_914_231L,
                sha256 = "4f6885be5959ae26af3089d38ee7972c5fafbeeb1cf8d5e76eab6d8b61ca5771"
            ),
            merges = sharedMerges,
            tokenizerConfig = sharedTokenizerConfig,
            vocab = sharedVocab
        )

        private val qwen3Asr17bInt8 = Qwen3AsrVariantFileSpecs(
            convFrontend = LocalModelFileSpec(
                name = "conv_frontend.onnx",
                sizeBytes = 48_080_441L,
                sha256 = "3cb27a9fe94d95c938e476f2012b21aba2ec0bfceef33b0e58acd208946bafdd"
            ),
            encoder = LocalModelFileSpec(
                name = "encoder.int8.onnx",
                sizeBytes = 314_222_162L,
                sha256 = "a5deedae034ece715de8ed204378d8c77f889af3a60c2566581135e84cced7cd"
            ),
            decoder = LocalModelFileSpec(
                name = "decoder.int8.onnx",
                sizeBytes = 2_037_091_087L,
                sha256 = "41eafdf582b124c5fb050a59ca3331a6ec62db0d5ce35d456e9f999f7e6ad042"
            ),
            merges = sharedMerges,
            tokenizerConfig = sharedTokenizerConfig,
            vocab = sharedVocab
        )

        val convFrontend: LocalModelFileSpec get() = qwen3Asr06bInt8.convFrontend
        val encoder: LocalModelFileSpec get() = qwen3Asr06bInt8.encoder
        val decoder: LocalModelFileSpec get() = qwen3Asr06bInt8.decoder
        val merges: LocalModelFileSpec get() = qwen3Asr06bInt8.merges
        val tokenizerConfig: LocalModelFileSpec get() = qwen3Asr06bInt8.tokenizerConfig
        val vocab: LocalModelFileSpec get() = qwen3Asr06bInt8.vocab

        fun forVariant(variant: String?): Qwen3AsrVariantFileSpecs = if (normalizeQwen3AsrVariant(variant) == "qwen3-1.7b-int8") qwen3Asr17bInt8 else qwen3Asr06bInt8
    }

    object Parakeet {
        val v2Tokens = LocalModelFileSpec("tokens.txt", 9_384L, "ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d")
        val v2Encoder = LocalModelFileSpec("encoder.int8.onnx", 652_184_296L, "a32b12d17bbbc309d0686fbbcc2987b5e9b8333a7da83fa6b089f0a2acd651ab")
        val v2Decoder = LocalModelFileSpec("decoder.int8.onnx", 7_257_753L, "b6bb64963457237b900e496ee9994b59294526439fbcc1fecf705b31a15c6b4e")
        val v2Joiner = LocalModelFileSpec("joiner.int8.onnx", 1_739_080L, "7946164367946e7f9f29a122407c3252b680dbae9a51343eb2488d057c3c43d2")
        val v3Tokens = LocalModelFileSpec("tokens.txt", 93_939L, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d")
        val v3Encoder = LocalModelFileSpec("encoder.int8.onnx", 652_184_281L, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247")
        val v3Decoder = LocalModelFileSpec("decoder.int8.onnx", 11_845_275L, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e")
        val v3Joiner = LocalModelFileSpec("joiner.int8.onnx", 6_355_277L, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3")
    }

    object GigaAm {
        val tokens = LocalModelFileSpec("tokens.txt", 13_354L, "39abae20e692998290c574e606f11a9edef2902a1995463fcff63d1490cf22b7")
        val encoder = LocalModelFileSpec("encoder.int8.onnx", 224_570_820L, "369f35a71bf288d3b8e0391fabd8dba5f2314088d440bca474056b7b4b6e66bf")
        val decoder = LocalModelFileSpec("decoder.onnx", 4_600_132L, "38fc7475443ea2a26f63211ca350f73ac50fff824ab7a3876ee2bd610c53bbc4")
        val joiner = LocalModelFileSpec("joiner.onnx", 2_712_896L, "602ff7017a93311aad34df1437c8d7f49911353c13d6eae7a6ee7b041339465c")
    }

    object FireRedAsr {
        val tokens = LocalModelFileSpec("tokens.txt", 79_172L, "1bc613de2112d257e61a349c3e72d1b1a9cf19c33d3ca954197ad2171e5ea07b")
        val ctcModel = LocalModelFileSpec("model.int8.onnx", 775_861_420L, "ca3dbabd82170110cc0b343c2890866d449984bc9cd92b9a18371ff80a81bb99")
    }

    object XAsr {
        val tokens = LocalModelFileSpec("tokens.txt", 58_806L, "b818a60878b9aae978cbb8ad594acbd403d76d1af2e31ef4197c84e2dbdba27c")
        val encoder = LocalModelFileSpec("encoder-480ms.onnx", 592_968_361L, "0c3454033d249081df124ddcd7adaf3deca07d0b999b26f2ee5d2475d37abc74")
        val decoder = LocalModelFileSpec("decoder-480ms.onnx", 11_309_084L, "3658368d274a5d5fd39a7ac20c46bed0ad9cfea1f0feddef30d5d89797c1f499")
        val joiner = LocalModelFileSpec("joiner-480ms.onnx", 10_260_467L, "03781c98165a2385024c9cecdd2b6b13310d81db23a62c7da420782c2915cf81")
    }

    object Punctuation {
        val model = LocalModelFileSpec("model.int8.onnx", 75_519_198L, "65a3fb9f5ad7bfb96bf69e0dc4481df97f6ee60513c1d94ce981ba6effd524b1")
        val tokens = LocalModelFileSpec("tokens.json", 4_207_480L, "c960ab87bccea4aa15cf49a59f71973c2c330b46668048cd8da253749ec71ee3")
    }
}
