package com.unishare.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.launch

class UniShareApplication : Application(), DefaultLifecycleObserver {
    private val graphOwner = ProcessGraphOwner { AppGraph(this) }
    val graph: AppGraph get() = graphOwner.value

    override fun onCreate() {
        super<Application>.onCreate()
        graphOwner.initialize()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        graph.scope.launch { graph.runtimeController.setAppVisible(true) }
    }

    override fun onStop(owner: LifecycleOwner) {
        graph.scope.launch { graph.runtimeController.setAppVisible(false) }
    }
}
