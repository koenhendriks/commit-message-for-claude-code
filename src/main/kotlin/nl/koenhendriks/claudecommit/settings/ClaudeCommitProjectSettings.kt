package nl.koenhendriks.claudecommit.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

@Service(Service.Level.PROJECT)
@State(name = "ClaudeCommitMessageProjectSettings", storages = [Storage("claudeCommitMessage.xml")])
class ClaudeCommitProjectSettings : PersistentStateComponent<ClaudeCommitProjectSettings.State> {

    class State {
        var overrideEnabled: Boolean = false
        var prompt: String = DEFAULT_PROMPT
        var model: String = ""
        var effort: String = ""
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    companion object {
        fun getInstance(project: Project): ClaudeCommitProjectSettings = project.service()
    }
}
