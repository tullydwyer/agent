package dev.pixelagent.model

import dev.pixelagent.conversation.ConversationModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ModelChoice(val slug: String, val name: String)

class PlanUsageProvider(private val auth: PlanUsageAuth) : ModelProvider, ConversationModel {
    private val http = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).followRedirects(false).retryOnConnectionFailure(false).build()
    @Volatile private var call: Call? = null
    var selectedModel: String? = null
    override val displayName = "ChatGPT plan experiment"
    override val isConfigured get() = selectedModel != null
    fun cancel() { call?.cancel() }

    suspend fun models(): List<ModelChoice> = withContext(Dispatchers.IO) {
        val token = auth.accessToken()
        val request = Request.Builder().url("https://api.openai.com/v1/models").header("Authorization", "Bearer $token").build()
        val requestCall = http.newCall(request).also { call = it }
        requestCall.execute().use {
            check(it.isSuccessful) { "Model catalog failed (HTTP ${it.code}). Reconnect or check plan access." }
            val models = JSONObject(it.body!!.string()).getJSONArray("models")
            (0 until models.length()).map(models::getJSONObject).filter { item -> item.optString("visibility") == "list" }
                .map { item -> ModelChoice(item.getString("slug"), item.getString("display_name")) }
        }.also { call = null }
    }
    override suspend fun reply(message: String): String = text(request(JSONArray().put(user(message))))

    suspend fun image(dataUrl: String): String = text(request(JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray()
        .put(JSONObject().put("type", "input_text").put("text", "Describe the shapes and colors in this diagnostic image."))
        .put(JSONObject().put("type", "input_image").put("image_url", dataUrl))))))

    suspend fun toolTrial(): String {
        val tool = JSONObject().put("type", "namespace").put("name", "diagnostics").put("description", "Harmless device feasibility tests")
            .put("tools", JSONArray().put(JSONObject().put("type", "function").put("name", "echo")
                .put("description", "Echo the literal diagnostic value.").put("strict", true)
                .put("parameters", JSONObject("""{"type":"object","properties":{"value":{"type":"string","enum":["pixel-agent-test"]}},"required":["value"],"additionalProperties":false}"""))))
        val tools = JSONArray().put(tool)
        val input = JSONArray().put(user("Call diagnostics.echo with value pixel-agent-test, then report its result."))
        val first = request(input, tools, true)
        val output = first.getJSONArray("output")
        var calls = 0
        for (i in 0 until output.length()) {
            val item = output.getJSONObject(i)
            input.put(item)
            if (item.optString("type") != "function_call") continue
            require(++calls == 1 && item.optString("name") == "echo" && item.optString("namespace") == "diagnostics") { "Unexpected tool call rejected." }
            val args = JSONObject(item.getString("arguments"))
            require(args.length() == 1 && args.optString("value") == "pixel-agent-test") { "Invalid diagnostic arguments rejected." }
            input.put(JSONObject().put("type", "function_call_output").put("call_id", item.getString("call_id"))
                .put("output", "pixel-agent-test"))
        }
        check(calls == 1) { "Completed response did not call the diagnostic function." }
        return "Function executed once after completion. " + text(request(input, tools))
    }

    suspend fun conversation(input: JSONArray, onDelta: (String) -> Unit): String = text(request(input, onDelta = onDelta))

    override suspend fun complete(input: JSONArray, tools: JSONArray, onDelta: (String) -> Unit): JSONObject =
        request(input, tools, onDelta = onDelta, allowTools = true)

    private suspend fun request(input: JSONArray, tools: JSONArray? = null, requireTool: Boolean = false, onDelta: (String) -> Unit = {}, allowTools: Boolean = false): JSONObject {
        val model = selectedModel ?: error("Fetch and select an account model first.")
        val token = withContext(Dispatchers.IO) { auth.accessToken() }
        val body = JSONObject().put("model", model).put("input", input).put("store", false).put("stream", true)
            .put("include", JSONArray().put("reasoning.encrypted_content"))
        if (tools != null) body.put("tools", tools).put("tool_choice", if (requireTool) "required" else if (allowTools) "auto" else "none")
        val request = Request.Builder().url("https://api.openai.com/v1/responses")
            .header("Authorization", "Bearer $token").header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val requestCall = http.newCall(request).also { call = it }
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { requestCall.cancel() }
            requestCall.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (this@PlanUsageProvider.call === requestCall) this@PlanUsageProvider.call = null
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            if (!it.isSuccessful) throw ModelHttpException(it.code)
                            ResponseStream.read(it.body!!.charStream().buffered(), onDelta)
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (failure: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    } finally {
                        if (this@PlanUsageProvider.call === requestCall) this@PlanUsageProvider.call = null
                    }
                }
            })
        }
    }
    private fun user(text: String) = JSONObject().put("role", "user").put("content", text.take(4000))
    private fun text(response: JSONObject): String {
        val output = response.getJSONArray("output")
        val text = StringBuilder()
        for (i in 0 until output.length()) {
            val content = output.getJSONObject(i).optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val item = content.getJSONObject(j)
                if (item.optString("type") == "output_text") text.append(item.getString("text"))
            }
        }
        return text.toString().takeIf(String::isNotBlank) ?: error("Completed response contained no text.")
    }
}

class ModelHttpException(val status: Int) : IOException("Model HTTP $status")
