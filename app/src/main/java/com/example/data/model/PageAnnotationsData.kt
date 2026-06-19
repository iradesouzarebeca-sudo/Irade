package com.example.data.model

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PointF(
    val x: Float,
    val y: Float
)

@JsonClass(generateAdapter = true)
data class Stroke(
    val points: List<PointF>,
    val color: Int,
    val thickness: Float,
    val isEraser: Boolean = false
)

@JsonClass(generateAdapter = true)
data class TextNote(
    val id: String,
    val text: String,
    val x: Float, // Normalized X position (0f..1f)
    val y: Float, // Normalized Y position (0f..1f)
    val color: Int,
    val fontSize: Float = 16f
)

@JsonClass(generateAdapter = true)
data class PageAnnotationsData(
    val strokes: List<Stroke> = emptyList(),
    val notes: List<TextNote> = emptyList()
)
