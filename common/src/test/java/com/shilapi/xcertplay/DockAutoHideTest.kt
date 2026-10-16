package com.shilapi.xcertplay

import android.content.Context
import android.view.Surface
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import com.shilapi.xcertplay.media.CarPlayVideoLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class DockAutoHideTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @Before
    fun clearPreferences() {
        prefs.edit().clear().apply()
    }

    @Test
    fun dockAutoHideDefaultAndPersistence() {
        assertFalse(AirPlayPersistence.loadDockAutoHide(context))
        AirPlayPersistence.saveDockAutoHide(context, true)
        assertTrue(AirPlayPersistence.loadDockAutoHide(context))
        AirPlayPersistence.saveDockAutoHide(context, false)
        assertFalse(AirPlayPersistence.loadDockAutoHide(context))
    }

    @Test
    fun contentRectExpandsAppAreaWhenAutoHideActive() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val contentRectMethod = CarPlayHostActivity::class.java.getDeclaredMethod("contentRect", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply {
            isAccessible = true
        }
        val setField = { name: String, value: Any? ->
            CarPlayHostActivity::class.java.getDeclaredField(name).apply {
                isAccessible = true
                set(activity, value)
            }
        }

        val sessionDisplay = CarPlaySessionDisplay(1024, 600, Surface.ROTATION_0, true, true, 1024, 600)
        setField("sessionDisplay", sessionDisplay)
        setField("dockAutoHideEnabled", false)
        setField("dockRevealed", false)

        // 1. When auto-hide is disabled, standard fit
        val normalLayout = contentRectMethod.invoke(activity, 1024, 600) as CarPlayVideoLayout
        assertEquals(0f, normalLayout.left, 0.01f)
        assertEquals(0f, normalLayout.top, 0.01f)
        assertEquals(1024f, normalLayout.width, 0.01f)
        assertEquals(600f, normalLayout.height, 0.01f)

        // 2. When auto-hide is enabled (left-hand drive) and not revealed:
        setField("dockAutoHideEnabled", true)
        setField("dockRevealed", false)
        setField("rightHandDrive", false)

        val autoHideLayout = contentRectMethod.invoke(activity, 1024, 600) as CarPlayVideoLayout
        val expectedWidth = 1024f / (1f - 0.13f)
        val expectedLeft = -expectedWidth * 0.13f
        assertEquals(expectedLeft, autoHideLayout.left, 0.05f)
        assertEquals(0f, autoHideLayout.top, 0.01f)
        assertEquals(expectedWidth, autoHideLayout.width, 0.05f)
        assertEquals(600f, autoHideLayout.height, 0.01f)

        // Verify touch coordinate mapping across screen edges:
        val leftEdgeNormX = (0f - autoHideLayout.left) / autoHideLayout.width
        val rightEdgeNormX = (1024f - autoHideLayout.left) / autoHideLayout.width
        assertEquals(0.13, leftEdgeNormX.toDouble(), 0.001)
        assertEquals(1.0, rightEdgeNormX.toDouble(), 0.001)

        // 3. When dock is temporarily revealed, layout returns to standard fit:
        setField("dockRevealed", true)
        val revealedLayout = contentRectMethod.invoke(activity, 1024, 600) as CarPlayVideoLayout
        assertEquals(0f, revealedLayout.left, 0.01f)
        assertEquals(1024f, revealedLayout.width, 0.01f)

        // 4. Right-hand drive:
        setField("dockRevealed", false)
        setField("rightHandDrive", true)
        val rhdLayout = contentRectMethod.invoke(activity, 1024, 600) as CarPlayVideoLayout
        assertEquals(0f, rhdLayout.left, 0.01f)
        assertEquals(expectedWidth, rhdLayout.width, 0.05f)
        val rhdLeftEdgeNormX = (0f - rhdLayout.left) / rhdLayout.width
        val rhdRightEdgeNormX = (1024f - rhdLayout.left) / rhdLayout.width
        assertEquals(0.0, rhdLeftEdgeNormX.toDouble(), 0.001)
        assertEquals(0.87, rhdRightEdgeNormX.toDouble(), 0.001)
    }
}
