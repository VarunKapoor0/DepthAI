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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Camera
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.varun.depthai.arcore.BackgroundRenderer
import com.varun.depthai.arcore.DepthTextureHandler
import com.varun.depthai.gemini.FrameConverter
import com.varun.depthai.helpers.TapHelper
import com.varun.depthai.ui.theme.DepthAITheme
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
    @Volatile private var lastCapturedBase64: String? = null

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

                    Text(
                        text = statusMessage,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 16.dp)
                    )

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

                        // Scan button — no condition, always pressable
                        // GL thread checks tracking state before capturing
                        Button(onClick = {
                            pendingScan = true
                            Log.d(TAG, "Scan button pressed, pendingScan = true")
                        }) {
                            Text("Scan")
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

            // Check pendingScan regardless of tracking — log both cases
            if (pendingScan) {
                pendingScan = false
                Log.d(TAG, "Processing scan request. Tracking: $tracking")
                if (tracking) {
                    captureFrame(frame)
                } else {
                    Log.w(TAG, "Scan requested but not tracking yet")
                    runOnUiThread { statusMessage = "Not tracking yet — try again" }
                }
            }

            backgroundRenderer.draw(frame)

            if (showDepthMap && isDepthSupported && depthShadersCreated) {
                backgroundRenderer.drawDepth(frame)
            }

            runOnUiThread {
                isTracking = tracking
                statusMessage = when {
                    !isDepthSupported -> "Depth not supported on this device"
                    !tracking -> "Tracking lost — move slowly"
                    else -> "Point at object and tap Scan"
                }
            }

        } catch (t: Throwable) {
            Log.e(TAG, "Exception on the OpenGL thread", t)
        }
    }

    private fun captureFrame(frame: Frame) {
        try {
            Log.d(TAG, "Acquiring camera image...")
            val cameraImage = frame.acquireCameraImage()
            Log.d(TAG, "Camera image acquired: ${cameraImage.width}x${cameraImage.height}")
            val base64 = FrameConverter.toBase64Jpeg(cameraImage)
            cameraImage.close()

            if (base64 != null) {
                Log.d(TAG, "Frame captured successfully. Base64 length: ${base64.length}")
                Log.d(TAG, "Base64 preview: ${base64.take(100)}...")
                lastCapturedBase64 = base64
                runOnUiThread { statusMessage = "Frame captured — ready for Gemini" }
            } else {
                Log.e(TAG, "FrameConverter returned null")
                runOnUiThread { statusMessage = "Capture failed — try again" }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during frame capture: ${e.message}", e)
            runOnUiThread { statusMessage = "Capture failed: ${e.message}" }
        }
    }
}
