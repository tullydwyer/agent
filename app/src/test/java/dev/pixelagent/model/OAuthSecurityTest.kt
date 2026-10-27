package dev.pixelagent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

class OAuthSecurityTest {
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private fun encode(value: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    private fun jwks(): JSONObject {
        val key = pair.public as RSAPublicKey
        return JSONObject().put("keys", JSONArray().put(JSONObject().put("kty", "RSA").put("kid", "test")
            .put("n", encode(key.modulus.toByteArray())).put("e", encode(key.publicExponent.toByteArray()))))
    }
    private fun token(claims: JSONObject): String {
        val body = encode("""{"alg":"RS256","kid":"test"}""".toByteArray()) + "." + encode(claims.toString().toByteArray())
        val signature = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(body.toByteArray()) }.sign()
        return "$body.${encode(signature)}"
    }
    private fun claims() = JSONObject().put("iss", "https://auth.openai.com").put("sub", "account").put("aud", "client")
        .put("exp", 2000).put("nonce", "nonce")
    @Test fun pkceMatchesRfc7636() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", OAuthSecurity.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }
    @Test fun verifiesSignedIdentity() {
        assertEquals("account", OAuthSecurity.validateIdToken(token(claims()), jwks(), "client", "nonce", 1000).getString("sub"))
    }
    @Test fun rejectsInvalidClaims() {
        listOf(claims().put("nonce", "wrong"), claims().put("iss", "https://attacker.invalid"), claims().put("aud", "other"), claims().put("exp", 1000))
            .forEach { claims -> assertThrows(IllegalArgumentException::class.java) { OAuthSecurity.validateIdToken(token(claims), jwks(), "client", "nonce", 1000) } }
    }
    @Test fun rejectsTamperedSignature() {
        val signed = token(claims()).split('.').toMutableList()
        signed[1] = encode(claims().put("sub", "attacker").toString().toByteArray())
        assertThrows(IllegalArgumentException::class.java) { OAuthSecurity.validateIdToken(signed.joinToString("."), jwks(), "client", "nonce", 1000) }
    }
    @Test fun validatesNewAndReturningCallbackRegistrations() {
        val callback = mapOf("state" to listOf("state"), "code" to listOf("code"), "client_id" to listOf("issued"))
        assertEquals("issued", OAuthSecurity.callbackClient(callback, "state", null))
        assertEquals("issued", OAuthSecurity.callbackClient(callback - "client_id", "state", "issued"))
        listOf(callback + ("state" to listOf("forged")), callback + ("code" to listOf("one", "two")),
            callback + ("error" to listOf("access_denied")), callback + ("client_id" to listOf("dynamic_agent_client")))
            .forEach { value -> assertThrows(IllegalArgumentException::class.java) { OAuthSecurity.callbackClient(value, "state", null) } }
        assertThrows(IllegalArgumentException::class.java) { OAuthSecurity.callbackClient(callback, "state", "different") }
    }
}
