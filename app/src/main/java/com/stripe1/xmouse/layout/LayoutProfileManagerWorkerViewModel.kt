package com.stripe1.xmouse.layout

import androidx.lifecycle.ViewModel
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Keeps name input and repository callbacks on one serial owner, without an Activity reference. */
class LayoutProfileManagerWorkerViewModel(
    repository: LayoutProfileRepository,
    private val worker: Executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "xmouse-profile-manager").apply { isDaemon = true }
    },
) : ViewModel() {
    private var manager: LayoutProfileManagerViewModel? = null
    private val mutableState = MutableStateFlow(LayoutProfileManagerState())
    val state = mutableState.asStateFlow()

    init {
        worker.execute {
            manager = LayoutProfileManagerViewModel(repository)
            mutableState.value = manager!!.state.value
        }
    }

    fun edit(action: (LayoutProfileManagerViewModel) -> Unit) {
        worker.execute {
            val owner = manager ?: return@execute
            action(owner)
            mutableState.value = owner.state.value
        }
    }

    override fun onCleared() { (worker as? ExecutorService)?.shutdown() }
}
