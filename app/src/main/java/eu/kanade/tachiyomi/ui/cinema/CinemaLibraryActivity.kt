// KMK -->
package eu.kanade.tachiyomi.ui.cinema

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** The Cinema service remains the source of truth; never copy its login or
 * watched events into Manga's private SQLCipher database. */
class CinemaLibraryActivity : Activity() {
    private lateinit var browser: WebView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(11, 16, 29)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(11, 16, 29))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18, 12, 12, 12)
        }
        val back = Button(this).apply {
            text = "Back"
            setOnClickListener { finish() }
        }
        header.addView(back)
        header.addView(TextView(this).apply {
            text = "Cinema Library"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(16, 0, 8, 0)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val reload = Button(this).apply {
            text = "Refresh"
            setOnClickListener { browser.reload() }
        }
        header.addView(reload)
        root.addView(header)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, 4))
        status = TextView(this).apply {
            text = "Cinema and Manga stay in sync with their own accounts. Sign in to Cinema once."
            setTextColor(Color.LTGRAY)
            textSize = 12f
            setPadding(20, 10, 20, 10)
        }
        root.addView(status)
        browser = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                    progress.progress = newProgress
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                    val url = request.url
                    // Only Cinema's own HTTPS origin is allowed in this embedded session.
                    if (url.scheme == "https" && url.host == "cinema.demos25.me") return false
                    status.text = "External links are not opened in the embedded library."
                    return true
                }
                override fun onReceivedError(
                    view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        status.text = "Cinema is unavailable. Use Refresh to retry; Manga data is safe."
                    }
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url?.startsWith("https://cinema.demos25.me") == true) {
                        status.text = "Cinema Library • Movies, TV, Anime and watch history"
                    }
                }
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        root.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        if (savedInstanceState != null) browser.restoreState(savedInstanceState)
        else browser.loadUrl("https://cinema.demos25.me/")
    }
    override fun onSaveInstanceState(outState: Bundle) {
        browser.saveState(outState)
        super.onSaveInstanceState(outState)
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (browser.canGoBack()) browser.goBack() else super.onBackPressed()
    }
    override fun onDestroy() {
        browser.destroy()
        super.onDestroy()
    }
}
// KMK <--
