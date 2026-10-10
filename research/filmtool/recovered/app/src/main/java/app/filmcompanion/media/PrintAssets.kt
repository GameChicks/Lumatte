package app.filmcompanion.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** App-owned copies survive revoked document-provider grants; only hash-shaped keys can be opened. */
class PrintAssets(private val context: Context) {
    val directory=File(context.filesDir,"print-assets").apply {mkdirs()}
    fun file(key: String?): File? = key?.takeIf {it.matches(Regex("[a-f0-9]{64}\\.(png|font)"))}
        ?.let {File(directory,it)}?.takeIf {it.isFile}
    fun bitmap(key: String?): Bitmap?=file(key)?.let {BitmapFactory.decodeFile(it.absolutePath)}
    fun font(key: String?): Typeface?=file(key)?.let {runCatching {Typeface.createFromFile(it)}.getOrNull()}
    suspend fun importImage(uri: Uri,name: String): String=withContext(Dispatchers.IO) {
        val b=ScanDecoder(context).decode(uri,name,640)
        try { val bytes=java.io.ByteArrayOutputStream().also {check(b.compress(Bitmap.CompressFormat.PNG,100,it))}.toByteArray();save(bytes,"png") }
        finally {b.recycle()}
    }
    suspend fun importFont(uri: Uri): String=withContext(Dispatchers.IO) {
        val bytes=context.contentResolver.openInputStream(uri)?.use {input ->
            val out=java.io.ByteArrayOutputStream();val chunk=ByteArray(8192)
            while(true) {val count=input.read(chunk);if(count<0)break;require(out.size()+count<=8*1024*1024) {"字体文件超过 8 MiB"};out.write(chunk,0,count)}
            out.toByteArray()
        } ?: error("字体不可读取")
        require(bytes.size<=8*1024*1024) {"字体文件超过 8 MiB"}
        val key=save(bytes,"font")
        require(font(key)!=null) {"无法读取该字体，请选择 TTF 或 OTF"}
        key
    }
    private fun save(bytes: ByteArray,extension: String): String {
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
        val key=hash+"."+extension;val target=File(directory,key)
        if(!target.exists()) {val temp=File.createTempFile("import-",".part",directory);try {temp.writeBytes(bytes);check(temp.renameTo(target))} finally {temp.delete()}}
        return key
    }
}
