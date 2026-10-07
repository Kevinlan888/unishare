package com.unishare.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.launch

class UniShareApplication : Application(), DefaultLifecycleObserver {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super<Application>.onCreate()
        graph = AppGraph(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        graph.scope.launch { graph.runtimeController.setAppVisible(true) }
    }

    override fun onStop(owner: LifecycleOwner) {
        graph.scope.launch { graph.runtimeController.setAppVisible(false) }
    }
}
