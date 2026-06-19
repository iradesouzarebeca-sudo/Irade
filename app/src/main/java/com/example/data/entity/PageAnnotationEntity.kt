package com.example.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "page_annotations")
data class PageAnnotationEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val documentId: Int,
    val pageIndex: Int,
    val annotationsJson: String // PageAnnotationsData serialized to JSON
)
