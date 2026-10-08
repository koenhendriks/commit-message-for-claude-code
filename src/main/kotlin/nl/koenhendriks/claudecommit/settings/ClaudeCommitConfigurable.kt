package nl.koenhendriks.claudecommit.settings

import com.intellij.openapi.options.Configurable
import nl.koenhendriks.claudecommit.ClaudeCommitBundle
import javax.swing.JComponent

class ClaudeCommitConfigurable : Configurable {
    private var form: SettingsForm? = null

    override fun getDisplayName(): String = ClaudeCommitBundle.message("settings.displayName")

    override fun createComponent(): JComponent = SettingsForm(null).also { form = it }.panel

    override fun isModified(): Boolean = form?.getValues() != stored()

    override fun apply() {
        val values = form?.getValues() ?: return
        ClaudeCommitSettings.getInstance().state.apply {
            prompt = values.prompt
            model = values.model
            effort = values.effort
            claudePath = values.claudePath
            useProjectContext = values.useProjectContext
        }
    }

    override fun reset() {
        form?.setValues(stored())
    }

    override fun disposeUIResources() {
        form = null
    }

    private fun stored(): FormValues = ClaudeCommitSettings.getInstance().state.let {
        FormValues(it.prompt, it.model, it.effort, it.claudePath, useProjectContext = it.useProjectContext)
    }
}
