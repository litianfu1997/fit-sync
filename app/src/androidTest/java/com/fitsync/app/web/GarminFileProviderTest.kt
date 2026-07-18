package com.fitsync.app.web

import android.content.Context
import android.content.Intent
import android.webkit.WebView
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fitsync.app.data.db.entity.ActivityVariantEntity
import com.fitsync.app.data.file.FitFileStore
import com.fitsync.app.domain.model.CollectorType
import com.fitsync.app.domain.model.ValidationStatus
import com.fitsync.app.fit.FitCrc
import com.fitsync.app.fit.FitEngine
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class GarminFileProviderTest {
    @Test
    fun validFit_isPreparedAndReadableByWebView() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fitsDir = File(context.filesDir, "fits").also(File::mkdirs)
        val source = File(fitsDir, "garmin_provider_source.fit")
        val fitBytes = validActivityFit()
        source.writeBytes(fitBytes)
        val preparer = GarminUploadFilePreparer(context, FitEngine())

        try {
            val prepared = preparer.prepare(jobId = 9_001, variant = source.asVariant())
            assertThat(prepared).isInstanceOf(GarminUploadPreparation.Ready::class.java)
            val ready = prepared as GarminUploadPreparation.Ready
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", ready.file)
            val webViewPackage = WebView.getCurrentWebViewPackage()
            assertThat(webViewPackage).isNotNull()

            context.grantUriPermission(
                webViewPackage!!.packageName,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            val readBack = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }

            assertThat(uri.scheme).isEqualTo("content")
            assertThat(readBack).isEqualTo(fitBytes)
            assertThat(ready.recordCount).isEqualTo(1)
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } finally {
            preparer.cleanup(9_001)
            source.delete()
        }
    }

    @Test
    fun zipRenamedAsFit_isExtractedBeforeGarminUpload() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fitsDir = File(context.filesDir, "fits").also(File::mkdirs)
        val disguisedZip = File(fitsDir, "garmin_zip_source.fit")
        val fitBytes = validActivityFit()
        ZipOutputStream(FileOutputStream(disguisedZip)).use { zip ->
            zip.putNextEntry(ZipEntry("设备导出/骑行.fit"))
            zip.write(fitBytes)
            zip.closeEntry()
        }
        val preparer = GarminUploadFilePreparer(context, FitEngine())

        try {
            val prepared = preparer.prepare(jobId = 9_002, variant = disguisedZip.asVariant())
            assertThat(prepared).isInstanceOf(GarminUploadPreparation.Ready::class.java)
            val ready = prepared as GarminUploadPreparation.Ready

            assertThat(ready.file.name).matches("FitSync_9002_[0-9a-f]{12}\\.fit")
            assertThat(ready.file.readBytes()).isEqualTo(fitBytes)
            assertThat(ready.recordCount).isEqualTo(1)
        } finally {
            preparer.cleanup(9_002)
            disguisedZip.delete()
        }
    }

    private fun File.asVariant() = ActivityVariantEntity(
        canonicalActivityId = 1,
        collectorType = CollectorType.LOCAL,
        localFilePath = absolutePath,
        fileSha256 = FitFileStore.sha256Of(this),
        fileSize = length(),
        validationStatus = ValidationStatus.VALID,
    )

    private fun validActivityFit(): ByteArray {
        val data = ByteArrayOutputStream().apply {
            definition(local = 0, global = 0, fields = listOf(Field(0, 1, 0x00)))
            data(local = 0) { write(4) }

            definition(
                local = 1,
                global = 20,
                fields = listOf(Field(3, 1, 0x02), Field(253, 4, 0x86)),
            )
            data(local = 1) {
                write(150)
                writeLe32(1_000)
            }

            definition(
                local = 2,
                global = 18,
                fields = listOf(
                    Field(2, 4, 0x86),
                    Field(5, 1, 0x00),
                    Field(7, 4, 0x86),
                    Field(8, 4, 0x86),
                    Field(9, 4, 0x86),
                    Field(253, 4, 0x86),
                ),
            )
            data(local = 2) {
                writeLe32(1_000)
                write(2)
                writeLe32(3_600_000)
                writeLe32(3_500_000)
                writeLe32(4_219_500)
                writeLe32(4_600)
            }
        }.toByteArray()

        val headerSize = 14
        return ByteArray(headerSize + data.size + 2).apply {
            this[0] = headerSize.toByte()
            this[1] = 0x20
            ByteBuffer.wrap(this, 2, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(0x083E.toShort())
            ByteBuffer.wrap(this, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size)
            ".FIT".toByteArray().copyInto(this, 8)
            val headerCrc = FitCrc.compute(this, 0, 12)
            this[12] = (headerCrc and 0xFF).toByte()
            this[13] = ((headerCrc shr 8) and 0xFF).toByte()
            data.copyInto(this, headerSize)
            val fileCrc = FitCrc.compute(this, 0, headerSize + data.size)
            this[headerSize + data.size] = (fileCrc and 0xFF).toByte()
            this[headerSize + data.size + 1] = ((fileCrc shr 8) and 0xFF).toByte()
        }
    }

    private data class Field(val number: Int, val size: Int, val baseType: Int)

    private fun ByteArrayOutputStream.definition(local: Int, global: Int, fields: List<Field>) {
        write(0x40 or local)
        write(0)
        write(0)
        writeLe16(global)
        write(fields.size)
        fields.forEach { field ->
            write(field.number)
            write(field.size)
            write(field.baseType)
        }
    }

    private fun ByteArrayOutputStream.data(local: Int, body: ByteArrayOutputStream.() -> Unit) {
        write(local)
        body()
    }

    private fun ByteArrayOutputStream.writeLe16(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeLe32(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }
}
