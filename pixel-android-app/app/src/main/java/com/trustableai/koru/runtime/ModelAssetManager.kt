package com.trustableai.koru.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.trustableai.koru.BuildConfig
import com.trustableai.koru.model.ModelInstallStatus
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

class ModelAssetManager(private val context: Context) {
    private val tag = "KoruModelAssets"
    private val modelRoot: File by lazy {
        File(context.filesDir, "models").apply { mkdirs() }
    }
    private val devModelRoot: File = File(BuildConfig.MODEL_DEV_ROOT)

    fun versionedModelFile(version: String = BuildConfig.DEFAULT_MODEL_VERSION): File {
        val versionDir = File(modelRoot, version).apply { mkdirs() }
        return File(versionDir, BuildConfig.DEFAULT_MODEL_FILENAME)
    }

    fun installStatus(version: String = BuildConfig.DEFAULT_MODEL_VERSION): ModelInstallStatus {
        return installStatusFor(
            version = version,
            fileName = BuildConfig.DEFAULT_MODEL_FILENAME,
            checksum = BuildConfig.DEFAULT_MODEL_CHECKSUM,
            requireMediaPipeNativeCompatible = true,
        )
    }

    fun liteRtLmInstallStatus(version: String = BuildConfig.DEFAULT_MODEL_VERSION): ModelInstallStatus {
        return installStatusFor(
            version = version,
            fileName = BuildConfig.DEFAULT_MODEL_FILENAME,
            checksum = BuildConfig.DEFAULT_MODEL_CHECKSUM,
            requireMediaPipeNativeCompatible = false,
        )
    }

    fun liteRtLmNpuInstallStatus(version: String = BuildConfig.DEFAULT_MODEL_VERSION): ModelInstallStatus {
        return installStatusFor(
            version = version,
            fileName = BuildConfig.DEFAULT_NPU_MODEL_FILENAME,
            checksum = BuildConfig.DEFAULT_NPU_MODEL_CHECKSUM,
            requireMediaPipeNativeCompatible = false,
        )
    }

    private fun installStatusFor(
        version: String,
        fileName: String,
        checksum: String,
        requireMediaPipeNativeCompatible: Boolean,
    ): ModelInstallStatus {
        val modelFile = resolveModelFile(version, fileName)
        val isPresent = modelFile.exists()
        val nativeContainer = modelFile.extension.equals("litertlm", ignoreCase = true)
        val unsupportedNativeMarkers = isPresent && hasUnsupportedLiteRtLmMarkers(readHeader(modelFile))
        val nativeCompatible =
            nativeContainer && (!requireMediaPipeNativeCompatible || !unsupportedNativeMarkers)
        val checksumVerified = when {
            !isPresent -> false
            !nativeCompatible -> false
            checksum.isBlank() -> true
            else -> computeSha256(modelFile) == checksum
        }
        val issue = when {
            !isPresent -> null
            !nativeContainer ->
                "Detected ${modelFile.name}. Stage a .litertlm artifact for the native backend."
            requireMediaPipeNativeCompatible && unsupportedNativeMarkers ->
                "Detected unsupported multimodal LiteRT-LM markers in ${modelFile.name}. Stage a text-only LiteRT-LM artifact before enabling MediaPipe EDGE."
            !checksumVerified ->
                "Checksum mismatch for ${modelFile.name}. Re-stage the model artifact."
            else -> null
        }
        return ModelInstallStatus(
            version = version,
            isPresent = isPresent,
            checksumVerified = checksumVerified,
            downloadAllowedOnCurrentNetwork = isUnmeteredNetworkAvailable(),
            filePath = modelFile.absolutePath,
            fileName = modelFile.name,
            supportsNativeAndroid = nativeCompatible,
            issue = issue,
        )
    }

    fun recommendedDownloadAction(version: String = BuildConfig.DEFAULT_MODEL_VERSION): String {
        val status = installStatus(version)
        if (status.isPresent && status.checksumVerified && status.supportsNativeAndroid) {
            return "Model ready at ${status.filePath}"
        }
        if (status.issue != null) return status.issue
        return if (status.downloadAllowedOnCurrentNetwork) {
            "Model missing. Stage ${BuildConfig.DEFAULT_MODEL_FILENAME} from ${BuildConfig.DEFAULT_MODEL_URL}."
        } else {
            "Model missing. Wait for Wi-Fi before downloading $version."
        }
    }

    fun resolveModelFile(version: String = BuildConfig.DEFAULT_MODEL_VERSION): File {
        return resolveModelFile(version, BuildConfig.DEFAULT_MODEL_FILENAME)
    }

    fun resolveNpuModelFile(version: String = BuildConfig.DEFAULT_MODEL_VERSION): File {
        return resolveModelFile(version, BuildConfig.DEFAULT_NPU_MODEL_FILENAME)
    }

    private fun resolveModelFile(
        version: String,
        fileName: String,
    ): File {
        val devVersionDir = File(devModelRoot, version)
        val devCandidates = sequenceOf(
            File(devVersionDir, fileName),
            File(devVersionDir, "model.litertlm"),
            File(devVersionDir, "model.task"),
        )
        for (candidate in devCandidates) {
            if (candidate.exists()) return candidate
        }

        val internalFile =
            if (fileName == BuildConfig.DEFAULT_MODEL_FILENAME) {
                versionedModelFile(version)
            } else {
                File(File(modelRoot, version).apply { mkdirs() }, fileName)
            }
        if (internalFile.exists()) return internalFile
        return File(devVersionDir, fileName)
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun isUnmeteredNetworkAvailable(): Boolean {
        return try {
            val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } catch (securityException: SecurityException) {
            Log.w(tag, "ACCESS_NETWORK_STATE unavailable during model status check", securityException)
            false
        }
    }

    private fun readHeader(file: File, maxBytes: Int = MODEL_HEADER_BYTES): ByteArray {
        return runCatching {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(maxBytes)
                val read = input.read(buffer)
                if (read <= 0) ByteArray(0) else buffer.copyOf(read)
            }
        }.getOrDefault(ByteArray(0))
    }

    companion object {
        private const val MODEL_HEADER_BYTES = 8 * 1024
        private val UNSUPPORTED_LITERTLM_MARKERS =
            listOf(
                "tf_lite_audio_adapter",
                "tf_lite_audio_encoder",
                "tf_lite_audio_encoder_hw",
            )

        internal fun hasUnsupportedLiteRtLmMarkers(header: ByteArray): Boolean {
            val text = header.toString(Charsets.ISO_8859_1)
            return UNSUPPORTED_LITERTLM_MARKERS.any { marker -> text.contains(marker) }
        }
    }
}
