package com.example.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pdf_documents")
data class PdfDocument(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val fileName: String,
    val filePath: String, // Path to the copied local file in internal storage
    val addedAt: Long = System.currentTimeMillis(),
    val totalPages: Int = 0,
    val fileSize: Long = 0
)
