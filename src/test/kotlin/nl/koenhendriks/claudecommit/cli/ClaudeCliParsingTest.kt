package nl.koenhendriks.claudecommit.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import nl.koenhendriks.claudecommit.settings.EffectiveSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class ClaudeCliParsingTest {
    private val response = """
        {"type":"control_response","response":{"subtype":"success","request_id":"r1","response":{"commands":[],"models":[
          {"value":"default","resolvedModel":"claude-opus-5-5[1m]","displayName":"Default (recommended)","description":"Opus 5.5","supportedEffortLevels":["low","high"]},
          {"value":"haiku","resolvedModel":"claude-haiku-4-5-20251001","displayName":"Haiku","description":"Haiku 4.5"}
        ]}}}
    """.trimIndent().replace("\n", "")

    @Test
    fun `parses models from the initialize response`() {
        val models = ClaudeModelService.parseModels(response, "r1")!!

        assertEquals(listOf("default", "haiku"), models.map { it.value })
        assertEquals(listOf("low", "high"), models[0].supportedEffortLevels)
        assertEquals(emptyList<String>(), models[1].supportedEffortLevels)
    }

    @Test
    fun `ignores unrelated lines`() {
        assertNull(ClaudeModelService.parseModels("""{"type":"system","subtype":"hook_started"}""", "r1"))
        assertNull(ClaudeModelService.parseModels("not json", "r1"))
        assertNull(ClaudeModelService.parseModels(response, "other-id"))
    }

    @Test
    fun `surfaces control errors`() {
        val error = """{"type":"control_response","response":{"subtype":"error","request_id":"r1","error":"boom"}}"""

        assertEquals("boom", assertThrows(ClaudeCliException::class.java) { ClaudeModelService.parseModels(error, "r1") }.message)
    }

    @Test
    fun `strips a wrapping markdown fence`() {
        assertEquals("feat: add x\n\nbody", ClaudeCli.cleanMessage("```text\nfeat: add x\n\nbody\n```\n"))
        assertEquals("fix: y", ClaudeCli.cleanMessage("  fix: y \n"))
    }

    @Test
    fun `passes valid model and effort as single arguments`() {
        assertEquals(listOf("--model=opus[1m]", "--effort=high"), ClaudeCli.modelParameters(settings("opus[1m]", "high")))
        assertEquals(listOf("--model=us.anthropic.claude-sonnet-5:0"), ClaudeCli.modelParameters(settings("us.anthropic.claude-sonnet-5:0", "")))
        assertEquals(emptyList<String>(), ClaudeCli.modelParameters(settings("default", "")))
    }

    @Test
    fun `rejects model and effort values that could smuggle in options`() {
        listOf("--settings={}", "-p", "haiku --mcp-config x", "hai\nku", "a\"b", "a&b").forEach { model ->
            assertThrows(ClaudeCliException::class.java) { ClaudeCli.modelParameters(settings(model, "")) }
        }
        assertThrows(ClaudeCliException::class.java) { ClaudeCli.modelParameters(settings("", "--max")) }
    }

    private fun settings(model: String, effort: String) = EffectiveSettings("prompt", model, effort, "")

    @Test
    fun `isolates the CLI unless project context is opted into`() {
        val isolated = ClaudeCli.commandLine("claude").parametersList.list
        assertTrue(isolated.containsAll(listOf("--setting-sources", "user", "--strict-mcp-config", "--disable-slash-commands")))

        val project = Path.of("/tmp/some-project")
        val withContext = ClaudeCli.commandLine("claude", project)
        assertEquals(project, withContext.workingDirectory)
        assertFalse(withContext.parametersList.list.any { it in listOf("--setting-sources", "--strict-mcp-config", "--disable-slash-commands") })

        listOf(isolated, withContext.parametersList.list).forEach {
            assertTrue(it.containsAll(listOf("--no-session-persistence", "--tools", "")))
        }
    }
}
