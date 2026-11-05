package com.dc.andagent.tools

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageEditEngineTest {
    private fun sample(): Bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).also {
        it.eraseColor(Color.rgb(60, 100, 140))
    }

    @Test fun cropAndRotateDimensions() {
        val cropped = ImageEditEngine.apply(sample(), listOf(ImageOp.Crop(10, 20, 60, 40, null, "center")))
        assertEquals(60, cropped.width)
        assertEquals(40, cropped.height)
        val rotated = ImageEditEngine.apply(cropped, listOf(ImageOp.Rotate(90f)))
        assertEquals(40, rotated.width)
        assertEquals(60, rotated.height)
        rotated.recycle()
    }

    @Test fun brightnessRaisesPixelValue() {
        val source = sample()
        val original = Color.red(source.getPixel(50, 50))
        val brighter = ImageEditEngine.apply(source, listOf(ImageOp.Brightness(30)))
        assertTrue(Color.red(brighter.getPixel(50, 50)) > original)
        brighter.recycle()
    }

    @Test fun grayscaleMakesChannelsEqual() {
        val gray = ImageEditEngine.apply(sample(), listOf(ImageOp.Grayscale))
        val pixel = gray.getPixel(50, 50)
        assertEquals(Color.red(pixel), Color.green(pixel))
        assertEquals(Color.green(pixel), Color.blue(pixel))
        gray.recycle()
    }
}
