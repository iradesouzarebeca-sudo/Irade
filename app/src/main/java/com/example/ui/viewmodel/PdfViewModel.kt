package com.example.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.entity.PdfDocument
import com.example.data.model.PageAnnotationsData
import com.example.data.model.PointF
import com.example.data.model.Stroke
import com.example.data.model.TextNote
import com.example.data.repository.PdfRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

sealed interface UiState {
    object ListDocuments : UiState
    data class AnnotationWorkspace(val document: PdfDocument) : UiState
}

class PdfViewModel(
    private val repository: PdfRepository,
    private val application: Application
) : ViewModel() {

    // List of active documents
    val documents: StateFlow<List<PdfDocument>> = repository.allDocuments
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _uiState = MutableStateFlow<UiState>(UiState.ListDocuments)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Workspace States
    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    private val _totalPages = MutableStateFlow(0)
    val totalPages: StateFlow<Int> = _totalPages.asStateFlow()

    private val _strokes = MutableStateFlow<List<Stroke>>(emptyList())
    val strokes: StateFlow<List<Stroke>> = _strokes.asStateFlow()

    private val _textNotes = MutableStateFlow<List<TextNote>>(emptyList())
    val textNotes: StateFlow<List<TextNote>> = _textNotes.asStateFlow()

    private val _currentStrokePoints = MutableStateFlow<List<PointF>>(emptyList())
    val currentStrokePoints: StateFlow<List<PointF>> = _currentStrokePoints.asStateFlow()

    // Tool Configurations
    private val _selectedTool = MutableStateFlow("PEN") // "PEN", "ERASER", "TEXT"
    val selectedTool: StateFlow<String> = _selectedTool.asStateFlow()

    private val _selectedColor = MutableStateFlow(android.graphics.Color.RED)
    val selectedColor: StateFlow<Int> = _selectedColor.asStateFlow()

    private val _brushThickness = MutableStateFlow(8f)
    val brushThickness: StateFlow<Float> = _brushThickness.asStateFlow()

    // PDF bitmap rendering state
    private val _pageBitmap = MutableStateFlow<Bitmap?>(null)
    val pageBitmap: StateFlow<Bitmap?> = _pageBitmap.asStateFlow()

    private val _isLoadingBitmap = MutableStateFlow(false)
    val isLoadingBitmap: StateFlow<Boolean> = _isLoadingBitmap.asStateFlow()

    private val _importingStatus = MutableStateFlow<String?>(null)
    val importingStatus: StateFlow<String?> = _importingStatus.asStateFlow()

    private var currentPdfRenderer: PdfRenderer? = null
    private var currentFileDescriptor: ParcelFileDescriptor? = null

    fun selectTool(tool: String) {
        _selectedTool.value = tool
    }

    fun selectColor(color: Int) {
        _selectedColor.value = color
    }

    fun setBrushThickness(thickness: Float) {
        _brushThickness.value = thickness
    }

    fun startNewStroke(x: Float, y: Float) {
        _currentStrokePoints.value = listOf(PointF(x, y))
    }

    fun addPointToStroke(x: Float, y: Float) {
        val currentPoints = _currentStrokePoints.value
        _currentStrokePoints.value = currentPoints + PointF(x, y)
    }

    fun finishStroke() {
        val points = _currentStrokePoints.value
        if (points.isNotEmpty()) {
            if (_selectedTool.value == "PEN") {
                val newStroke = Stroke(
                    points = points,
                    color = _selectedColor.value,
                    thickness = _brushThickness.value,
                    isEraser = false
                )
                _strokes.value = _strokes.value + newStroke
            } else if (_selectedTool.value == "ERASER") {
                // Erase stroke if we are drawing with eraser
                _strokes.value = _strokes.value + Stroke(
                    points = points,
                    color = android.graphics.Color.WHITE, // simple visual eraser
                    thickness = _brushThickness.value * 2f,
                    isEraser = true
                )
            }
            _currentStrokePoints.value = emptyList()
            saveCurrentAnnotations()
        }
    }

    fun eraseStrokesAtProximity(x: Float, y: Float) {
        // Erase any existing stroke that contains points close to this coordinate
        val threshold = 25f
        val filtered = _strokes.value.filter { stroke ->
            stroke.points.none { point ->
                val dx = point.x - x
                val dy = point.y - y
                (dx * dx + dy * dy) < (threshold * threshold)
            }
        }
        if (filtered.size != _strokes.value.size) {
            _strokes.value = filtered
            saveCurrentAnnotations()
        }
    }

    fun undoLastStroke() {
        val currentStrokesList = _strokes.value
        if (currentStrokesList.isNotEmpty()) {
            _strokes.value = currentStrokesList.dropLast(1)
            saveCurrentAnnotations()
        }
    }

    fun addTextNote(text: String, x: Float, y: Float) {
        val note = TextNote(
            id = UUID.randomUUID().toString(),
            text = text,
            x = x,
            y = y,
            color = _selectedColor.value,
            fontSize = 16f
        )
        _textNotes.value = _textNotes.value + note
        saveCurrentAnnotations()
    }

    fun deleteTextNote(noteId: String) {
        _textNotes.value = _textNotes.value.filter { it.id != noteId }
        saveCurrentAnnotations()
    }

    fun updateTextNotePosition(noteId: String, newX: Float, newY: Float) {
        _textNotes.value = _textNotes.value.map {
            if (it.id == noteId) it.copy(x = newX.coerceIn(0f, 1f), y = newY.coerceIn(0f, 1f)) else it
        }
        saveCurrentAnnotations()
    }

    fun openWorkspace(document: PdfDocument) {
        _uiState.value = UiState.AnnotationWorkspace(document)
        _currentPage.value = 0
        _totalPages.value = document.totalPages
        
        initializeRenderer(document.filePath)
        loadAnnotationsAndPage(document.id, 0)
    }

    fun closeWorkspace() {
        saveCurrentAnnotations()
        releaseRenderer()
        _pageBitmap.value = null
        _uiState.value = UiState.ListDocuments
    }

    private fun initializeRenderer(filePath: String) {
        releaseRenderer()
        try {
            val file = File(filePath)
            if (file.exists()) {
                currentFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                currentFileDescriptor?.let { fd ->
                    currentPdfRenderer = PdfRenderer(fd)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseRenderer() {
        try {
            currentPdfRenderer?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            currentFileDescriptor?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        currentPdfRenderer = null
        currentFileDescriptor = null
    }

    fun nextPage() {
        val activeDoc = (_uiState.value as? UiState.AnnotationWorkspace)?.document ?: return
        val current = _currentPage.value
        val total = _totalPages.value
        if (current < total - 1) {
            saveCurrentAnnotations()
            val nextIdx = current + 1
            _currentPage.value = nextIdx
            loadAnnotationsAndPage(activeDoc.id, nextIdx)
        }
    }

    fun prevPage() {
        val activeDoc = (_uiState.value as? UiState.AnnotationWorkspace)?.document ?: return
        val current = _currentPage.value
        if (current > 0) {
            saveCurrentAnnotations()
            val prevIdx = current - 1
            _currentPage.value = prevIdx
            loadAnnotationsAndPage(activeDoc.id, prevIdx)
        }
    }

    private fun loadAnnotationsAndPage(documentId: Int, pageIndex: Int) {
        viewModelScope.launch {
            // Load annotations from database
            val anims = repository.getAnnotationsForPage(documentId, pageIndex)
            _strokes.value = anims.strokes
            _textNotes.value = anims.notes

            // Render PDF Page as bitmap
            renderPdfPageToBitmap(pageIndex)
        }
    }

    private fun renderPdfPageToBitmap(pageIndex: Int) {
        val renderer = currentPdfRenderer ?: return
        if (pageIndex < 0 || pageIndex >= renderer.pageCount) return

        _isLoadingBitmap.value = true
        _pageBitmap.value = null

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val page = renderer.openPage(pageIndex)
                
                // Render at a high density scale for crystal clear visual output
                // Normal scale: 2x or 3x for high res zoom
                val scale = 2.5f
                val targetWidth = (page.width * scale).toInt()
                val targetHeight = (page.height * scale).toInt()

                val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                withContext(Dispatchers.Main) {
                    _pageBitmap.value = bitmap
                    _isLoadingBitmap.value = false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    _isLoadingBitmap.value = false
                }
            }
        }
    }

    private fun saveCurrentAnnotations() {
        val activeDoc = (_uiState.value as? UiState.AnnotationWorkspace)?.document ?: return
        val pageIdx = _currentPage.value
        val strokesList = _strokes.value
        val notesList = _textNotes.value
        
        viewModelScope.launch {
            repository.saveAnnotationsForPage(
                documentId = activeDoc.id,
                pageIndex = pageIdx,
                data = PageAnnotationsData(strokes = strokesList, notes = notesList)
            )
        }
    }

    fun deleteDocument(document: PdfDocument) {
        viewModelScope.launch {
            repository.deleteDocument(document)
        }
    }

    fun importSelectedPdf(uri: Uri, contentResolver: android.content.ContentResolver) {
        _importingStatus.value = "Importando arquivo..."
        viewModelScope.launch {
            try {
                val fileName = getFileName(uri, contentResolver) ?: "documento.pdf"
                val importedFile = repository.importPdf(uri, fileName)
                
                if (importedFile != null && importedFile.exists()) {
                    // Try to open file to verify page count
                    var pageCount = 0
                    try {
                        val fd = ParcelFileDescriptor.open(importedFile, ParcelFileDescriptor.MODE_READ_ONLY)
                        val renderer = PdfRenderer(fd)
                        pageCount = renderer.pageCount
                        renderer.close()
                        fd.close()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    val title = fileName.removeSuffix(".pdf").removeSuffix(".PDF")
                        .replace("_", " ")
                        .trim()

                    val pdfDoc = PdfDocument(
                        title = title,
                        fileName = fileName,
                        filePath = importedFile.absolutePath,
                        totalPages = pageCount,
                        fileSize = importedFile.length()
                    )

                    val newId = repository.insertDocument(pdfDoc)
                    _importingStatus.value = "Sucesso!"
                    
                    // Immediately open workspace for newly imported document
                    val completeDoc = pdfDoc.copy(id = newId.toInt())
                    openWorkspace(completeDoc)
                } else {
                    _importingStatus.value = "Falha ao copiar PDF."
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _importingStatus.value = "Erro: ${e.localizedMessage}"
            } finally {
                // Diminish status message after a brief delay
                viewModelScope.launch {
                    kotlinx.coroutines.delay(2000)
                    _importingStatus.value = null
                }
            }
        }
    }

    private fun getFileName(uri: Uri, contentResolver: android.content.ContentResolver): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        name = it.getString(index)
                    }
                }
            }
        }
        if (name == null) {
            name = uri.path
            val cut = name?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                name = name?.substring(cut + 1)
            }
        }
        return name
    }

    /**
     * Renders a flattened visual representation (PDF page bitmap + overlay annotations)
     * and saves it to a shared cache directory so the user can share it as an image.
     */
    suspend fun exportCurrentPageToImage(onComplete: (File?) -> Unit) {
        val bitmap = _pageBitmap.value ?: return onComplete(null)
        val strokesList = _strokes.value
        val notesList = _textNotes.value

        withContext(Dispatchers.IO) {
            try {
                // Create a mutable copy of the bitmap to draw on
                val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(mutableBitmap)
                val width = bitmap.width.toFloat()
                val height = bitmap.height.toFloat()

                // 1. Draw strokes
                val paint = Paint().apply {
                    isAntiAlias = true
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }

                strokesList.forEach { stroke ->
                    paint.color = stroke.color
                    paint.strokeWidth = stroke.thickness * 2.5f // scale width to match visual resolution
                    if (stroke.isEraser) {
                        paint.color = android.graphics.Color.WHITE // white out
                    }

                    if (stroke.points.size > 1) {
                        val path = android.graphics.Path()
                        path.moveTo(stroke.points[0].x * width, stroke.points[0].y * height)
                        for (i in 1 until stroke.points.size) {
                            path.lineTo(stroke.points[i].x * width, stroke.points[i].y * height)
                        }
                        canvas.drawPath(path, paint)
                    } else if (stroke.points.size == 1) {
                        canvas.drawPoint(stroke.points[0].x * width, stroke.points[0].y * height, paint)
                    }
                }

                // 2. Draw text notes
                val textPaint = Paint().apply {
                    isAntiAlias = true
                    textSize = 40f // robust scale
                    style = Paint.Style.FILL
                }
                
                val bgPaint = Paint().apply {
                    isAntiAlias = true
                    color = android.graphics.Color.parseColor("#FEF9C3") // rich yellow post-it background
                    style = Paint.Style.FILL
                }

                val borderPaint = Paint().apply {
                    isAntiAlias = true
                    color = android.graphics.Color.parseColor("#CA8A04")
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                }

                notesList.forEach { note ->
                    val xPos = note.x * width
                    val yPos = note.y * height
                    
                    textPaint.color = note.color
                    val noteText = note.text
                    val textWidth = textPaint.measureText(noteText)
                    val textHeight = textPaint.textSize

                    // Draw a cute styled card background for the note
                    val padding = 20f
                    val rect = android.graphics.RectF(
                        xPos - padding,
                        yPos - textHeight - padding,
                        xPos + textWidth + padding,
                        yPos + padding
                    )
                    canvas.drawRoundRect(rect, 10f, 10f, bgPaint)
                    canvas.drawRoundRect(rect, 10f, 10f, borderPaint)
                    canvas.drawText(noteText, xPos, yPos, textPaint)
                }

                // Save bitmap to cache
                val cacheDir = application.cacheDir
                val exportFile = File(cacheDir, "AnotadorPDF_${System.currentTimeMillis()}.png")
                val out = FileOutputStream(exportFile)
                mutableBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
                out.close()

                withContext(Dispatchers.Main) {
                    onComplete(exportFile)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onComplete(null)
                }
            }
        }
    }
}

class PdfViewModelFactory(
    private val repository: PdfRepository,
    private val application: Application
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PdfViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return PdfViewModel(repository, application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
