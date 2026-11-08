package com.gameguide.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.content.Intent
import android.view.Gravity
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

private data class Guide(
    val id: Long,
    var title: String,
    var url: String,
    var savedUrl: String,
    var scrollY: Int,
    var savedSessionState: String = "",
    var coverUri: String = ""
)

class MainActivity : Activity() {
    companion object {
        private var runtime: GeckoRuntime? = null
    }

    private val guides = mutableListOf<Guide>()
    private lateinit var root: LinearLayout
    private var geckoView: GeckoView? = null
    private var geckoSession: GeckoSession? = null
    private var activeGuide: Guide? = null
    private var browserReady = false
    private var pendingLoadUrl: String? = null
    private var browserStartupStatus = "Preparing browser…"
    private var coverPickerCallback: ((String) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val browserStartupTimeout = Runnable {
        if (!browserReady) {
            browserReady = true
            browserStartupStatus = "uBlock startup timed out; opening without ad blocking."
            Log.w("GameGuide", browserStartupStatus)
            Toast.makeText(this, browserStartupStatus, Toast.LENGTH_LONG).show()
            flushPendingLoad()
        }
    }
    private val prefs by lazy { getSharedPreferences("gameguide", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(15, 18, 26)
        window.navigationBarColor = Color.rgb(15, 18, 26)
        loadGuides()
        initializeBrowser()
        showLibrary()
    }

    private fun initializeBrowser() {
        try {
            if (runtime == null) runtime = GeckoRuntime.create(this)
            browserStartupStatus = "Installing uBlock Origin…"
            Log.i("GameGuide", "Starting built-in uBlock installation")
            // Never let a stuck WebExtension install prevent the walkthrough itself from opening.
            mainHandler.postDelayed(browserStartupTimeout, 8000)
            runtime!!.webExtensionController
                .ensureBuiltIn("resource://android/assets/ublock/", "uBlock0@raymondhill.net")
                .accept(
                    { extension ->
                        mainHandler.removeCallbacks(browserStartupTimeout)
                        browserReady = true
                        browserStartupStatus = "uBlock is installed and ready."
                        Log.i("GameGuide", "uBlock ready: ${extension?.id ?: "unknown extension"}")
                        runOnUiThread { flushPendingLoad() }
                    },
                    { error ->
                        mainHandler.removeCallbacks(browserStartupTimeout)
                        browserReady = true
                        browserStartupStatus = "uBlock failed to start: ${error?.message ?: "unknown error"}"
                        if (error != null) Log.e("GameGuide", "uBlock installation failed", error)
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                "$browserStartupStatus Pages will open without ad blocking.",
                                Toast.LENGTH_LONG
                            ).show()
                            flushPendingLoad()
                        }
                    }
                )
        } catch (e: Exception) {
            mainHandler.removeCallbacks(browserStartupTimeout)
            browserReady = true
            browserStartupStatus = "uBlock startup error: ${e.message ?: "unknown error"}"
            Log.e("GameGuide", "Browser startup failed; continuing without uBlock", e)
            Toast.makeText(this, "$browserStartupStatus Pages will open without ad blocking.", Toast.LENGTH_LONG).show()
            flushPendingLoad()
        }
    }

    private fun flushPendingLoad() {
        if (!browserReady) return
        val url = pendingLoadUrl ?: return
        val session = geckoSession ?: return
        pendingLoadUrl = null
        // Always start a fresh navigation. Restoring a previously saved
        // GeckoSession state can leave the embedded browser stuck loading.
        activeGuide?.savedSessionState = ""
        session.loadUri(url)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun baseLayout(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.rgb(15, 18, 26))
        val side = dp(18)
        val top = dp(16)
        val bottom = dp(12)
        setPadding(side, top, side, bottom)

        // Android 15 enforces edge-to-edge for apps targeting SDK 35.
        // Keep the library/header below the status bar and above the
        // navigation area instead of letting text draw underneath them.
        if (Build.VERSION.SDK_INT >= 35) {
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(
                    side + insets.systemWindowInsetLeft,
                    top + insets.systemWindowInsetTop,
                    side + insets.systemWindowInsetRight,
                    bottom + insets.systemWindowInsetBottom
                )
                insets
            }
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

    private fun rounded(color: Int, radius: Int, strokeColor: Int = Color.TRANSPARENT): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (strokeColor != Color.TRANSPARENT) setStroke(dp(1), strokeColor)
        }

    private fun styledButton(label: String, primary: Boolean = false, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (primary) Color.WHITE else Color.rgb(205, 215, 232))
            background = rounded(
                if (primary) Color.rgb(57, 112, 220) else Color.rgb(43, 52, 69),
                12
            )
            stateListAnimator = null
            minHeight = dp(42)
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { action() }
        }

    private fun fetchBitmap(url: String): Bitmap? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 7000
                readTimeout = 7000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "GameGuideAndroid/2.9")
            }
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            Log.w("GameGuide", "Cover image download failed: $url", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun loadCoverInto(url: String, target: ImageView) {
        if (url.startsWith("content://") || url.startsWith("file://")) {
            try { target.setImageURI(Uri.parse(url)) } catch (_: Exception) { }
            return
        }
        Thread {
            val bitmap = fetchBitmap(url)
            if (bitmap != null) runOnUiThread {
                if (target.isAttachedToWindow) target.setImageBitmap(bitmap)
            }
        }.start()
    }

    private fun showCoverSearch(onSelected: (String) -> Unit) {
        val queryInput = EditText(this).apply {
            hint = "Search a game title"
            setSingleLine()
        }
        val results = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val resultScroll = ScrollView(this).apply {
            addView(results)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(330)
            )
        }
        val searchButton = styledButton("Search Steam games", true) {
            val query = queryInput.text.toString().trim()
            if (query.isBlank()) {
                Toast.makeText(this, "Enter a game title first.", Toast.LENGTH_SHORT).show()
            } else {
                results.removeAllViews()
                results.addView(text("Searching for games…", 14f, Color.LTGRAY))
                Thread {
                    val matches = mutableListOf<Triple<String, String, Bitmap?>>()
                    try {
                        val encoded = URLEncoder.encode(query, "UTF-8")
                        val api = URL("https://store.steampowered.com/api/storesearch/?term=$encoded&l=english&cc=us")
                        val connection = api.openConnection() as HttpURLConnection
                        connection.connectTimeout = 8000
                        connection.readTimeout = 8000
                        connection.setRequestProperty("User-Agent", "GameGuideAndroid/2.9")
                        val json = connection.inputStream.bufferedReader().use { it.readText() }
                        connection.disconnect()
                        val items = JSONObject(json).optJSONArray("items") ?: JSONArray()
                        for (i in 0 until minOf(items.length(), 8)) {
                            val item = items.getJSONObject(i)
                            val id = item.optLong("id")
                            val name = item.optString("name", "Unknown game")
                            if (id <= 0) continue
                            val base = "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/$id/"
                            var art = base + "library_600x900.jpg"
                            var bitmap = fetchBitmap(art)
                            if (bitmap == null) {
                                art = "https://cdn.akamai.steamstatic.com/steam/apps/$id/header.jpg"
                                bitmap = fetchBitmap(art)
                            }
                            if (bitmap != null) matches.add(Triple(name, art, bitmap))
                        }
                    } catch (e: Exception) {
                        Log.e("GameGuide", "Cover art search failed", e)
                    }
                    runOnUiThread {
                        results.removeAllViews()
                        if (matches.isEmpty()) {
                            results.addView(text("No artwork found. Try another title or use your gallery.", 14f, Color.LTGRAY))
                        } else {
                            matches.forEach { (name, art, bitmap) ->
                                val itemRow = LinearLayout(this).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    gravity = Gravity.CENTER_VERTICAL
                                    background = rounded(Color.rgb(27, 35, 49), 12)
                                    setPadding(dp(8), dp(8), dp(8), dp(8))
                                    isClickable = true
                                    isFocusable = true
                                    setOnClickListener {
                                        onSelected(art)
                                        Toast.makeText(this@MainActivity, "Cover art selected.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                itemRow.addView(ImageView(this).apply {
                                    setImageBitmap(bitmap)
                                    scaleType = ImageView.ScaleType.CENTER_CROP
                                    layoutParams = LinearLayout.LayoutParams(dp(62), dp(86))
                                })
                                itemRow.addView(text(name, 15f).apply {
                                    setPadding(dp(12), 0, 0, 0)
                                })
                                results.addView(itemRow, LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                                ).apply { bottomMargin = dp(8) })
                            }
                        }
                    }
                }.start()
            }
        }
        val searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
            addView(queryInput)
            addView(searchButton)
            addView(resultScroll)
        }
        AlertDialog.Builder(this)
            .setTitle("Find game cover art")
            .setView(searchBox)
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showLibrary() {
        geckoView?.releaseSession()
        geckoSession?.close()
        geckoView = null
        geckoSession = null
        activeGuide = null
        root = baseLayout()
        root.setPadding(dp(18), dp(18), dp(18), dp(12))

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("GG", 15f, Color.WHITE).apply {
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                background = rounded(Color.rgb(57, 112, 220), 12)
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(11), 0, 0, 0)
                addView(text("GAMEGUIDE", 13f, Color.rgb(125, 170, 255)).apply {
                    typeface = Typeface.DEFAULT_BOLD
                    letterSpacing = 0.12f
                })
                addView(text("YOUR GAME LIBRARY", 10f, Color.rgb(145, 157, 178)).apply {
                    letterSpacing = 0.08f
                })
            })
        }
        root.addView(brand)
        root.addView(text("Pick up where you left off.", 25f).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(22), 0, dp(4))
        })
        root.addView(text("${guides.size} ${if (guides.size == 1) "WALKTHROUGH" else "WALKTHROUGHS"} SAVED", 11f, Color.rgb(145, 157, 178)).apply {
            letterSpacing = 0.08f
            setPadding(0, 0, 0, dp(16))
        })
        root.addView(styledButton("+   Add a game", true) { showAddDialog() }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
                bottomMargin = dp(16)
            }
        })

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(8))
        }
        if (guides.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rounded(Color.rgb(25, 32, 45), 20, Color.rgb(48, 61, 82))
                setPadding(dp(24), dp(34), dp(24), dp(34))
                addView(text("＋", 36f, Color.rgb(125, 170, 255)).apply { gravity = Gravity.CENTER })
                addView(text("Your library starts here", 19f).apply {
                    gravity = Gravity.CENTER
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, dp(8), 0, dp(5))
                })
                addView(text("Add a game and its walkthrough. You can add cover art from your gallery.", 14f, Color.rgb(160, 172, 192)).apply {
                    gravity = Gravity.CENTER
                })
            }
            list.addView(empty)
        }
        guides.forEach { guide ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(Color.rgb(27, 35, 49), 18, Color.rgb(47, 59, 79))
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            val cover = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(Color.rgb(43, 58, 83), 12)
                clipToOutline = true
                if (guide.coverUri.isNotBlank()) {
                    loadCoverInto(guide.coverUri, this)
                } else {
                    setImageResource(android.R.drawable.ic_menu_gallery)
                    setColorFilter(Color.rgb(155, 181, 225))
                }
                layoutParams = LinearLayout.LayoutParams(dp(82), dp(112))
            }
            card.addView(cover)
            val details = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(13), 0, 0, 0)
            }
            details.addView(text(guide.title, 17f).apply {
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 2
            })
            val hasSpot = guide.savedUrl.isNotBlank()
            details.addView(text(if (hasSpot) "●  SAVED SPOT" else "○  NOT STARTED", 10f,
                if (hasSpot) Color.rgb(105, 213, 161) else Color.rgb(157, 169, 190)).apply {
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.06f
                setPadding(0, dp(7), 0, dp(11))
            })
            details.addView(styledButton(if (hasSpot) "Continue  →" else "Open walkthrough", true) {
                openGuide(guide)
            })
            val tools = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(5), 0, 0)
                addView(styledButton("Edit", false) { showEditDialog(guide) })
                addView(styledButton("Remove", false) {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage("Remove ${guide.title} from your library?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Remove") { _, _ ->
                            guides.remove(guide)
                            saveGuides()
                            showLibrary()
                        }.show()
                }.apply { setPadding(dp(8), 0, dp(8), 0) }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(6) })
            }
            details.addView(tools)
            card.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
        var selectedCoverUri = existing?.coverUri ?: ""
        val coverPreview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(Color.rgb(38, 50, 70), 10)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(dp(76), dp(104)).apply {
                topMargin = dp(10)
                bottomMargin = dp(8)
            }
            if (selectedCoverUri.isNotBlank()) {
                loadCoverInto(selectedCoverUri, this)
            } else setImageResource(android.R.drawable.ic_menu_gallery)
        }
        val searchCoverButton = styledButton("Search for cover art online") {
            showCoverSearch { art ->
                selectedCoverUri = art
                loadCoverInto(art, coverPreview)
            }
        }
        val coverButton = styledButton("Choose cover art from gallery") {
            coverPickerCallback = { uri ->
                selectedCoverUri = uri
                loadCoverInto(uri, coverPreview)
            }
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            startActivityForResult(pick, 501)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(titleInput)
            addView(urlInput)
            addView(coverPreview)
            addView(searchCoverButton)
            addView(coverButton)
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
                        if (!url.startsWith("https://") && !url.startsWith("http://")) url = "https://$url"
                        if (existing == null) guides.add(Guide(System.currentTimeMillis(), title, url, "", 0, coverUri = selectedCoverUri))
                        else {
                            existing.title = title
                            existing.url = url
                            existing.savedUrl = ""
                            existing.scrollY = 0
                            existing.savedSessionState = ""
                            existing.coverUri = selectedCoverUri
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
            val url = guide.savedUrl.ifBlank { guide.url }
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        })
        root.addView(header)
        root.addView(text(guide.title, 18f).apply { setPadding(0, dp(5), 0, dp(4)) })
        val pageStatus = text(browserStartupStatus, 12f, Color.LTGRAY).apply {
            setPadding(0, 0, 0, dp(6))
        }
        root.addView(pageStatus)

        val view = GeckoView(this)
        val session = GeckoSession()
        geckoView = view
        geckoSession = session
        pendingLoadUrl = if (guide.savedUrl.isNotBlank()) guide.savedUrl else guide.url

        session.setContentDelegate(object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                pageStatus.text = title?.takeIf { it.isNotBlank() } ?: "Page loaded (no title)"
                android.util.Log.d("GameGuide", "Page title: $title")
            }
        })
        session.setProgressDelegate(object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                guide.savedUrl = url
                saveGuides()
                pageStatus.text = "Loading: $url"
                android.util.Log.d("GameGuide", "Page started: $url")
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                pageStatus.text = if (success) "Page load finished. If the area below is blank, the site rendered no visible content." else "Page load failed. Check connection or site compatibility."
                android.util.Log.d("GameGuide", "Page stopped; success=$success")
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                if (progress < 100) pageStatus.text = "Loading page… $progress%"
            }

            override fun onSessionStateChange(session: GeckoSession, sessionState: GeckoSession.SessionState) {
                guide.savedSessionState = sessionState.toString()
                saveGuides()
            }
        })
        session.open(runtime!!)
        view.setSession(session)

        root.addView(view, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)

        flushPendingLoad()
    }

    private fun saveCurrentSpot() {
        val guide = activeGuide ?: return
        val session = geckoSession ?: return
        if (guide.savedUrl.isBlank()) guide.savedUrl = guide.url
        saveGuides()
        Toast.makeText(this, "Spot saved!", Toast.LENGTH_SHORT).show()
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 501 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { }
            coverPickerCallback?.invoke(uri.toString())
            coverPickerCallback = null
        }
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
                put("savedSessionState", guide.savedSessionState)
                put("coverUri", guide.coverUri)
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
                    item.optInt("scrollY"),
                    item.optString("savedSessionState", ""),
                    item.optString("coverUri", "")
                ))
            }
        } catch (_: Exception) { }
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        if (activeGuide != null) {
            saveCurrentSpot()
            showLibrary()
        } else super.onBackPressed()
    }

    override fun onPause() {
        if (geckoSession != null) saveCurrentSpot()
        super.onPause()
    }

    override fun onDestroy() {
        geckoView?.releaseSession()
        geckoSession?.close()
        super.onDestroy()
    }
}
