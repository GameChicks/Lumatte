package com.lumixfilmmirror.media

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.*
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MediaBackendTest {
    private val identity = "LUT_3D_SIZE 2\n" + (0..1).flatMap { b -> (0..1).flatMap { g ->
        (0..1).map { r -> "$r $g $b" }
    } }.joinToString("\n")

    @Test fun cubeUsesRedFastestAndInterpolatesAllAxes() {
        val lut = CubeLut.parse(identity,"identity")
        val result = FloatArray(3)
        for (sample in listOf(floatArrayOf(.13f,.71f,.39f),floatArrayOf(0f,1f,.5f))) {
            lut.apply(sample,result); assertArrayEquals(sample,result,1e-6f)
        }
    }
    @Test fun cubeCacheRoundTripRejectsTrailingAndTruncatedBytes() {
        val output = ByteArrayOutputStream()
        CubeLut.parse(identity,"identity").write(DataOutputStream(output))
        val bytes = output.toByteArray()
        val lut = CubeLut.read(DataInputStream(bytes.inputStream()))
        val result = FloatArray(3); lut.apply(floatArrayOf(.2f,.4f,.9f), result)
        assertArrayEquals(floatArrayOf(.2f,.4f,.9f),result,1e-6f)
        assertThrows(IllegalArgumentException::class.java) { CubeLut.read(DataInputStream((bytes+byteArrayOf(1)).inputStream())) }
        assertThrows(EOFException::class.java) { CubeLut.read(DataInputStream(bytes.copyOf(bytes.size-1).inputStream())) }
    }
    @Test fun invalidCubeNeverBecomesSilentIdentity() {
        for (text in listOf("LUT_3D_SIZE 66", identity.replace("0 0 0","NaN 0 0"),
            "LUT_1D_SIZE 2\n0 0 0", "DOMAIN_MIN 1 0 0\nDOMAIN_MAX 0 1 1\n$identity"))
            assertThrows(Exception::class.java) { CubeLut.parse(text,"bad") }
        assertThrows(CancellationException::class.java) { CubeLut.parse(identity,"cancel") { throw CancellationException() } }
    }
    private fun zip(vararg entries: Pair<String,String>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip -> entries.forEach { (name,text) ->
            zip.putNextEntry(ZipEntry(name));zip.write(text.toByteArray());zip.closeEntry()
        } }
    }.toByteArray()
    @Test fun officialZipSelectsOneCubeAndRejectsAmbiguousOrUnsafeMembers() {
        assertArrayEquals(identity.toByteArray(),LumixLabZip.cube(zip("folder/test.cube" to identity,"readme.txt" to "notice")) {})
        for (bytes in listOf(zip("../test.cube" to identity), zip("a.cube" to identity,"b.CUBE" to identity),
            zip("readme.txt" to "none"),zip("empty.cube" to ""),zip("/root.cube" to identity)))
            assertThrows(LumixLabProtocolException::class.java) { LumixLabZip.cube(bytes) {} }
        assertThrows(CancellationException::class.java) { LumixLabZip.cube(zip("test.cube" to identity)) { throw CancellationException() } }
    }
    @Test fun catalogueRequiresKnownTypesAndPreservesMetadata() {
        val list = LumixLabJson.luts(JSONObject("""{"filter":[{"code":"abc","title":"Film","thumbnail":{"path":"/example.jpg","width":"320","height":240}}]}"""))
        assertEquals("abc",list.single().code);assertEquals(320,list.single().thumbnail?.width)
        for (json in listOf("{}","{\"filter\":{}}","{\"filter\":[null]}","{\"filter\":[{\"code\":42,\"title\":\"bad\"}]}"))
            assertThrows(LumixLabProtocolException::class.java) { LumixLabJson.luts(JSONObject(json)) }
        assertTrue(LumixLabJson.luts(JSONObject("{\"filter\":[]}")).isEmpty())
    }
    private fun colours(vararg families: Pair<LutColourFamily,Float>, mono: Boolean = false) = LutExampleColours(
        LutColourOrigin.CREATOR_EXAMPLE,"sample",null,emptyList(),families.toMap(),
        if(mono) LutExampleColourMode.NEAR_MONOCHROME else LutExampleColourMode.COLOUR)
    @Test fun remoteRankingUsesExampleColoursWithoutALutDownload() {
        val warm = colours(LutColourFamily.ORANGE to .8f,LutColourFamily.NEUTRAL to .2f)
        val cool = colours(LutColourFamily.BLUE to .8f,LutColourFamily.NEUTRAL to .2f)
        assertEquals(0.0,LutExampleSimilarity.distance(warm,warm),1e-9)
        assertTrue(LutExampleSimilarity.distance(warm,cool) > .5)
        assertEquals(LutRecommendationStyle.WARM,LutExampleSimilarity.style(warm))
        assertEquals(LutRecommendationStyle.COOL,LutExampleSimilarity.style(cool))
        assertEquals(LutRecommendationStyle.MONOCHROME,LutExampleSimilarity.style(colours(LutColourFamily.NEUTRAL to 1f,mono=true)))
        assertTrue(LutExampleSimilarity.distance(warm,colours()).isInfinite())
    }
}
