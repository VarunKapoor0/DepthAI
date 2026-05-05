package com.varun.depthai

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Camera
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.varun.depthai.arcore.BackgroundRenderer
import com.varun.depthai.arcore.DepthTextureHandler
import com.varun.depthai.gemini.FrameConverter
import com.varun.depthai.gemini.GeminiClient
import com.varun.depthai.gemini.GeminiResponseParser
import com.varun.depthai.helpers.TapHelper
import com.varun.depthai.model.AnchoredComponent
import com.varun.depthai.model.ObjectAnalysis
import com.varun.depthai.model.ObjectComponent
import com.varun.depthai.ui.theme.DepthAITheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : ComponentActivity(), GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var composeOverlay: ComposeView
    private lateinit var tapHelper: TapHelper

    private var session: Session? = null
    private var installRequested = false
    private var isDepthSupported = false
    private var depthShadersCreated = false
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private val backgroundRenderer = BackgroundRenderer()
    private val depthTextureHandler = DepthTextureHandler()

    @Volatile private var pendingScan = false
    @Volatile private var isAnalyzing = false
    @Volatile private var lockStatus = false

    // Components waiting to be anchored on the GL thread
    // Set from main thread after Gemini response, consumed by GL thread
    @Volatile private var pendingComponents: List<ObjectComponent>? = null

    // Active anchored components — written on GL thread, read on main thread for rendering
    private val anchoredComponents = mutableListOf<AnchoredComponent>()

    // Screen positions for Compose labels — updated every frame on GL thread
    // List of (screenX, screenY, componentName) triples
    private var labelPositions by mutableStateOf<List<Triple<Float, Float, String>>>(emptyList())

    private var currentAnalysis by mutableStateOf<ObjectAnalysis?>(null)
    private var showDepthMap by mutableStateOf(false)
    private var statusMessage by mutableStateOf("Initializing...")
    private var depthButtonText by mutableStateOf("Show Depth")
    private var isTracking by mutableStateOf(false)

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.surfaceview)
        composeOverlay = findViewById(R.id.compose_overlay)

        tapHelper = TapHelper(this)
        surfaceView.setOnTouchListener(tapHelper)

        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surfaceView.setRenderer(this)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        surfaceView.setWillNotDraw(false)

        composeOverlay.setContent {
            DepthAITheme {
                Box(modifier = Modifier.fillMaxSize()) {

                    // Component labels — rendered at projected anchor screen positions
                    labelPositions.forEach { (x, y, name) ->
                        Card(
                            modifier = Modifier
                                .offset(
                                    x = (x - 60).dp,
                                    y = (y - 20).dp
                                ),
                            colors = CardDefaults.cardColors(
                                containerColor = Color.Black.copy(alpha = 0.7f)
                            )
                        ) {
                            Text(
                                text = name,
                                color = Color.White,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    // Status message
                    Text(
                        text = statusMessage,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 16.dp)
                    )

                    // Buttons
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Button(onClick = {
                            if (isDepthSupported) {
                                showDepthMap = !showDepthMap
                                depthButtonText = if (showDepthMap) "Hide Depth" else "Show Depth"
                            }
                        }) {
                            Text(depthButtonText)
                        }

                        Button(
                            onClick = {
                                if (!isAnalyzing) {
                                    lockStatus = false
                                    currentAnalysis = null
                                    labelPositions = emptyList()
                                    clearAnchors()
                                    pendingScan = true
                                    Log.d(TAG, "Scan button pressed")
                                }
                            },
                            enabled = !isAnalyzing
                        ) {
                            Text(if (isAnalyzing) "Analyzing..." else "Scan")
                        }
                    }
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()

        if (session == null) {
            var exception: Exception? = null
            var message: String? = null
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> {}
                }

                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                    return
                }

                session = Session(this)
                val config = session!!.config
                isDepthSupported = session!!.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                config.depthMode = if (isDepthSupported) Config.DepthMode.AUTOMATIC
                else Config.DepthMode.DISABLED
                session!!.configure(config)

            } catch (e: Exception) {
                message = "Failed to create AR session: ${e.message}"
                exception = e
            }

            if (message != null) {
                Log.e(TAG, "Exception creating session", exception)
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                return
            }
        }

        try {
            session!!.resume()
        } catch (e: CameraNotAvailableException) {
            Toast.makeText(this, "Camera not available. Try restarting.", Toast.LENGTH_LONG).show()
            session = null
            return
        }

        surfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        session?.let {
            surfaceView.onPause()
            it.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        clearAnchors()
        session?.close()
        session = null
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        try {
            depthTextureHandler.createOnGLThread()
            backgroundRenderer.createOnGlThread(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize renderers", e)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        surfaceWidth = width
        surfaceHeight = height
        session?.setDisplayGeometry(windowManager.defaultDisplay.rotation, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        if (surfaceWidth == 0 || surfaceHeight == 0) return
        val currentSession = session ?: return

        try {
            currentSession.setCameraTextureName(backgroundRenderer.textureId)
            currentSession.setDisplayGeometry(
                windowManager.defaultDisplay.rotation,
                surfaceWidth,
                surfaceHeight
            )

            val frame: Frame = currentSession.update()

            if (!depthShadersCreated && isDepthSupported) {
                backgroundRenderer.createDepthShaders(this, depthTextureHandler.depthTextureId)
                depthShadersCreated = true
            }

            if (isDepthSupported) {
                depthTextureHandler.update(frame)
            }

            val camera: Camera = frame.camera
            val tracking = camera.trackingState == TrackingState.TRACKING

            // Handle scan request
            if (pendingScan && !isAnalyzing) {
                pendingScan = false
                if (tracking) {
                    captureAndAnalyze(frame)
                } else {
                    runOnUiThread { statusMessage = "Not tracking yet — try again" }
                }
            }

            // Process pending components — create anchors via hit test on GL thread
            val components = pendingComponents
            if (components != null && tracking) {
                pendingComponents = null
                createAnchorsForComponents(frame, components)
            }

            backgroundRenderer.draw(frame)

            if (showDepthMap && isDepthSupported && depthShadersCreated) {
                backgroundRenderer.drawDepth(frame)
            }

            // Project anchors to screen space every frame
            if (anchoredComponents.isNotEmpty() && tracking) {
                updateLabelPositions(frame, camera)
            }

            if (!lockStatus) {
                runOnUiThread {
                    isTracking = tracking
                    if (!isAnalyzing) {
                        statusMessage = when {
                            !isDepthSupported -> "Depth not supported on this device"
                            !tracking -> "Tracking lost — move slowly"
                            else -> "Point at object and tap Scan"
                        }
                    }
                }
            }

        } catch (t: Throwable) {
            Log.e(TAG, "Exception on the OpenGL thread", t)
        }
    }

    /**
     * For each component, run a hit test at its UV coordinate.
     * If the hit test succeeds, create an ARCore anchor at the 3D position.
     * If it fails (no surface detected), fall back to placing anchor at screen center.
     * Must be called on the GL thread.
     */
    private fun createAnchorsForComponents(frame: Frame, components: List<ObjectComponent>) {
        val newAnchored = mutableListOf<AnchoredComponent>()

        for (component in components) {
            val (u, v) = component.anchorUv

            // Convert UV [0,1] to screen pixel coordinates
            val screenX = u * surfaceWidth
            val screenY = v * surfaceHeight

            // Run hit test at screen position against depth mesh
            val hits = frame.hitTest(screenX, screenY)
            val hit = hits.firstOrNull()

            val anchor: Anchor? = if (hit != null) {
                Log.d(TAG, "Hit test succeeded for ${component.id} at UV($u, $v)")
                hit.createAnchor()
            } else {
                // Fallback — hit test at center of screen
                Log.w(TAG, "Hit test failed for ${component.id} — using center fallback")
                val fallbackHits = frame.hitTest(surfaceWidth / 2f, surfaceHeight / 2f)
                fallbackHits.firstOrNull()?.createAnchor()
            }

            if (anchor != null) {
                newAnchored.add(AnchoredComponent(component, anchor))
                Log.d(TAG, "Anchor created for ${component.id}")
            } else {
                Log.w(TAG, "Could not create anchor for ${component.id} — no surface detected")
            }
        }

        synchronized(anchoredComponents) {
            anchoredComponents.clear()
            anchoredComponents.addAll(newAnchored)
        }

        Log.d(TAG, "Created ${newAnchored.size} anchors")
    }

    /**
     * Projects each anchor's 3D world position to 2D screen coordinates.
     * Updates labelPositions state which triggers Compose recomposition.
     * Must be called on the GL thread.
     */
    private fun updateLabelPositions(frame: Frame, camera: Camera) {
        val projMatrix = FloatArray(16)
        camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100.0f)

        val viewMatrix = FloatArray(16)
        camera.getViewMatrix(viewMatrix, 0)

        val positions = mutableListOf<Triple<Float, Float, String>>()

        synchronized(anchoredComponents) {
            for (anchored in anchoredComponents) {
                if (anchored.anchor.trackingState != TrackingState.TRACKING) continue

                val pose = anchored.anchor.pose
                val worldPos = floatArrayOf(pose.tx(), pose.ty(), pose.tz(), 1.0f)

                // Transform world position to clip space
                val viewPos = FloatArray(4)
                android.opengl.Matrix.multiplyMV(viewPos, 0, viewMatrix, 0, worldPos, 0)

                val clipPos = FloatArray(4)
                android.opengl.Matrix.multiplyMV(clipPos, 0, projMatrix, 0, viewPos, 0)

                // Skip if behind camera
                if (clipPos[3] <= 0) continue

                // Perspective divide → NDC [-1, 1]
                val ndcX = clipPos[0] / clipPos[3]
                val ndcY = clipPos[1] / clipPos[3]

                // NDC to screen pixels
                // NDC x: -1=left, +1=right → screen 0=left, width=right
                // NDC y: -1=bottom, +1=top → screen 0=top, height=bottom (inverted)
                val screenX = (ndcX + 1f) / 2f * surfaceWidth
                val screenY = (1f - ndcY) / 2f * surfaceHeight

                // Convert pixels to dp for Compose offset
                val density = resources.displayMetrics.density
                val xDp = screenX / density
                val yDp = screenY / density

                positions.add(Triple(xDp, yDp, anchored.component.name))
            }
        }

        runOnUiThread { labelPositions = positions }
    }

    private fun clearAnchors() {
        synchronized(anchoredComponents) {
            anchoredComponents.forEach { it.anchor.detach() }
            anchoredComponents.clear()
        }
    }

    private fun captureAndAnalyze(frame: Frame) {
        try {
            val cameraImage = frame.acquireCameraImage()
            val base64 = FrameConverter.toBase64Jpeg(cameraImage)
            cameraImage.close()

            if (base64 == null) {
                Log.e(TAG, "Frame capture returned null")
                runOnUiThread { statusMessage = "Capture failed — try again" }
                return
            }

            Log.d(TAG, "Frame captured: ${base64.length} chars. Sending to Gemini...")

            lifecycleScope.launch {
                isAnalyzing = true
                withContext(Dispatchers.Main) { statusMessage = "Analyzing..." }

                val json = withContext(Dispatchers.IO) {
                    GeminiClient.analyze(base64)
                }

                val analysis = json?.let { GeminiResponseParser.parse(it) }

                isAnalyzing = false

                withContext(Dispatchers.Main) {
                    if (analysis != null) {
                        currentAnalysis = analysis
                        lockStatus = true
                        statusMessage = "Found: ${analysis.objectName} (${analysis.level1.size} components)"
                        Log.d(TAG, "Analysis complete: ${analysis.objectName}")
                        // Post components to GL thread for anchor creation
                        pendingComponents = analysis.level1
                    } else {
                        lockStatus = false
                        statusMessage = if (json != null) "Parse failed — check Logcat"
                                        else "Gemini failed — try again"
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Exception during capture: ${e.message}", e)
            runOnUiThread {
                isAnalyzing = false
                lockStatus = false
                statusMessage = "Capture failed — try again"
            }
        }
    }
}
