package dev.pixelagent.tools

import dev.pixelagent.skills.SkillSession
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class ToolCall(val id: String, val namespace: String, val name: String, val arguments: String)

/** Only registered native capabilities execute. Skill text/allowed-tools are never grants. */
class ToolDispatcher(
    private val session: SkillSession,
    private val skillsAllowed: () -> Boolean,
) {
    val definitions: JSONArray get() = JSONArray().put(JSONObject().put("type", "namespace").put("name", "skills")
        .put("description", "Load bundled instructions and bounded text resources. No scripts or phone actions.")
        .put("tools", JSONArray()
            .put(function("activate", "Activate a compatible skill from the catalog.", listOf("name")))
            .put(function("read_resource", "Read a listed text resource of an active skill. Use its relative path.", listOf("name", "relative_path")))))

    suspend fun execute(call: ToolCall): String {
        currentCoroutineContext().ensureActive()
        if (call.namespace != "skills" || call.name !in setOf("activate", "read_resource")) return failure("unknown_tool")
        if (!skillsAllowed()) return failure("permission_denied")
        val result = try {
            require(call.arguments.length <= 2_048) { "invalid_arguments" }
            val tokener = JSONTokener(call.arguments)
            val args = tokener.nextValue() as? JSONObject ?: error("invalid_arguments")
            require(tokener.nextClean() == '\u0000') { "invalid_arguments" }
            val keys = if (call.name == "activate") setOf("name") else setOf("name", "relative_path")
            require(args.keys().asSequence().toSet() == keys && keys.all { args.get(it) is String }) { "invalid_arguments" }
            require(args.getString("name").length in 1..64) { "invalid_arguments" }
            if (call.name == "read_resource") require(args.getString("relative_path").length in 1..200) { "invalid_arguments" }
            currentCoroutineContext().ensureActive()
            val value = if (call.name == "activate") session.activate(args.getString("name"))
                else session.read(args.getString("name"), args.getString("relative_path"))
            JSONObject().put("ok", true).put("result", value).toString()
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { return failure("invalid_or_unavailable_skill_resource") }
        check(result.length <= 32_000) { "tool_result_limit" }
        return result
    }

    private fun failure(code: String) = JSONObject().put("ok", false).put("error", code).toString()
    private fun function(name: String, description: String, keys: List<String>): JSONObject {
        val properties = JSONObject()
        keys.forEach { properties.put(it, JSONObject().put("type", "string")) }
        return JSONObject().put("type", "function").put("name", name).put("description", description).put("strict", true)
            .put("parameters", JSONObject().put("type", "object").put("properties", properties)
                .put("required", JSONArray(keys)).put("additionalProperties", false))
    }
}
