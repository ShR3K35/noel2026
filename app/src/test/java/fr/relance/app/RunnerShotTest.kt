package fr.relance.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import fr.relance.app.core.RunnerState
import fr.relance.app.ui.C
import fr.relance.app.ui.Runner
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Captures du coureur animé dans ses quatre attitudes, à plusieurs instants. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class RunnerShotTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun runnerInEveryState() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Column(Modifier.background(C.Night)) {
                Runner(0.0, RunnerState.STOPPED, true, Modifier.fillMaxWidth().height(136.dp))
                Runner(5.0, RunnerState.WALK, true, Modifier.fillMaxWidth().height(136.dp))
                Runner(7.0, RunnerState.JOG, true, Modifier.fillMaxWidth().height(136.dp))
                Runner(11.5, RunnerState.FAST, true, Modifier.fillMaxWidth().height(136.dp))
            }
        }
        for ((i, ms) in listOf(1500L, 170L, 170L, 4200L).withIndex()) {
            rule.mainClock.advanceTimeBy(ms)
            rule.onRoot().captureRoboImage("build/shots/runner-$i.png")
        }
    }
}
