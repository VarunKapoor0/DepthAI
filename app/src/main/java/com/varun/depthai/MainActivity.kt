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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Camera
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Point.OrientationMode
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.varun.depthai.arcore.BackgroundRenderer
import com.varun.depthai.arcore.DepthTextureHandler
import com.varun.depthai.arcore.ObjectRenderer
import com.varun.depthai.arcore.OcclusionObjectRenderer
import com.varun.depthai.helpers.TapHelper
import com.varun.depthai.ui.theme.DepthAITheme
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : ComponentActivity(), GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "MainActivity"
        private val OBJECT_COLOR = floatArrayOf(139.0f, 195.0f, 74.0f, 255.0f)
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
    private val virtualObject = ObjectRenderer()
    private val occludedVirtualObject = OcclusionObjectRenderer()

    private val anchorMatrix = FloatArray(16)
    private val anchors = ArrayList<Anchor>()

    private var showDepthMap by mutableStateOf(false)
    private var statusMessage by mutableStateOf("Initializing...")
    private var depthButtonText by mutableStateOf("Show Depth")

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

                    // Status message top center
                    Text(
                        text = statusMessage,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 16.dp)
                    )

                    // Depth toggle button top right
                    Button(
                        onClick = {
                            if (isDepthSupported) {
                                showDepthMap = !showDepthMap
                                depthButtonText = if (showDepthMap) "Hide Depth" else "Show Depth"
                            } else {
                                depthButtonText = "Depth N/A"
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 8.dp, end = 8.dp)
                    ) {
                        Text(depthButtonText)
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
            virtualObject.createOnGlThread(this, "models/andy.obj", "models/andy.png")
            virtualObject.setMaterialProperties(0.0f, 2.0f, 0.5f, 6.0f)
            occludedVirtualObject.createOnGlThread(this, "models/andy.obj", "models/andy.png")
            occludedVirtualObject.setMaterialProperties(0.0f, 2.0f, 0.5f, 6.0f)
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
                if (depthShadersCreated) {
                    occludedVirtualObject.setDepthTexture(
                        depthTextureHandler.depthTextureId,
                        depthTextureHandler.depthWidth,
                        depthTextureHandler.depthHeight
                    )
                }
            }

            val camera: Camera = frame.camera

            if (frame.hasDisplayGeometryChanged()) {
                val uvTransform = getTextureTransformMatrix(frame)
                occludedVirtualObject.setUvTransformMatrix(uvTransform)
            }

            handleTap(frame, camera)

            backgroundRenderer.draw(frame)

            if (showDepthMap && isDepthSupported && depthShadersCreated) {
                backgroundRenderer.drawDepth(frame)
            }

            if (camera.trackingState == TrackingState.PAUSED) {
                runOnUiThread { statusMessage = "Tracking lost — move slowly" }
                return
            }

            val projmtx = FloatArray(16)
            camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f)

            val viewmtx = FloatArray(16)
            camera.getViewMatrix(viewmtx, 0)

            val colorCorrectionRgba = FloatArray(4)
            frame.getLightEstimate().getColorCorrection(colorCorrectionRgba, 0)

            for (anchor in anchors) {
                if (anchor.trackingState != TrackingState.TRACKING) continue
                anchor.pose.toMatrix(anchorMatrix, 0)

                if (isDepthSupported && depthShadersCreated) {
                    occludedVirtualObject.updateModelMatrix(anchorMatrix, 1.0f)
                    occludedVirtualObject.draw(viewmtx, projmtx, colorCorrectionRgba, OBJECT_COLOR)
                } else {
                    virtualObject.updateModelMatrix(anchorMatrix, 1.0f)
                    virtualObject.draw(viewmtx, projmtx, colorCorrectionRgba, OBJECT_COLOR)
                }
            }

            val hasPlane = hasTrackingPlane()
            runOnUiThread {
                statusMessage = when {
                    !isDepthSupported -> "Depth not supported on this device"
                    camera.trackingState == TrackingState.TRACKING && hasPlane -> "Tap to place Andy"
                    camera.trackingState == TrackingState.TRACKING -> "Move slowly to detect surfaces"
                    else -> "Initializing..."
                }
            }

        } catch (t: Throwable) {
            Log.e(TAG, "Exception on the OpenGL thread", t)
        }
    }

    private fun handleTap(frame: Frame, camera: Camera) {
        val tap = tapHelper.poll() ?: return
        if (camera.trackingState != TrackingState.TRACKING) return

        for (hit in frame.hitTest(tap)) {
            val trackable = hit.trackable
            if ((trackable is Plane
                        && trackable.isPoseInPolygon(hit.hitPose)
                        && calculateDistanceToPlane(hit.hitPose, camera.pose) > 0)
                || (trackable is Point
                        && trackable.orientationMode == OrientationMode.ESTIMATED_SURFACE_NORMAL)
            ) {
                if (anchors.size >= 20) {
                    anchors[0].detach()
                    anchors.removeAt(0)
                }
                anchors.add(hit.createAnchor())
                break
            }
        }
    }

    private fun hasTrackingPlane(): Boolean {
        val currentSession = session ?: return false
        return currentSession.getAllTrackables(Plane::class.java)
            .any { it.trackingState == TrackingState.TRACKING }
    }

    private fun calculateDistanceToPlane(planePose: Pose, cameraPose: Pose): Float {
        val normal = FloatArray(3)
        planePose.getTransformedAxis(1, 1.0f, normal, 0)
        return ((cameraPose.tx() - planePose.tx()) * normal[0]
                + (cameraPose.ty() - planePose.ty()) * normal[1]
                + (cameraPose.tz() - planePose.tz()) * normal[2])
    }

    private fun getTextureTransformMatrix(frame: Frame): FloatArray {
        val frameTransform = FloatArray(6)
        val uvTransform = FloatArray(9)
        val ndcBasis = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)

        frame.transformCoordinates2d(
            Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
            ndcBasis,
            Coordinates2d.TEXTURE_NORMALIZED,
            frameTransform
        )

        val ndcOriginX = frameTransform[0]
        val ndcOriginY = frameTransform[1]
        uvTransform[0] = frameTransform[2] - ndcOriginX
        uvTransform[1] = frameTransform[3] - ndcOriginY
        uvTransform[2] = 0f
        uvTransform[3] = frameTransform[4] - ndcOriginX
        uvTransform[4] = frameTransform[5] - ndcOriginY
        uvTransform[5] = 0f
        uvTransform[6] = ndcOriginX
        uvTransform[7] = ndcOriginY
        uvTransform[8] = 1f

        return uvTransform
    }
}
