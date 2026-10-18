package com.lumixfilmmirror.model

import org.junit.Assert.*
import org.junit.Test

class BatchContractTest {
    @Test fun inheritedReferenceDoesNotFollowReorderingOrOverwriteDrafts() {
        val first = BatchPhoto(PhotoId("first"), FilmParameters(exposureEv = 1f), 11)
        val other = BatchPhoto(PhotoId("other"), FilmParameters(exposureEv = -1f), 22)
        val batch = FilmBatch(listOf(other, first), first.id)
        assertEquals(first.parameters, batch.effectiveParameters(other.id))
        assertEquals(other.parameters, batch.copy(mode = FilmBatchMode.INDIVIDUAL).effectiveParameters(other.id))
        assertEquals(22L, batch.copyReferenceToAll().photos.first().grainSeed)
    }
    @Test fun firstPhotoModeRejectsEditingOtherPhoto() {
        val p = BatchPhoto(PhotoId("a"), FilmParameters(), 0)
        val q = p.copy(id = PhotoId("b"))
        assertThrows(IllegalArgumentException::class.java) {
            FilmBatch(listOf(p,q), p.id).updateParameters(q.id, FilmParameters(exposureEv = 2f))
        }
    }
    @Test fun dimensionPercentageIsIndependentOfCompression() {
        assertEquals(PixelSize(6000,4000), JpegOutput(compressionQuality = 1).contentSize(PixelSize(6000,4000)))
        assertEquals(PixelSize(3000,2000), JpegOutput(50,100).contentSize(PixelSize(6000,4000)))
    }
    @Test fun frameDoesNotShrinkRequestedPhotoAcrossRatiosAndOrientations() {
        for (size in listOf(PixelSize(6000,4000), PixelSize(4000,6000)))
            for (preset in FramePreset.entries) for (ratio in FrameRatio.entries) {
                val layout = FrameLayouts.plan(size, FrameOptions(preset = preset, ratio = ratio))
                assertEquals(size.width.toFloat(), layout.photo.width, .01f)
                assertEquals(size.height.toFloat(), layout.photo.height, .01f)
                assertTrue(layout.photo.left >= 0 && layout.photo.top >= 0)
                assertTrue(layout.photo.right <= layout.canvas.width && layout.photo.bottom <= layout.canvas.height)
            }
    }
    @Test fun specialHalationDefaultsDoNotSpreadToOtherStocks() {
        assertTrue(FilmParameters("cinestill_800t").halationEnabled)
        assertTrue(FilmParameters("cinestill_400d").halationEnabled)
        assertFalse(FilmParameters("kodak_gold_200").halationEnabled)
        assertFalse(FilmParameters("kodak_vision3_500t").halationEnabled)
        assertFalse(FilmParameters("cinestill_800t").withPreset("kodak_gold_200").halationEnabled)
    }
    @Test fun invalidParametersFailBeforeRendering() {
        for (ev in listOf(Float.NaN, Float.POSITIVE_INFINITY, -3.1f, 3.1f))
            assertThrows(IllegalArgumentException::class.java) { FilmParameters(exposureEv = ev) }
        assertThrows(IllegalArgumentException::class.java) { FrameOptions(imageKey = "../user.png") }
        assertThrows(IllegalArgumentException::class.java) { FrameOptions(borderFraction = Float.NaN) }
    }
}
