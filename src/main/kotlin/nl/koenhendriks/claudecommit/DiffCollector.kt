package nl.koenhendriks.claudecommit

import com.intellij.openapi.diff.impl.patch.FilePatch
import com.intellij.openapi.diff.impl.patch.IdeaTextPatchBuilder
import com.intellij.openapi.diff.impl.patch.TextFilePatch
import com.intellij.openapi.diff.impl.patch.UnifiedDiffWriter
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import java.io.StringWriter
import java.nio.file.Path

object DiffCollector {
    const val MAX_CHARS = 200_000

    data class Result(val diff: String, val truncated: Boolean)

    fun collect(project: Project, changes: List<Change>, unversioned: List<FilePath>): Result {
        val all = changes + unversioned.map { Change(null, CurrentContentRevision.create(it)) }
        val basePath = Path.of(project.basePath ?: System.getProperty("user.home"))
        // honorExcludedFromCommit keeps lines unchecked in the commit diff out of the patch, matching what gets committed.
        val patches = IdeaTextPatchBuilder.buildPatch(project, all, basePath, false, true)

        val writer = StringWriter()
        UnifiedDiffWriter.write(project, patches.filterIsInstance<TextFilePatch>(), writer, "\n", null)
        patches.filterNot { it is TextFilePatch }.forEach { writer.append("Binary file changed: ${it.displayPath()}\n") }

        val diff = writer.toString()
        if (diff.length <= MAX_CHARS) return Result(diff, false)
        return Result(diff.take(MAX_CHARS) + "\n\n[diff truncated after $MAX_CHARS characters]\n", true)
    }

    private fun FilePatch.displayPath(): String = afterName ?: beforeName ?: "?"
}
