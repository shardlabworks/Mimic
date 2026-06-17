package com.example

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.rotate

data class ParsedLandmark(val id: Int, val x: Float, val y: Float, val visibility: Float)
data class ParsedFrame(val timestamp: Double, val landmarks: List<ParsedLandmark>)

data class PlaybackMetadata(
    var isFaceData: Boolean = false,
    var imageWidth: Int = 0,
    var imageHeight: Int = 0,
    var isFrontCamera: Boolean = true,
    var minX: Float = Float.MAX_VALUE,
    var maxX: Float = Float.MIN_VALUE,
    var minY: Float = Float.MAX_VALUE,
    var maxY: Float = Float.MIN_VALUE
)

@Composable
fun PlaybackScreen(modifier: Modifier = Modifier, file: File, onNavigateBack: () -> Unit) {
    val bgDark = Color(0xFF1A1C1E)
    val accentBlue = Color(0xFFD0E4FF)
    
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var frames by remember { mutableStateOf<List<ParsedFrame>?>(null) }
    var metadata by remember { mutableStateOf(PlaybackMetadata()) }
    var currentPlaybackTime by remember { mutableStateOf(0.0) }
    var isPlaying by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var playbackSpeed by remember { mutableStateOf(1f) }

    LaunchedEffect(file) {
        if (file.exists() && (file.extension == "json" || file.extension == "mimic")) {
            try {
                val parsedData = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val result = mutableListOf<ParsedFrame>()
                    val parsedMetadata = PlaybackMetadata()
                    
                    val inputStream = java.io.FileInputStream(file)
                    val reader = if (file.extension == "mimic") {
                        val gzis = java.util.zip.GZIPInputStream(inputStream)
                        android.util.JsonReader(java.io.InputStreamReader(gzis, "UTF-8"))
                    } else {
                        android.util.JsonReader(java.io.InputStreamReader(inputStream, "UTF-8"))
                    }
                    reader.beginArray()
                    while (reader.hasNext()) {
                        reader.beginObject()
                        val frameLandmarks = mutableListOf<ParsedLandmark>()
                        var timestamp = 0.0
                        while (reader.hasNext()) {
                            val name = reader.nextName()
                            when (name) {
                                "timestamp" -> timestamp = reader.nextDouble()
                                "type" -> {
                                    if (reader.nextString() == "metadata") {
                                        // inside metadata
                                    }
                                }
                                "tracking_mode" -> {
                                    if (reader.nextString() == "FACE") {
                                        parsedMetadata.isFaceData = true
                                    }
                                }
                                "image_width" -> parsedMetadata.imageWidth = reader.nextInt()
                                "image_height" -> parsedMetadata.imageHeight = reader.nextInt()
                                "is_front_camera" -> parsedMetadata.isFrontCamera = reader.nextBoolean()
                                "landmarks" -> {
                                    reader.beginArray()
                                    while (reader.hasNext()) {
                                        var id = 0
                                        var x = 0f
                                        var y = 0f
                                        var visibility = 0f
                                        
                                        reader.beginObject()
                                        while (reader.hasNext()) {
                                            when (reader.nextName()) {
                                                "id" -> id = reader.nextInt()
                                                "x" -> x = reader.nextDouble().toFloat()
                                                "y" -> y = reader.nextDouble().toFloat()
                                                "visibility" -> visibility = reader.nextDouble().toFloat()
                                                else -> reader.skipValue()
                                            }
                                        }
                                        reader.endObject()
                                        frameLandmarks.add(ParsedLandmark(id, x, y, visibility))
                                        
                                        if (visibility > 0) {
                                            if (x < parsedMetadata.minX) parsedMetadata.minX = x
                                            if (x > parsedMetadata.maxX) parsedMetadata.maxX = x
                                            if (y < parsedMetadata.minY) parsedMetadata.minY = y
                                            if (y > parsedMetadata.maxY) parsedMetadata.maxY = y
                                        }
                                    }
                                    reader.endArray()
                                }
                                "blendshapes" -> reader.skipValue()
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        if (frameLandmarks.isNotEmpty()) {
                            result.add(ParsedFrame(timestamp, frameLandmarks))
                        }
                    }
                    reader.endArray()
                    reader.close()
                    
                    Pair(result, parsedMetadata)
                }
                frames = parsedData.first
                metadata = parsedData.second
            } catch (e: Exception) {
                e.printStackTrace()
                errorMessage = "Failed to load file. It may be corrupted."
            }
        }
    }

    LaunchedEffect(isPlaying, frames, playbackSpeed) {
        if (isPlaying && frames != null && frames!!.isNotEmpty()) {
            val totalDuration = frames!!.last().timestamp - frames!!.first().timestamp
            var lastUpdate = System.currentTimeMillis()
            while (isPlaying) {
                val now = System.currentTimeMillis()
                val dt = (now - lastUpdate) / 1000.0 * playbackSpeed
                lastUpdate = now

                currentPlaybackTime += dt
                if (currentPlaybackTime >= totalDuration) {
                    currentPlaybackTime = totalDuration
                    isPlaying = false
                }
                delay(16L) // ~60fps
            }
        }
    }

    // Standard MediaPipe Pose connections
    val connections = listOf(
        Pair(11, 12), Pair(11, 13), Pair(13, 15), Pair(12, 14), Pair(14, 16),
        Pair(11, 23), Pair(12, 24), Pair(23, 24), Pair(23, 25), Pair(24, 26),
        Pair(25, 27), Pair(26, 28), Pair(27, 29), Pair(28, 30), Pair(29, 31),
        Pair(30, 32), Pair(27, 31), Pair(28, 32)
    )

    Column(modifier = modifier.fillMaxSize().background(bgDark).clickable(
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        indication = null
    ) { showControls = !showControls }) {
        // App Bar
        androidx.compose.animation.AnimatedVisibility(
            visible = showControls,
            enter = androidx.compose.animation.slideInVertically(initialOffsetY = { -it }) + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { -it }) + androidx.compose.animation.fadeOut()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Text(
                    text = "Viewing: ${file.nameWithoutExtension}",
                    color = Color.White,
                    fontSize = 18.sp,
                    maxLines = 1,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            if (errorMessage != null) {
                Text(text = errorMessage!!, color = Color.Red, fontWeight = FontWeight.Bold)
            } else if (frames == null) {
                if (file.extension == "bvh") {
                    Text(text = "BVH playback not supported yet.", color = Color.Gray)
                } else {
                    Text(text = "Loading frames...", color = Color.Gray)
                }
            } else if (frames!!.isNotEmpty()) {
                val frameList = frames!!
                
                val baseTime = frameList.first().timestamp + currentPlaybackTime
                
                var frameA = frameList.first()
                var frameB = frameList.first()
                
                // Binary search finds the exact index in O(log N) time
                var index = frameList.binarySearch { it.timestamp.compareTo(baseTime) }

                // If exact match isn't found, binarySearch returns -(insertion point) - 1
                if (index < 0) {
                    index = -(index + 1)
                }

                // Ensure we don't go out of bounds
                if (index == 0) {
                    frameA = frameList.first()
                    frameB = frameList.first()
                } else if (index >= frameList.size) {
                    frameA = frameList.last()
                    frameB = frameList.last()
                } else {
                    frameA = frameList[index - 1]
                    frameB = frameList[index]
                }

                val fraction = if (frameA.timestamp == frameB.timestamp) 0f else {
                    ((baseTime - frameA.timestamp) / (frameB.timestamp - frameA.timestamp)).toFloat()
                }

                val interpolatedLandmarks = frameA.landmarks.map { lmA ->
                    val lmB = frameB.landmarks.find { it.id == lmA.id }
                    if (lmB != null) {
                        ParsedLandmark(
                            lmA.id,
                            lmA.x + (lmB.x - lmA.x) * fraction,
                            lmA.y + (lmB.y - lmA.y) * fraction,
                            lmA.visibility + (lmB.visibility - lmA.visibility) * fraction
                        )
                    } else {
                        lmA
                    }
                }

                val isFace = metadata.isFaceData
                
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val mappedPoints = mutableMapOf<Int, Offset>()
                    val visibilities = mutableMapOf<Int, Float>()
                    
                    // Pass 1: map points
                    for (lm in interpolatedLandmarks) {
                            val id = lm.id
                            var x = lm.x
                            var y = lm.y
                            val visibility = lm.visibility
                            
                            visibilities[id] = visibility
                            
                            if (visibility > 0.0f) {
                                // If imageWidth/Height is provided, use it. Otherwise, use min/max bounds.
                                val iw = if (metadata.imageWidth > 0) metadata.imageWidth.toFloat() else (metadata.maxX - metadata.minX)
                                val ih = if (metadata.imageHeight > 0) metadata.imageHeight.toFloat() else (metadata.maxY - metadata.minY)
                                val wX = if (metadata.imageWidth > 0) x else (x - metadata.minX)
                                val wY = if (metadata.imageHeight > 0) y else (y - metadata.minY)
                                
                                val safeW = if (iw > 0) iw else 1f
                                val safeH = if (ih > 0) ih else 1f
                                
                                // Calculate scale to fit canvas width or height depending on aspect ratio
                                val scale = minOf(size.width / safeW, size.height / safeH)
                                
                                // Center the mapped rect in the canvas
                                val xOffset = (size.width - (safeW * scale)) / 2f
                                val yOffset = (size.height - (safeH * scale)) / 2f
                                
                                // Front camera needs mirroring logically, but the points are usually already mirrored in MLKit space if mapped correctly
                                // Actually, we'll just plot them as-is.
                                val cx = xOffset + wX * scale
                                val cy = yOffset + wY * scale
                                
                                mappedPoints[id] = Offset(cx, cy)
                            }
                        }
                        
                        // Draw Bones
                        if (!isFace) {
                            connections.forEach { (a, b) ->
                                val ptA = mappedPoints[a]
                                val ptB = mappedPoints[b]
                                val visA = visibilities[a] ?: 0f
                                val visB = visibilities[b] ?: 0f
                                
                                if (ptA != null && ptB != null && visA > 0.0f && visB > 0.0f) {
                                    val minVis = minOf(visA, visB)
                                    val color = when {
                                        minVis > 0.7f -> Color.Green.copy(alpha = 0.5f)
                                        minVis > 0.4f -> Color.Yellow.copy(alpha = 0.5f)
                                        minVis > 0.3f -> Color.Red.copy(alpha = 0.5f)
                                        else -> Color.Gray.copy(alpha = 0.4f)
                                    }
                                    val width = if (minVis > 0.3f) 8f else 4f
                                    drawLine(color, start = ptA, end = ptB, strokeWidth = width)
                                }
                            }
                        }

                        // Draw Joints
                        mappedPoints.forEach { (id, offset) ->
                            val visibility = visibilities[id] ?: 0f
                            val color = when {
                                visibility > 0.75f -> Color.Green
                                visibility > 0.4f -> Color.Yellow
                                visibility > 0.3f -> Color.Red
                                else -> Color.Gray
                            }
                            val radius = if (isFace) 2f else if (visibility > 0.3f) 6f else 4f
                            drawCircle(color, radius = radius, center = offset)
                        }
                    }
            }
        }

        // Controls
        if (frames != null && frames!!.isNotEmpty()) {
            androidx.compose.animation.AnimatedVisibility(
                visible = showControls,
                enter = androidx.compose.animation.slideInVertically(initialOffsetY = { it }) + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { it }) + androidx.compose.animation.fadeOut()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha=0.6f)).padding(horizontal = 16.dp, vertical = 24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val totalDuration = frames!!.last().timestamp - frames!!.first().timestamp
                    
                    val playInteractionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    val isPlayPressed by playInteractionSource.collectIsPressedAsState()
                    val playScale by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = if (isPlayPressed) 0.85f else 1f,
                        animationSpec = androidx.compose.animation.core.spring(dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy, stiffness = androidx.compose.animation.core.Spring.StiffnessLow),
                        label = "playScale"
                    )
                    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition()
                    val rotation by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                            animation = androidx.compose.animation.core.tween(3000, easing = androidx.compose.animation.core.LinearEasing),
                            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
                        ),
                        label = "rotation"
                    )

                    IconButton(
                        onClick = { 
                            if (currentPlaybackTime >= totalDuration) currentPlaybackTime = 0.0
                            isPlaying = !isPlaying 
                        },
                        modifier = Modifier
                            .graphicsLayer { scaleX = playScale; scaleY = playScale }
                            .drawBehind {
                                if (isPlaying) {
                                    rotate(rotation) {
                                        drawCircle(
                                            brush = androidx.compose.ui.graphics.Brush.sweepGradient(
                                                listOf(Color.Transparent, accentBlue.copy(alpha=0.4f), Color.Transparent)
                                            ),
                                            radius = size.minDimension / 1.5f
                                        )
                                    }
                                }
                            },
                        interactionSource = playInteractionSource
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = accentBlue
                        )
                    }
                    Text(
                        text = String.format("%.2fs / %.2fs", currentPlaybackTime, totalDuration),
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    Slider(
                        value = currentPlaybackTime.toFloat(),
                        onValueChange = { currentPlaybackTime = it.toDouble() },
                        valueRange = 0f..maxOf(0.01f, totalDuration.toFloat()),
                        modifier = Modifier.weight(1f),
                        colors = androidx.compose.material3.SliderDefaults.colors(
                            thumbColor = accentBlue,
                            activeTrackColor = accentBlue.copy(alpha = 0.7f),
                            inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                        )
                    )
                    Spacer(modifier = Modifier.width(16.dp))

                    val speedInteractionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    val isSpeedPressed by speedInteractionSource.collectIsPressedAsState()
                    val speedScale by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = if (isSpeedPressed) 0.85f else 1f,
                        animationSpec = androidx.compose.animation.core.spring(dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy, stiffness = androidx.compose.animation.core.Spring.StiffnessLow),
                        label = "speedScale"
                    )

                    Text(
                        text = "${playbackSpeed}x",
                        color = accentBlue,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .graphicsLayer { scaleX = speedScale; scaleY = speedScale }
                            .clickable(interactionSource = speedInteractionSource, indication = null) {
                                playbackSpeed = when (playbackSpeed) {
                                    1f -> 2f
                                    2f -> 0.5f
                                    else -> 1f
                                }
                            }
                            .padding(8.dp)
                    )
                }
            }
        }
    }
}
