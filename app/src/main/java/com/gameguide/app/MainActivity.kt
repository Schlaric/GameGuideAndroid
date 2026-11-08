package com.gameguide.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

private data class Guide(
    val id: Long,
    var title: String,
    var url: String,
    var savedUrl: String,
    var scrollY: Int
)

class MainActivity : Activity() {
    private val guides = mutableListOf<Guide>()
    private lateinit var root: LinearLayout
    private var webView: WebView? = null
    private var activeGuide: Guide? = null
    private val prefs by lazy { getSharedPreferences("gameguide", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(15, 18, 26)
        window.navigationBarColor = Color.rgb(15, 18, 26)
        loadGuides()
        showLibrary()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun baseLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(15, 18, 26))
            setPadding(dp(18), dp(16), dp(18), dp(12))
        }
    }

    private fun text(value: String, size: Float, color: Int = Color.WHITE): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
        }

    private fun button(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }

    private fun showLibrary() {
        webView?.destroy()
        webView = null
        activeGuide = null
        root = baseLayout()
        root.addView(text("GAMEGUIDE", 13f, Color.rgb(120, 170, 255)))
        root.addView(text("Your walkthrough library", 27f).apply {
            setPadding(0, dp(5), 0, dp(14))
        })
        root.addView(button("+  Add walkthrough") { showAddDialog() })

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        if (guides.isEmpty()) {
            list.addView(text("No games yet. Add an IGN walkthrough URL to get started.", 16f, Color.LTGRAY).apply {
                setPadding(dp(4), dp(20), dp(4), dp(20))
            })
        }
        guides.forEach { guide ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.rgb(29, 35, 48))
                setPadding(dp(14), dp(12), dp(14), dp(10))
            }
            card.addView(text(guide.title, 19f))
            val progress = if (guide.savedUrl.isNotBlank()) "Saved spot available" else "Not started"
            card.addView(text(progress, 13f, Color.LTGRAY).apply {
                setPadding(0, dp(4), 0, dp(8))
            })
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(button(if (guide.savedUrl.isNotBlank()) "Continue" else "Open") {
                openGuide(guide)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            actions.addView(button("Edit") { showEditDialog(guide) })
            actions.addView(button("Remove") {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage("Remove ${guide.title} from your library?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Remove") { _, _ ->
                        guides.remove(guide)
                        saveGuides()
                        showLibrary()
                    }.show()
            })
            card.addView(actions)
            list.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) })
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
    }

    private fun showAddDialog(existing: Guide? = null) {
        val titleInput = EditText(this).apply {
            hint = "Game title"
            setSingleLine()
            setText(existing?.title ?: "")
        }
        val urlInput = EditText(this).apply {
            hint = "https://www.ign.com/wikis/..."
            setSingleLine()
            setText(existing?.url ?: "")
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(titleInput)
            addView(urlInput)
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add walkthrough" else "Edit walkthrough")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val title = titleInput.text.toString().trim()
                        var url = urlInput.text.toString().trim()
                        if (title.isBlank() || url.isBlank()) {
                            Toast.makeText(this, "Enter a game title and URL.", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        if (!url.startsWith("https://") && !url.startsWith("http://")) {
                            url = "https://$url"
                        }
                        if (existing == null) {
                            guides.add(Guide(System.currentTimeMillis(), title, url, "", 0))
                        } else {
                            existing.title = title
                            existing.url = url
                            existing.savedUrl = url
                        }
                        saveGuides()
                        dialog.dismiss()
                        showLibrary()
                    }
                }
                dialog.show()
            }
    }

    private fun showEditDialog(guide: Guide) = showAddDialog(guide)

    private fun openGuide(guide: Guide) {
        activeGuide = guide
        root = baseLayout()
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(button("← Library") { saveCurrentSpot(); showLibrary() })
        header.addView(button("SAVE SPOT") { saveCurrentSpot() })
        header.addView(button("Open in browser") {
            val url = webView?.url ?: guide.url
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        })
        root.addView(header)
        root.addView(text(guide.title, 18f).apply { setPadding(0, dp(5), 0, dp(8)) })

        val browser = WebView(this)
        webView = browser
        browser.settings.javaScriptEnabled = true
        browser.settings.domStorageEnabled = true
        browser.settings.loadsImagesAutomatically = true
        browser.webChromeClient = WebChromeClient()
        browser.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (guide.savedUrl == url && guide.scrollY > 0) {
                    view.postDelayed({ view.scrollTo(0, guide.scrollY) }, 500)
                }
            }
        }
        root.addView(browser, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
        browser.loadUrl(if (guide.savedUrl.isNotBlank()) guide.savedUrl else guide.url)
    }

    private fun saveCurrentSpot() {
        val guide = activeGuide ?: return
        val browser = webView ?: return
        guide.savedUrl = browser.url ?: guide.url
        guide.scrollY = browser.scrollY
        saveGuides()
        Toast.makeText(this, "Spot saved!", Toast.LENGTH_SHORT).show()
    }

    private fun saveGuides() {
        val array = JSONArray()
        guides.forEach { guide ->
            array.put(JSONObject().apply {
                put("id", guide.id)
                put("title", guide.title)
                put("url", guide.url)
                put("savedUrl", guide.savedUrl)
                put("scrollY", guide.scrollY)
            })
        }
        prefs.edit().putString("guides", array.toString()).apply()
    }

    private fun loadGuides() {
        guides.clear()
        val raw = prefs.getString("guides", "[]") ?: "[]"
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                guides.add(Guide(
                    item.optLong("id"),
                    item.optString("title"),
                    item.optString("url"),
                    item.optString("savedUrl"),
                    item.optInt("scrollY")
                ))
            }
        } catch (_: Exception) { }
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        val browser = webView
        if (browser != null && browser.canGoBack()) {
            browser.goBack()
        } else if (activeGuide != null) {
            saveCurrentSpot()
            showLibrary()
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        if (webView != null) saveCurrentSpot()
        super.onPause()
    }
}
