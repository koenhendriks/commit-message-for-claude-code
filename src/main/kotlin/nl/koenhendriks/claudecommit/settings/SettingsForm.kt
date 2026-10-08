package nl.koenhendriks.claudecommit.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.UIUtil
import nl.koenhendriks.claudecommit.ClaudeCommitBundle
import nl.koenhendriks.claudecommit.cli.ClaudeCli
import nl.koenhendriks.claudecommit.cli.ClaudeModel
import nl.koenhendriks.claudecommit.cli.ClaudeModelService
import javax.swing.DefaultComboBoxModel
import javax.swing.JList

data class FormValues(
    val prompt: String,
    val model: String,
    val effort: String,
    val claudePath: String = "",
    val overrideEnabled: Boolean = false,
    val useProjectContext: Boolean = false,
)

/**
 * Shared by the global page and the project override page. [project] is null for the global page, which is the
 * only one that shows the executable path.
 */
class SettingsForm(private val project: Project?) {
    private val isProjectPage = project != null

    private val overrideCheckBox = JBCheckBox(ClaudeCommitBundle.message("settings.override"))
    private val projectContextCheckBox = JBCheckBox(ClaudeCommitBundle.message("settings.projectContext"))
    private val executableField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile())
    }
    private val modelCombo = ComboBox<ClaudeModel>().apply { renderer = ModelRenderer() }
    private val effortCombo = ComboBox<String>().apply {
        renderer = textListCellRenderer { it.ifEmpty { ClaudeCommitBundle.message("settings.effort.cliDefault") } }
    }
    private val promptArea = JBTextArea(6, 60).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val modelStatus = JBLabel().apply {
        componentStyle = UIUtil.ComponentStyle.SMALL
        fontColor = UIUtil.FontColor.BRIGHTER
    }
    private var loadingModels = false
    private var resetLink: ActionLink? = null
    private val refreshAction = RefreshModelsAction()

    val panel: DialogPanel = panel {
        if (isProjectPage) {
            row {
                cell(overrideCheckBox).comment(ClaudeCommitBundle.message("settings.override.comment"))
            }
        } else {
            row(ClaudeCommitBundle.message("settings.executable")) {
                cell(executableField).align(AlignX.FILL).comment(executableComment())
            }
            row {
                cell(projectContextCheckBox).comment(ClaudeCommitBundle.message("settings.projectContext.comment"))
            }
        }
        row(ClaudeCommitBundle.message("settings.model")) {
            cell(modelCombo).align(AlignX.FILL).resizableColumn()
            cell(
                ActionButton(
                    refreshAction,
                    refreshAction.templatePresentation.clone(),
                    ActionPlaces.UNKNOWN,
                    ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE,
                )
            )
        }
        row("") { cell(modelStatus) }
        row(ClaudeCommitBundle.message("settings.effort")) {
            cell(effortCombo)
        }
        row(ClaudeCommitBundle.message("settings.prompt")) {}
        row {
            scrollCell(promptArea).align(Align.FILL).comment(ClaudeCommitBundle.message("settings.prompt.comment"))
        }.resizableRow()
        row {
            resetLink = link(ClaudeCommitBundle.message("settings.prompt.reset")) { promptArea.text = DEFAULT_PROMPT }
                .component
        }
    }

    init {
        modelCombo.addItemListener { fillEfforts(effortCombo.item.orEmpty()) }
        overrideCheckBox.addItemListener {
            // A first-time override starts from the global values instead of the plugin defaults.
            if (overrideCheckBox.isSelected && project != null &&
                !ClaudeCommitProjectSettings.getInstance(project).state.overrideEnabled
            ) {
                val global = ClaudeCommitSettings.getInstance().state
                setValues(FormValues(global.prompt, global.model, global.effort, overrideEnabled = true))
            }
            updateEnabled()
        }
    }

    fun getValues(): FormValues = FormValues(
        prompt = promptArea.text,
        model = modelCombo.item?.value.orEmpty(),
        effort = effortCombo.item.orEmpty(),
        claudePath = executableField.text.trim(),
        overrideEnabled = overrideCheckBox.isSelected,
        useProjectContext = projectContextCheckBox.isSelected,
    )

    fun setValues(values: FormValues) {
        promptArea.text = values.prompt
        executableField.text = values.claudePath
        projectContextCheckBox.isSelected = values.useProjectContext
        if (overrideCheckBox.isSelected != values.overrideEnabled) overrideCheckBox.isSelected = values.overrideEnabled
        fillModels(ClaudeCommitSettings.getInstance().state.cachedModels, values.model, values.effort)
        updateEnabled()
    }

    private fun fillModels(models: List<ClaudeModel>, selected: String, effort: String) {
        // The CLI's own "default" entry becomes our "omit --model" item, so the two never show up side by side.
        val cliDefault = models.firstOrNull { it.value == ClaudeCli.CLI_DEFAULT_MODEL }
        val items = mutableListOf(
            ClaudeModel(
                value = "",
                displayName = cliDefault?.displayName ?: ClaudeCommitBundle.message("settings.model.cliDefault"),
                description = cliDefault?.description.orEmpty(),
                supportedEffortLevels = cliDefault?.supportedEffortLevels ?: mutableListOf(),
            )
        )
        items += models.filter { it.value.isNotEmpty() && it.value != ClaudeCli.CLI_DEFAULT_MODEL }
        val wanted = selected.takeUnless { it == ClaudeCli.CLI_DEFAULT_MODEL }.orEmpty()
        if (items.none { it.value == wanted }) {
            items += ClaudeModel(
                value = wanted,
                displayName = ClaudeCommitBundle.message("settings.model.notInList", wanted),
                supportedEffortLevels = if (effort.isEmpty()) mutableListOf() else mutableListOf(effort),
            )
        }
        modelCombo.model = DefaultComboBoxModel(items.toTypedArray())
        modelCombo.selectedItem = items.first { it.value == wanted }
        fillEfforts(effort)

        modelStatus.text = if (models.isEmpty()) {
            ClaudeCommitBundle.message("settings.model.neverFetched")
        } else {
            ClaudeCommitBundle.message("settings.model.fetched", models.size)
        }
    }

    private fun fillEfforts(selected: String) {
        val levels = modelCombo.item?.supportedEffortLevels.orEmpty()
        val items = listOf("") + levels
        effortCombo.model = DefaultComboBoxModel(items.toTypedArray())
        effortCombo.selectedItem = if (selected in levels) selected else ""
        updateEnabled()
    }

    private fun updateEnabled() {
        val editable = !isProjectPage || overrideCheckBox.isSelected
        modelCombo.isEnabled = editable && !loadingModels
        effortCombo.isEnabled = editable && !loadingModels && effortCombo.itemCount > 1
        promptArea.isEnabled = editable
        resetLink?.isEnabled = editable
    }

    private fun refreshModels() {
        val claudePath = if (isProjectPage) {
            ClaudeCommitSettings.getInstance().state.claudePath
        } else {
            executableField.text.trim()
        }
        val current = getValues()
        loadingModels = true
        updateEnabled()
        try {
            val models = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<List<ClaudeModel>, Exception> {
                    ClaudeModelService.fetch(claudePath, ProgressManager.getInstance().progressIndicator)
                },
                ClaudeCommitBundle.message("task.models.title"),
                true,
                project,
            )
            ClaudeCommitSettings.getInstance().state.cachedModels = models.toMutableList()
            fillModels(models, current.model, current.effort)
        } catch (e: Exception) {
            modelStatus.text = e.message ?: e.javaClass.simpleName
        } finally {
            loadingModels = false
            updateEnabled()
        }
    }

    private fun executableComment(): String {
        val detected = ClaudeCli.detect()
        return if (detected != null) {
            ClaudeCommitBundle.message("settings.executable.autodetected", detected)
        } else {
            ClaudeCommitBundle.message("settings.executable.notDetected")
        }
    }

    private inner class RefreshModelsAction : DumbAwareAction(
        ClaudeCommitBundle.message("settings.model.refresh"),
        null,
        AllIcons.Actions.Refresh,
    ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !loadingModels && (!isProjectPage || overrideCheckBox.isSelected)
        }

        override fun actionPerformed(e: AnActionEvent) = refreshModels()
    }

    private class ModelRenderer : ColoredListCellRenderer<ClaudeModel>() {
        override fun customizeCellRenderer(
            list: JList<out ClaudeModel>,
            value: ClaudeModel?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            if (value == null) return
            append(value.displayName)
            if (value.description.isNotBlank()) {
                append("   " + value.description, SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }
}
