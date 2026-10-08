package nl.koenhendriks.claudecommit.cli

import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection

@Tag("model")
data class ClaudeModel(
    var value: String = "",
    var displayName: String = "",
    var description: String = "",
    @get:XCollection(elementName = "effort")
    var supportedEffortLevels: MutableList<String> = mutableListOf(),
)
