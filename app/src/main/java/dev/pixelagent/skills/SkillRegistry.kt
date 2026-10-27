package dev.pixelagent.skills

import org.json.JSONArray
import org.json.JSONObject
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/** A package source must enforce the byte limit before decoding, and never follow symlinks.
 * Production uses immutable APK assets; importing filesystem packages is not supported. */
interface SkillSource {
    fun directories(): List<String>
    fun files(name: String): List<String>
    fun read(name: String, path: String, maxBytes: Int): String
}

data class Skill(
    val name: String, val description: String, val instructions: String,
    val compatibility: String, val allowedTools: String, val resources: List<String>,
    val unavailable: String? = null,
)
data class SkillIssue(val directory: String, val reason: String)

class SkillRegistry(private val source: SkillSource) {
    val skills: List<Skill>
    val issues: List<SkillIssue>

    init {
        val found = mutableListOf<Skill>()
        val rejected = mutableListOf<SkillIssue>()
        val directories = source.directories().sorted()
        require(directories.size <= 24) { "skill_catalog_limit" }
        directories.forEach { name ->
            try {
                require(validName(name)) { "invalid_directory" }
                val files = source.files(name)
                require(files.size <= 64 && files.all(::validPath)) { "invalid_package_paths_or_count" }
                val skill = parse(name, source.read(name, "SKILL.md", 16_384), files)
                found.add(skill)
            } catch (_: Exception) {
                // Never put parser exceptions (which can contain skill text) in logs/UI.
                rejected.add(SkillIssue(name.take(64), "Invalid or oversized SKILL.md/package"))
            }
        }
        skills = found.toList()
        issues = rejected.toList()
    }

    fun get(name: String): Skill = skills.singleOrNull { it.name == name } ?: throw IllegalArgumentException("unknown_skill")

    fun readResource(name: String, path: String): String {
        val skill = get(name)
        require(skill.unavailable == null) { "incompatible_runtime" }
        require(validPath(path) && path in skill.resources && !path.startsWith("scripts/")) { "resource_not_allowed" }
        val text = source.read(name, path, 8_192)
        require('\u0000' !in text) { "binary_resource" }
        return text
    }

    fun catalog(): String = JSONArray(skills.map {
        JSONObject().put("name", it.name).put("description", it.description)
            .put("compatibility", it.compatibility).put("unavailable", it.unavailable ?: JSONObject.NULL)
    }).toString()

    companion object {
        fun validName(value: String) = value.length in 1..64 && value.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))
        fun validPath(value: String): Boolean = value.length in 1..200 &&
            value.split('/').all { it != "." && it != ".." && it.matches(Regex("[a-zA-Z0-9_.-]+")) }

        fun parse(directory: String, text: String, files: List<String>): Skill {
            require(text.toByteArray(Charsets.UTF_8).size <= 16_384) { "skill_size_limit" }
            val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n")
            val lines = normalized.lines()
            require(lines.firstOrNull() == "---") { "missing_frontmatter" }
            val end = (1 until lines.size).firstOrNull { lines[it] == "---" } ?: error("missing_frontmatter_end")
            val header = lines.subList(1, end).joinToString("\n")
            require(header.length <= 8_192) { "frontmatter_size_limit" }
            val options = LoaderOptions().apply {
                isAllowDuplicateKeys = false
                maxAliasesForCollections = 0
                nestingDepthLimit = 8
                codePointLimit = 8_192
            }
            val fields = Yaml(SafeConstructor(options)).load<Any>(header) as? Map<*, *> ?: error("invalid_frontmatter")
            val accepted = setOf("name", "description", "license", "compatibility", "metadata", "allowed-tools")
            require(fields.keys.all { it is String && it in accepted }) { "unknown_frontmatter_field" }
            fun string(key: String, max: Int, required: Boolean = false): String {
                val value = fields[key] ?: if (required) error("missing_$key") else return ""
                require(value is String && value.isNotBlank() && value.length <= max) { "invalid_$key" }
                return value
            }
            val name = string("name", 64, true)
            require(validName(name) && name == directory) { "name_directory_mismatch" }
            val description = string("description", 1024, true)
            val compatibility = string("compatibility", 500)
            string("license", 1024)
            val allowedTools = string("allowed-tools", 1024)
            val metadata = fields["metadata"] as? Map<*, *>
            require(!fields.containsKey("metadata") || metadata != null) { "invalid_metadata" }
            require(metadata == null || metadata.all { it.key is String && it.value is String }) { "invalid_metadata_values" }
            val body = lines.drop(end + 1).joinToString("\n").trim()
            require(body.isNotBlank() && body.length <= 8_192) { "invalid_instructions" }
            // Unknown requirements are visible but fail closed. A declared instruction-only
            // package may carry optional scripts, but those scripts are never executable here.
            val instructionsOnly = metadata?.get("pixel-agent-runtime") == "instructions"
            val unavailable = when {
                compatibility.isNotBlank() && !instructionsOnly -> "Unverified requirements: $compatibility"
                files.any { it.startsWith("scripts/") } && !instructionsOnly -> "Script runtime unavailable on this phone"
                else -> null
            }
            return Skill(name, description, body, compatibility, allowedTools, files.filter { it != "SKILL.md" }.sorted(), unavailable)
        }
    }
}
