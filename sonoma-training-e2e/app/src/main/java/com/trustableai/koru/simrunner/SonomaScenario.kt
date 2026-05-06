package com.trustableai.koru.simrunner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ScenarioSample(
    val timestampSec: Double,
    val latitude: Double,
    val longitude: Double,
    val speedMps: Double,
    val bearingDeg: Double,
    val altitude: Double,
    val labels: List<String>,
)

data class SonomaScenario(
    val schemaVersion: Int,
    val name: String,
    val trackName: String,
    val sampleRateHz: Double,
    val samples: List<ScenarioSample>,
)

object SonomaScenarioLoader {
    fun load(context: Context, assetPath: String): SonomaScenario {
        val jsonText = context.assets.open(assetPath).bufferedReader().use { it.readText() }
        val root = JSONObject(jsonText)
        val samplesJson = root.getJSONArray("samples")
        val samples =
            buildList {
                for (i in 0 until samplesJson.length()) {
                    add(samplesJson.getJSONObject(i).toSample())
                }
            }
        return SonomaScenario(
            schemaVersion = root.getInt("schemaVersion"),
            name = root.getString("name"),
            trackName = root.getString("trackName"),
            sampleRateHz = root.getDouble("sampleRateHz"),
            samples = samples,
        )
    }

    private fun JSONObject.toSample(): ScenarioSample {
        return ScenarioSample(
            timestampSec = getDouble("timestamp"),
            latitude = getDouble("lat"),
            longitude = getDouble("lon"),
            speedMps = getDouble("speedMps"),
            bearingDeg = getDouble("bearingDeg"),
            altitude = optDouble("altitude", 32.0),
            labels = optJSONArray("labels").toStringList(),
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) add(getString(i))
        }
    }
}
