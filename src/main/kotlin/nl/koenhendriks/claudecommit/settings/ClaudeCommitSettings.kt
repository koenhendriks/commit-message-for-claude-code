package nl.koenhendriks.claudecommit.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.XCollection
import nl.koenhendriks.claudecommit.cli.ClaudeModel

const val DEFAULT_PROMPT =
    "propose a conventional commit message for these changes. output only the message, nothing else, without any markdown or backticks."

@Service(Service.Level.APP)
@State(name = "ClaudeCommitMessageSettings", storages = [Storage("claudeCommitMessage.xml")])
class ClaudeCommitSettings : PersistentStateComponent<ClaudeCommitSettings.State> {

    class State {
        var prompt: String = DEFAULT_PROMPT
        var model: String = ""
        var effort: String = ""
        var claudePath: String = ""

        // Global only on purpose: a project-level flag could be committed to a repository to opt itself in.
        var useProjectContext: Boolean = false

        @XCollection(style = XCollection.Style.v2)
        var cachedModels: MutableList<ClaudeModel> = mutableListOf()
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    companion object {
        fun getInstance(): ClaudeCommitSettings = service()
    }
}
