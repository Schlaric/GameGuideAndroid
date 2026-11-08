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
import android.content.res.Configuration
import android.view.View
import android.view.Gravity
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
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

private data class AppTheme(val name: String, val primary: Int, val surface: Int, val border: Int, val softAccent: Int)

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
    private var pendingScrollSave: Runnable? = null
    private var pendingAfterSessionSave: (() -> Unit)? = null
    private val sessionSaveTimeout = Runnable {
        pendingAfterSessionSave?.also { callback ->
            pendingAfterSessionSave = null
            callback()
        }
    }
    private var browserStartupStatus = "Preparing browser…"
    private var coverPickerCallback: ((String) -> Unit)? = null
    private var backButton: Button? = null
    private var walkthroughHeader: LinearLayout? = null
    private var fullscreenButton: Button? = null
    private var isFullscreen = false
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

    private fun currentTheme(): AppTheme = when (prefs.getString("app_theme", "Blue")) {
        "Purple" -> AppTheme("Purple", Color.rgb(155, 89, 220), Color.rgb(43, 31, 55), Color.rgb(91, 62, 116), Color.rgb(75, 48, 96))
        "Green" -> AppTheme("Green", Color.rgb(35, 160, 110), Color.rgb(27, 46, 40), Color.rgb(48, 91, 73), Color.rgb(35, 70, 56))
        "Red" -> AppTheme("Red", Color.rgb(210, 75, 75), Color.rgb(49, 32, 36), Color.rgb(105, 57, 62), Color.rgb(78, 42, 47))
        "Orange" -> AppTheme("Orange", Color.rgb(220, 130, 40), Color.rgb(49, 39, 29), Color.rgb(105, 78, 48), Color.rgb(79, 57, 34))
        "Teal" -> AppTheme("Teal", Color.rgb(20, 155, 160), Color.rgb(26, 44, 48), Color.rgb(45, 88, 91), Color.rgb(33, 68, 71))
        "Pink" -> AppTheme("Pink", Color.rgb(210, 85, 145), Color.rgb(49, 31, 43), Color.rgb(104, 57, 83), Color.rgb(78, 43, 63))
        else -> AppTheme("Blue", Color.rgb(57, 112, 220), Color.rgb(27, 35, 49), Color.rgb(47, 59, 79), Color.rgb(38, 50, 70))
    }

    private fun applyThemeToWindow() {
        val background = Color.rgb(15, 18, 26)
        window.statusBarColor = background
        window.navigationBarColor = background
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyThemeToWindow()
        loadGuides()
        initializeBrowser()
        showLibrary()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Keep the existing Activity and GeckoSession alive when a Fold changes
        // orientation or switches between folded and unfolded screen sizes.
        root?.requestLayout()
        geckoView?.requestLayout()
    }

    private fun setFullscreen(enabled: Boolean) {
        isFullscreen = enabled
        walkthroughHeader?.visibility = if (enabled) View.GONE else View.VISIBLE
        root.setPadding(if (enabled) 0 else dp(8), if (enabled) 0 else dp(4), if (enabled) 0 else dp(8), 0)
        val flags = if (enabled) {
            (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
        } else {
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
        window.decorView.systemUiVisibility = flags
        fullscreenButton?.text = if (enabled) "⛶ Exit" else "⛶ Full"
        root.requestLayout()
        root.requestApplyInsets()
        geckoView?.requestLayout()
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
        // GeckoView session snapshots include scroll position, zoom, form data,
        // and navigation history. Restore them when available; otherwise load fresh.
        val guide = activeGuide
        val savedState = guide?.savedSessionState
        val restored = try {
            savedState?.takeIf { it.isNotBlank() }?.let {
                GeckoSession.SessionState.fromString(it)
            }
        } catch (e: Exception) {
            Log.w("GameGuide", "Saved browser state could not be restored; loading URL instead", e)
            null
        }
        if (restored != null) {
            session.restoreState(restored)
        } else {
            session.loadUri(url)
        }
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
                if (isFullscreen) {
                    view.setPadding(0, 0, 0, 0)
                } else {
                    view.setPadding(
                        side + insets.systemWindowInsetLeft,
                        top + insets.systemWindowInsetTop,
                        side + insets.systemWindowInsetRight,
                        bottom + insets.systemWindowInsetBottom
                    )
                }
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
                if (primary) currentTheme().primary else Color.rgb(43, 52, 69),
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
            hint = "Search any game (Zelda, Mario, etc.)"
            setSingleLine()
        }
        val results = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val resultScroll = ScrollView(this).apply {
            addView(results)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(360)
            )
        }
        val searchButton = styledButton("Search online", true) {
            val query = queryInput.text.toString().trim()
            if (query.isBlank()) {
                Toast.makeText(this, "Enter a game title first.", Toast.LENGTH_SHORT).show()
            } else {
                results.removeAllViews()
                results.addView(text("Searching game databases…", 14f, Color.LTGRAY))
                Thread {
                    val matches = mutableListOf<Triple<String, String, Bitmap>>()
                    val seen = mutableSetOf<String>()

                    // SteamGridDB: search game titles, then fetch portrait game grids.
                    // The API key is stored only in this app's private preferences.
                    val apiKey = prefs.getString("steamgriddb_api_key", "").orEmpty().trim()
                    if (apiKey.isNotBlank()) {
                        try {
                            val encoded = URLEncoder.encode(query, "UTF-8")
                            val searchConnection = (URL("https://www.steamgriddb.com/api/v2/search/autocomplete/$encoded").openConnection() as HttpURLConnection).apply {
                                connectTimeout = 9000
                                readTimeout = 9000
                                setRequestProperty("Authorization", "Bearer $apiKey")
                                setRequestProperty("User-Agent", "GameGuideAndroid/2.14")
                            }
                            val searchJson = searchConnection.inputStream.bufferedReader().use { it.readText() }
                            searchConnection.disconnect()
                            val games = JSONObject(searchJson).optJSONArray("data") ?: JSONArray()
                            for (i in 0 until minOf(games.length(), 8)) {
                                val game = games.optJSONObject(i) ?: continue
                                val gameId = game.optLong("id")
                                val gameName = game.optString("name", "Game")
                                if (gameId <= 0) continue
                                val gridConnection = (URL("https://www.steamgriddb.com/api/v2/grids/game/$gameId?dimensions=600x900&types=static&nsfw=false").openConnection() as HttpURLConnection).apply {
                                    connectTimeout = 9000
                                    readTimeout = 9000
                                    setRequestProperty("Authorization", "Bearer $apiKey")
                                    setRequestProperty("User-Agent", "GameGuideAndroid/2.14")
                                }
                                val gridJson = gridConnection.inputStream.bufferedReader().use { it.readText() }
                                gridConnection.disconnect()
                                val grids = JSONObject(gridJson).optJSONArray("data") ?: JSONArray()
                                for (j in 0 until minOf(grids.length(), 5)) {
                                    val grid = grids.optJSONObject(j) ?: continue
                                    val art = grid.optString("thumb").ifBlank { grid.optString("url") }
                                    if (art.isBlank() || !seen.add(art)) continue
                                    val bitmap = fetchBitmap(art)
                                    if (bitmap != null) matches.add(Triple(gameName, art, bitmap))
                                }
                                if (matches.size >= 12) break
                            }
                        } catch (e: Exception) {
                            Log.w("GameGuide", "SteamGridDB cover search failed", e)
                        }
                    }

                    // Search using the actual title and reject unrelated page titles.
                    // This stops "Ocarina of Time" returning the musical instrument.
                    try {
                        val queryWords = query.lowercase().split(Regex("[^a-z0-9]+"))
                            .filter { it.length > 2 && it !in setOf("the", "and", "for", "game", "edition") }.toSet()
                        val wikiQuery = URLEncoder.encode("\"$query\" (video game OR videogame OR game)", "UTF-8")
                        val wikiUrl = URL(
                            "https://en.wikipedia.org/w/api.php?action=query&generator=search" +
                                "&gsrsearch=$wikiQuery&gsrnamespace=0&gsrlimit=20" +
                                "&prop=pageimages&piprop=thumbnail&pithumbsize=420&format=json&origin=*"
                        )
                        val connection = wikiUrl.openConnection() as HttpURLConnection
                        connection.connectTimeout = 9000
                        connection.readTimeout = 9000
                        connection.setRequestProperty("User-Agent", "GameGuideAndroid/2.12 (Android app)")
                        val json = connection.inputStream.bufferedReader().use { it.readText() }
                        connection.disconnect()
                        val pages = JSONObject(json).optJSONObject("query")?.optJSONObject("pages")
                        val candidates = mutableListOf<Pair<Double, JSONObject>>()
                        if (pages != null) {
                            val keys = pages.keys()
                            while (keys.hasNext()) {
                                val page = pages.getJSONObject(keys.next())
                                val title = page.optString("title", "")
                                val normalized = title.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
                                val titleWords = normalized.split(" ").filter { it.isNotBlank() }.toSet()
                                val overlap = if (queryWords.isEmpty()) 0.0 else
                                    queryWords.count { it in titleWords }.toDouble() / queryWords.size
                                val exactPhrase = normalized.contains(query.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim())
                                val score = overlap + if (exactPhrase) 1.0 else 0.0
                                if (overlap >= 0.60) candidates.add(score to page)
                            }
                        }
                        candidates.sortByDescending { it.first }
                        for ((_, page) in candidates.take(8)) {
                            val title = page.optString("title", "Game")
                            val art = page.optJSONObject("thumbnail")?.optString("source").orEmpty()
                            if (art.isNotBlank() && seen.add(art)) {
                                val bitmap = fetchBitmap(art)
                                if (bitmap != null) matches.add(Triple(title, art, bitmap))
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("GameGuide", "Wikipedia cover search failed", e)
                    }

                    // Keep Steam as a second source for PC games and alternate artwork.
                    try {
                        val encoded = URLEncoder.encode(query, "UTF-8")
                        val api = URL("https://store.steampowered.com/api/storesearch/?term=$encoded&l=english&cc=us")
                        val connection = api.openConnection() as HttpURLConnection
                        connection.connectTimeout = 8000
                        connection.readTimeout = 8000
                        connection.setRequestProperty("User-Agent", "GameGuideAndroid/2.11")
                        val json = connection.inputStream.bufferedReader().use { it.readText() }
                        connection.disconnect()
                        val items = JSONObject(json).optJSONArray("items") ?: JSONArray()
                        val queryWords = query.lowercase().split(Regex("[^a-z0-9]+"))
                            .filter { it.length > 2 && it !in setOf("the", "and", "for", "game", "edition") }.toSet()
                        val ranked = (0 until items.length()).mapNotNull { i ->
                            val item = items.getJSONObject(i)
                            val name = item.optString("name", "Unknown game")
                            val words = name.lowercase().split(Regex("[^a-z0-9]+")).toSet()
                            val overlap = if (queryWords.isEmpty()) 0.0 else
                                queryWords.count { it in words }.toDouble() / queryWords.size
                            if (overlap < 0.60) null else Triple(overlap, item, name)
                        }.sortedByDescending { it.first }
                        for ((_, item, name) in ranked.take(8)) {
                            val id = item.optLong("id")
                            if (id <= 0) continue
                            var art = "https://cdn.akamai.steamstatic.com/steam/apps/$id/library_600x900.jpg"
                            if (seen.add(art)) {
                                var bitmap = fetchBitmap(art)
                                if (bitmap == null) {
                                    art = "https://cdn.akamai.steamstatic.com/steam/apps/$id/header.jpg"
                                    bitmap = fetchBitmap(art)
                                }
                                if (bitmap != null) matches.add(Triple(name, art, bitmap))
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("GameGuide", "Steam cover search failed", e)
                    }

                    runOnUiThread {
                        results.removeAllViews()
                        if (matches.isEmpty()) {
                            results.addView(text("No artwork found. Try a more specific title or use your gallery.", 14f, Color.LTGRAY))
                        } else {
                            matches.forEach { (name, art, bitmap) ->
                                val itemRow = LinearLayout(this).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    gravity = Gravity.CENTER_VERTICAL
                                    background = rounded(currentTheme().surface, 12)
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

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("My games", 22f, Color.WHITE).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(text("•••", 22f, Color.rgb(145, 157, 178)).apply {
                gravity = Gravity.CENTER
                background = rounded(Color.rgb(27, 35, 49), 12)
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(40))
                setOnClickListener { showCoverSettings() }
            })
        }
        root.addView(topBar)
        root.addView(text("${guides.size} ${if (guides.size == 1) "walkthrough" else "walkthroughs"} saved", 12f, Color.rgb(145, 157, 178)).apply {
            setPadding(0, dp(3), 0, dp(14))
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
                background = rounded(currentTheme().surface, 20, currentTheme().border)
                setPadding(dp(24), dp(34), dp(24), dp(34))
                addView(text("＋", 36f, currentTheme().primary).apply { gravity = Gravity.CENTER })
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
                background = rounded(currentTheme().surface, 18, currentTheme().border)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            val cover = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(currentTheme().softAccent, 12)
                clipToOutline = true
                if (guide.coverUri.isNotBlank()) {
                    loadCoverInto(guide.coverUri, this)
                } else {
                    setImageResource(android.R.drawable.ic_menu_gallery)
                    setColorFilter(currentTheme().primary)
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

    private fun showCoverSettings() {
        val keyInput = EditText(this).apply {
            hint = "SteamGridDB API key"
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("steamgriddb_api_key", "").orEmpty())
        }
        val help = text("Create a free account at SteamGridDB, then generate an API key in Profile → Preferences. Paste it here; it stays on this device and is not added to the public GitHub project.", 13f, Color.LTGRAY)
        lateinit var settingsDialog: AlertDialog
        val themeHeading = text("Theme", 14f, Color.WHITE).apply {
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(18), 0, dp(4))
        }
        val themeButton = styledButton("Choose theme: ${currentTheme().name}") {
            settingsDialog.dismiss()
            showThemePicker()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(help)
            addView(keyInput)
            addView(themeHeading)
            addView(themeButton)
        }
        settingsDialog = AlertDialog.Builder(this)
            .setTitle("Cover art settings")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear key") { _, _ ->
                prefs.edit().remove("steamgriddb_api_key").apply()
                Toast.makeText(this, "SteamGridDB key cleared.", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("Save key") { _, _ ->
                prefs.edit().putString("steamgriddb_api_key", keyInput.text.toString().trim()).apply()
                Toast.makeText(this, "SteamGridDB key saved on this device.", Toast.LENGTH_SHORT).show()
            }
            .create()
        settingsDialog.show()
    }

    private fun showThemePicker() {
        val names = arrayOf("Blue", "Purple", "Green", "Red", "Orange", "Teal", "Pink")
        val selected = names.indexOf(currentTheme().name).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Choose a theme")
            .setSingleChoiceItems(names, selected) { dialog, which ->
                prefs.edit().putString("app_theme", names[which]).apply()
                applyThemeToWindow()
                dialog.dismiss()
                showLibrary()
                Toast.makeText(this, "${names[which]} theme applied.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
            background = rounded(currentTheme().softAccent, 10)
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
        root = baseLayout().apply {
            setPadding(dp(8), dp(4), dp(8), 0)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(6))
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // Put the Library action first; browser history navigation is clearly
        // labeled separately so it cannot be mistaken for returning to Library.
        toolbar.addView(styledButton("Library") { saveCurrentSpot { showLibrary() } }.apply {
            textSize = 11f
            minHeight = dp(36)
            setPadding(dp(8), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                rightMargin = dp(5)
            }
        })
        backButton = styledButton("Previous webpage") {
            geckoSession?.goBack()
        }.apply {
            textSize = 11f
            minHeight = dp(36)
            setPadding(dp(8), 0, dp(8), 0)
            isEnabled = false
            alpha = 0.45f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                rightMargin = dp(5)
            }
        }
        toolbar.addView(backButton)
        toolbar.addView(styledButton("↻ Reset") {
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Reset walkthrough?")
                .setMessage("Return to the original walkthrough page for ${guide.title}?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset") { _, _ ->
                    guide.savedUrl = ""
                    guide.savedSessionState = ""
                    guide.scrollY = 0
                    saveGuides()
                    geckoSession?.purgeHistory()
                    geckoSession?.loadUri(guide.url)
                }.show()
        }.apply {
            textSize = 11f
            minHeight = dp(36)
            setPadding(dp(8), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                leftMargin = dp(5)
            }
        })
        fullscreenButton = styledButton("⛶ Full") {
            setFullscreen(!isFullscreen)
        }.apply {
            textSize = 11f
            minHeight = dp(36)
            setPadding(dp(8), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                leftMargin = dp(5)
            }
        }
        toolbar.addView(fullscreenButton)
        header.addView(toolbar)
        walkthroughHeader = header
        root.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(2) })

        val view = GeckoView(this)
        val session = GeckoSession()
        geckoView = view
        geckoSession = session
        pendingLoadUrl = if (guide.savedUrl.isNotBlank()) guide.savedUrl else guide.url

        session.setNavigationDelegate(object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                backButton?.isEnabled = canGoBack
                backButton?.alpha = if (canGoBack) 1f else 0.45f
            }
        })

        session.setScrollDelegate(object : GeckoSession.ScrollDelegate {
            override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) {
                // Keep the latest real content position separately from Gecko's
                // asynchronous session snapshot. Ignore callbacks while we are
                // applying the saved position during a page restore.
                guide.scrollY = scrollY.coerceAtLeast(0)
                pendingScrollSave?.let { mainHandler.removeCallbacks(it) }
                val save = Runnable { saveGuides(); pendingScrollSave = null }
                pendingScrollSave = save
                mainHandler.postDelayed(save, 300)
            }
        })

        session.setProgressDelegate(object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                if (guide.savedUrl.isNotBlank() && guide.savedUrl != url) {
                    guide.scrollY = 0
                    guide.savedSessionState = ""
                }
                guide.savedUrl = url
                saveGuides()
                android.util.Log.d("GameGuide", "Page started: $url")
            }

            override fun onSessionStateChange(session: GeckoSession, sessionState: GeckoSession.SessionState) {
                guide.savedSessionState = sessionState.toString()
                saveGuides()
                // Complete Library/Back navigation only after Gecko has delivered
                // the refreshed snapshot, including its native scroll position.
                pendingAfterSessionSave?.also { callback ->
                    pendingAfterSessionSave = null
                    mainHandler.removeCallbacks(sessionSaveTimeout)
                    callback()
                }
            }
        })
        session.open(runtime!!)
        view.setSession(session)

        val browserFrame = FrameLayout(this)
        browserFrame.addView(view, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.addView(browserFrame, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)

        flushPendingLoad()
    }

    private fun saveCurrentSpot(afterSave: (() -> Unit)? = null) {
        val guide = activeGuide ?: run { afterSave?.invoke(); return }
        val session = geckoSession ?: run { afterSave?.invoke(); return }
        // Gecko's own session snapshot is the authoritative restore source:
        // it keeps scroll position, zoom, history, and form state together.
        // Do not also force a separately recorded pixel offset after restore;
        // page reflow (ads/images) can make that second correction inaccurate.
        guide.savedUrl = guide.savedUrl.ifBlank { guide.url }
        pendingScrollSave?.let { mainHandler.removeCallbacks(it) }
        pendingScrollSave = null
        if (afterSave != null) {
            mainHandler.removeCallbacks(sessionSaveTimeout)
            pendingAfterSessionSave = afterSave
        }
        session.flushSessionState()
        if (afterSave != null) mainHandler.postDelayed(sessionSaveTimeout, 1200)
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
        // Android's system Back gesture/button is the unobtrusive fullscreen exit.
        if (isFullscreen) {
            setFullscreen(false)
        } else if (activeGuide != null) {
            saveCurrentSpot { showLibrary() }
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
