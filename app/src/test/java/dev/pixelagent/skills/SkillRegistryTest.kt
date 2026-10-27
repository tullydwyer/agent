package dev.pixelagent.skills

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class FixtureSource(val packages: Map<String, Map<String, String>> = mapOf("sample" to mapOf(
    "SKILL.md" to "---\nname: sample\ndescription: A test skill\n---\nRead references/note.md.",
    "references/note.md" to "Test resource marker.",
))) : SkillSource {
    override fun directories() = packages.keys.toList()
    override fun files(name: String) = packages.getValue(name).keys.toList()
    override fun read(name: String, path: String, maxBytes: Int): String = packages.getValue(name).getValue(path).also {
        require(it.toByteArray().size <= maxBytes)
    }
}

class SkillRegistryTest {
    private fun parse(header: String, body: String = "Instructions") = SkillRegistry.parse("sample", "---\n$header\n---\n$body", listOf("SKILL.md"))
    @Test fun parsesStandardYamlIncludingBlocksQuotesAndMetadata() {
        val skill = parse("""name: sample
description: >-
  A useful skill:
  use it for tests.
license: 'MIT'
allowed-tools: 'skills.read_resource'
metadata:
  author: 'Example'
""")
        assertEquals("A useful skill: use it for tests.", skill.description)
        assertNull(skill.unavailable)
    }
    @Test fun rejectsMalformedMetadataAndNames() {
        listOf("name: Sample\ndescription: test", "name: other\ndescription: test", "name: sample\ndescription: ''",
            "name: sample\ndescription: test\nmetadata: []", "name: sample\ndescription: test\nmetadata: {version: 1}",
            "name: sample\ndescription: test\nunknown: yes", "name: sample\nname: sample\ndescription: test").forEach {
            assertThrows(Exception::class.java) { parse(it) }
        }
    }
    @Test fun rejectsObjectTagsAliasCollectionsAndOversizedInput() {
        listOf("name: !!java.lang.String sample\ndescription: test",
            "name: sample\ndescription: test\nmetadata: {a: &a [x], b: *a}").forEach {
            assertThrows(Exception::class.java) { parse(it) }
        }
        assertThrows(Exception::class.java) { parse("name: sample\ndescription: test", "x".repeat(17000)) }
    }
    @Test fun incompatibleRequirementsRemainVisibleAndCannotActivate() {
        val registry = SkillRegistry(FixtureSource(mapOf("sample" to mapOf("SKILL.md" to "---\nname: sample\ndescription: Test\ncompatibility: Requires Python and bash\n---\nRun a script"))))
        assertTrue(registry.catalog().contains("Requires Python"))
        assertThrows(Exception::class.java) { SkillSession(registry).activate("sample") }
    }
    @Test fun scriptsAreNeverMadeExecutableAndAllowedToolsAreAdvisory() {
        val skill = SkillRegistry.parse("sample", "---\nname: sample\ndescription: Test\nallowed-tools: phone.send\n---\nRun script", listOf("SKILL.md", "scripts/run.py"))
        assertNotNull(skill.unavailable)
        assertEquals("phone.send", skill.allowedTools)
    }
    @Test fun rejectsTraversalAbsoluteWindowsEncodedAndEmptyPaths() {
        val registry = SkillRegistry(FixtureSource())
        listOf("../SKILL.md", "/secret", "C:/secret", "references/../../secret", "references\\note.md", "%2e%2e/secret", "references//note.md", "./references/note.md").forEach {
            assertFalse(it, SkillRegistry.validPath(it))
            assertThrows(Exception::class.java) { registry.readResource("sample", it) }
        }
    }
    @Test fun resourcesRequireActivationAndExistingListedPath() {
        val registry = SkillRegistry(FixtureSource())
        val session = SkillSession(registry)
        assertThrows(Exception::class.java) { session.read("sample", "references/note.md") }
        session.activate("sample")
        assertEquals("Test resource marker.", session.read("sample", "references/note.md").getString("text"))
        assertThrows(Exception::class.java) { session.read("sample", "references/missing.md") }
    }
    @Test fun restoresNamesAndResourceContentWithoutCallsOrAuthority() {
        val registry = SkillRegistry(FixtureSource())
        val original = SkillSession(registry)
        original.activate("sample"); original.read("sample", "references/note.md")
        val restored = SkillSession(registry, original.save())
        assertEquals(original.context(), restored.context())
        assertFalse(original.save().contains("instructions"))
        assertEquals(listOf("sample"), restored.names)
        assertTrue(SkillSession(registry, "not json").names.isEmpty())
        assertTrue(SkillSession(registry, "[{\"name\":\"removed\"}]").names.isEmpty())
    }
    @Test fun resourceAndContextLimitsAreEnforcedBeforeLoading() {
        val source = FixtureSource(mapOf("sample" to mapOf("SKILL.md" to "---\nname: sample\ndescription: Test\n---\nRead notes", "large.md" to "x".repeat(8193))))
        val session = SkillSession(SkillRegistry(source))
        session.activate("sample")
        assertThrows(Exception::class.java) { session.read("sample", "large.md") }
        assertEquals(0, JSONArray(session.save()).getJSONObject(0).getJSONArray("resources").length())
    }
    @Test fun discoveryIsolatesMalformedPackage() {
        val source = FixtureSource(FixtureSource().packages + ("bad" to mapOf("SKILL.md" to "missing frontmatter")))
        val registry = SkillRegistry(source)
        assertEquals(listOf("sample"), registry.skills.map { it.name })
        assertEquals(listOf("bad"), registry.issues.map { it.directory })
    }
}
