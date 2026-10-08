package nl.koenhendriks.claudecommit.settings

import com.intellij.openapi.project.Project

data class EffectiveSettings(
    val prompt: String,
    val model: String,
    val effort: String,
    val claudePath: String,
    val useProjectContext: Boolean = false,
) {
    companion object {
        fun of(project: Project): EffectiveSettings {
            val global = ClaudeCommitSettings.getInstance().state
            val local = ClaudeCommitProjectSettings.getInstance(project).state
            val source = if (local.overrideEnabled) {
                Triple(local.prompt, local.model, local.effort)
            } else {
                Triple(global.prompt, global.model, global.effort)
            }
            return EffectiveSettings(
                prompt = source.first.ifBlank { DEFAULT_PROMPT },
                model = source.second,
                effort = source.third,
                claudePath = global.claudePath,
                useProjectContext = global.useProjectContext,
            )
        }
    }
}
