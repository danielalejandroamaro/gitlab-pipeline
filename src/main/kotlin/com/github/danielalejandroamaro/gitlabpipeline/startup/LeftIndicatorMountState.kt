package com.github.danielalejandroamaro.gitlabpipeline.startup

import com.intellij.openapi.components.Service

/**
 * Project-level flag flipped by [LeftIndicatorMounter] when the leftmost pipeline
 * indicator is successfully pinned to the status bar's `leftPanel`. The right-side
 * widget factory reads it from `isAvailable` to suppress the duplicate widget without
 * touching the internal `StatusBar.removeWidget(String)` API.
 */
@Service(Service.Level.PROJECT)
class LeftIndicatorMountState {
    @Volatile
    var leftMounted: Boolean = false
}
