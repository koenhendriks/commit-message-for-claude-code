package nl.koenhendriks.claudecommit

import com.intellij.icons.AllIcons
import com.intellij.ide.ActivityTracker
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.vcs.commit.AbstractCommitWorkflowHandler
import com.intellij.vcs.commit.CommitWorkflowUi
import nl.koenhendriks.claudecommit.cli.ClaudeCli
import nl.koenhendriks.claudecommit.cli.ClaudeCliException
import nl.koenhendriks.claudecommit.settings.EffectiveSettings
import java.nio.file.Path

class GenerateCommitMessageAction : DumbAwareAction() {
    private val log = logger<GenerateCommitMessageAction>()

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val available = project != null &&
            e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) != null &&
            commitUi(e) != null
        e.presentation.isEnabledAndVisible = available
        if (!available) return

        if (GenerationState.getInstance(project).isRunning) {
            e.presentation.icon = AllIcons.Actions.Suspend
            e.presentation.text = ClaudeCommitBundle.message("action.generate.stop.text")
        } else {
            e.presentation.icon = ClaudeIcons.Generate
            e.presentation.text = ClaudeCommitBundle.message("action.ClaudeCommit.Generate.text")
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val messageControl = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL) ?: return
        val ui = commitUi(e) ?: return
        val state = GenerationState.getInstance(project)

        state.indicator?.takeIf { it.isRunning }?.let {
            it.cancel()
            return
        }

        val changes = ui.getIncludedChanges()
        val unversioned = ui.getIncludedUnversionedFiles()
        if (changes.isEmpty() && unversioned.isEmpty()) {
            notify(project, ClaudeCommitBundle.message("notification.noChanges"), NotificationType.WARNING)
            return
        }
        val settings = EffectiveSettings.of(project)

        object : Task.Backgroundable(project, ClaudeCommitBundle.message("task.generate.title"), true) {
            private var message: String? = null

            override fun run(indicator: ProgressIndicator) {
                state.indicator = indicator
                ActivityTracker.getInstance().inc()
                indicator.isIndeterminate = true

                val result = DiffCollector.collect(project, changes, unversioned)
                if (result.truncated) {
                    notify(
                        project,
                        ClaudeCommitBundle.message("notification.truncated", DiffCollector.MAX_CHARS),
                        NotificationType.WARNING,
                    )
                }
                indicator.checkCanceled()
                message = ClaudeCli.generate(settings, project.basePath?.let(Path::of), result.diff, indicator)
            }

            override fun onSuccess() {
                message?.let(messageControl::setCommitMessage)
            }

            override fun onThrowable(error: Throwable) {
                if (error !is ClaudeCliException) log.warn(error)
                notify(
                    project,
                    error.message ?: error.javaClass.simpleName,
                    NotificationType.ERROR,
                    ClaudeCommitBundle.message("notification.failed.title"),
                )
            }

            override fun onFinished() {
                state.indicator = null
                ActivityTracker.getInstance().inc()
            }
        }.queue()
    }

    private fun commitUi(e: AnActionEvent): CommitWorkflowUi? =
        e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)
            ?: (e.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER) as? AbstractCommitWorkflowHandler<*, *>)?.ui
}
