package app.a16vr

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import kotlin.math.abs
import kotlin.math.atan

/** Main (first back-facing) camera -> SurfaceTexture. This is the "see-through" feed. */
class Passthrough(ctx: Context) {
    private val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("passthrough").apply { start() }
    private val h = Handler(thread.looper)
    private var device: CameraDevice? = null

    val id: String? = runCatching {
        mgr.cameraIdList.firstOrNull {
            mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }.getOrNull()
    var w = 1280; var h2 = 720
    var hfov = 66f
    var sensorOri = 90

    init {
        runCatching {
            val c = mgr.getCameraCharacteristics(id!!)
            sensorOri = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val sizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                .getOutputSizes(SurfaceTexture::class.java)
            val best = sizes.filter { it.width * 9 == it.height * 16 && it.width <= 1920 }.maxByOrNull { it.width }
                ?: sizes.minByOrNull { abs(it.width - 1280) }!!
            w = best.width; h2 = best.height
            val pw = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)!!.width
            val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)!!.first()
            hfov = Math.toDegrees(2.0 * atan(pw / (2f * f))).toFloat()
        }
    }

    @SuppressLint("MissingPermission")
    fun start(st: SurfaceTexture) {
        val camId: String = id ?: return
        if (device != null) return
        st.setDefaultBufferSize(w, h2)
        val surface = Surface(st)
        val cb = object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                device = d
                val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                req.addTarget(surface)
                req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                val sessionCb = object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) { s.setRepeatingRequest(req.build(), null, h) }
                    override fun onConfigureFailed(s: CameraCaptureSession) {}
                }
                val exec = java.util.concurrent.Executor { r -> h.post(r) }
                val cfg = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(OutputConfiguration(surface)),
                    exec, sessionCb)
                d.createCaptureSession(cfg)
            }
            override fun onDisconnected(d: CameraDevice) { d.close(); device = null }
            override fun onError(d: CameraDevice, e: Int) { d.close(); device = null }
        }
        try { mgr.openCamera(camId, cb, h) } catch (e: Exception) { }
    }

    fun stop() { device?.close(); device = null }
}
