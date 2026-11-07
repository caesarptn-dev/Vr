package app.a16vr

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.*
import android.widget.Toast
import kotlin.math.hypot

class MainActivity : Activity(), VrRenderer.Host, SensorEventListener, Controls {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: VrRenderer
    private lateinit var cam: Passthrough
    private lateinit var sm: SensorManager
    private lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())

    private var camST: SurfaceTexture? = null
    private var panelST: SurfaceTexture? = null
    private var vd: VirtualDisplay? = null
    private var browser: VrBrowser? = null
    private var lastUrl: String? = null

    // gaze / dwell state
    private var gx = 0f; private var gy = 0f; private var ghit = false
    private var ax = -999f; private var ay = -999f; private var aT = 0L; private var fired = false

    private val perms = arrayOf(
        Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setDecorFitsSystemWindows(false)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            screenBrightness = 1f
        }

        prefs = getSharedPreferences("vr", MODE_PRIVATE)
        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        cam = Passthrough(this)

        renderer = VrRenderer(this, cam.w, cam.h2, cam.hfov)
        renderer.bgMode = prefs.getInt("bg", 0)
        renderer.camRot = prefs.getInt("rot", 0)
        renderer.camMirror = prefs.getBoolean("mir", false)
        renderer.camFill = prefs.getFloat("zoom", 1.6f)
        updateCamBase()
        prefs.getString("img", null)?.let { loadImage(Uri.parse(it), false) }
        if (renderer.bgMode == 6 && prefs.getString("img", null) == null) renderer.bgMode = 0

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        setContentView(glView)

        // ask for everything the app needs, up front
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    /** Camera image rotation needed so the feed is upright in the current landscape direction. */
    private fun updateCamBase() {
        val rot = runCatching { display?.rotation }.getOrNull() ?: Surface.ROTATION_90
        val deg = when (rot) {
            Surface.ROTATION_0 -> 0; Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; else -> 270
        }
        renderer.camBaseDeg = (cam.sensorOri - deg + 360) % 360
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateCamBase()
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            Toast.makeText(this, "Camera denied: passthrough off. Enable it in App settings.", Toast.LENGTH_LONG).show()
        startCamera()
    }

    // ---- settings controls (called from the floating panel) ----
    override fun recenter() { renderer.recenter() }
    override fun setBackground(i: Int) { renderer.bgMode = i; prefs.edit().putInt("bg", i).apply() }
    override fun rotateCamera() { renderer.camRot = (renderer.camRot + 1) % 4; prefs.edit().putInt("rot", renderer.camRot).apply() }
    override fun mirrorCamera() { renderer.camMirror = !renderer.camMirror; prefs.edit().putBoolean("mir", renderer.camMirror).apply() }
    override fun zoom(d: Float) { renderer.camFill = (renderer.camFill + d).coerceIn(0.6f, 3.2f); prefs.edit().putFloat("zoom", renderer.camFill).apply() }

    override fun pickImage() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try { startActivityForResult(i, 7) } catch (e: Exception) {
            Toast.makeText(this, "No photo picker found", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != 7 || res != RESULT_OK) return
        val u = data?.data ?: return
        try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { }
        prefs.edit().putString("img", u.toString()).apply()
        loadImage(u, true)
    }

    private fun loadImage(uri: Uri, switchTo: Boolean) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var s = 1
            while (bounds.outWidth / s > 4096) s *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = s }
            val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (bmp != null) {
                renderer.setCustomBitmap(bmp)
                if (switchTo) setBackground(6)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not load image", Toast.LENGTH_LONG).show()
        }
    }

    // ---- GL host callbacks (GL thread) ----
    override fun onSurfaces(cam: SurfaceTexture, panel: SurfaceTexture) {
        ui.post { camST = cam; panelST = panel; startPanel(); startCamera() }
    }
    override fun onGaze(x: Float, y: Float, hit: Boolean) { ui.post { gaze(x, y, hit) } }

    private fun startCamera() {
        val st = camST ?: return
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) cam.start(st)
    }

    private fun startPanel() {
        val st = panelST ?: return
        if (browser != null) return
        try {
            val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
            vd = dm.createVirtualDisplay(
                "vr-panel", VrRenderer.PW, VrRenderer.PH, 240, Surface(st),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            )
            browser = VrBrowser(this, vd!!.display, lastUrl, this).also { it.show() }
        } catch (e: Exception) {
            Toast.makeText(this, "Panel error: " + e.javaClass.simpleName + " " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun teardown() {
        try { lastUrl = browser?.web?.url?.takeIf { it.startsWith("http") && !it.contains("home.local") } } catch (e: Exception) { }
        try { browser?.dismiss() } catch (e: Exception) { }
        browser = null
        try { vd?.release() } catch (e: Exception) { }
        vd = null
        cam.stop()
        camST = null; panelST = null
    }

    // ---- gaze dwell + click injection ----
    private fun gaze(x: Float, y: Float, hit: Boolean) {
        gx = x; gy = y; ghit = hit
        val now = SystemClock.uptimeMillis()
        if (!hit) { renderer.dwell = 0f; ax = -999f; fired = false; return }
        if (hypot(x - ax, y - ay) > 45f) { ax = x; ay = y; aT = now; fired = false }
        if (fired) { renderer.dwell = 0f; return }
        val p = ((now - aT) / 1100f).coerceAtMost(1f)
        renderer.dwell = p
        if (p >= 1f) { fired = true; click(ax, ay) }
    }

    private fun click(x: Float, y: Float) {
        val v = browser?.window?.decorView ?: return
        val t0 = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        v.dispatchTouchEvent(down); down.recycle()
        ui.postDelayed({
            val up = MotionEvent.obtain(t0, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            v.dispatchTouchEvent(up); up.recycle()
        }, 70)
    }

    // Cardboard button / any screen tap = click at gaze
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && ghit) click(gx, gy)
        return true
    }

    // Volume keys = recenter the panel in front of you
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { renderer.recenter(); return true }
        return super.onKeyDown(keyCode, event)
    }

    // ---- head tracking ----
    override fun onSensorChanged(e: SensorEvent) {
        val v = if (e.values.size > 4) e.values.copyOf(4) else e.values
        val r = FloatArray(16); SensorManager.getRotationMatrixFromVector(r, v)
        val out = FloatArray(16)
        if (runCatching { display?.rotation }.getOrNull() == Surface.ROTATION_270)
            SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, out)
        else
            SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, out)
        renderer.head = out
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onResume() {
        super.onResume()
        glView.onResume()
        updateCamBase()
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val s = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (s == null) Toast.makeText(this, "No gyroscope/rotation sensor found", Toast.LENGTH_LONG).show()
        else sm.registerListener(this, s, SensorManager.SENSOR_DELAY_FASTEST)
    }

    override fun onPause() {
        sm.unregisterListener(this)
        teardown()
        glView.onPause()
        super.onPause()
    }
}
