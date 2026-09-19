package pt.drprint3d.kidcam

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.launch
import pt.drprint3d.kidcam.ui.theme.KidCamTheme
import kotlin.math.max

class MainActivity : ComponentActivity() {

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

fun generateQrCodeBitmap(content: String, width: Int, height: Int): Bitmap {
    val bitMatrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, width, height)
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
    for (x in 0 until width) {
        for (y in 0 until height) {
            bmp.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    return bmp
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraView(isPipMode: Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraPreferences = remember { CameraPreferences(context) }
    val coroutineScope = rememberCoroutineScope()
    
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    
    // Zoom and pan state
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    // Preferences state
    val mirrorXState = cameraPreferences.mirrorX.collectAsState(initial = false)
    val mirrorYState = cameraPreferences.mirrorY.collectAsState(initial = false)
    val lastUsedIndexState = cameraPreferences.lastUsedCameraId.collectAsState(initial = null)

    // Drawer / Menu state
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var selectedTab by remember { mutableStateOf("settings") } // "settings" or "about"

    // Initialize CameraProvider
    LaunchedEffect(Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
        }, ContextCompat.getMainExecutor(context))
    }

    val provider = cameraProvider ?: return
    val cameraSelectors = remember(provider) {
        provider.availableCameraInfos.map { it.cameraSelector }
    }

    var currentSelectorIndex by remember(cameraSelectors, lastUsedIndexState.value) {
        mutableStateOf(
            lastUsedIndexState.value?.toIntOrNull()?.let { 
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
    val qrCodeBitmap = remember { generateQrCodeBitmap("https://linktr.ee/drprint3d", 300, 300) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(320.dp)
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    FilterChip(
                        selected = selectedTab == "settings",
                        onClick = { selectedTab = "settings" },
                        label = { Text("Settings") }
                    )
                    FilterChip(
                        selected = selectedTab == "about",
                        onClick = { selectedTab = "about" },
                        label = { Text("About") }
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                if (selectedTab == "settings") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Text(
                            text = "Camera Settings",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        
                        // Mirror X
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Mirror Horizontally (X)")
                            Switch(
                                checked = mirrorXState.value,
                                onCheckedChange = { checked ->
                                    coroutineScope.launch {
                                        cameraPreferences.saveMirrorX(checked)
                                    }
                                }
                            )
                        }

                        // Mirror Y
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Mirror Vertically (Y)")
                            Switch(
                                checked = mirrorYState.value,
                                onCheckedChange = { checked ->
                                    coroutineScope.launch {
                                        cameraPreferences.saveMirrorY(checked)
                                    }
                                }
                            )
                        }

                        if (cameraSelectors.size > 1) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Select Camera",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            cameraSelectors.forEachIndexed { index, _ ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            currentSelectorIndex = index
                                            coroutineScope.launch {
                                                cameraPreferences.saveLastUsedCameraId(index.toString())
                                            }
                                            scale = 1f
                                            offsetX = 0f
                                            offsetY = 0f
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = currentSelectorIndex == index,
                                        onClick = {
                                            currentSelectorIndex = index
                                            coroutineScope.launch {
                                                cameraPreferences.saveLastUsedCameraId(index.toString())
                                            }
                                            scale = 1f
                                            offsetX = 0f
                                            offsetY = 0f
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Camera ${index + 1}")
                                }
                            }
                        }
                    }
                } else {
                    // About Tab
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Made for my kids with love. Please support",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(bottom = 16.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        
                        Image(
                            bitmap = qrCodeBitmap.asImageBitmap(),
                            contentDescription = "QR Code to linktr.ee/drprint3d",
                            modifier = Modifier
                                .size(200.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://linktr.ee/drprint3d"))
                                    context.startActivity(intent)
                                }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "https://linktr.ee/drprint3d",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://linktr.ee/drprint3d"))
                                    context.startActivity(intent)
                                }
                                .padding(8.dp)
                        )
                    }
                }
            }
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = max(1f, scale * zoom)
                        if (scale == 1f) {
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            offsetX += pan.x
                            offsetY += pan.y
                        }
                        try {
                            cameraControl?.setZoomRatio(scale)
                        } catch (_: Exception) {}
                    }
                }
        ) {
            val effectiveScaleX = scale * if (mirrorXState.value) -1f else 1f
            val effectiveScaleY = scale * if (mirrorYState.value) -1f else 1f

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
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = effectiveScaleX,
                        scaleY = effectiveScaleY,
                        translationX = offsetX,
                        translationY = offsetY
                    ),
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
                    } catch (exc: Exception) {
                        exc.printStackTrace()
                    }
                }
            )

            // Hamburger menu button (top-left) & Camera switch button (bottom-end)
            if (!isPipMode) {
                IconButton(
                    onClick = {
                        coroutineScope.launch {
                            drawerState.open()
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 24.dp, top = 48.dp)
                        .size(48.dp)
                        .background(Color.Black.copy(alpha = 0.5f), shape = MaterialTheme.shapes.extraLarge)
                ) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "Menu",
                        tint = Color.White
                    )
                }

                if (cameraSelectors.size > 1) {
                    IconButton(
                        onClick = {
                            currentSelectorIndex = (currentSelectorIndex + 1) % cameraSelectors.size
                            coroutineScope.launch {
                                cameraPreferences.saveLastUsedCameraId(currentSelectorIndex.toString())
                            }
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
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
    }
}
