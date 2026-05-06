package com.trustableai.koru.runtime

import android.content.Context
import com.trustableai.koru.model.CoachAction
import com.trustableai.koru.model.SkillLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

interface PhraseRenderer {
    fun phraseIdFor(action: CoachAction, skillLevel: SkillLevel, coachId: String): String

    fun render(action: CoachAction, skillLevel: SkillLevel, coachId: String): String
}

class PhraseCatalog(private val context: Context) : PhraseRenderer {
    private var loaded = false
    private var phrases: Map<String, PhraseEntry> = emptyMap()

    suspend fun ensureLoaded() {
        if (loaded) return
        withContext(Dispatchers.IO) {
            if (loaded) return@withContext
            val raw = context.assets.open("coaching-phrases.json").bufferedReader().use { it.readText() }
            val root = JSONObject(raw)
            val actions = root.getJSONObject("actions")
            val parsed = mutableMapOf<String, PhraseEntry>()
            actions.keys().forEach { actionName ->
                val entry = actions.getJSONObject(actionName)
                val personas = mutableMapOf<String, String>()
                if (entry.has("personas")) {
                    val personaObject = entry.getJSONObject("personas")
                    personaObject.keys().forEach { coachId ->
                        personas[coachId] = personaObject.getString(coachId)
                    }
                }
                parsed[actionName] = PhraseEntry(
                    defaultText = entry.getString("default"),
                    beginnerText = entry.optString("beginner").ifBlank { null },
                    advancedText = entry.optString("advanced").ifBlank { null },
                    personas = personas,
                )
            }
            phrases = parsed
            loaded = true
        }
    }

    override fun phraseIdFor(action: CoachAction, skillLevel: SkillLevel, coachId: String): String {
        return "${action.name}:${skillLevel.name.lowercase()}:$coachId"
    }

    override fun render(action: CoachAction, skillLevel: SkillLevel, coachId: String): String {
        val entry = phrases[action.name] ?: return action.name.replace('_', ' ').lowercase()
        entry.personas[coachId]?.let { return it }
        return when (skillLevel) {
            SkillLevel.BEGINNER -> entry.beginnerText ?: entry.defaultText
            SkillLevel.ADVANCED -> entry.advancedText ?: entry.defaultText
            SkillLevel.INTERMEDIATE -> entry.defaultText
        }
    }

    private data class PhraseEntry(
        val defaultText: String,
        val beginnerText: String?,
        val advancedText: String?,
        val personas: Map<String, String>,
    )
}
