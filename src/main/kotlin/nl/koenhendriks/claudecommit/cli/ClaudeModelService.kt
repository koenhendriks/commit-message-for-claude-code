package nl.koenhendriks.claudecommit.cli

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.util.concurrency.AppExecutorUtil
import nl.koenhendriks.claudecommit.ClaudeCommitBundle
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The CLI has no command that lists models, but the `initialize` request of its stream-json control protocol
 * answers with the same list the `/model` picker shows. No prompt is sent, so this costs no tokens.
 */
object ClaudeModelService {
    private val LOG = logger<ClaudeModelService>()
    private const val TIMEOUT_MS = 30_000L
    private const val REQUEST_ID = "claude-commit-models"

    fun fetch(claudePath: String, indicator: ProgressIndicator?): List<ClaudeModel> {
        val cmd = ClaudeCli.commandLine(ClaudeCli.resolve(claudePath)).withParameters(
            "-p",
            "--input-format", "stream-json",
            "--output-format", "stream-json",
            "--verbose",
        )
        val process = cmd.createProcess()
        val stderr = StringBuffer()
        val stderrDrain = AppExecutorUtil.getAppExecutorService().submit {
            process.errorStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { stderr.appendLine(it) }
        }
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        val timedOut = AtomicBoolean()
        val watchdog = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
            if (System.currentTimeMillis() > deadline) timedOut.set(true)
            if (timedOut.get() || indicator?.isCanceled == true) process.destroyForcibly()
        }, 200, 200, TimeUnit.MILLISECONDS)

        try {
            // stdin must stay open until the response arrives: on EOF the CLI exits without answering.
            process.outputStream.apply {
                write("""{"type":"control_request","request_id":"$REQUEST_ID","request":{"subtype":"initialize"}}""".toByteArray())
                write('\n'.code)
                flush()
            }
            val reader = process.inputStream.bufferedReader(StandardCharsets.UTF_8)
            while (true) {
                val line = reader.readLine() ?: break
                parseModels(line, REQUEST_ID)?.let { return it }
            }
            indicator?.checkCanceled()
            if (timedOut.get()) throw ClaudeCliException(ClaudeCommitBundle.message("error.timeout"))
            stderrDrain.get(2, TimeUnit.SECONDS)
            throw ClaudeCliException(ClaudeCommitBundle.message("error.models.noResponse", stderr.toString().trim()))
        } finally {
            watchdog.cancel(false)
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            LOG.debug("Model fetch stderr: $stderr")
        }
    }

    internal fun parseModels(line: String, requestId: String): List<ClaudeModel>? {
        val message = runCatching { JsonParser.parseString(line) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        if (message.string("type") != "control_response") return null
        val response = message.obj("response") ?: return null
        if (response.string("request_id") != requestId) return null
        if (response.string("subtype") == "error") {
            throw ClaudeCliException(response.string("error") ?: ClaudeCommitBundle.message("error.models.unknown"))
        }
        val models = response.obj("response")?.get("models")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return emptyList()
        return models.filter { it.isJsonObject }.map { it.asJsonObject }.mapNotNull { model ->
            val value = model.string("value") ?: return@mapNotNull null
            ClaudeModel(
                value = value,
                displayName = model.string("displayName") ?: value,
                description = model.string("description").orEmpty(),
                supportedEffortLevels = model.get("supportedEffortLevels")
                    ?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { level -> level.takeIf { it.isJsonPrimitive }?.asString }
                    ?.toMutableList()
                    ?: mutableListOf(),
            )
        }
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject
}
