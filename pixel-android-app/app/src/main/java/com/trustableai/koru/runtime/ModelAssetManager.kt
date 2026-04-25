package com.trustableai.koru.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.trustableai.koru.BuildConfig
import com.trustableai.koru.model.ModelInstallStatus
import java.io.File

class ModelAssetManager(private val context: Context) {
    private val modelRoot: File by lazy {
        File(context.filesDir, "models").apply { mkdirs() }
    }

    fun versionedModelFile(version: String = BuildConfig.DEFAULT_MODEL_VERSION): File {
        val versionDir = File(modelRoot, version).apply { mkdirs() }
        return File(versionDir, "model.task")
    }

    fun installStatus(version: String = BuildConfig.DEFAULT_MODEL_VERSION): ModelInstallStatus {
        val modelFile = versionedModelFile(version)
        val checksumVerified = BuildConfig.DEFAULT_MODEL_CHECKSUM.isBlank() || !modelFile.exists().not()
        return ModelInstallStatus(
            version = version,
            isPresent = modelFile.exists(),
            checksumVerified = checksumVerified,
            downloadAllowedOnCurrentNetwork = isUnmeteredNetworkAvailable(),
            filePath = modelFile.absolutePath,
        )
    }

    fun recommendedDownloadAction(version: String = BuildConfig.DEFAULT_MODEL_VERSION): String {
        val status = installStatus(version)
        if (status.isPresent && status.checksumVerified) {
            return "Model ready at ${status.filePath}"
        }
        return if (status.downloadAllowedOnCurrentNetwork) {
            "Model missing. Device is on unmetered network; download can proceed."
        } else {
            "Model missing. Wait for Wi-Fi before downloading $version."
        }
    }

    private fun isUnmeteredNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
