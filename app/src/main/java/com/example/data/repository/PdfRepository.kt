package com.example.data.repository

import android.content.Context
import android.net.Uri
import com.example.data.dao.PdfDao
import com.example.data.entity.PageAnnotationEntity
import com.example.data.entity.PdfDocument
import com.example.data.model.PageAnnotationsData
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class PdfRepository(
    private val pdfDao: PdfDao,
    private val context: Context
) {
    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
    private val adapter = moshi.adapter(PageAnnotationsData::class.java)

    val allDocuments: Flow<List<PdfDocument>> = pdfDao.getAllDocuments()

    suspend fun getDocumentById(id: Int): PdfDocument? = withContext(Dispatchers.IO) {
        pdfDao.getDocumentById(id)
    }

    suspend fun insertDocument(document: PdfDocument): Long = withContext(Dispatchers.IO) {
        pdfDao.insertDocument(document)
    }

    suspend fun deleteDocument(document: PdfDocument) = withContext(Dispatchers.IO) {
        // Delete the local file
        try {
            val file = File(document.filePath)
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        pdfDao.deleteDocument(document)
        pdfDao.deleteAnnotationsForDocument(document.id)
    }

    suspend fun getAnnotationsForPage(documentId: Int, pageIndex: Int): PageAnnotationsData = withContext(Dispatchers.IO) {
        val entity = pdfDao.getAnnotationsForPage(documentId, pageIndex)
        if (entity != null) {
            try {
                return@withContext adapter.fromJson(entity.annotationsJson) ?: PageAnnotationsData()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return@withContext PageAnnotationsData()
    }

    suspend fun saveAnnotationsForPage(documentId: Int, pageIndex: Int, data: PageAnnotationsData) = withContext(Dispatchers.IO) {
        val json = adapter.toJson(data)
        val entity = pdfDao.getAnnotationsForPage(documentId, pageIndex)
        val updatedEntity = if (entity != null) {
            entity.copy(annotationsJson = json)
        } else {
            PageAnnotationEntity(
                documentId = documentId,
                pageIndex = pageIndex,
                annotationsJson = json
            )
        }
        pdfDao.insertOrUpdateAnnotations(updatedEntity)
    }

    /**
     * Copies a PDF file from an external URI (like from content resolver / document picker)
     * to the app's secure internal storage files directory where we can open it directly in code.
     */
    suspend fun importPdf(uri: Uri, fileName: String): File? = withContext(Dispatchers.IO) {
        try {
            val cleanName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val uniqueName = "${System.currentTimeMillis()}_$cleanName"
            val targetFile = File(context.filesDir, uniqueName)
            
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(targetFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            return@withContext targetFile
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }
}
