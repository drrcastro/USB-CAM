package pt.drprint3d.kidcam

import android.Manifest
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import pt.drprint3d.kidcam.ui.theme.KidCamTheme
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {

    // PiP state to hide UI elements like buttons when minimized
    private val isPipMode = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KidCamTheme {
                MainScreen(isPipMode = isPipMode.value)
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isPipMode.value = isInPictureInPictureMode
    }
}

@Composable
fun MainScreen(isPipMode: Boolean) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (hasCameraPermission) {
        CameraView(isPipMode = isPipMode)
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Camera permission is required.",
                color = Color.White
            )
        }
    }
}

@Composable
fun CameraView(isPipMode: Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraPreferences = remember { CameraPreferences(context) }
    val coroutineScope = rememberCoroutineScope()
    
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var availableCameraIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentCameraId by remember { mutableStateOf<String?>(null) }
    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    
    // Zoom state
    var zoomRatio by remember { mutableStateOf(1f) }
    var maxZoomRatio by remember { mutableStateOf(1f) }
    var minZoomRatio by remember { mutableStateOf(1f) }

    // Initialize CameraProvider
    LaunchedEffect(Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider
            
            // Discover all available cameras
            val cameraInfos = provider.availableCameraInfos
            val ids = cameraInfos.mapNotNull { it.cameraSelector.toString() } 
            
            // Actually cameraSelector itself isn't an ID string, let's use lensFacing or extensions,
            // but for generic USB cameras, we just build selectors by looping or keep track of indices.
            // Android CameraX doesn't expose raw camera IDs easily in stable without Camera2Interop.
            // Let's fallback to checking front/back and then any other available if we must,
            // but for USB cameras, they might register as LENS_FACING_EXTERNAL.
        }, ContextCompat.getMainExecutor(context))
    }

    // Wait for provider
    val provider = cameraProvider ?: return

    // Since we need to dynamically switch cameras including external ones, 
    // let's use a simple selector list.
    val cameraSelectors = remember(provider) {
        provider.availableCameraInfos.map { it.cameraSelector }
    }
    
    // Read the last used camera index
    val lastUsedIndexFlow = cameraPreferences.lastUsedCameraId.collectAsState(initial = null)
    
    var currentSelectorIndex by remember(cameraSelectors, lastUsedIndexFlow.value) {
        mutableStateOf(
            lastUsedIndexFlow.value?.toIntOrNull()?.let { 
                if (it in cameraSelectors.indices) it else 0 
            } ?: 0
        )
    }

    if (cameraSelectors.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text("No cameras found", color = Color.White)
        }
        return
    }

    val currentSelector = cameraSelectors[currentSelectorIndex]

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(cameraControl) {
                detectTransformGestures { _, _, zoom, _ ->
                    if (cameraControl != null) {
                        zoomRatio = min(max(zoomRatio * zoom, minZoomRatio), maxZoomRatio)
                        cameraControl?.setZoomRatio(zoomRatio)
                    }
                }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    this.scaleType = PreviewView.ScaleType.FILL_CENTER
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { previewView ->
                try {
                    provider.unbindAll()

                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                    val camera = provider.bindToLifecycle(
                        lifecycleOwner,
                        currentSelector,
                        preview
                    )

                    cameraControl = camera.cameraControl
                    val cameraInfo = camera.cameraInfo
                    val zoomState = cameraInfo.zoomState.value
                    if (zoomState != null) {
                        maxZoomRatio = zoomState.maxZoomRatio
                        minZoomRatio = zoomState.minZoomRatio
                        zoomRatio = zoomState.zoomRatio
                    }
                } catch (exc: Exception) {
                    exc.printStackTrace()
                }
            }
        )

        // Overlay UI
        if (!isPipMode && cameraSelectors.size > 1) {
            IconButton(
                onClick = {
                    currentSelectorIndex = (currentSelectorIndex + 1) % cameraSelectors.size
                    coroutineScope.launch {
                        cameraPreferences.saveLastUsedCameraId(currentSelectorIndex.toString())
                    }
                    // reset zoom on switch
                    zoomRatio = 1f
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(32.dp)
                    .size(64.dp)
                    .background(Color.Black.copy(alpha = 0.5f), shape = MaterialTheme.shapes.extraLarge)
            ) {
                Icon(
                    imageVector = Icons.Default.Cameraswitch,
                    contentDescription = "Switch Camera",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}
