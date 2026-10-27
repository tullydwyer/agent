package dev.pixelagent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pixelagent.conversation.ConversationContext
import dev.pixelagent.conversation.ConversationController
import dev.pixelagent.conversation.ConversationModel
import dev.pixelagent.model.CredentialStore
import dev.pixelagent.model.PlanUsageAuth
import dev.pixelagent.model.PlanUsageProvider
import dev.pixelagent.skills.AssetSkillSource
import dev.pixelagent.skills.SkillRegistry
import dev.pixelagent.skills.SkillSession
import dev.pixelagent.skills.SkillSource
import dev.pixelagent.tools.ToolCall
import dev.pixelagent.tools.ToolDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import android.os.ParcelFileDescriptor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.delay
import java.io.IOException
import dev.pixelagent.model.ModelStreamInterruptedException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Install/run directly with adb; never uninstall the signed-in target app.
 * Live trials are opt-in via -e liveModel <catalog slug> and use plan allowance. */
@RunWith(AndroidJUnit4::class)
class PhaseOneTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun registry() = SkillRegistry(AssetSkillSource(context.assets))
    private fun liveProvider(): PlanUsageProvider = runBlocking {
        val slug = InstrumentationRegistry.getArguments().getString("liveModel")
        assumeTrue("Pass liveModel explicitly to use the signed-in plan", !slug.isNullOrBlank())
        PlanUsageProvider(PlanUsageAuth(CredentialStore(context))).also {
            check(it.models().any { model -> model.slug == slug }) { "Requested model not in account catalog" }
            it.selectedModel = slug
        }
    }

    @Test fun bundledDiscoveryResourcesPermissionsAndRecreation() = runBlocking {
        val registry = registry()
        assertEquals(2, registry.skills.size)
        assertTrue(registry.issues.isEmpty())
        val session = SkillSession(registry)
        var allowed = true
        val dispatcher = ToolDispatcher(session) { allowed }
        val activate = ToolCall("activate", "skills", "activate", "{\"name\":\"clear-explanation\"}")
        assertTrue(JSONObject(dispatcher.execute(activate)).getBoolean("ok"))
        val read = ToolCall("read", "skills", "read_resource", "{\"name\":\"clear-explanation\",\"relative_path\":\"references/approach.md\"}")
        assertTrue(JSONObject(dispatcher.execute(read)).getBoolean("ok"))
        val prefs = context.getSharedPreferences("phase-one-test-only", 0)
        try {
            assertTrue(prefs.edit().putString("active", session.save()).commit())
            val restored = SkillSession(registry, prefs.getString("active", "[]")!!)
            assertEquals(session.context(), restored.context())
            assertTrue(restored.context().contains("river stone"))
            allowed = false
            assertEquals("permission_denied", JSONObject(dispatcher.execute(read)).getString("error"))
            assertEquals("permission_denied", JSONObject(dispatcher.execute(activate)).getString("error"))
            allowed = true
            for (path in listOf("../SKILL.md", "/data/secret", "references/missing.md", "references\\approach.md", "%2e%2e/secret")) {
                val invalid = read.copy(arguments = JSONObject().put("name", "clear-explanation").put("relative_path", path).toString())
                assertFalse(JSONObject(dispatcher.execute(invalid)).getBoolean("ok"))
            }
            val input = ConversationContext.build("Concise", emptyList(), "Explain", registry, restored, true).toString()
            assertTrue(input.contains("river stone"))
            assertFalse(input.contains("function_call"))
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun malformedAndIncompatiblePackagesAreIsolatedOnAndroid() {
        val source = object : SkillSource {
            override fun directories() = listOf("broken", "desktop")
            override fun files(name: String) = listOf("SKILL.md")
            override fun read(name: String, path: String, maxBytes: Int) = if (name == "broken") "bad" else
                "---\nname: desktop\ndescription: Desktop fixture\ncompatibility: Requires Python and bash\n---\nRun a desktop command"
        }
        val registry = SkillRegistry(source)
        assertEquals(1, registry.issues.size)
        assertNotNull(registry.get("desktop").unavailable)
        try { SkillSession(registry).activate("desktop"); fail("Incompatible skill activated") } catch (_: IllegalArgumentException) { }
    }

    @Test fun cancellationAndPartialFailureDoNotExecuteOrReplayOnAndroid() = runBlocking {
        val session = SkillSession(registry())
        var requests = 0
        val started = CompletableDeferred<Unit>()
        val model = object : ConversationModel {
            override suspend fun complete(input: JSONArray, tools: JSONArray, onDelta: (String) -> Unit): JSONObject {
                requests++; onDelta("Partial reply."); started.complete(Unit); awaitCancellation()
            }
        }
        val job = launch { ConversationController(model, ToolDispatcher(session) { true }).run(JSONArray(), {}) }
        started.await(); job.cancelAndJoin()
        assertEquals(1, requests)
        assertTrue(session.names.isEmpty())
        assertTrue(SkillSession(registry(), session.save()).names.isEmpty())
        val failingModel = object : ConversationModel {
            override suspend fun complete(input: JSONArray, tools: JSONArray, onDelta: (String) -> Unit): JSONObject {
                onDelta("Partial reply."); throw IOException("Simulated interrupted stream")
            }
        }
        try { ConversationController(failingModel, ToolDispatcher(session) { true }).run(JSONArray(), {}); fail("Expected disconnect") }
        catch (_: IOException) { }
        assertTrue(session.names.isEmpty())
    }

    @Test fun liveModelActivatesReadsAndUsesRestoredSkillContext() = runBlocking {
        val provider = liveProvider()
        val registry = registry()
        val session = SkillSession(registry)
        val events = mutableListOf<String>()
        val input = ConversationContext.build("Be concise.", emptyList(),
            "Activate clear-explanation with the skills tool, read its references/approach.md with the resource tool, then reply with the resource check phrase. Use the tools even if you know the skill name.", registry, session, true)
        val reply = ConversationController(provider, ToolDispatcher(session) { true }).run(input, {}, { call, result ->
            assertTrue(JSONObject(result).getBoolean("ok")); events.add(call.name)
        })
        assertTrue(events.contains("activate"))
        assertTrue(events.contains("read_resource"))
        assertTrue(reply.lowercase().contains("river stone"))
        val restored = SkillSession(registry, session.save())
        val rebuilt = ConversationContext.build("Be concise.", emptyList(), "Without calling tools, what was the resource check phrase in your active skill context?", registry, restored, true)
        var calls = 0
        val restoredReply = ConversationController(provider, ToolDispatcher(restored) { true }).run(rebuilt, {}, { _, _ -> calls++ })
        assertTrue(restoredReply.lowercase().contains("river stone"))
        assertEquals(0, calls)
    }

    @Test fun liveModelPermissionDenialIsReturnedAsToolResult() = runBlocking {
        val provider = liveProvider()
        val registry = registry()
        val session = SkillSession(registry)
        // Advertise a catalog, then revoke the native grant before execution. This
        // proves dispatch enforces permission even if the model requests the tool.
        val input = ConversationContext.build("Be concise.", emptyList(),
            "Call skills.activate for daily-briefing. Report whether the tool succeeded or returned an error.", registry, session, true)
        var denied = 0
        ConversationController(provider, ToolDispatcher(session) { false }).run(input, {}, { _, result ->
            assertEquals("permission_denied", JSONObject(result).getString("error")); denied++
        })
        assertTrue(denied > 0)
        assertTrue(session.names.isEmpty())
    }

    @Test fun liveStreamCancellationAndManualRecovery() = runBlocking {
        val provider = liveProvider()
        val session = SkillSession(registry())
        val started = CompletableDeferred<Unit>()
        var finished = false
        val job = launch {
            ConversationController(provider, ToolDispatcher(session) { true }).run(
                JSONArray().put(JSONObject().put("role", "user").put("content", "Write 250 numbered sentences about geometric shapes. Do not use tools.")),
                { started.complete(Unit) })
            finished = true
        }
        try { withTimeout(30_000) { started.await() }; job.cancelAndJoin() }
        finally { job.cancelAndJoin() }
        assertFalse(finished)
        assertTrue(session.names.isEmpty())
        val reply = ConversationController(provider, ToolDispatcher(session) { true }).run(
            JSONArray().put(JSONObject().put("role", "user").put("content", "Reply exactly: manual recovery succeeded")), {})
        assertTrue(reply.lowercase().contains("manual recovery succeeded"))
    }

    @Test fun liveDisconnectAfterPartialOutput() = runBlocking {
        val provider = liveProvider()
        val originalWifi = shell("settings get global wifi_on").trim()
        val originalData = shell("settings get global mobile_data").trim()
        assumeTrue("Cannot safely restore unknown network flags", originalWifi in listOf("0", "1") && originalData in listOf("0", "1"))
        val session = SkillSession(registry())
        val started = CompletableDeferred<Unit>()
        val result = async {
            runCatching {
                ConversationController(provider, ToolDispatcher(session) { true }).run(
                    JSONArray().put(JSONObject().put("role", "user").put("content", "Write 1000 numbered distinct sentences of at least 15 words about geometry. Start immediately. Do not call tools.")),
                    { started.complete(Unit) })
            }
        }
        try {
            withTimeout(30_000) { started.await() }
            shell("svc wifi disable")
            shell("svc data disable")
            val failure = withTimeout(95_000) { result.await() }.exceptionOrNull()
            assertTrue("Expected an interrupted network stream", failure is IOException || failure is ModelStreamInterruptedException)
            assertTrue(session.names.isEmpty())
        } finally {
            result.cancelAndJoin()
            shell("svc wifi ${if (originalWifi == "1") "enable" else "disable"}")
            shell("svc data ${if (originalData == "1") "enable" else "disable"}")
            // Enabled flags precede usable connectivity. Do not let the next test
            // race radio reconnection or populate the process's negative DNS cache.
            if (originalWifi == "1" || originalData == "1") withTimeout(30_000) {
                val connectivity = context.getSystemService(ConnectivityManager::class.java)
                while (true) {
                    val network = connectivity.activeNetwork
                    val validated = network != null && connectivity.getNetworkCapabilities(network)
                        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                    if (validated && runCatching { network!!.getAllByName("api.openai.com").isNotEmpty() }.getOrDefault(false)) break
                    delay(500)
                }
            }
        }
        assertEquals(originalWifi, shell("settings get global wifi_on").trim())
        assertEquals(originalData, shell("settings get global mobile_data").trim())
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
}
