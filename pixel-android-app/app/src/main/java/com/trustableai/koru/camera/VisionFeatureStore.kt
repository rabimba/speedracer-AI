package com.trustableai.koru.camera

import com.trustableai.koru.model.VisionFeatureSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object VisionFeatureStore {
    private val latestSnapshotFlow = MutableStateFlow<VisionFeatureSnapshot?>(null)

    val latest: StateFlow<VisionFeatureSnapshot?> = latestSnapshotFlow.asStateFlow()

    fun update(snapshot: VisionFeatureSnapshot) {
        latestSnapshotFlow.value = snapshot
    }

    fun clear() {
        latestSnapshotFlow.value = null
    }
}
