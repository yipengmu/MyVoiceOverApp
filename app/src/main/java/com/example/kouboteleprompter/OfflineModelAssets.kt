package com.example.kouboteleprompter

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal data class ModelFileSpec(
    val name: String,
    val size: Long,
    val sha256: String
) {
    fun matches(file: File): Boolean =
        file.isFile && file.length() == size && file.sha256() == sha256
}

internal data class InstalledOnlineModel(
    val encoder: File,
    val decoder: File,
    val joiner: File,
    val tokens: File
)

/**
 * Installs the bundled ASR model into app-private storage before native initialization.
 *
 * Some Android vendor builds terminate the process inside sherpa-onnx's AssetManager loader.
 * File-backed loading avoids that vendor-specific path. Each model file is content-verified and
 * replaced atomically, so a killed install cannot leave a partial model at the final path.
 */
internal object OfflineModelAssets {
    private const val TAG = "KouboSpeech"
    private const val ASSET_ROOT =
        "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23"
    private const val MODEL_SET_ID = "streaming-zipformer-zh-14m-20230223-v1"
    private const val COPY_BUFFER_BYTES = 1024 * 1024

    private val encoderSpec = ModelFileSpec(
        name = "encoder-epoch-99-avg-1.int8.onnx",
        size = 21_621_684,
        sha256 = "1c556ea57cec304e55ec4b72e52c1cc098bb01476ed7d90f3de939fe126487b1"
    )
    private val decoderSpec = ModelFileSpec(
        name = "decoder-epoch-99-avg-1.onnx",
        size = 7_509_745,
        sha256 = "5ee0f03a2768ff1d5c83ef3a493243c7935d316cd41280037b14783a3467cc78"
    )
    private val joinerSpec = ModelFileSpec(
        name = "joiner-epoch-99-avg-1.int8.onnx",
        size = 1_795_562,
        sha256 = "a7cf9d82757bdcf786059454495a9ca95e4bd7347f72473fc08d794475c36169"
    )
    private val tokensSpec = ModelFileSpec(
        name = "tokens.txt",
        size = 48_697,
        sha256 = "8b294db9045d6e5f94647f4c1eec1af4da143a75053c399611444b378ff966ac"
    )
    private val specs = listOf(encoderSpec, decoderSpec, joinerSpec, tokensSpec)

    @Synchronized
    fun install(context: Context): InstalledOnlineModel {
        val modelDirectory = File(context.noBackupFilesDir, "offline-asr/$MODEL_SET_ID")
        check(modelDirectory.mkdirs() || modelDirectory.isDirectory) {
            "Unable to create model directory: ${modelDirectory.absolutePath}"
        }

        specs.forEach { spec ->
            val target = modelDirectory.resolve(spec.name)
            if (!spec.matches(target)) installFile(context, spec, target)
        }

        return InstalledOnlineModel(
            encoder = modelDirectory.resolve(encoderSpec.name),
            decoder = modelDirectory.resolve(decoderSpec.name),
            joiner = modelDirectory.resolve(joinerSpec.name),
            tokens = modelDirectory.resolve(tokensSpec.name)
        )
    }

    private fun installFile(context: Context, spec: ModelFileSpec, target: File) {
        val temporary = target.resolveSibling(".${spec.name}.installing")
        temporary.delete()
        try {
            context.assets.open("$ASSET_ROOT/${spec.name}").use { input ->
                FileOutputStream(temporary, false).use { output ->
                    input.copyTo(output, COPY_BUFFER_BYTES)
                    output.flush()
                    output.fd.sync()
                }
            }
            check(spec.matches(temporary)) { "Model asset verification failed: ${spec.name}" }
            atomicReplace(temporary, target)
            check(spec.matches(target)) { "Installed model verification failed: ${spec.name}" }
            Log.i(TAG, "offline_model_asset_installed name=${spec.name} bytes=${target.length()}")
        } finally {
            temporary.delete()
        }
    }

    private fun atomicReplace(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}
