package app.a16vr

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/**
 * Stereo renderer: passthrough camera (head-locked) + floating world-locked panel + gaze reticle,
 * rendered per eye into an FBO, then lens-corrected (barrel) to the screen for Cardboard lenses.
 */
class VrRenderer(
    private val host: Host,
    private val camW: Int, private val camH: Int,
    private val camHFov: Float
) : GLSurfaceView.Renderer {

    interface Host {
        fun onSurfaces(cam: SurfaceTexture, panel: SurfaceTexture)
        fun onGaze(x: Float, y: Float, hit: Boolean)
    }

    companion object {
        const val PW = 1280; const val PH = 720          // panel pixels
        const val PANEL_DIST = 2.0f                        // metres
        const val PANEL_W = 2.2f
        const val PANEL_H = PANEL_W * PH / PW
        const val IPD = 0.064f
        const val FOV_Y = 90f
        const val K1 = 0.20f; const val K2 = 0.10f         // lens distortion
    }

    @Volatile var head = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    @Volatile var dwell = 0f
    @Volatile var bgMode = 0            // 0 camera, 1 black, 2 space, 3 sunset, 4 ocean, 5 studio, 6 photo
    @Volatile var camRot = 0            // extra user rotation, quarter turns
    @Volatile var camMirror = false
    @Volatile var camFill = 1.6f        // camera zoom (bigger = fills more of the view)
    @Volatile var camBaseDeg = 0        // automatic rotation from sensor/display
    @Volatile private var customBmp: Bitmap? = null
    @Volatile private var bmpDirty = false
    fun setCustomBitmap(b: Bitmap?) { customBmp = b; bmpDirty = true }
    @Volatile private var recenterFlag = true
    fun recenter() { recenterFlag = true }

    private var sceneProg = 0; private var cursorProg = 0; private var distProg = 0; private var bgProg = 0; private var bgTex = 0
    private var camTex = 0; private var panelTex = 0
    private var camST: SurfaceTexture? = null; private var panelST: SurfaceTexture? = null
    private val camDirty = AtomicBoolean(false); private val panelDirty = AtomicBoolean(false)
    private var camReady = false; private var panelReady = false
    private val camTM = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val panTM = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val idTM = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private var fbo = 0; private var fboTex = 0
    private var sw = 1; private var sh = 1
    private var pfx = 0f; private var pfy = 1f
    private var sx = PW / 2f; private var sy = PH / 2f

    private val vb = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-.5f, -.5f, 0f, 0f, .5f, -.5f, 1f, 0f, -.5f, .5f, 0f, 1f, .5f, .5f, 1f, 1f)); position(0)
    }
    private val proj = FloatArray(16); private val ve = FloatArray(16); private val t = FloatArray(16)
    private val tmp = FloatArray(16); private val mvp = FloatArray(16); private val m = FloatArray(16)

    private val VS = """
uniform mat4 uMVP; uniform mat4 uTex; attribute vec2 aPos; attribute vec2 aUV;
varying vec2 vUV; varying vec2 vRaw;
void main(){ gl_Position=uMVP*vec4(aPos,0.,1.); vRaw=aUV; vUV=(uTex*vec4(aUV,0.,1.)).xy; }"""

    private val FS_OES = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vUV; varying vec2 vRaw;
uniform samplerExternalOES uTexture; uniform float uAspect;
void main(){
  vec4 c = texture2D(uTexture, vUV); float a = 1.0;
  if (uAspect > 0.0) {
    vec2 p = (vRaw-0.5)*vec2(uAspect,1.0); float rr = 0.045;
    float d = length(max(abs(p)-(vec2(uAspect*0.5,0.5)-rr),0.0))-rr;
    a = 1.0-smoothstep(0.0,0.004,d);
    float rim = smoothstep(-0.012,-0.004,d)*(1.0-smoothstep(-0.004,0.0,d));
    c.rgb = mix(c.rgb, vec3(1.0), rim*0.4);
  } else {
    float e = min(min(vRaw.x,1.0-vRaw.x),min(vRaw.y,1.0-vRaw.y));
    a = smoothstep(0.0,0.06,e);
  }
  gl_FragColor = vec4(c.rgb,a);
}"""

    private val FS_CURSOR = """
precision mediump float;
varying vec2 vRaw; uniform float uProg;
void main(){
  vec2 p = vRaw*2.0-1.0; float d = length(p);
  float dot_ = 1.0-smoothstep(0.14,0.18,d);
  float ring = smoothstep(0.62,0.66,d)*(1.0-smoothstep(0.86,0.9,d));
  float ang = atan(p.x,p.y); if (ang<0.0) ang += 6.28318; ang /= 6.28318;
  float on = step(ang,uProg);
  vec3 col = mix(vec3(1.0),vec3(0.35,0.7,1.0),on);
  gl_FragColor = vec4(col, max(dot_, ring*mix(0.35,1.0,on)));
}"""

    private val FS_BG = """
precision highp float;
varying vec2 vUV; uniform mat3 uInv; uniform vec2 uTan; uniform float uMode; uniform sampler2D uImg;
float hash(vec3 p){ p=fract(p*0.1031); p+=dot(p,p.yzx+33.33); return fract((p.x+p.y)*p.z); }
void main(){
  vec2 n = vUV*2.0-1.0;
  vec3 d = normalize(uInv*vec3(n.x*uTan.x, n.y*uTan.y, -1.0));
  float h = d.z*0.5+0.5;
  vec3 c = vec3(0.0);
  if (uMode < 2.5) {
    c = mix(vec3(0.01,0.01,0.04), vec3(0.06,0.04,0.14), h);
    c += vec3(0.18,0.12,0.30)*exp(-d.z*d.z*8.0)*0.6;
    float s = hash(floor(d*80.0));
    c += vec3(1.0)*smoothstep(0.985,1.0,s);
  } else if (uMode < 3.5) {
    vec3 sky = mix(vec3(0.98,0.5,0.2), vec3(0.25,0.12,0.4), smoothstep(0.5,0.95,h));
    c = mix(vec3(0.07,0.05,0.09), sky, smoothstep(0.49,0.52,h));
    c += vec3(1.0,0.85,0.5)*smoothstep(0.07,0.04,distance(d, normalize(vec3(0.3,1.0,0.12))));
  } else if (uMode < 4.5) {
    c = mix(vec3(0.0,0.08,0.2), vec3(0.15,0.7,0.85), smoothstep(0.25,0.8,h));
  } else if (uMode < 5.5) {
    c = mix(vec3(0.10), vec3(0.42,0.44,0.5), smoothstep(0.2,0.9,h));
  } else {
    float u = atan(d.x,d.y)/6.28318+0.5;
    float v = 0.5-asin(clamp(d.z,-1.0,1.0))/3.14159;
    c = texture2D(uImg, vec2(u,v)).rgb;
  }
  gl_FragColor = vec4(c,1.0);
}"""

    private val VS_DIST = """
attribute vec2 aPos; attribute vec2 aUV; varying vec2 vUV;
void main(){ gl_Position=vec4(aPos*2.0,0.,1.); vUV=aUV; }"""

    private val FS_DIST = """
precision highp float;
varying vec2 vUV; uniform sampler2D uTexture; uniform float uAspect; uniform vec2 uK;
void main(){
  bool left = vUV.x < 0.5;
  vec2 e = vec2(left ? vUV.x*2.0 : (vUV.x-0.5)*2.0, vUV.y);
  vec2 q = (e*2.0-1.0)*vec2(uAspect,1.0);
  float r2 = dot(q,q);
  float s = (1.0+uK.x*r2+uK.y*r2*r2)/(1.0+uK.x+uK.y);
  vec2 se = vec2(q.x*s/uAspect, q.y*s)*0.5+0.5;
  if (se.x<0.0||se.x>1.0||se.y<0.0||se.y>1.0) { gl_FragColor=vec4(0.0,0.0,0.0,1.0); return; }
  gl_FragColor = texture2D(uTexture, vec2(left ? se.x*0.5 : 0.5+se.x*0.5, se.y));
}"""

    private fun program(vs: String, fs: String): Int {
        fun sh(type: Int, src: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, sh(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(it, sh(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(it)
        }
    }

    private fun oesTex(): Int {
        val a = IntArray(1); GLES20.glGenTextures(1, a, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, a[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return a[0]
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        sceneProg = program(VS, FS_OES); cursorProg = program(VS, FS_CURSOR); distProg = program(VS_DIST, FS_DIST)
        bgProg = program(VS_DIST, FS_BG)
        run {
            val bt = IntArray(1); GLES20.glGenTextures(1, bt, 0); bgTex = bt[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bgTex)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.allocateDirect(4))
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            bmpDirty = true
        }
        camTex = oesTex(); panelTex = oesTex()
        camReady = false; panelReady = false
        camST = SurfaceTexture(camTex).also {
            it.setDefaultBufferSize(camW, camH)
            it.setOnFrameAvailableListener { camDirty.set(true) }
        }
        panelST = SurfaceTexture(panelTex).also {
            it.setDefaultBufferSize(PW, PH)
            it.setOnFrameAvailableListener { panelDirty.set(true) }
        }
        host.onSurfaces(camST!!, panelST!!)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        sw = w; sh = h
        val a = IntArray(1)
        GLES20.glGenTextures(1, a, 0); fboTex = a[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glGenFramebuffers(1, a, 0); fbo = a[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun model(o: FloatArray, rx: Float, ry: Float, rz: Float, ux: Float, uy: Float, uz: Float,
                      nx: Float, ny: Float, nz: Float, cx: Float, cy: Float, cz: Float) {
        o[0] = rx; o[1] = ry; o[2] = rz; o[3] = 0f
        o[4] = ux; o[5] = uy; o[6] = uz; o[7] = 0f
        o[8] = nx; o[9] = ny; o[10] = nz; o[11] = 0f
        o[12] = cx; o[13] = cy; o[14] = cz; o[15] = 1f
    }

    private fun quad(prog: Int, mvpM: FloatArray, tm: FloatArray) {
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uMVP"), 1, false, mvpM, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uTex"), 1, false, tm, 0)
        val a = GLES20.glGetAttribLocation(prog, "aPos"); val u = GLES20.glGetAttribLocation(prog, "aUV")
        vb.position(0); GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 16, vb); GLES20.glEnableVertexAttribArray(a)
        vb.position(2); GLES20.glVertexAttribPointer(u, 2, GLES20.GL_FLOAT, false, 16, vb); GLES20.glEnableVertexAttribArray(u)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (camDirty.getAndSet(false)) { camST!!.updateTexImage(); camST!!.getTransformMatrix(camTM); camReady = true }
        if (panelDirty.getAndSet(false)) { panelST!!.updateTexImage(); panelST!!.getTransformMatrix(panTM); panelReady = true }

        val bmp = customBmp
        if (bmpDirty) {
            if (bmp != null && !bmp.isRecycled) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bgTex)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            }
            bmpDirty = false
        }

        val r = head
        val fhx = -r[2]; val fhy = -r[6]; val fhz = -r[10]          // head forward in world (z up)
        if (recenterFlag) {
            val n = hypot(fhx, fhy)
            if (n > 0.2f) { pfx = fhx / n; pfy = fhy / n; recenterFlag = false }
        }

        // gaze ray vs panel plane
        val dotp = fhx * pfx + fhy * pfy
        var tHit = PANEL_DIST; var hit = false
        if (dotp > 0.05f) {
            val tt = PANEL_DIST / dotp
            val px = fhx * tt - pfx * PANEL_DIST; val py = fhy * tt - pfy * PANEL_DIST; val pz = fhz * tt
            val u = (px * pfy - py * pfx) / PANEL_W + 0.5f
            val v = 0.5f - pz / PANEL_H
            if (u in 0f..1f && v in 0f..1f) {
                hit = true; tHit = tt.coerceIn(0.5f, 8f)
                sx += (u * PW - sx) * 0.3f; sy += (v * PH - sy) * 0.3f
            }
        }
        host.onGaze(sx, sy, hit)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, sw, sh)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val ew = sw / 2
        for (eye in 0..1) {
            GLES20.glViewport(eye * ew, 0, ew, sh)
            Matrix.perspectiveM(proj, 0, FOV_Y, ew.toFloat() / sh, 0.1f, 50f)
            Matrix.setIdentityM(t, 0); t[12] = if (eye == 0) IPD / 2 else -IPD / 2
            Matrix.multiplyMM(ve, 0, t, 0, r, 0)

            // 0) virtual background (world-fixed)
            if (bgMode >= 2) {
                val ty = tan(Math.toRadians(FOV_Y / 2.0)).toFloat()
                GLES20.glUseProgram(bgProg)
                GLES20.glUniformMatrix3fv(GLES20.glGetUniformLocation(bgProg, "uInv"), 1, false,
                    floatArrayOf(r[0], r[4], r[8], r[1], r[5], r[9], r[2], r[6], r[10]), 0)
                GLES20.glUniform2f(GLES20.glGetUniformLocation(bgProg, "uTan"), ty * ew / sh, ty)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(bgProg, "uMode"), bgMode.toFloat())
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bgTex)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(bgProg, "uImg"), 0)
                quad(bgProg, idTM, idTM)
            }
            // 1) passthrough camera, head-locked at "infinity"
            if (bgMode == 0 && camReady) {
                val cd = 5f
                val wq = 2f * cd * tan(Math.toRadians(camHFov / 2.0)).toFloat() * camFill
                val hq = wq * camH / camW
                val th = Math.toRadians((-camBaseDeg + camRot * 90).toDouble()).toFloat()
                val cs = cos(th); val sn = sin(th)
                val mx = if (camMirror) -1f else 1f
                model(m, wq * cs * mx, wq * sn * mx, 0f, -hq * sn, hq * cs, 0f, 0f, 0f, 1f, 0f, 0f, -cd)
                Matrix.multiplyMM(mvp, 0, proj, 0, m, 0)
                GLES20.glUseProgram(sceneProg)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(sceneProg, "uAspect"), 0f)
                quad(sceneProg, mvp, camTM)
            }
            // 2) floating panel, world-locked
            if (panelReady) {
                model(m, pfy * PANEL_W, -pfx * PANEL_W, 0f, 0f, 0f, PANEL_H, -pfx, -pfy, 0f,
                    pfx * PANEL_DIST, pfy * PANEL_DIST, 0f)
                Matrix.multiplyMM(tmp, 0, ve, 0, m, 0)
                Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0)
                GLES20.glUseProgram(sceneProg)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, panelTex)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(sceneProg, "uAspect"), PW.toFloat() / PH)
                quad(sceneProg, mvp, panTM)
            }
            // 3) gaze reticle (centre of the head, at the depth of whatever it hits)
            val s = 0.06f * tHit
            model(m, s, 0f, 0f, 0f, s, 0f, 0f, 0f, 1f, 0f, 0f, -tHit)
            Matrix.multiplyMM(tmp, 0, t, 0, m, 0)
            Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0)
            GLES20.glUseProgram(cursorProg)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(cursorProg, "uProg"), dwell)
            quad(cursorProg, mvp, idTM)
        }

        // lens correction pass
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, sw, sh)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(distProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(distProg, "uAspect"), ew.toFloat() / sh)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(distProg, "uK"), K1, K2)
        quad(distProg, idTM, idTM)
        GLES20.glEnable(GLES20.GL_BLEND)
    }
}
