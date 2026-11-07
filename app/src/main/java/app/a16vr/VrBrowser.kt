package app.a16vr

import android.Manifest
import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.*
import android.webkit.*
import android.widget.*

interface Controls {
    fun recenter()
    fun setBackground(i: Int)
    fun rotateCamera()
    fun mirrorCamera()
    fun zoom(d: Float)
    fun pickImage()
}

/** The floating window: a real WebView + toolbar + gaze keyboard, living on a VirtualDisplay. */
class VrBrowser(
    ctx: Context, display: Display,
    private val startUrl: String?,
    private val ctl: Controls
) : Presentation(ctx, display, android.R.style.Theme_Material_NoActionBar) {

    lateinit var web: WebView
    private lateinit var urlBox: EditText
    private lateinit var kb: LinearLayout
    private lateinit var root: FrameLayout
    private lateinit var settingsOv: FrameLayout
    private var custom: View? = null
    private var speech: SpeechRecognizer? = null

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private val home = """<html><head><meta name=viewport content="width=device-width,initial-scale=1"><style>
body{margin:0;background:radial-gradient(circle at 50% 0%,#2b3150,#0a0a10);color:#fff;font-family:sans-serif;text-align:center}
h1{font-weight:300;margin:24px 0 16px;font-size:30px}
.g{display:grid;grid-template-columns:repeat(4,1fr);gap:14px;padding:0 24px}
a{display:block;padding:34px 0;border-radius:26px;background:rgba(255,255,255,.12);color:#fff;text-decoration:none;font-size:24px;border:1px solid rgba(255,255,255,.22)}
</style></head><body><h1>A16 VR</h1><div class=g>
<a href="https://m.youtube.com">YouTube</a><a href="https://www.google.com">Google</a>
<a href="https://en.m.wikipedia.org">Wikipedia</a><a href="https://www.reddit.com">Reddit</a>
<a href="https://x.com">X</a><a href="https://m.twitch.tv">Twitch</a>
<a href="https://www.google.com/maps">Maps</a><a href="https://www.netflix.com">Netflix</a>
</div></body></html>"""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))

        root = FrameLayout(context)
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF111114.toInt()) }

        val bar = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1C1C22.toInt())
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        fun btn(t: String, w: Int = 46, f: () -> Unit) = Button(context).apply {
            text = t; isAllCaps = false; setTextColor(Color.WHITE); textSize = 18f
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0x33FFFFFF) }
            minWidth = 0; minimumWidth = 0; setPadding(0, 0, 0, 0)
            setOnClickListener { f() }
            layoutParams = LinearLayout.LayoutParams(dp(w), dp(44)).apply { setMargins(dp(3), 0, dp(3), 0) }
        }
        urlBox = EditText(context).apply {
            setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(0x88FFFFFF.toInt())
            hint = "Search or type URL"; textSize = 16f; showSoftInputOnFocus = false
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0x33FFFFFF) }
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { kb.visibility = if (kb.visibility == View.GONE) View.VISIBLE else View.GONE }
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(3), 0, dp(3), 0) }
        }

        web = WebView(context).apply {
            setBackgroundColor(Color.BLACK)
            settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                useWideViewPort = true; loadWithOverviewMode = true
                setGeolocationEnabled(true)
                javaScriptCanOpenWindowsAutomatically = true
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    val s = r.url.scheme
                    return s != "http" && s != "https" && s != "about" && s != "data"
                }
                override fun onPageFinished(v: WebView, u: String) {
                    urlBox.setText(if (u.contains("home.local") || u.startsWith("data:")) "" else u)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(r: PermissionRequest) { r.grant(r.resources) }
                override fun onGeolocationPermissionsShowPrompt(o: String, cb: GeolocationPermissions.Callback) { cb.invoke(o, true, false) }
                override fun onShowCustomView(v: View, cb: WebChromeClient.CustomViewCallback) {
                    custom = v; root.addView(v, FrameLayout.LayoutParams(-1, -1))
                }
                override fun onHideCustomView() { custom?.let { root.removeView(it) }; custom = null }
            }
        }

        bar.addView(btn("◀") { if (web.canGoBack()) web.goBack() })
        bar.addView(btn("⟳") { web.reload() })
        bar.addView(btn("⌂") { loadHome() })
        bar.addView(btn("YouTube", 84) { web.loadUrl("https://m.youtube.com") })
        bar.addView(urlBox)
        bar.addView(btn("⌨") { kb.visibility = if (kb.visibility == View.GONE) View.VISIBLE else View.GONE })
        bar.addView(btn("🎤") { listen() })
        bar.addView(btn("▲") { scroll(-600) })
        bar.addView(btn("▼") { scroll(600) })
        bar.addView(btn("⊙") { ctl.recenter() })
        bar.addView(btn("⚙") { settingsOv.visibility = if (settingsOv.visibility == View.GONE) View.VISIBLE else View.GONE })

        kb = buildKeyboard().apply { visibility = View.GONE }

        col.addView(bar, LinearLayout.LayoutParams(-1, dp(52)))
        col.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        col.addView(kb, LinearLayout.LayoutParams(-1, -2))
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        settingsOv = buildSettings()
        root.addView(settingsOv, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        window?.setLayout(-1, -1)

        if (startUrl != null) web.loadUrl(startUrl) else loadHome()
    }

    private fun chip(t: String, f: () -> Unit) = Button(context).apply {
        text = t; isAllCaps = false; setTextColor(Color.WHITE); textSize = 15f
        background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0x44FFFFFF) }
        setPadding(dp(10), 0, dp(10), 0); minWidth = 0; minimumWidth = 0
        setOnClickListener { f() }
        layoutParams = LinearLayout.LayoutParams(-2, dp(46)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
    }

    private fun heading(t: String) = TextView(context).apply {
        text = t; setTextColor(0xFFBBBBCC.toInt()); textSize = 14f
        setPadding(dp(4), dp(10), 0, dp(2))
    }

    private fun buildSettings(): FrameLayout {
        val ov = FrameLayout(context).apply { setBackgroundColor(0x99000000.toInt()); visibility = View.GONE; isClickable = true }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(14))
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(0xF01C1C22.toInt()) }
        }
        fun row(vararg items: Pair<String, () -> Unit>) = LinearLayout(context).apply {
            items.forEach { addView(chip(it.first, it.second)) }
        }
        card.addView(heading("BACKGROUND"))
        card.addView(row(
            "Camera" to { ctl.setBackground(0) }, "Black" to { ctl.setBackground(1) },
            "Space" to { ctl.setBackground(2) }, "Sunset" to { ctl.setBackground(3) },
            "Ocean" to { ctl.setBackground(4) }, "Studio" to { ctl.setBackground(5) },
            "Photo…" to { ctl.pickImage() }))
        card.addView(heading("CAMERA"))
        card.addView(row(
            "⟲ Rotate" to { ctl.rotateCamera() }, "⇋ Mirror" to { ctl.mirrorCamera() },
            "Zoom −" to { ctl.zoom(-0.2f) }, "Zoom +" to { ctl.zoom(0.2f) }))
        card.addView(row(
            "Recenter" to { ctl.recenter(); ov.visibility = View.GONE },
            "Close" to { ov.visibility = View.GONE }))
        ov.addView(card, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        return ov
    }

    private fun loadHome() = web.loadDataWithBaseURL("https://home.local/", home, "text/html", "utf-8", null)

    private fun scroll(dy: Int) =
        web.evaluateJavascript("window.scrollBy({top:$dy,behavior:'smooth'})", null)

    fun go(q: String) {
        val t = q.trim(); if (t.isEmpty()) return
        val u = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(".") && !t.contains(" ") -> "https://$t"
            else -> "https://www.google.com/search?q=" + Uri.encode(t)
        }
        web.loadUrl(u)
    }

    private fun buildKeyboard(): LinearLayout {
        val c = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xF0202026.toInt()); setPadding(dp(6), dp(4), dp(6), dp(4)) }
        fun key(t: String, w: Float = 1f, f: () -> Unit) = Button(context).apply {
            text = t; isAllCaps = false; setTextColor(Color.WHITE); textSize = 17f
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0x33FFFFFF) }
            setPadding(0, 0, 0, 0); minWidth = 0; minimumWidth = 0
            setOnClickListener { f() }
            layoutParams = LinearLayout.LayoutParams(0, dp(40), w).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
        }
        listOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm").forEach { row ->
            val r = LinearLayout(context)
            row.forEach { ch -> r.addView(key(ch.toString()) { urlBox.append(ch.toString()) }) }
            c.addView(r)
        }
        val last = LinearLayout(context)
        listOf(".", "/", "-", ":").forEach { s -> last.addView(key(s) { urlBox.append(s) }) }
        last.addView(key("space", 3f) { urlBox.append(" ") })
        last.addView(key("⌫", 1.5f) { val t = urlBox.text; if (t.isNotEmpty()) t.delete(t.length - 1, t.length) })
        last.addView(key("Go", 1.5f) { kb.visibility = View.GONE; go(urlBox.text.toString()) })
        c.addView(last)
        return c
    }

    private fun listen() {
        val app = context.applicationContext
        if (!SpeechRecognizer.isRecognitionAvailable(app)) { urlBox.hint = "Voice unavailable on this ROM"; return }
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { urlBox.hint = "Mic permission denied"; return }
        speech?.destroy()
        speech = SpeechRecognizer.createSpeechRecognizer(app).also { sr ->
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) { urlBox.hint = "Listening…" }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(e: Int) { urlBox.hint = "Search or type URL" }
                override fun onPartialResults(p: Bundle?) {}
                override fun onEvent(t: Int, p: Bundle?) {}
                override fun onResults(r: Bundle?) {
                    urlBox.hint = "Search or type URL"
                    r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { go(it) }
                }
            })
            sr.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
        }
    }

    override fun onStop() {
        speech?.destroy(); speech = null
        web.destroy()
        super.onStop()
    }
}
