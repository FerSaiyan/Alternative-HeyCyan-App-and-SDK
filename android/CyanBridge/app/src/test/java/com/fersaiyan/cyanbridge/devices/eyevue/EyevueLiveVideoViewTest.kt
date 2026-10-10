package com.fersaiyan.cyanbridge.devices.eyevue

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class EyevueLiveVideoViewTest {
    @Test
    fun rotatedLandscapeLayoutFitsThePortraitViewportWithoutOverscaling() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = EyevueLiveVideoView(context)
        view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 360, 480)
        val landscape = view.getChildAt(0)
        assertEquals(480, landscape.width)
        assertEquals(360, landscape.height)
        assertEquals(-90f, landscape.rotation, 0f)
        assertEquals(1f, landscape.scaleX, 0f)
        assertEquals(1f, landscape.scaleY, 0f)
        assertEquals(180f, landscape.left + landscape.pivotX, 0f)
        assertEquals(240f, landscape.top + landscape.pivotY, 0f)
    }
}
