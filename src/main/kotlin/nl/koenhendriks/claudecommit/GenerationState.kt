package nl.koenhendriks.claudecommit

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class GenerationState {
    @Volatile
    var indicator: ProgressIndicator? = null

    val isRunning: Boolean
        get() = indicator?.isRunning == true

    companion object {
        fun getInstance(project: Project): GenerationState = project.service()
    }
}
