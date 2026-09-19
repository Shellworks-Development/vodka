package dev.vodka

import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class LoginActivity : AppCompatActivity() {

  private var lastCookie: String = ""

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val web = WebView(this)
    @Suppress("SetJavaScriptEnabled")
    web.settings.javaScriptEnabled = true
    web.settings.domStorageEnabled = true

    val cookies = CookieManager.getInstance()
    cookies.setAcceptCookie(true)
    cookies.setAcceptThirdPartyCookies(web, true)

    web.webViewClient = object : WebViewClient() {
      override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        if (url.startsWith("roblox-studio-auth:")) {
          finishWith(null, url.removePrefix("roblox-studio-auth:"))
          return true
        }
        return false
      }

      override fun onPageFinished(view: WebView, url: String) {
        val cookie = cookies.getCookie("https://www.roblox.com") ?: return
        if (cookie.contains(".ROBLOSECURITY")) {
          lastCookie = cookie
          if (url.contains("home") || url.contains("return")) {
            finishWith(cookie, null)
          }
        }
      }
    }

    web.loadUrl("https://www.roblox.com/login")
    setContentView(web)
  }

  private fun finishWith(cookie: String?, ticket: String?) {
    val data = Intent()
    if (cookie != null) data.putExtra("cookie", cookie)
    if (ticket != null) data.putExtra("ticket", ticket)
    setResult(RESULT_OK, data)
    finish()
  }
}
