package dev.pixelagent.conversation

import android.app.Application
import androidx.lifecycle.AndroidViewModel

class AgentViewModel(application: Application) : AndroidViewModel(application) {
    val runtime = AgentRuntime.get(application)
}
