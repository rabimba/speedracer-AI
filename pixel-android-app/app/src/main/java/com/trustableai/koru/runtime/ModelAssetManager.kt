package com.trustableai.koru.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.trustableai.koru.BuildConfig
import com.trustableai.koru.model.ModelInstallStatus
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

class ModelAssetManager(private val context: Context) {
    private val modelRoot: File by lazy {
        File(context.filesDir, "models").apply { mkdirs() }
    }
    private val devModelRoot: File = File(BuildConfig.MODEL_DEV_ROOT)

    fun versionedModelFile(version: String = BuildConfig.DEFAULT_MODEL_VERSION): File {
        val versionDir = File(modelRoot, version).apply { mkdirs() }
        return File(versionDir, BuildConfig.DEFAULT_MODEL_FILENAME)
    }

    fun installStatus(version: String = BuildConfig.DEFAULT_MODEL_VERSION): ModelInstallStatus {
        val modelFile = resolveModelFile(version)
        val isPresent = modelFile.exists()
        val nativeCompatible = !modelFile.name.endsWith("-web.task")
        val checksumVerified = when {
            !isPresent -> false
            BuildConfig.DEFAULT_MODEL_CHECKSUM.isBlank() -> true
            else -> computeSha256(modelFile) == BuildConfig.DEFAULT_MODEL_CHECKSUM
        }
        val issue = when {
            !isPresent -> null
            !nativeCompatible ->
                "Detected ${modelFile.name}, which is the web artifact. Use ${BuildConfig.DEFAULT_MODEL_FILENAME} for the native backend."
            !checksumVerified ->
                "Checksum mismatch for ${modelFile.name}. Re-stage from ${BuildConfig.DEFAULT_MODEL_URL}."
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
        val devVersionDir = File(devModelRoot, version)
        val devCandidates = sequenceOf(
            File(devVersionDir, BuildConfig.DEFAULT_MODEL_FILENAME),
            File(devVersionDir, "model.litertlm"),
            File(devVersionDir, "model.task"),
        )
        for (candidate in devCandidates) {
            if (candidate.exists()) return candidate
        }

        val internalFile = versionedModelFile(version)
        if (internalFile.exists()) return internalFile
        return File(devVersionDir, BuildConfig.DEFAULT_MODEL_FILENAME)
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
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
