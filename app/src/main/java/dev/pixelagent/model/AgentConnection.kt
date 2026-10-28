package dev.pixelagent.model

import android.content.Context

/** One auth mutex and provider per process, shared by the UI and microphone service. */
class AgentConnection private constructor(context: Context) {
    val auth = PlanUsageAuth(CredentialStore(context))
    val provider = PlanUsageProvider(auth)
    var catalog: List<ModelChoice> = emptyList()
    companion object {
        @Volatile private var instance: AgentConnection? = null
        fun get(context: Context): AgentConnection = instance ?: synchronized(this) {
            instance ?: AgentConnection(context.applicationContext).also { instance = it }
        }
    }
}
