package dev.pixelagent.model

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/** Local feasibility spike: no embedded secret, billable fallback, or backend-api endpoints. */
class PlanUsageAuth(private val store: CredentialStore) {
    private val http = OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS).followRedirects(false).retryOnConnectionFailure(false).build()
    private val mutex = Mutex()
    @Volatile private var listener: ServerSocket? = null
    private val resource = "https://api.openai.com/v1"

    fun cancel() { listener?.close(); listener = null }
    suspend fun accounts(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val accounts = store.read().optJSONObject("accounts") ?: JSONObject()
        accounts.keys().asSequence().map { id -> id to label(accounts.getJSONObject(id)) }.toList()
    }
    suspend fun activeLabel(): String = withContext(Dispatchers.IO) {
        val root = store.read()
        root.optJSONObject("accounts")?.optJSONObject(root.optString("active"))?.let(::label) ?: "Not connected"
    }

    suspend fun signIn(client: String?, openBrowser: (Uri) -> Unit) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val root = store.read()
            val previous = root.optJSONObject("accounts")?.optJSONObject(client.orEmpty())
            val state = OAuthSecurity.random()
            val nonce = OAuthSecurity.random()
            val verifier = OAuthSecurity.random()
            ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
                listener = server
                server.soTimeout = 1000
                val redirect = "http://127.0.0.1:${server.localPort}/auth/callback"
                val url = Uri.parse("https://auth.openai.com/api/accounts/authorize").buildUpon()
                    .appendQueryParameter("client_id", client ?: "dynamic_agent_client")
                    .appendQueryParameter("ext_agent_host_id", root.getString("host"))
                    .appendQueryParameter("response_type", "code").appendQueryParameter("redirect_uri", redirect)
                    .appendQueryParameter("scope", "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct")
                    .appendQueryParameter("resource", resource).appendQueryParameter("state", state)
                    .appendQueryParameter("nonce", nonce).appendQueryParameter("code_challenge_method", "S256")
                    .appendQueryParameter("code_challenge", OAuthSecurity.challenge(verifier))
                if (client == null) url.appendQueryParameter("agent_name_hint", "Pixel Agent")
                else previous?.optString("id_token")?.takeIf(String::isNotBlank)?.let { url.appendQueryParameter("id_token_hint", it) }
                withContext(Dispatchers.Main) { openBrowser(url.build()) }
                val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5)
                var callback: Uri? = null
                while (callback == null && System.nanoTime() < deadline) {
                    val socket = try { server.accept() } catch (_: java.net.SocketTimeoutException) { continue }
                    socket.use {
                        it.soTimeout = 2000
                        val reader = it.getInputStream().bufferedReader()
                        val request = runCatching {
                            val first = readHttpLine(reader)
                            val hosts = mutableListOf<String>()
                            var size = first.length
                            while (true) {
                                val header = readHttpLine(reader)
                                if (header.isEmpty()) break
                                size += header.length
                                require(size < 32768)
                                if (header.substringBefore(':').equals("Host", true)) hosts.add(header.substringAfter(':').trim())
                            }
                            first to hosts
                        }.getOrNull()
                        val line = request?.first.orEmpty()
                        val target = line.split(' ').getOrNull(1).orEmpty()
                        val uri = Uri.parse("http://127.0.0.1$target")
                        val valid = line.startsWith("GET /auth/callback?") && uri.path == "/auth/callback" &&
                            request?.second == listOf("127.0.0.1:${server.localPort}") &&
                            uri.getQueryParameters("state").size == 1 && uri.getQueryParameter("state") == state &&
                            listOf("code", "client_id", "error").all { key -> uri.getQueryParameters(key).size <= 1 }
                        val script = "history.replaceState(null, '', '/auth/complete');"
                        val hash = java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(script.toByteArray()))
                        val body = if (valid) "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><title>Return to Pixel Agent</title><h1>Return to Pixel Agent</h1><p>Authorization received. The app will finish checking your connection.</p><p><a href=\"pixelagent://auth/complete\">Open Pixel Agent</a></p><script>$script</script></html>" else "Invalid callback."
                        runCatching { it.getOutputStream().write("HTTP/1.1 ${if (valid) "200 OK" else "400 Bad Request"}\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'none'; script-src 'sha256-$hash'; frame-ancestors 'none'; base-uri 'none'\r\nConnection: close\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body".toByteArray()) }
                        if (valid) callback = uri
                    }
                }
                val result = callback ?: error("Sign-in timed out. Try again from the app.")
                val issued = OAuthSecurity.callbackClient(result.queryParameterNames.associateWith(result::getQueryParameters), state, client)
                val accounts = root.optJSONObject("accounts") ?: JSONObject().also { root.put("accounts", it) }
                val registration = previous ?: JSONObject().put("label", "Connection ${accounts.length() + 1}")
                accounts.put(issued, registration)
                store.write(root) // Retain issued registration even when a one-time code expires.
                val tokens = form("token", mapOf("grant_type" to "authorization_code", "client_id" to issued,
                    "code" to (result.getQueryParameter("code") ?: error("Authorization code missing.")),
                    "code_verifier" to verifier, "redirect_uri" to redirect, "resource" to resource))
                val claims = OAuthSecurity.validateIdToken(tokens.getString("id_token"), jwks(), issued, nonce)
                require(!registration.has("subject") || registration.getString("subject") == claims.getString("sub")) { "Account identity changed. Add a separate connection." }
                registration.put("subject", claims.getString("sub"))
                registration.put("email", claims.optString("email"))
                registration.put("scope", tokens.optString("scope"))
                saveTokens(registration, tokens)
                root.put("active", issued)
                store.write(root)
            }
        }
    }.also { listener = null }

    suspend fun select(client: String) = mutex.withLock { withContext(Dispatchers.IO) {
        val root = store.read()
        require(root.getJSONObject("accounts").has(client))
        root.put("active", client); store.write(root)
    } }

    suspend fun accessToken(forceRefresh: Boolean = false): String = mutex.withLock { withContext(Dispatchers.IO) {
        val root = store.read()
        val client = root.optString("active").takeIf(String::isNotBlank) ?: error("Continue with ChatGPT first.")
        val account = root.getJSONObject("accounts").getJSONObject(client)
        require(account.optString("scope").split(' ').contains("chatgpt.tokens.use.direct")) { "ChatGPT plan permission was not granted. Reconnect to authorize it." }
        if (forceRefresh || account.optLong("expires_at") < System.currentTimeMillis() + 60000) {
            val refresh = account.optString("refresh_token").takeIf(String::isNotBlank) ?: error("Reconnect to renew this session.")
            val tokens = form("token", mapOf("grant_type" to "refresh_token", "client_id" to client, "refresh_token" to refresh, "resource" to resource))
            if (tokens.has("id_token")) {
                val claims = OAuthSecurity.validateIdToken(tokens.getString("id_token"), jwks(), client, null)
                require(claims.getString("sub") == account.getString("subject")) { "Renewed account identity rejected." }
            }
            saveTokens(account, tokens)
            store.write(root)
        }
        require(account.getString("scope").split(' ').contains("chatgpt.tokens.use.direct")) { "Plan access is no longer granted. Reconnect." }
        account.getString("access_token")
    } }

    suspend fun signOut(): Boolean = mutex.withLock { withContext(Dispatchers.IO) {
        val root = store.read()
        val client = root.optString("active")
        val account = root.optJSONObject("accounts")?.optJSONObject(client) ?: return@withContext true
        val revoked = runCatching { form("revoke", mapOf("token" to account.getString("refresh_token"), "token_type_hint" to "refresh_token", "client_id" to client)); true }.getOrDefault(false)
        listOf("access_token", "refresh_token", "id_token", "scope", "expires_at").forEach(account::remove)
        root.remove("active"); store.write(root)
        revoked
    } }

    private fun saveTokens(account: JSONObject, tokens: JSONObject) {
        require(tokens.optString("token_type").equals("Bearer", true)) { "Unsupported token type." }
        account.put("access_token", tokens.getString("access_token"))
        listOf("refresh_token", "id_token", "scope").forEach { if (tokens.has(it)) account.put(it, tokens.getString(it)) }
        account.put("expires_at", System.currentTimeMillis() + tokens.getLong("expires_in") * 1000)
    }
    private fun label(account: JSONObject): String = listOf(account.optString("label", "Connection"), account.optString("email"))
        .filter(String::isNotBlank).joinToString(" · ")
    private fun readHttpLine(reader: java.io.Reader): String {
        val line = StringBuilder()
        while (line.length < 8192) {
            val char = reader.read()
            require(char >= 0) { "Incomplete local request." }
            if (char == 10) return line.toString().trimEnd('\r')
            line.append(char.toChar())
        }
        error("Local request line exceeded its limit.")
    }
    private fun jwks(): JSONObject = http.newCall(Request.Builder().url("https://auth.openai.com/.well-known/jwks.json").build()).execute().use {
        check(it.isSuccessful) { "Unable to fetch identity signing keys." }; JSONObject(it.body!!.string())
    }
    private fun form(endpoint: String, fields: Map<String, String>): JSONObject {
        val body = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        return http.newCall(Request.Builder().url("https://auth.openai.com/api/accounts/oauth/$endpoint").post(body).build()).execute().use {
            check(it.isSuccessful) { "OAuth $endpoint failed (HTTP ${it.code}). Reconnect if the grant was revoked or expired." }
            val value = it.body!!.string()
            if (value.isBlank()) JSONObject() else JSONObject(value)
        }
    }
}
