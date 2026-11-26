package com.smartisan.music.effects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [效果验证] 基础设施冒烟测试。
 *
 * 目的只有一个：证明 Robolectric Native Graphics + Compose + Roborazzi
 * 在本项目（JDK 20 / compileSdk 37 / AGP 9.3.2）下能真正渲染出 PNG。
 * 跑通 recordRoborazziDebug → verifyRoborazziDebug 闭环后，
 * 才有资格把真实 UI 效果（置灰/分界线/眨眼）固化成 baseline。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EffectSnapshotSmokeTest {

    @Test
    fun smoke_captureSolidBox() {
        captureRoboImage("effect_smoke_box.png") {
            Box(Modifier.size(120.dp).background(Color.Red))
        }
    }
}
