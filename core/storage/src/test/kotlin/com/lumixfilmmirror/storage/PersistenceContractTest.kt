package com.lumixfilmmirror.storage

import com.lumixfilmmirror.model.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class PersistenceContractTest {
    @Test fun recipesRoundTripWithSeedsAndBothLutSlots() {
        val recipes = listOf(PhotoRecipe.Original,
            PhotoRecipe.Film(FilmParameters("cinestill_800t",-.75f,DiffusionGrade.QUARTER,true),Long.MIN_VALUE),
            PhotoRecipe.Lut(LutRecipe(LutSlot("first",.37f),LutSlot("second",.8f),.25f,false,true)))
        recipes.forEach { assertEquals(it,PhotoRecipeCodec.read(PhotoRecipeCodec.write(it))) }
    }
    @Test fun stalePushPresetMigratesWithoutChangingExposure() {
        val text = PhotoRecipeCodec.write(PhotoRecipe.Film(FilmParameters("kodak_portra_800_push1",1.2f),55))
        val loaded = PhotoRecipeCodec.read(text) as PhotoRecipe.Film
        assertEquals("kodak_portra_800",loaded.parameters.presetId)
        assertEquals(1.2f,loaded.parameters.exposureEv,0f)
        assertEquals(55L,loaded.grainSeed)
    }
    @Test fun allSharedFramePresetsPreserveSettingsAndCustomAssets() {
        for(preset in FramePreset.entries) {
            val frame = FrameOptions(preset=preset,title="中文 title",signature="© name",fixedColorArgb=0xff123456.toInt(),
                portrait=true,filmBlackWhite=false,showCamera=false,showCapturedAt=true,
                imageKey="a".repeat(64)+".png",titleFontKey="b".repeat(64)+".font")
            assertEquals(frame,FrameOptionsCodec.read(FrameOptionsCodec.write(frame)))
        }
    }
    @Test fun futureVersionsAndInvalidValuesCannotSilentlyChangeExports() {
        assertThrows(IllegalArgumentException::class.java) {
            PhotoRecipeCodec.read(JSONObject(PhotoRecipeCodec.write(PhotoRecipe.Original)).put("version",99).toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            FrameOptionsCodec.read(JSONObject(FrameOptionsCodec.write(FrameOptions())).put("border",.9).toString())
        }
    }
}
