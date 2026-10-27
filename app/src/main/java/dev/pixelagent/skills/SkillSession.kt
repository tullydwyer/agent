package dev.pixelagent.skills

import org.json.JSONArray
import org.json.JSONObject

/** Persist names/paths only. Rebuild context by reading trusted bundled packages again;
 * never restore tool calls, grants, or executable work from a conversation. */
class SkillSession(private val registry: SkillRegistry, saved: String = "[]") {
    private val active = linkedMapOf<String, LinkedHashSet<String>>()
    val names: List<String> get() = active.keys.toList()

    init {
        runCatching {
            require(saved.length <= 4_096)
            val entries = JSONArray(saved)
            for (i in 0 until minOf(entries.length(), 3)) {
                val entry = entries.getJSONObject(i)
                runCatching {
                    activate(entry.getString("name"))
                    val paths = entry.optJSONArray("resources") ?: JSONArray()
                    for (j in 0 until minOf(paths.length(), 6)) runCatching { read(entry.getString("name"), paths.getString(j)) }
                }
            }
        }
    }

    fun activate(name: String): JSONObject {
        val skill = registry.get(name)
        require(skill.unavailable == null) { "incompatible_runtime" }
        require(name in active || active.size < 3) { "active_skill_limit" }
        val added = name !in active
        active.getOrPut(name) { linkedSetOf() }
        if (context().length > 24_000) {
            if (added) active.remove(name)
            error("skill_context_limit")
        }
        return instructions(skill)
    }

    fun deactivate(name: String) { active.remove(name) }
    fun clear() { active.clear() }

    fun read(name: String, path: String): JSONObject {
        val paths = active[name] ?: error("skill_not_active")
        require(path in paths || paths.size < 6) { "resource_count_limit" }
        val text = registry.readResource(name, path)
        val added = paths.add(path)
        if (context().length > 24_000) {
            if (added) paths.remove(path)
            error("skill_context_limit")
        }
        return JSONObject().put("name", name).put("path", path).put("text", text)
    }

    fun save(): String = JSONArray(active.map { (name, paths) -> JSONObject().put("name", name).put("resources", JSONArray(paths.toList())) }).toString()

    fun context(): String = JSONArray(active.map { (name, paths) ->
        instructions(registry.get(name)).put("loaded_resources", JSONArray(paths.map { path ->
            JSONObject().put("path", path).put("text", registry.readResource(name, path))
        }))
    }).toString()

    private fun instructions(skill: Skill) = JSONObject().put("name", skill.name)
        .put("base", "bundled://skills/${skill.name}/").put("instructions", skill.instructions)
        .put("resources", JSONArray(skill.resources)).put("allowed_tools_advisory", skill.allowedTools)
}
