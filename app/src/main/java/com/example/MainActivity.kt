package com.example

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.widget.Toast
import android.media.ToneGenerator
import android.media.AudioManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.with
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import android.view.OrientationEventListener
import android.view.WindowManager
import android.os.PowerManager
import android.content.Context
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.FileProvider
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.*
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.nativeCanvas
import java.io.File
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        com.google.android.gms.ads.MobileAds.initialize(this) {}
        
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MoCapApp()
            }
        }
    }
}

enum class AppScreen { Splash, Capture, Library, Analytics, Playback, Privacy }

enum class TrackingState { SEARCHING, CALIBRATING, READY, RECORDING, LOSS }

@Composable
fun MoCapApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("mocap_prefs", android.content.Context.MODE_PRIVATE) }
    var privacyAccepted by remember { mutableStateOf(prefs.getBoolean("privacy_accepted", false)) }
    
    var hasCameraPermission by remember { 
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) 
    }
    var hasAudioPermission by remember { 
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) 
    }
    val allPermissionsGranted = hasCameraPermission && hasAudioPermission
    
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasCameraPermission = permissions[Manifest.permission.CAMERA] ?: hasCameraPermission
        hasAudioPermission = permissions[Manifest.permission.RECORD_AUDIO] ?: hasAudioPermission
    }
    
    var currentScreen by remember { mutableStateOf(AppScreen.Splash) }
    var fileToPlay by remember { mutableStateOf<java.io.File?>(null) }

    var isRecordingSession by remember { mutableStateOf(false) }

    val bgDark = androidx.compose.material3.MaterialTheme.colorScheme.background
    val textLight = androidx.compose.material3.MaterialTheme.colorScheme.onBackground
    val accentBlue = androidx.compose.material3.MaterialTheme.colorScheme.primary

    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Intercept back gesture for navigation screens (Library, Analytics, Playback)
    androidx.activity.compose.BackHandler(
        enabled = currentScreen != AppScreen.Capture && currentScreen != AppScreen.Splash && currentScreen != AppScreen.Privacy
    ) {
        // If we are deep in the Playback screen, swipe back to Library
        if (currentScreen == AppScreen.Playback) {
            currentScreen = AppScreen.Library
        } else {
            // Otherwise, swipe back to the Main Capture layout
            currentScreen = AppScreen.Capture
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        when {
            currentScreen == AppScreen.Splash -> {
                com.example.ui.AnimatedSplashScreen(
                    onAnimationFinished = {
                        currentScreen = if (privacyAccepted) AppScreen.Capture else AppScreen.Privacy
                    }
                )
            }
            
            !privacyAccepted || currentScreen == AppScreen.Privacy -> {
                PrivacyScreen(
                    modifier = Modifier.padding(innerPadding),
                    onAccept = {
                        prefs.edit().putBoolean("privacy_accepted", true).apply()
                        privacyAccepted = true
                        currentScreen = AppScreen.Capture
                    }
                )
            }
            allPermissionsGranted -> {
                when (currentScreen) {
                    AppScreen.Capture -> MoCapScreen(
                        modifier = Modifier.padding(innerPadding),
                        onNavigate = { currentScreen = it },
                        onRecordingStateChange = { isRecordingSession = it }
                    )
                    AppScreen.Library -> LibraryScreen(
                        modifier = Modifier.padding(innerPadding),
                        onNavigate = { currentScreen = it },
                        onNavigateBack = { currentScreen = AppScreen.Capture },
                        onPlay = { 
                            fileToPlay = it 
                            currentScreen = AppScreen.Playback 
                        }
                    )
                    AppScreen.Analytics -> AnalyticsScreen(
                        modifier = Modifier.padding(innerPadding),
                        onNavigate = { currentScreen = it },
                        onNavigateBack = { currentScreen = AppScreen.Capture }
                    )
                    AppScreen.Playback -> {
                        if (fileToPlay != null) {
                            PlaybackScreen(
                                modifier = Modifier.padding(innerPadding),
                                file = fileToPlay!!,
                                onNavigateBack = { currentScreen = AppScreen.Library }
                            )
                        } else {
                            currentScreen = AppScreen.Library
                        }
                    }
                    AppScreen.Splash -> {} // handled outside
                    AppScreen.Privacy -> {
                        // Handled above, but required for exhaustiveness
                    }
                }
                } // Close allPermissionsGranted -> {
                else -> {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Camera and Audio permissions are required.", color = Color.White)
                        Spacer(modifier = Modifier.height(16.dp))
                        androidx.compose.material3.Button(onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }) {
                            Text("Grant Permissions")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        androidx.compose.material3.TextButton(onClick = { 
                            (context as? android.app.Activity)?.finish() 
                        }) {
                            Text("Decline & Exit", color = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}


@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.animation.ExperimentalAnimationApi::class)
@Composable
fun MoCapScreen(
    modifier: Modifier = Modifier, 
    onNavigate: (AppScreen) -> Unit, 
    onRecordingStateChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("mocap_prefs", android.content.Context.MODE_PRIVATE) }
    
    var countdownSetting by remember { mutableStateOf(prefs.getInt("countdown_setting", 5)) }
    var autoStopSetting by remember { mutableStateOf(prefs.getInt("auto_stop_setting", 0)) }
    var xGestureEnabled by remember { mutableStateOf(prefs.getBoolean("x_gesture_enabled", true)) }
    var ipAddress by remember { mutableStateOf(prefs.getString("osc_ip", "192.168.1.100") ?: "192.168.1.100") }
    
    var showSettingsSheet by remember { mutableStateOf(false) }
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = false)
    
    var isProUnlocked by remember { mutableStateOf(prefs.getBoolean("is_pro", false)) }
    var totalStreamTimeMs by remember { mutableStateOf(prefs.getLong("stream_time_ms", 0L)) }
    var lastResetDate by remember { mutableStateOf(prefs.getString("last_reset_date", "")) }
    
    // Daily Reset Check
    val currentDateString = remember { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date()) }
    if (lastResetDate != currentDateString) {
        totalStreamTimeMs = 0L
        lastResetDate = currentDateString
        prefs.edit().putLong("stream_time_ms", 0L).putString("last_reset_date", currentDateString).apply()
    }
    val STREAM_LIMIT_MS = 15L * 60L * 1000L // 15 Minutes
    var isStreamLimitReached by remember { mutableStateOf(totalStreamTimeMs >= STREAM_LIMIT_MS) }
    
    var currentPose by remember { mutableStateOf<SmoothedPose?>(null) }
    var currentFaceMesh by remember { mutableStateOf<FaceTrackingFrame?>(null) }
    var trackingMode by remember { mutableStateOf(TrackingMode.BODY) }
    var imageWidth by remember { mutableStateOf(0) }
    var imageHeight by remember { mutableStateOf(0) }
    
    var trackingState by remember { mutableStateOf(TrackingState.SEARCHING) }
    var calibrationTime by remember { mutableStateOf(0L) }
    var trackingConfidence by remember { mutableStateOf(0f) }
    
    var isRecording by remember { mutableStateOf(false) }
    var lastSavedFile by remember { mutableStateOf<File?>(null) }
    val frameCount = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val bufferSize = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var recordingStartTime by remember { mutableStateOf(0L) }
    var currentDuration by remember { mutableStateOf(0L) }
    
    var showExportDialog by remember { mutableStateOf<File?>(null) }
    var showQualityScore by remember { mutableStateOf<PoseRecorder.SessionStats?>(null) }
    var isLowStorage by remember { mutableStateOf(false) }
    var isOverheating by remember { mutableStateOf(false) }
    var countdownTimer by remember { mutableStateOf(0) }
    var xGestureTime by remember { mutableStateOf(0L) }
    var triggerFlash by remember { mutableStateOf(false) }
    
    LaunchedEffect(triggerFlash) {
        if (triggerFlash) {
            kotlinx.coroutines.delay(1000)
            triggerFlash = false
        }
    }
    
    // Telemetry & Hardware Setup
    val toneGenerator = remember {
        try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    // Add this right under your ToneGenerator declaration
    DisposableEffect(toneGenerator) {
        onDispose {
            toneGenerator?.release()
        }
    }
    var latestGravity by remember { mutableStateOf(floatArrayOf(0f, 9.8f, 0f)) }
    var isFlashActive by remember { mutableStateOf(false) }
    var isGhostMode by remember { mutableStateOf(false) }
    var isFrontCamera by remember { mutableStateOf(false) }
    
    val activity = LocalContext.current as? android.app.Activity
    LaunchedEffect(isGhostMode) {
        activity?.let {
            val window = it.window
            val layoutParams = window.attributes
            if (isGhostMode) {
                layoutParams.screenBrightness = 0.01f
            } else {
                layoutParams.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            window.attributes = layoutParams
        }
    }
    
    LaunchedEffect(trackingState, isRecording) {
        if (trackingState == TrackingState.LOSS && isRecording) {
            while (true) {
                isFlashActive = true
                kotlinx.coroutines.delay(200)
                isFlashActive = false
                kotlinx.coroutines.delay(200)
            }
        } else {
            isFlashActive = false
        }
    }
    
    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(android.content.Context.SENSOR_SERVICE) as SensorManager
        val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type == Sensor.TYPE_GRAVITY) {
                    latestGravity = event.values.clone()
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorManager.registerListener(listener, gravitySensor, SensorManager.SENSOR_DELAY_UI)
        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }
    
    DisposableEffect(context) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
                    isOverheating = status >= PowerManager.THERMAL_STATUS_SEVERE
                }
                powerManager.addThermalStatusListener(androidx.core.content.ContextCompat.getMainExecutor(context), thermalListener)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener != null) {
                try {
                    powerManager.removeThermalStatusListener(thermalListener)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
    
    val haptic = LocalHapticFeedback.current
    val recorder = remember { PoseRecorder(context) }
    val faceRecorder = remember { 
        val fr = FaceRecorder(context)
        fr.audioAnalyzer = AudioVisemeAnalyzer()
        fr
    }

    DisposableEffect(faceRecorder) {
        onDispose {
            if (recorder.isRecording()) recorder.stopRecording()
            faceRecorder.close()
        }
    }

    fun startActiveRecording() {
        try { toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 500) } catch (e: Exception) {}
        lastSavedFile = null
        frameCount.intValue = 0
        bufferSize.intValue = 0
        if (trackingMode == TrackingMode.BODY) {
            recorder.setDeviceGravity(latestGravity)
            recorder.startRecording(imageWidth, imageHeight, isFrontCamera)
        } else {
            faceRecorder.startRecording(imageWidth, imageHeight, isFrontCamera)
        }
        isRecording = true
        trackingState = TrackingState.RECORDING
    }

    fun stopActiveRecording() {
        isRecording = false
        trackingState = TrackingState.SEARCHING
        lastSavedFile = if (trackingMode == TrackingMode.BODY) {
            recorder.stopRecording()
        } else {
            faceRecorder.stopRecording()
        }
        showQualityScore = if (trackingMode == TrackingMode.BODY) {
            recorder.lastSessionStats
        } else {
            null
        }
        currentDuration = 0L
    }

    androidx.activity.compose.BackHandler(enabled = isRecording) {
        stopActiveRecording()
        android.widget.Toast.makeText(
            context, 
            "Recording stopped safely before exiting.", 
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                if (isRecording) {
                    stopActiveRecording()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    LaunchedEffect(isRecording) {
        onRecordingStateChange(isRecording)
        if (isRecording) {
            recordingStartTime = System.currentTimeMillis()
            var tick = 0
            while (true) {
                currentDuration = System.currentTimeMillis() - recordingStartTime
                
                if (autoStopSetting > 0 && currentDuration >= autoStopSetting * 1000L) {
                    stopActiveRecording()
                }
                
                // --- NEW STOPWATCH LOGIC HERE ---
                if (!isProUnlocked) {
                    totalStreamTimeMs += 1000L // Add 1 second
                    
                    // Save to device storage every 5 seconds
                    if (tick % 5 == 0) { 
                        prefs.edit().putLong("stream_time_ms", totalStreamTimeMs).apply()
                    }
                    
                    // If they hit 15 minutes, stop the camera!
                    if (totalStreamTimeMs >= STREAM_LIMIT_MS && !isStreamLimitReached) {
                        isStreamLimitReached = true
                        stopActiveRecording()
                    }
                }
                // ---------------------------------
                
                if (tick % 2 == 0) { // Check roughly every 2 seconds
                    try {
                        val path = Environment.getDataDirectory()
                        val stat = StatFs(path.path)
                        val bytesAvailable = stat.blockSizeLong * stat.availableBlocksLong
                        isLowStorage = bytesAvailable < 500L * 1024L * 1024L
                    } catch (e: Exception) {
                        isLowStorage = false
                    }
                }
                tick++
                
                kotlinx.coroutines.delay(1000L)
            }
        } else {
            currentDuration = 0L
            isLowStorage = false
        }
    }
    
    LaunchedEffect(countdownTimer) {
        if (countdownTimer > 0) {
            try { toneGenerator?.startTone(ToneGenerator.TONE_CDMA_PIP, 150) } catch (e: Exception) {}
            kotlinx.coroutines.delay(1000)
            countdownTimer--
            if (countdownTimer == 0) {
                startActiveRecording()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (recorder.isRecording()) recorder.stopRecording()
            if (faceRecorder.isRecording()) faceRecorder.stopRecording()
        }
    }

    val bgDark = androidx.compose.material3.MaterialTheme.colorScheme.background
    val textLight = androidx.compose.material3.MaterialTheme.colorScheme.onBackground
    val accentBlue = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val recordRed = androidx.compose.material3.MaterialTheme.colorScheme.secondary
    val panelBg = GlassSurface
    val btnBg = Color.White.copy(alpha = 0.1f)

    Column(modifier = modifier.fillMaxSize().background(bgDark)) {
        // Top App Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "M I M I C",
                        color = androidx.compose.material3.MaterialTheme.colorScheme.primary, // Pure White
                        style = androidx.compose.material3.MaterialTheme.typography.labelLarge.copy(
                            letterSpacing = 8.sp 
                        )
                    )
                }
            }
            
            // Top Right Settings
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mode Toggle
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(panelBg)
                        .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(20.dp))
                        .clickable {
                            if (!isRecording) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                trackingMode = if (trackingMode == TrackingMode.BODY) TrackingMode.FACE else TrackingMode.BODY
                            }
                        }
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.animation.AnimatedContent(
                        targetState = trackingMode,
                        transitionSpec = {
                            if (targetState == TrackingMode.BODY) {
                                (androidx.compose.animation.slideInVertically { height -> height } + androidx.compose.animation.fadeIn()).with(androidx.compose.animation.slideOutVertically { height -> -height } + androidx.compose.animation.fadeOut())
                            } else {
                                (androidx.compose.animation.slideInVertically { height -> -height } + androidx.compose.animation.fadeIn()).with(androidx.compose.animation.slideOutVertically { height -> height } + androidx.compose.animation.fadeOut())
                            }
                        },
                        label = "mode_switch"
                    ) { mode ->
                        Text(
                            text = if (mode == TrackingMode.BODY) "BODY" else "FACE",
                            color = textLight,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                val gearRotation by androidx.compose.animation.core.animateFloatAsState(targetValue = if (showSettingsSheet) 180f else 0f, label = "gearRotation")
                androidx.compose.material3.IconButton(
                    onClick = { showSettingsSheet = true },
                    modifier = Modifier.size(40.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings, 
                        contentDescription = "Settings", 
                        tint = Color.White,
                        modifier = Modifier.size(20.dp).graphicsLayer(rotationZ = gearRotation)
                    )
                }
            }
        }

        val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition()
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0.2f,
            targetValue = 1.0f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(400, easing = androidx.compose.animation.core.FastOutLinearInEasing),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
            ),
            label = "pulseAlpha"
        )

        // Main Camera Viewport
        val trackerColor = when {
            triggerFlash -> Color.Green
            trackingState == TrackingState.CALIBRATING -> Color.Yellow.copy(alpha = pulseAlpha)
            trackingState == TrackingState.READY -> Color.Green
            else -> accentBlue
        }
        val trackerStroke = when {
            triggerFlash -> 20f
            trackingState == TrackingState.CALIBRATING -> 12f * pulseAlpha
            trackingState == TrackingState.SEARCHING || trackingState == TrackingState.LOSS -> 1.5f
            else -> 4f
        }
        
        val viewportBorderModifier = if (trackingState == TrackingState.RECORDING) {
            Modifier.border(width = 3.dp, color = PrimaryWhite, shape = RoundedCornerShape(24.dp))
        } else if (trackingState == TrackingState.CALIBRATING) {
            Modifier.border(width = (4f * pulseAlpha).dp, color = PrimaryWhite.copy(alpha=0.5f), shape = RoundedCornerShape(24.dp))
        } else {
            Modifier.border(width = 1.dp, color = GlassSurface, shape = RoundedCornerShape(24.dp))
        }
        
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
                .then(viewportBorderModifier)
                .clip(RoundedCornerShape(24.dp))
        ) {
            CameraPreviewAndAnalysis(
                    trackingMode = trackingMode,
                    isFlashActive = isFlashActive,
                    isGhostMode = isGhostMode,
                    isFrontCamera = isFrontCamera,
                isOverheating = isOverheating,
                onFaceDetected = { face, w, h ->
                    currentFaceMesh = face
                    imageWidth = w
                    imageHeight = h
                    
                    trackingConfidence = 0.99f // Face mode is always highly confident if detected
                    
                    if (isRecording) {
                        faceRecorder.recordFrame(face)
                        frameCount.intValue++
                        bufferSize.intValue = faceRecorder.getBufferLength()
                        trackingState = TrackingState.RECORDING
                    } else {
                        trackingState = TrackingState.READY
                    }
                },
                onPoseDetected = { pose, w, h ->
                    currentPose = pose
                    imageWidth = w
                    imageHeight = h
                    
                    val isFullBody = analyzeFullBodyVisibility(pose)
                    val isTPose = isFullBody && analyzeTPose(pose)
                    val confidence = getAverageConfidence(pose)
                    trackingConfidence = confidence
                    
                    if (isRecording) {
                        val lWrist = pose.getPoseLandmark(PoseLandmark.LEFT_WRIST)
                        val rWrist = pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST)
                        val lShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
                        val rShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
                        if (lWrist != null && rWrist != null && lShoulder != null && rShoulder != null &&
                            lWrist.inFrameLikelihood > 0.5f && rWrist.inFrameLikelihood > 0.5f) {
                            if (xGestureEnabled &&
                                lWrist.x < rWrist.x &&
                                Math.abs(lWrist.y - rWrist.y) < 100f &&
                                lWrist.y < lShoulder.y + 100f) {
                                if (xGestureTime == 0L) xGestureTime = System.currentTimeMillis()
                                else if (System.currentTimeMillis() - xGestureTime > 2000) {
                                    try { toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 500) } catch (e: Exception) {}
                                    stopActiveRecording()
                                    xGestureTime = 0L
                                }
                            } else {
                                xGestureTime = 0L
                            }
                        } else {
                            xGestureTime = 0L
                        }

                        if (confidence > 0.4f) { // Only record if we have decent confidence to avoid snapping
                            recorder.recordFrame(pose)
                            frameCount.intValue++
                            bufferSize.intValue = recorder.getBufferLength()
                            trackingState = TrackingState.RECORDING
                        } else {
                            if (trackingState != TrackingState.LOSS) {
                                try { toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 300) } catch (e: Exception) {}
                            }
                            trackingState = TrackingState.LOSS
                        }
                    } else {
                        when (trackingState) {
                            TrackingState.SEARCHING, TrackingState.LOSS -> {
                                if (isFullBody) trackingState = TrackingState.READY
                            }
                            TrackingState.READY -> {
                                if (!isFullBody) {
                                    trackingState = TrackingState.SEARCHING
                                } else if (isTPose) {
                                    trackingState = TrackingState.CALIBRATING
                                    calibrationTime = System.currentTimeMillis()
                                    try { toneGenerator?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 200) } catch (e: Exception) {}
                                }
                            }
                            TrackingState.CALIBRATING -> {
                                if (!isTPose) {
                                    trackingState = TrackingState.READY
                                } else {
                                    recorder.accumulateCalibration(pose)
                                    if (System.currentTimeMillis() - calibrationTime > 1500) {
                                        try { toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 500) } catch (e: Exception) {}
                                        recorder.finalizeCalibration()
                                        trackingState = TrackingState.READY
                                        triggerFlash = true
                                    }
                                }
                            }
                            TrackingState.RECORDING -> {
                                // Handled externally when isRecording is false
                            }
                        }
                    }
                }
            )

            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = trackerStroke
                
                if (trackingMode == TrackingMode.FACE) {
                    val ovalWidth = size.width * 0.6f
                    val ovalHeight = size.height * 0.5f
                    val ovalLeft = (size.width - ovalWidth) / 2f
                    val ovalTop = (size.height - ovalHeight) / 2f
                    drawOval(
                        color = trackerColor,
                        topLeft = Offset(ovalLeft, ovalTop),
                        size = androidx.compose.ui.geometry.Size(ovalWidth, ovalHeight),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
                    )
                } else {
                    val bracketLength = 32.dp.toPx()
                    // Top-Left
                    drawLine(trackerColor, start = Offset(0f, 0f), end = Offset(bracketLength, 0f), strokeWidth = stroke)
                    drawLine(trackerColor, start = Offset(0f, 0f), end = Offset(0f, bracketLength), strokeWidth = stroke)
                    // Top-Right
                    drawLine(trackerColor, start = Offset(this.size.width, 0f), end = Offset(this.size.width - bracketLength, 0f), strokeWidth = stroke)
                    drawLine(trackerColor, start = Offset(this.size.width, 0f), end = Offset(this.size.width, bracketLength), strokeWidth = stroke)
                    // Bottom-Left
                    drawLine(trackerColor, start = Offset(0f, this.size.height), end = Offset(bracketLength, this.size.height), strokeWidth = stroke)
                    drawLine(trackerColor, start = Offset(0f, this.size.height), end = Offset(0f, this.size.height - bracketLength), strokeWidth = stroke)
                    // Bottom-Right
                    drawLine(trackerColor, start = Offset(this.size.width, this.size.height), end = Offset(this.size.width - bracketLength, this.size.height), strokeWidth = stroke)
                    drawLine(trackerColor, start = Offset(this.size.width, this.size.height), end = Offset(this.size.width, this.size.height - bracketLength), strokeWidth = stroke)
                }

            }

            if (isGhostMode) {
                Text(
                    "PRIVACY MODE ACTIVE\nVideo Feed Disabled",
                    color = Color.White.copy(alpha=0.3f),
                    textAlign = TextAlign.Center,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            if (trackingMode == TrackingMode.BODY) {
                PoseOverlay(
                    poseProvider = { currentPose },
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    isFrontCamera = isFrontCamera,
                    modifier = Modifier.alpha(if (isGhostMode) 0.0f else 1f)
                )
            } else {
                FaceOverlay(
                    faceProvider = { currentFaceMesh },
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    isFrontCamera = true,
                    modifier = Modifier.alpha(if (isGhostMode) 0.0f else 1f)
                )
            }

            if (showQualityScore != null) {
                val stats = showQualityScore!!
                val score = (stats.avgConfidence * 100).toInt()
                val scoreColor = if (score > 80) accentBlue else if (score > 50) Color(0xFFFFB74D) else recordRed
                AlertDialog(
                    onDismissRequest = { showQualityScore = null },
                    containerColor = panelBg,
                    title = { Text("Take Quality Report", color = textLight) },
                    text = {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Overall Quality:", color = textLight.copy(alpha=0.7f))
                                Spacer(Modifier.weight(1f))
                                Text("$score%", color = scoreColor, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            }
                            Spacer(Modifier.height(16.dp))
                            Text("Frames Captured: ${stats.totalFrames}", color = textLight, fontSize = 14.sp)
                            Text("Dropped/Low Conf frames: ${stats.lowConfFrames}", color = textLight, fontSize = 14.sp)
                            Spacer(Modifier.height(8.dp))
                            if (stats.totalFrames > 0 && stats.lowConfFrames > stats.totalFrames * 0.1f) {
                                Text("Warning: Many frames lacked strong tracking. Consider reshooting with full body view and better lighting.", color = Color(0xFFFFB74D), fontSize = 12.sp)
                            } else {
                                Text("Good tracking! Ready to export.", color = accentBlue, fontSize = 12.sp)
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { 
                            showQualityScore = null
                            if (lastSavedFile != null) {
                                Toast.makeText(context, "Saved to ${lastSavedFile!!.name}", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("Confirm", color = accentBlue) }
                    }
                )
            }

            if (!isRecording && trackingState != TrackingState.READY) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = when (trackingState) {
                                TrackingState.SEARCHING -> if (trackingMode == TrackingMode.BODY) "Step Back" else "Looking for face..."
                                else -> ""
                            },
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = when (trackingState) {
                                TrackingState.SEARCHING -> if (trackingMode == TrackingMode.FACE) 
                                    "Position your face in the center of the frame." 
                                else 
                                    "Step back to capture full body."
                                else -> ""
                            },
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 14.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else if (trackingState == TrackingState.LOSS && isRecording) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (trackingMode == TrackingMode.BODY) "TRACKING LOST\nPlease step back into frame" else "TRACKING LOST\nPlease bring face back into frame",
                        color = Color.Red,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // HUD Overlays
            Box(modifier = Modifier.fillMaxSize()) {
                // Top Left HUD
                Column(modifier = Modifier.padding(16.dp).align(Alignment.TopStart)) {
                    val confidenceColor = when {
                        trackingConfidence > 0.75f -> Color(0xFF4ADE80)
                        trackingConfidence > 0.4f -> Color(0xFFFBBF24)
                        else -> Color(0xFFF87171)
                    }
                    Row(
                        modifier = Modifier
                            .background(panelBg, RoundedCornerShape(8.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "CONF: ${(trackingConfidence * 100).toInt()}%",
                            color = confidenceColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    androidx.compose.animation.AnimatedVisibility(visible = isRecording) {
                        val seconds = (currentDuration / 1000) % 60
                        val minutes = (currentDuration / 1000) / 60
                        Row(
                            modifier = Modifier
                                .background(recordRed.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.size(8.dp).background(Color.White, CircleShape))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(String.format("%02d:%02d", minutes, seconds), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    
                    // Warnings
                    androidx.compose.animation.AnimatedVisibility(
                        visible = isLowStorage,
                        enter = androidx.compose.animation.slideInVertically(initialOffsetY = { -it }) + androidx.compose.animation.fadeIn(),
                        exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { -it }) + androidx.compose.animation.fadeOut()
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .background(Color.Red.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = "Warning", tint = Color.White, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("STORAGE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    
                    androidx.compose.animation.AnimatedVisibility(
                        visible = isOverheating,
                        enter = androidx.compose.animation.slideInVertically(initialOffsetY = { -it }) + androidx.compose.animation.fadeIn(),
                        exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { -it }) + androidx.compose.animation.fadeOut()
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .background(Color(0xFFFF9800).copy(alpha = 0.9f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = "Overheating", tint = Color.Black, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("HOT", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (countdownTimer > 0) {
                    Text(
                        "$countdownTimer",
                        color = accentBlue,
                        fontSize = 120.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                // Bottom Left HUD
                RecordingHudOverlay(
                    frameCountState = frameCount,
                    bufferSizeState = bufferSize,
                    panelBg = panelBg,
                    modifier = Modifier.align(Alignment.BottomStart)
                )
            } // Close HUD Overlays Box
        }

        // Bottom Control Panel
        Column(modifier = Modifier.padding(24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Secondary Action Left (Library Shortcut)
                val leftInteractionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val leftIsPressed by leftInteractionSource.collectIsPressedAsState()
                val leftScale by animateFloatAsState(targetValue = if (leftIsPressed) 0.85f else 1f, animationSpec = tween(durationMillis = 150))

                IconButton(
                    onClick = { onNavigate(AppScreen.Library) },
                    modifier = Modifier.size(56.dp).graphicsLayer { scaleX = leftScale; scaleY = leftScale }.background(btnBg, RoundedCornerShape(16.dp)),
                    interactionSource = leftInteractionSource
                ) {
                    Icon(imageVector = Icons.Default.Folder, contentDescription = "Library", tint = textLight)
                }

                // Main Record Button
                if (isStreamLimitReached && !isProUnlocked) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        // Gold Ad Button
                        Box(
                            modifier = Modifier.size(72.dp).background(Color(0xFFFFD700), CircleShape)
                                .clickable { 
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (activity != null) {
                                        loadAndShowStreamingAd(context, activity) {
                                            // ON REWARD: Reset the timer to unlock the next 15 minutes!
                                            totalStreamTimeMs = 0L
                                            prefs.edit().putLong("stream_time_ms", 0L).apply()
                                            isStreamLimitReached = false
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Watch Ad", tint = Color.Black)
                        }
                        
                        Spacer(Modifier.height(8.dp))
                        
                        // "Unlock Pro" Stub Button
                        androidx.compose.material3.TextButton(
                            onClick = {
                                // TODO: Launch Google Play Billing Flow here
                                isProUnlocked = true
                                prefs.edit().putBoolean("is_pro", true).apply()
                            }
                        ) {
                            Text("Unlock PRO", color = Color(0xFFFFD700), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                } else {
                    val buttonShape by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = if (isRecording) 16f else 50f, 
                        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 400f),
                        label = "buttonShape"
                    )
                    val buttonSize by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = if (isRecording) 36f else 72f,
                        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 400f),
                        label = "buttonSize"
                    )
                    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    val isPressed by interactionSource.collectIsPressedAsState()
                    val scale by animateFloatAsState(targetValue = if (isPressed) 0.85f else 1f, animationSpec = tween(durationMillis = 150))
                    
                    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse")
                    val pulseProgress by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                            animation = tween(1000, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                        ),
                        label = "pulseProgress"
                    )
                    
                    val currentPulseScale = if (isRecording) 1f + (0.05f * pulseProgress) else 1f
                    val currentPulseAlpha = if (isRecording) 1f - (0.2f * pulseProgress) else 1f
                    
                    val glowRadius by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = if (isRecording) 24f else 0f,
                        animationSpec = androidx.compose.animation.core.tween(500),
                        label = "glow"
                    )
                    
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .graphicsLayer {
                                scaleX = scale * currentPulseScale
                                scaleY = scale * currentPulseScale
                                alpha = currentPulseAlpha
                            }
                            .border(4.dp, if (isRecording) recordRed else btnBg, CircleShape)
                            .padding(4.dp)
                            .clickable(interactionSource = interactionSource, indication = null) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (isRecording) {
                                    stopActiveRecording()
                                } else if (countdownTimer > 0) {
                                    countdownTimer = 0 // Cancel countdown
                                } else {
                                    if (countdownSetting == 0) {
                                        startActiveRecording()
                                    } else {
                                        countdownTimer = countdownSetting // Start countdown
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(modifier = Modifier
                            .size(buttonSize.dp)
                            .drawBehind {
                                if (glowRadius > 0f) {
                                    val w = this.size.width
                                    val h = this.size.height
                                    drawContext.canvas.nativeCanvas.apply {
                                        drawRoundRect(
                                            0f, 0f, w, h,
                                            w * (buttonShape / 100f), w * (buttonShape / 100f),
                                            android.graphics.Paint().apply {
                                                color = android.graphics.Color.TRANSPARENT
                                                setShadowLayer(glowRadius, 0f, 0f, android.graphics.Color.parseColor("#FF2A55"))
                                            }
                                        )
                                    }
                                }
                            }
                            .background(recordRed, androidx.compose.foundation.shape.RoundedCornerShape(percent = buttonShape.toInt()))
                        )
                    }
                }

                // Secondary Action Right (Flip Camera)
                val rightInteractionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val rightIsPressed by rightInteractionSource.collectIsPressedAsState()
                val rightScale by animateFloatAsState(targetValue = if (rightIsPressed) 0.85f else 1f, animationSpec = tween(durationMillis = 150))

                IconButton(
                    onClick = { 
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        isFrontCamera = !isFrontCamera 
                    },
                    modifier = Modifier.size(56.dp).graphicsLayer { scaleX = rightScale; scaleY = rightScale }.background(btnBg, RoundedCornerShape(16.dp)),
                    interactionSource = rightInteractionSource
                ) {
                    Icon(imageVector = Icons.Default.FlipCameraAndroid, contentDescription = "Flip Camera", tint = textLight)
                }
            }
        }
        
        if (showExportDialog != null) {
            val fileToShare = showExportDialog!!
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showExportDialog = null },
                title = { Text("Export Format", color = textLight) },
                text = { Text("Choose the format you want to export:", color = textLight.copy(alpha = 0.8f)) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        shareMocapFile(context, fileToShare, "json")
                        showExportDialog = null
                    }) {
                        Text("JSON", color = accentBlue)
                    }
                },
                dismissButton = {
                    if (trackingMode == TrackingMode.BODY) {
                        androidx.compose.material3.TextButton(onClick = {
                            shareMocapFile(context, fileToShare, "bvh")
                            showExportDialog = null
                        }) {
                            Text("BVH", color = accentBlue)
                        }
                    } else {
                        androidx.compose.material3.TextButton(onClick = { showExportDialog = null }) {
                            Text("Cancel", color = textLight)
                        }
                    }
                },
                containerColor = panelBg
            )
        }
    }
    
    // Bottom Sheet for Settings
    if (showSettingsSheet) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            sheetState = sheetState,
            containerColor = bgDark,
            contentColor = textLight
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
            ) {
                Text(
                    "Settings",
                    color = accentBlue,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                // Ghost Mode Toggle
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Ghost Mode", color = textLight, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("Disables video rendering for privacy.", color = textLight.copy(alpha = 0.6f), fontSize = 12.sp)
                    }
                    CustomSwitch(
                        checked = isGhostMode,
                        onCheckedChange = { isGhostMode = it }
                    )
                }
                
                // Cross-Arm Stop Toggle
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Cross-Arm Stop", color = textLight, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("Cross your arms to start/stop recording.", color = textLight.copy(alpha = 0.6f), fontSize = 12.sp)
                    }
                    CustomSwitch(
                        checked = xGestureEnabled,
                        onCheckedChange = { 
                            xGestureEnabled = it
                            prefs.edit().putBoolean("x_gesture_enabled", it).apply()
                        }
                    )
                }
                
                Spacer(Modifier.height(16.dp))
                
                // Countdown Setting
                Text("Start Countdown: ${if (countdownSetting == 0) "Off" else "${countdownSetting}s"}", color = textLight, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                androidx.compose.material3.Slider(
                    value = countdownSetting.toFloat(),
                    onValueChange = { 
                        countdownSetting = it.toInt()
                        prefs.edit().putInt("countdown_setting", countdownSetting).apply()
                    },
                    valueRange = 0f..10f,
                    steps = 9
                )
                
                Spacer(Modifier.height(8.dp))
                
                // Auto-Stop Setting
                Text("Auto-Stop: ${if (autoStopSetting == 0) "Off" else "${autoStopSetting}s"}", color = textLight, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                androidx.compose.material3.Slider(
                    value = autoStopSetting.toFloat(),
                    onValueChange = { 
                        autoStopSetting = it.valToAutoStop()
                        prefs.edit().putInt("auto_stop_setting", autoStopSetting).apply()
                    },
                    valueRange = 0f..2f,
                    steps = 1
                )
                
                Spacer(Modifier.height(16.dp))
                
                // Target IP Address
                Text("Target IP Address", color = textLight, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                androidx.compose.material3.OutlinedTextField(
                    value = ipAddress,
                    onValueChange = { 
                        ipAddress = it 
                        val isValidIp = it.matches(Regex("^([0-9]{1,3}\\.){3}[0-9]{1,3}$"))
                        if (isValidIp) {
                            prefs.edit().putString("osc_ip", it).apply()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = textLight,
                        unfocusedTextColor = textLight,
                        focusedBorderColor = accentBlue,
                        unfocusedBorderColor = textLight.copy(alpha = 0.3f)
                    )
                )

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

// Helper extension function to map slider to 0, 30, 60
private fun Float.valToAutoStop(): Int {
    return when(this.toInt()) {
        0 -> 0
        1 -> 30
        2 -> 60
        else -> 0
    }
}

@Composable
fun RecordingHudOverlay(
    frameCountState: androidx.compose.runtime.State<Int>,
    bufferSizeState: androidx.compose.runtime.State<Int>,
    panelBg: Color,
    modifier: Modifier = Modifier
) {
    val memoryNum = remember { mutableStateOf(140) }
    LaunchedEffect(Unit) {
        while(true) {
            memoryNum.value = 140 + (Math.random() * 20).toInt()
            kotlinx.coroutines.delay(2000)
        }
    }

    val textShadow = androidx.compose.ui.graphics.Shadow(
        color = Color.Black,
        offset = androidx.compose.ui.geometry.Offset(2f, 2f),
        blurRadius = 4f
    )

    Column(
        modifier = modifier
            .padding(16.dp)
            .background(panelBg, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .border(1.dp, Color.White.copy(alpha = 0.05f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = "FRAME: ${String.format("%06d", frameCountState.value)}",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            style = androidx.compose.ui.text.TextStyle(shadow = textShadow)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "BUFFER: ${String.format("%06d", bufferSizeState.value)}",
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 10.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            style = androidx.compose.ui.text.TextStyle(shadow = textShadow)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "MEM: ${memoryNum.value}MB | TEMP: 38°C",
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 10.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            style = androidx.compose.ui.text.TextStyle(shadow = textShadow)
        )
    }
}

@Composable
fun CustomSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val bgColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (checked) Color.Green else Color.DarkGray,
        animationSpec = androidx.compose.animation.core.tween(300),
        label = "bgColor"
    )
    val thumbOffset by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (checked) 24f else 4f,
        animationSpec = androidx.compose.animation.core.tween(300),
        label = "thumbOffset"
    )
    
    Box(
        modifier = Modifier
            .width(52.dp)
            .height(28.dp)
            .clip(CircleShape)
            .background(bgColor)
            .clickable { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}
