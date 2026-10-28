package dev.pixelagent.model

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

data class ModelLabState(
    val busy: Boolean = false,
    val connection: String = "Not connected",
    val accounts: List<Pair<String, String>> = emptyList(),
    val models: List<ModelChoice> = emptyList(),
    val selectedModel: String? = null,
    val result: String = "Continue with ChatGPT to test account access.",
)

class ModelLabViewModel(application: Application) : AndroidViewModel(application) {
    private val connection = AgentConnection.get(application)
    private val auth = connection.auth
    val provider = connection.provider
    private val prefs = application.getSharedPreferences("model_selection", 0)
    private val mutableState = MutableStateFlow(ModelLabState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    init { run("Ready to talk.") {
        refreshConnections()
        if (provider.isConfigured) mutableState.update { it.copy(models = connection.catalog, selectedModel = provider.selectedModel) }
        else if (state.value.connection != "Not connected") loadModels()
    } }

    private suspend fun refreshConnections() {
        val accounts = auth.accounts()
        val label = auth.activeLabel()
        mutableState.update { it.copy(accounts = accounts, connection = label) }
    }
    private fun clearModels() {
        provider.selectedModel = null
        prefs.edit().remove("model").apply()
        mutableState.update { it.copy(models = emptyList(), selectedModel = null) }
    }
    fun signIn(client: String? = null, open: (Uri) -> Unit) = run("Connected. Ready to talk.") {
        val application = getApplication<Application>()
        AuthSessionService.cancelAttempt = ::cancel
        try {
            application.startForegroundService(Intent(application, AuthSessionService::class.java))
            mutableState.update { it.copy(result = "Finish authorization in the system browser, then return to Pixel Agent.") }
            auth.signIn(client, open); clearModels(); refreshConnections(); loadModels()
        } finally {
            AuthSessionService.cancelAttempt = null
            application.stopService(Intent(application, AuthSessionService::class.java))
        }
    }
    fun selectAccount(client: String) = run("Connection selected. Ready to talk.") {
        auth.select(client); clearModels(); refreshConnections(); loadModels()
    }
    fun fetchModels() = run("Account models loaded. Ready to talk.") { loadModels() }
    private suspend fun loadModels() {
        val choices = provider.models()
        connection.catalog = choices
        check(choices.isNotEmpty()) { "No visible models are available for this account." }
        val selected = choices.firstOrNull { it.slug == prefs.getString("model", null) } ?: choices.first()
        provider.selectedModel = selected.slug
        prefs.edit().putString("model", selected.slug).apply()
        mutableState.update { it.copy(models = choices, selectedModel = selected.slug) }
    }
    fun selectModel(slug: String) {
        if (state.value.busy || state.value.models.none { it.slug == slug }) return
        provider.selectedModel = slug
        prefs.edit().putString("model", slug).apply()
        mutableState.update { it.copy(selectedModel = slug) }
    }
    fun refreshTokens() = run("Credentials renewed and saved. Run a text trial to verify the renewed access token.") { auth.accessToken(true) }
    fun signOut() = run {
        val revoked = auth.signOut(); clearModels(); refreshConnections()
        mutableState.update { it.copy(result = if (revoked) "Signed out; session revocation confirmed." else "Signed out locally. Remote revocation was not confirmed; disconnect Pixel Agent in ChatGPT Settings.") }
    }
    fun textTrial() = trial { provider.reply("Reply with exactly: Pixel Agent text test succeeded.") }
    fun toolTrial() = trial { provider.toolTrial() }
    fun imageTrial() = trial {
        // Synthetic image avoids uploading a personal photo during feasibility tests.
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawRect(30f, 30f, 120f, 120f, Paint().apply { color = Color.RED })
        canvas.drawCircle(180f, 180f, 40f, Paint().apply { color = Color.BLUE })
        val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        bitmap.recycle()
        provider.image("data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))
    }
    private fun trial(block: suspend () -> String) = run {
        val start = android.os.SystemClock.elapsedRealtime()
        val result = block()
        mutableState.update { it.copy(result = "Completed · ${android.os.SystemClock.elapsedRealtime() - start} ms · ${provider.selectedModel}\n$result") }
    }
    private fun run(success: String? = null, block: suspend () -> Unit) {
        if (state.value.busy) return
        job = viewModelScope.launch {
            mutableState.update { it.copy(busy = true, result = "Experiment running…") }
            try { block(); if (success != null) mutableState.update { it.copy(result = success) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                runCatching { refreshConnections() }
                val message = when (failure) {
                    is IllegalStateException, is IllegalArgumentException -> failure.message.orEmpty().take(300)
                    else -> "Experiment failed (${failure.javaClass.simpleName}). Check connectivity or reconnect."
                }
                mutableState.update { it.copy(result = message) }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }
    fun cancel() {
        auth.cancel(); provider.cancel(); job?.cancel()
        // Blocking IO must unwind before another operation can replace rotating credentials.
        mutableState.update { it.copy(result = "Experiment cancelled. Any partial response is discarded.") }
    }
    override fun onCleared() { if (state.value.busy) cancel() }
}
