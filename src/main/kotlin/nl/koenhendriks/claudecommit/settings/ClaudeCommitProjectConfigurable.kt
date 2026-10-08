package nl.koenhendriks.claudecommit.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import nl.koenhendriks.claudecommit.ClaudeCommitBundle
import javax.swing.JComponent

class ClaudeCommitProjectConfigurable(private val project: Project) : Configurable {
    private var form: SettingsForm? = null

    override fun getDisplayName(): String = ClaudeCommitBundle.message("settings.project.displayName")

    override fun createComponent(): JComponent = SettingsForm(project).also { form = it }.panel

    override fun isModified(): Boolean = form?.getValues()?.copy(claudePath = "") != stored()

    override fun apply() {
        val values = form?.getValues() ?: return
        ClaudeCommitProjectSettings.getInstance(project).state.apply {
            overrideEnabled = values.overrideEnabled
            prompt = values.prompt
            model = values.model
            effort = values.effort
        }
    }

    override fun reset() {
        form?.setValues(stored())
    }

    override fun disposeUIResources() {
        form = null
    }

    private fun stored(): FormValues = ClaudeCommitProjectSettings.getInstance(project).state.let {
        FormValues(it.prompt, it.model, it.effort, overrideEnabled = it.overrideEnabled)
    }
}
