package nl.koenhendriks.claudecommit.cli

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import com.intellij.util.concurrency.AppExecutorUtil
import nl.koenhendriks.claudecommit.ClaudeCommitBundle
import nl.koenhendriks.claudecommit.settings.EffectiveSettings
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class ClaudeCliException(message: String) : Exception(message)

object ClaudeCli {
    private val LOG = logger<ClaudeCli>()
    private const val GENERATE_TIMEOUT_MS = 180_000
    const val CLI_DEFAULT_MODEL = "default"

    fun detect(): String? {
        val names = if (SystemInfo.isWindows) listOf("claude.exe", "claude.cmd") else listOf("claude")
        EnvironmentUtil.getValue("PATH").orEmpty()
            .split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .firstNotNullOfOrNull { dir -> names.map { File(dir, it) }.firstOrNull { it.isFile && it.canExecute() } }
            ?.let { return it.absolutePath }

        // IDEs started from a desktop launcher often lack the PATH entries that shell rc files add.
        val home = System.getProperty("user.home")
        return listOf(
            "$home/.local/bin/claude",
            "$home/.claude/local/claude",
            "$home/.local/share/mise/shims/claude",
            "$home/.local/share/mise/installs/claude/latest/claude",
            "$home/.npm-global/bin/claude",
            "/usr/local/bin/claude",
            "/opt/homebrew/bin/claude",
        ).firstOrNull { File(it).canExecute() }
    }

    fun resolve(configuredPath: String): String {
        if (configuredPath.isNotBlank()) {
            if (!File(configuredPath).canExecute()) {
                throw ClaudeCliException(ClaudeCommitBundle.message("error.executable.invalid", configuredPath))
            }
            return configuredPath
        }
        return detect() ?: throw ClaudeCliException(ClaudeCommitBundle.message("error.executable.notFound"))
    }

    // Running in the project directory lets the repository under review inject behaviour through its own
    // .claude/settings.json (hooks run in -p mode without a trust prompt), .mcp.json and CLAUDE.md, so that is
    // only done when the user opted in through the global "use project context" setting.
    private val isolatedWorkDir: Path by lazy {
        Path.of(PathManager.getSystemPath(), "claude-commit-message").also { Files.createDirectories(it) }
    }

    // --tools "" only removes the built-in tools; without --strict-mcp-config every configured MCP tool stays
    // callable by instructions injected through the diff.
    private val ISOLATION_FLAGS = arrayOf(
        "--setting-sources", "user",
        "--strict-mcp-config",
        "--disable-slash-commands",
    )
    private val COMMON_FLAGS = arrayOf(
        "--no-session-persistence",
        "--tools", "",
    )

    private val MODEL_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:@/\\[\\]-]{0,127}")
    private val EFFORT_PATTERN = Regex("[a-z]{1,16}")

    /** With a [projectDir] the CLI loads that project's settings, hooks, MCP servers, skills and CLAUDE.md. */
    fun commandLine(executable: String, projectDir: Path? = null): GeneralCommandLine =
        GeneralCommandLine(executable)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withCharset(StandardCharsets.UTF_8)
            .withWorkDirectory((projectDir ?: isolatedWorkDir).toFile())
            .apply { if (projectDir == null) withParameters(*ISOLATION_FLAGS) }
            .withParameters(*COMMON_FLAGS)

    fun generate(settings: EffectiveSettings, projectDir: Path?, diff: String, indicator: ProgressIndicator): String {
        // Prompt and diff both go through stdin. A prompt passed as argv is parsed as CLI options when it starts with
        // "-" (and can come from a committed .idea file), and argv has length limits.
        val cmd = commandLine(resolve(settings.claudePath), projectDir.takeIf { settings.useProjectContext })
            .withParameters("-p", "--output-format", "text")
            .withParameters(modelParameters(settings))
        LOG.debug("Running ${cmd.commandLineString}")

        val handler = CapturingProcessHandler(cmd)
        val input = buildInput(settings.prompt, diff).toByteArray(StandardCharsets.UTF_8)
        // Written from another thread so a full pipe cannot block us before the output is being drained.
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                handler.processInput.use { it.write(input) }
            } catch (_: IOException) {
                // The process exited before reading its input; the exit code and stderr report why.
            }
        }

        val output = handler.runProcessWithProgressIndicator(indicator, GENERATE_TIMEOUT_MS)
        when {
            output.isCancelled -> throw ProcessCanceledException()
            output.isTimeout -> throw ClaudeCliException(ClaudeCommitBundle.message("error.timeout"))
            output.exitCode != 0 -> throw ClaudeCliException(
                ClaudeCommitBundle.message(
                    "error.exitCode",
                    output.exitCode,
                    output.stderr.ifBlank { output.stdout }.trim(),
                )
            )
        }
        val message = cleanMessage(output.stdout)
        if (message.isEmpty()) throw ClaudeCliException(ClaudeCommitBundle.message("error.emptyOutput"))
        return message
    }

    /** Model and effort may come from a project file, so they are checked before they reach the command line. */
    internal fun modelParameters(settings: EffectiveSettings): List<String> = buildList {
        val model = settings.model.trim()
        if (model.isNotEmpty() && model != CLI_DEFAULT_MODEL) {
            if (!MODEL_PATTERN.matches(model)) {
                throw ClaudeCliException(ClaudeCommitBundle.message("error.invalidValue", "model", model))
            }
            add("--model=$model")
        }
        val effort = settings.effort.trim()
        if (effort.isNotEmpty()) {
            if (!EFFORT_PATTERN.matches(effort)) {
                throw ClaudeCliException(ClaudeCommitBundle.message("error.invalidValue", "effort", effort))
            }
            add("--effort=$effort")
        }
    }

    internal fun buildInput(prompt: String, diff: String): String = "<diff>\n$diff\n</diff>\n\n$prompt\n"

    internal fun cleanMessage(raw: String): String {
        val text = raw.trim()
        val fenced = Regex("^```[\\w-]*\\s*\\n(.*?)\\n?```$", RegexOption.DOT_MATCHES_ALL).matchEntire(text)
        return (fenced?.groupValues?.get(1) ?: text).trim()
    }
}
