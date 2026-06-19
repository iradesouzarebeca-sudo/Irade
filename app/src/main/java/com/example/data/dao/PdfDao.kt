package com.example.data.dao

import androidx.room.*
import com.example.data.entity.PageAnnotationEntity
import com.example.data.entity.PdfDocument
import kotlinx.coroutines.flow.Flow

@Dao
interface PdfDao {
    @Query("SELECT * FROM pdf_documents ORDER BY addedAt DESC")
    fun getAllDocuments(): Flow<List<PdfDocument>>

    @Query("SELECT * FROM pdf_documents WHERE id = :id")
    suspend fun getDocumentById(id: Int): PdfDocument?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: PdfDocument): Long

    @Delete
    suspend fun deleteDocument(document: PdfDocument)

    @Query("SELECT * FROM page_annotations WHERE documentId = :documentId AND pageIndex = :pageIndex")
    suspend fun getAnnotationsForPage(documentId: Int, pageIndex: Int): PageAnnotationEntity?

    @Query("SELECT * FROM page_annotations WHERE documentId = :documentId")
    fun getAnnotationsForDocument(documentId: Int): Flow<List<PageAnnotationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAnnotations(annotation: PageAnnotationEntity)

    @Query("DELETE FROM page_annotations WHERE documentId = :documentId")
    suspend fun deleteAnnotationsForDocument(documentId: Int)
}
