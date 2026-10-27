package dev.pixelagent.model

import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

object OAuthSecurity {
    fun callbackClient(parameters: Map<String, List<String>>, state: String, selectedClient: String?): String {
        require(parameters["state"] == listOf(state)) { "Authorization state rejected." }
        require(listOf("code", "client_id", "error").all { parameters[it].orEmpty().size <= 1 }) { "Ambiguous callback rejected." }
        require(parameters["error"].isNullOrEmpty()) { "Authorization declined or unavailable. Start a new sign-in." }
        require(!parameters["code"]?.singleOrNull().isNullOrBlank()) { "Authorization code missing." }
        val issued = parameters["client_id"]?.singleOrNull() ?: selectedClient ?: error("Registration did not issue a client ID.")
        require(issued.matches(Regex("[a-zA-Z0-9_-]{1,200}")) && issued != "dynamic_agent_client" && (selectedClient == null || issued == selectedClient)) { "Registration mismatch." }
        return issued
    }
    fun random(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun validateIdToken(token: String, jwks: JSONObject, clientId: String, nonce: String?, now: Long = System.currentTimeMillis() / 1000): JSONObject {
        val parts = token.split('.')
        require(parts.size == 3) { "Invalid identity token." }
        val decoder = Base64.getUrlDecoder()
        val header = JSONObject(String(decoder.decode(parts[0]), Charsets.UTF_8))
        require(header.getString("alg") == "RS256") { "Unsupported identity signature." }
        val keys = jwks.getJSONArray("keys")
        val key = (0 until keys.length()).map(keys::getJSONObject).firstOrNull {
            it.optString("kid") == header.getString("kid") && it.optString("kty") == "RSA" &&
                it.optString("use", "sig") == "sig" && it.optString("alg", "RS256") == "RS256"
        } ?: error("Identity signing key unavailable.")
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(
            BigInteger(1, decoder.decode(key.getString("n"))), BigInteger(1, decoder.decode(key.getString("e")))))
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initVerify(publicKey); update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        }
        require(signature.verify(decoder.decode(parts[2]))) { "Identity signature rejected." }
        val claims = JSONObject(String(decoder.decode(parts[1]), Charsets.UTF_8))
        require(claims.getString("iss") == "https://auth.openai.com") { "Identity issuer rejected." }
        val aud = claims.get("aud")
        val audiences = if (aud is org.json.JSONArray) (0 until aud.length()).map(aud::getString) else listOf(aud.toString())
        require(clientId in audiences && (audiences.size == 1 || claims.optString("azp") == clientId)) { "Identity audience rejected." }
        require(claims.getLong("exp") > now && claims.optLong("nbf", 0) <= now + 30) { "Identity token expired or not yet valid." }
        require(claims.getString("sub").isNotBlank()) { "Identity subject missing." }
        if (nonce != null) require(claims.optString("nonce") == nonce) { "Identity nonce rejected." }
        return claims
    }
}
