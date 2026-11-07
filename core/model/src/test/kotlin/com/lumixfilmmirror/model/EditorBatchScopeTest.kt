package com.lumixfilmmirror.model

import org.junit.Assert.*
import org.junit.Test

class EditorBatchScopeTest {
    private val a=BatchPhoto(PhotoId("a"),FilmParameters(exposureEv=1f,contrast=.3f),11)
    private val b=BatchPhoto(PhotoId("b"),FilmParameters(exposureEv=-.7f,grainIntensity=FilmGrainIntensity.LOW),22)
    @Test fun initialStockAppliesToWholeImportWhileLaterEditsAreIndependent() {
        val initial=FilmBatch(listOf(a,b),a.id).seedPreset("cinestill_400d")
        assertEquals(FilmBatchMode.INDIVIDUAL,initial.mode)
        assertTrue(initial.photos.all {it.parameters.presetId=="cinestill_400d" && it.parameters.halationEnabled})
        val edited=initial.updateParameters(b.id,initial.effectiveParameters(b.id).withPreset("cinestill_800t").copy(exposureEv=2f))
        assertEquals("cinestill_400d",edited.effectiveParameters(a.id).presetId)
        assertEquals(1f,edited.effectiveParameters(a.id).exposureEv,0f)
        assertEquals(.3f,edited.effectiveParameters(a.id).contrast,0f)
        assertEquals(FilmGrainIntensity.LOW,edited.effectiveParameters(b.id).grainIntensity)
        assertEquals(listOf(11L,22L),edited.photos.map {it.grainSeed})
    }
    @Test fun appendingAnImportDoesNotRestylePreviouslyEditedPhotos() {
        val batch=FilmBatch(listOf(a,b),a.id).seedPreset("cinestill_400d",setOf(b.id))
        assertEquals(a,batch.photos.first())
        assertEquals("cinestill_400d",batch.effectiveParameters(b.id).presetId)
        assertEquals(-.7f,batch.effectiveParameters(b.id).exposureEv,0f)
    }
    @Test fun explicitLinkingStillPreservesIndependentDrafts() {
        val linked=FilmBatch(listOf(b,a),a.id,FilmBatchMode.FIRST_PHOTO).seedPreset("cinestill_400d")
        assertEquals(1f,linked.effectiveParameters(b.id).exposureEv,0f)
        val independent=linked.copy(mode=FilmBatchMode.INDIVIDUAL)
        assertEquals(-.7f,independent.effectiveParameters(b.id).exposureEv,0f)
        assertEquals(listOf(22L,11L),independent.photos.map {it.grainSeed})
    }
}
