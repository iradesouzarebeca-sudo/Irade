package com.example.ui.screens

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke as CanvasStroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.entity.PdfDocument
import com.example.data.model.PointF
import com.example.data.model.Stroke
import com.example.data.model.TextNote
import com.example.ui.viewmodel.PdfViewModel
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfWorkspaceScreen(
    viewModel: PdfViewModel,
    document: PdfDocument,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val currentPage by viewModel.currentPage.collectAsState()
    val totalPages by viewModel.totalPages.collectAsState()
    
    val pageBitmap by viewModel.pageBitmap.collectAsState()
    val isLoadingBitmap by viewModel.isLoadingBitmap.collectAsState()

    val strokes by viewModel.strokes.collectAsState()
    val textNotes by viewModel.textNotes.collectAsState()
    val currentStrokePoints by viewModel.currentStrokePoints.collectAsState()

    val selectedTool by viewModel.selectedTool.collectAsState()
    val selectedColor by viewModel.selectedColor.collectAsState()
    val brushThickness by viewModel.brushThickness.collectAsState()

    var showAddNoteDialog by remember { mutableStateOf<PointF?>(null) }
    var selectedNoteToEdit by remember { mutableStateOf<TextNote?>(null) }
    
    var exportingPageMessage by remember { mutableStateOf<String?>(null) }

    // Color swatches (ARGB integer representation)
    val colorSwatches = remember {
        listOf(
            android.graphics.Color.parseColor("#FF3B30"), // Red
            android.graphics.Color.parseColor("#007AFF"), // Blue
            android.graphics.Color.parseColor("#34C759"), // Green
            android.graphics.Color.parseColor("#FFCC00"), // Yellow
            android.graphics.Color.parseColor("#000000"), // Black
            android.graphics.Color.parseColor("#AF52DE"), // Purple
            android.graphics.Color.parseColor("#FF9500"), // Orange
            android.graphics.Color.parseColor("#00C7BE")  // Teal
        )
    }

    // Brush thicknesses
    val coreThicknesses = listOf(4f, 8f, 16f, 24f)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = document.title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Página ${currentPage + 1} de $totalPages",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.closeWorkspace() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
                actions = {
                    // Undo action
                    IconButton(
                        onClick = { viewModel.undoLastStroke() },
                        enabled = strokes.isNotEmpty()
                    ) {
                        Icon(Icons.Default.Undo, contentDescription = "Desfazer desenho")
                    }

                    // Share action
                    IconButton(
                        onClick = {
                            exportingPageMessage = "Preparando compartilhamento..."
                            coroutineScope.launch {
                                viewModel.exportCurrentPageToImage { file ->
                                    exportingPageMessage = null
                                    if (file != null) {
                                        sharePdfPageImage(context, file)
                                    } else {
                                        // Error feedback
                                    }
                                }
                            }
                        },
                        enabled = pageBitmap != null
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Compartilhar página anotada")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            
            // Core PDF Canvas Box
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isLoadingBitmap) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator()
                        Text("Renderizando página...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (pageBitmap != null) {
                    val bitmap = pageBitmap!!
                    val aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()

                    // Canvas wrapper keeping perfect PDF aspect ratio
                    Box(
                        modifier = Modifier
                            .aspectRatio(aspectRatio)
                            .fillMaxHeight()
                            .shadow(3.dp, RoundedCornerShape(16.dp))
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                            .clipToBounds()
                            .pointerInput(selectedTool) {
                                detectTapGestures(
                                    onTap = { offset ->
                                        if (selectedTool == "TEXT") {
                                            val normX = offset.x / size.width
                                            val normY = offset.y / size.height
                                            showAddNoteDialog = PointF(normX, normY)
                                        }
                                    }
                                )
                            }
                            .pointerInput(selectedTool) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        if (selectedTool == "PEN" || selectedTool == "ERASER") {
                                            val normX = offset.x / size.width
                                            val normY = offset.y / size.height
                                            viewModel.startNewStroke(normX, normY)
                                        }
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        val offset = change.position
                                        val normX = offset.x / size.width
                                        val normY = offset.y / size.height

                                        if (selectedTool == "PEN") {
                                            viewModel.addPointToStroke(normX, normY)
                                        } else if (selectedTool == "ERASER") {
                                            viewModel.eraseStrokesAtProximity(normX, normY)
                                        }
                                    },
                                    onDragEnd = {
                                        if (selectedTool == "PEN" || selectedTool == "ERASER") {
                                            viewModel.finishStroke()
                                        }
                                    }
                                )
                            }
                    ) {
                        // 1. PDF Page Render Background
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Página do PDF",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )

                        // 2. Custom Drawing Canvas
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val containerWidth = size.width
                            val containerHeight = size.height

                            // Draw previously completed strokes
                            strokes.forEach { stroke ->
                                val strokeWidthPx = stroke.thickness.dp.toPx()
                                val paintColor = Color(stroke.color)

                                if (stroke.points.size > 1) {
                                    val path = Path()
                                    path.moveTo(stroke.points[0].x * containerWidth, stroke.points[0].y * containerHeight)
                                    for (i in 1 until stroke.points.size) {
                                        path.lineTo(stroke.points[i].x * containerWidth, stroke.points[i].y * containerHeight)
                                    }
                                    drawPath(
                                        path = path,
                                        color = if (stroke.isEraser) Color.White else paintColor,
                                        style = CanvasStroke(
                                            width = strokeWidthPx,
                                            cap = StrokeCap.Round,
                                            join = StrokeJoin.Round
                                        )
                                    )
                                } else if (stroke.points.size == 1) {
                                    drawCircle(
                                        color = if (stroke.isEraser) Color.White else paintColor,
                                        radius = strokeWidthPx / 2f,
                                        center = Offset(stroke.points[0].x * containerWidth, stroke.points[0].y * containerHeight)
                                    )
                                }
                            }

                            // Draw current stroke in progress
                            if (currentStrokePoints.size > 1) {
                                val path = Path()
                                path.moveTo(currentStrokePoints[0].x * containerWidth, currentStrokePoints[0].y * containerHeight)
                                for (i in 1 until currentStrokePoints.size) {
                                    path.lineTo(currentStrokePoints[i].x * containerWidth, currentStrokePoints[i].y * containerHeight)
                                }
                                drawPath(
                                    path = path,
                                    color = if (selectedTool == "ERASER") Color.White else Color(selectedColor),
                                    style = CanvasStroke(
                                        width = brushThickness.dp.toPx() * (if (selectedTool == "ERASER") 2f else 1f),
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round
                                    )
                                )
                            }
                        }

                        // 3. Text Notes Layer
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            val widthPx = constraints.maxWidth.toFloat()
                            val heightPx = constraints.maxHeight.toFloat()
                            val density = LocalDensity.current

                            textNotes.forEach { note ->
                                val xOffsetDp = with(density) { (note.x * widthPx).toDp() }
                                val yOffsetDp = with(density) { (note.y * heightPx).toDp() }

                                Box(
                                    modifier = Modifier
                                        .offset(x = xOffsetDp - 12.dp, y = yOffsetDp - 12.dp)
                                        .pointerInput(note) {
                                            detectDragGestures(
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    val newX = (note.x * widthPx + dragAmount.x) / widthPx
                                                    val newY = (note.y * heightPx + dragAmount.y) / heightPx
                                                    viewModel.updateTextNotePosition(note.id, newX, newY)
                                                }
                                            )
                                        }
                                        .shadow(4.dp, RoundedCornerShape(8.dp))
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFFFEF9C3)) // Yellow post-it
                                        .border(1.dp, Color(0xFFEAB308), RoundedCornerShape(8.dp))
                                        .clickable { selectedNoteToEdit = note }
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier.widthIn(max = 160.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ChatBubble,
                                            contentDescription = null,
                                            tint = Color(note.color),
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Text(
                                            text = note.text,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color.Black,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Text("Falha ao abrir visualização do PDF.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                }
            }

            // 1. AUXILIARY PROPERTIES PANEL (Color Picker / Brush Thickness)
            if (selectedTool == "PEN" || selectedTool == "TEXT" || selectedTool == "ERASER") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .shadow(2.dp, RoundedCornerShape(16.dp))
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(16.dp)
                        ),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (selectedTool == "PEN" || selectedTool == "TEXT") {
                            // Color Picker row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                colorSwatches.forEach { colorInt ->
                                    val isColorSelected = selectedColor == colorInt
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .shadow(if (isColorSelected) 4.dp else 1.dp, CircleShape)
                                            .clip(CircleShape)
                                            .background(Color(colorInt))
                                            .border(
                                                width = if (isColorSelected) 3.dp else 1.dp,
                                                color = if (isColorSelected) MaterialTheme.colorScheme.onSurface else Color.LightGray.copy(alpha = 0.5f),
                                                shape = CircleShape
                                            )
                                            .clickable { viewModel.selectColor(colorInt) }
                                    )
                                }
                            }
                        }

                        if (selectedTool == "PEN" || selectedTool == "ERASER") {
                            Divider(color = MaterialTheme.colorScheme.surfaceVariant, thickness = 1.dp)

                            // Brush thickness chooser
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Espessura:",
                                    fontSize = 12.sp,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                
                                coreThicknesses.forEach { thicknessVal ->
                                    val isThicknessSelected = brushThickness == thicknessVal
                                    Box(
                                        modifier = Modifier
                                            .height(32.dp)
                                            .widthIn(min = 52.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (isThicknessSelected) MaterialTheme.colorScheme.primaryContainer
                                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                            )
                                            .clickable { viewModel.setBrushThickness(thicknessVal) }
                                            .border(
                                                width = 1.dp,
                                                color = if (isThicknessSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .padding(horizontal = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .height(thicknessVal.coerceAtMost(10f).dp / 2f)
                                                    .width(16.dp)
                                                    .clip(CircleShape)
                                                    .background(if (isThicknessSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                            )
                                            Text(
                                                "${thicknessVal.toInt()}px",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isThicknessSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Space to separate controls from footer
            Spacer(modifier = Modifier.height(6.dp))

            // 2. FOOTER TOOLBAR: ANNOTATION PILL BAR & PAGINATION
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(vertical = 12.dp, horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left pagination
                    IconButton(
                        onClick = { viewModel.prevPage() },
                        enabled = currentPage > 0,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
                            contentDescription = "Página anterior",
                            tint = if (currentPage > 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    // Centered Pill Toolbar: bg-[#EADDFF], round-full
                    Row(
                        modifier = Modifier
                            .height(48.dp)
                            .background(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(24.dp)
                            )
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Pen Button
                        IconButton(
                            onClick = { viewModel.selectTool("PEN") },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selectedTool == "PEN") MaterialTheme.colorScheme.onPrimaryContainer
                                    else Color.Transparent
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Caneta",
                                tint = if (selectedTool == "PEN") Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Eraser Button
                        IconButton(
                            onClick = { viewModel.selectTool("ERASER") },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selectedTool == "ERASER") MaterialTheme.colorScheme.onPrimaryContainer
                                    else Color.Transparent
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Default.CleaningServices,
                                contentDescription = "Borracha",
                                tint = if (selectedTool == "ERASER") Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Text Comment Button
                        IconButton(
                            onClick = { viewModel.selectTool("TEXT") },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selectedTool == "TEXT") MaterialTheme.colorScheme.onPrimaryContainer
                                    else Color.Transparent
                                )
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.NoteAdd,
                                contentDescription = "Nota de Texto",
                                tint = if (selectedTool == "TEXT") Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Right pagination
                    IconButton(
                        onClick = { viewModel.nextPage() },
                        enabled = currentPage < totalPages - 1,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = "Próxima página",
                            tint = if (currentPage < totalPages - 1) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }
    }

    // Add Text Annotation Dialog
    if (showAddNoteDialog != null) {
        var tempText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddNoteDialog = null },
            title = { Text("Nota de Texto") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Digite o comentário que você deseja acoplar ao PDF neste ponto:", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = tempText,
                        onValueChange = { tempText = it },
                        placeholder = { Text("Ex: Revisar este parágrafo...") },
                        singleLine = false,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (tempText.isNotBlank()) {
                            showAddNoteDialog?.let { point ->
                                viewModel.addTextNote(tempText, point.x, point.y)
                            }
                        }
                        showAddNoteDialog = null
                    },
                    enabled = tempText.isNotBlank()
                ) {
                    Text("Adicionar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddNoteDialog = null }) {
                    Text("Cancelar")
                }
            },
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Edit Text Annotation Dialog
    if (selectedNoteToEdit != null) {
        val note = selectedNoteToEdit!!
        var tempText by remember { mutableStateOf(note.text) }
        AlertDialog(
            onDismissRequest = { selectedNoteToEdit = null },
            title = { Text("Gerenciar Elemento") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Modifique o texto da anotação selecionada livremente:", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = tempText,
                        onValueChange = { tempText = it },
                        singleLine = false,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        onClick = {
                            viewModel.deleteTextNote(note.id)
                            selectedNoteToEdit = null
                        }
                    ) {
                        Text("Remover")
                    }
                    Button(
                        onClick = {
                            if (tempText.isNotBlank()) {
                                viewModel.deleteTextNote(note.id)
                                viewModel.addTextNote(tempText, note.x, note.y)
                            }
                            selectedNoteToEdit = null
                        },
                        enabled = tempText.isNotBlank()
                    ) {
                        Text("Atualizar")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedNoteToEdit = null }) {
                    Text("Cancelar")
                }
            },
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Exporting overlay loader
    if (exportingPageMessage != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(enabled = false) {},
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier.padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(strokeWidth = 3.dp)
                    Text(exportingPageMessage ?: "", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
fun ToolActionButton(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    val scaleColor = if (isSelected) activeColor.copy(alpha = 0.15f) else Color.Transparent
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(scaleColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isSelected) activeColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

// Utility to start Android Share intent
fun sharePdfPageImage(context: Context, file: File) {
    try {
        val authority = "${context.packageName}.fileprovider"
        val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)
        
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "Compartilhar Página Anotada"))
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
