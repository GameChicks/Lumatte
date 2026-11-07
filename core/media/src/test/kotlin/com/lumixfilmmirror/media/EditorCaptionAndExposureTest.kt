package com.lumixfilmmirror.media

import com.lumixfilmmirror.model.*
import java.io.File
import kotlin.math.pow
import org.junit.Assert.*
import org.junit.Test

class EditorCaptionAndExposureTest {
    private val source=SourceImage(PhotoId("fixture"),"fixture",File("unused.jpg"),PixelSize(4000,6000),1,InputOrigin.Jpeg,PhotoMetadata())
    private val frame=FrameOptions(preset=FramePreset.PAPER,filmBrand="Kodak",filmName="Gold 200")
    @Test fun missingCameraDoesNotCreateAnEmptyMainTitle() {
        val fields=frameCaptionFields(source,frame,null)
        assertEquals(listOf("subtitle","signature"),fields.map {it.key})
        assertEquals("文字",fields.first().label)
        assertEquals("Kodak Gold 200",fields.first().text)
        assertEquals(24f,captionTypeSize(fields.first(),fields,frame),0f)
    }
    @Test fun captionProofContainsTextWithoutThePhotoAndAvoidsUnusedMargins() {
        val layout=FrameLayouts.plan(source.size,frame)
        val box=captionProofBounds(source,frame,null,layout)
        assertTrue(box.top>=layout.photo.bottom)
        assertTrue(box.height>0 && box.height<layout.caption.height)
        assertTrue(box.left>=0 && box.right<=layout.canvas.width)
    }
    @Test fun ticketKeepsItsRealThreeLinesAndCustomFontTargets() {
        val options=frame.copy(preset=FramePreset.TICKET,subtitleFont=FrameFont.SERIF)
        val fields=frameCaptionFields(source,options,null)
        assertEquals(listOf("title","subtitle","footer","signature"),fields.map {it.key})
        assertEquals(FrameFont.SERIF,fields[1].font)
        assertEquals("Where the light lingers",fields[0].text)
    }
    private fun encode(v:Float)=if(v<=.0031308f)12.92f*v else 1.055f*v.pow(1f/2.4f)-.055f
    @Test fun exposureDoublesLinearLightAndCanLowerClippedWhiteBeforeTheLook() {
        val input=FloatArray(3){encode(.18f)}
        PhotoFinishing.apply(input,PhotoAdjustments(exposure=1f))
        assertArrayEquals(FloatArray(3){encode(.36f)},input,2e-6f)
        val white=floatArrayOf(1f,1f,1f)
        PhotoFinishing.apply(white,PhotoAdjustments(exposure=-2f))
        assertArrayEquals(FloatArray(3){encode(.25f)},white,2e-6f)
    }
    @Test fun zeroAdjustmentsAreAnExactBypass() {
        val input=floatArrayOf(.137f,.612f,.923f);val original=input.copyOf()
        PhotoFinishing.apply(input,PhotoAdjustments())
        assertArrayEquals(original,input,0f)
    }
}
