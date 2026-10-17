package com.plate.launcher.fragments

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap

import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.view.*
import android.webkit.*
import android.widget.*
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.exoplayer.ExoPlayer
import com.plate.launcher.utils.VideoUtils
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.plate.launcher.R
import com.plate.launcher.workshop.data.WorkshopSource
import com.plate.launcher.workshop.data.WorkshopSourceDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class WorkshopFragment : Fragment() {

    companion object {
        // Bug fix — Resource Waste. OkHttpClient used to be created from scratch on
        // every download — every new instance reserves its own connection pool and
        // dispatcher thread pool (officially documented in OkHttp: "Each client holds
        // its own connection pool and thread pool. Reusing connections and threads
        // reduces latency and saves memory"). Downloading several files in the same
        // session used to open a whole new thread pool every time instead of reusing
        // the existing one. Fix: a single shared instance, created only once (lazy),
        // and reused afterward.
        private val sharedHttpClient: okhttp3.OkHttpClient by lazy {
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }

        private const val DOWNLOADS_ROOT    = "YoukiDex"
        private const val WALLPAPERS_FOLDER = "Wallpapers"
        private const val FONTS_FOLDER      = "Fonts"
        // 20s covers real slow-network cases without leaving a hung page
        // spinning for an uncomfortably long time (see onPageStarted's
        // timeout, added for the "page sometimes never loads" bug).
        private const val LOAD_TIMEOUT_MS   = 20_000L
    }

    private lateinit var typeChipGroup: ChipGroup
    private lateinit var sourceChipGroup: ChipGroup
    private lateinit var webView: WebView
    private lateinit var swipeRefresh: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var loadingState: View
    private lateinit var loadingGif: ImageView
    private val loadTimeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pageLoadTimeoutRunnable: Runnable? = null
    private lateinit var emptyState: View
    private lateinit var emptyMessage: TextView
    private lateinit var downloadBar: View
    private lateinit var downloadProgressBar: LinearProgressIndicator
    private lateinit var downloadProgressText: TextView
    private lateinit var menuButton: com.plate.launcher.widgets.LayoutToggleButton

    private lateinit var workshopDao: WorkshopSourceDao

    private var isDownloadsMode = false
    private var isGridLayout = false   // toggle between grid and list
    private var currentWebUrl = ""
    private var currentSources: List<WorkshopSource> = emptyList()
    private var isDesktopMode = true
    private var isAdBlockerEnabled = true

    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    private val MOBILE_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"



    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_workshop, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        workshopDao = WorkshopSourceDao(requireContext().applicationContext)
        bindViews(view)
        setupWebView()
        setupMenu()
        setupTypeChips()
        playLoadingGif()

        // FIX 1: swiping back returns to the previous WebView page instead of kicking you all the way out
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )

        // FIX 2: SwipeRefresh only works when the WebView is at the top of the page (not in the middle!)
        webView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            swipeRefresh.isEnabled = scrollY == 0
        }
    }

    private fun bindViews(v: View) {
        typeChipGroup        = v.findViewById(R.id.workshop_type_chip_group)
        sourceChipGroup      = v.findViewById(R.id.workshop_source_chip_group)
        webView              = v.findViewById(R.id.workshop_webview)
        swipeRefresh         = v.findViewById(R.id.workshop_swipe_refresh)
        recyclerView         = v.findViewById(R.id.workshop_recycler_view)
        loadingState         = v.findViewById(R.id.workshop_loading_state)
        loadingGif           = v.findViewById(R.id.workshop_loading_gif)
        emptyState           = v.findViewById(R.id.workshop_empty_state)
        emptyMessage         = v.findViewById(R.id.workshop_empty_message)
        downloadBar          = v.findViewById(R.id.workshop_download_bar)
        downloadProgressBar  = v.findViewById(R.id.workshop_download_progress)
        downloadProgressText = v.findViewById(R.id.workshop_download_progress_text)
        menuButton           = v.findViewById(R.id.workshop_fab_menu)
    }

    // Resolved issue: Workshop loading spinner. replaces the old CircularProgressIndicator
    // with the same bundled loading GIF used elsewhere (see UserMediaResolver —
    // BetaPreferences' "Change loading media" override applies here too, so a
    // person testing a different GIF sees it consistently across the app rather
    // than just on the QA/EasterEgg screens). Mirrors AvatarDisplay.showGif's
    // API-28 split (ImageDecoder/AnimatedImageDrawable vs. a static first
    // frame pre-28), but reads from a content/resource Uri via ContentResolver
    // rather than a plain file path, since the bundled default lives in
    // res/raw (an android.resource:// Uri) and a person's override is a
    // content:// Uri — neither is a real filesystem path BitmapFactory could
    // open directly on the pre-28 branch.
    private fun playLoadingGif() {
        val ctx = requireContext()
        val selection = com.plate.launcher.utils.UserMediaResolver.get(ctx)
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val source = android.graphics.ImageDecoder.createSource(ctx.contentResolver, selection.uri)
                val drawable = android.graphics.ImageDecoder.decodeDrawable(source) { decoder, _, _ ->
                    decoder.setMemorySizePolicy(android.graphics.ImageDecoder.MEMORY_POLICY_LOW_RAM)
                }
                loadingGif.setImageDrawable(drawable)
                (drawable as? android.graphics.drawable.AnimatedImageDrawable)?.start()
            } else {
                ctx.contentResolver.openInputStream(selection.uri)?.use { stream ->
                    val bmp = android.graphics.BitmapFactory.decodeStream(stream) ?: return
                    loadingGif.setImageBitmap(bmp)
                }
            }
        } catch (e: Exception) {
            // Bundled asset failing to decode would be a packaging bug, not
            // something to crash over — the loading text underneath still
            // gets the point across on its own.
        }
    }

    // ── Layout toggle button ───────────────────────────────────────────────
    private fun setupMenu() {
        // Short tap in Downloads → instant Grid/List toggle
        // Short tap in WebView → options menu
        menuButton.setOnClickListener {
            if (isDownloadsMode) {
                isGridLayout = !isGridLayout
                menuButton.setProgress(if (isGridLayout) 1f else 0f)
                applyLayoutManager()
            } else {
                showWebOptions()
            }
        }

        // Long press → always opens the options menu
        menuButton.setOnLongClickListener {
            showWebOptions()
            true
        }
    }

    private fun showWebOptions() {
        val popup = PopupMenu(requireContext(), menuButton)
        popup.menu.apply {
            add(0, 1, 0, if (isDesktopMode) "Switch to mobile" else "Switch to desktop")
            add(0, 2, 1, if (isAdBlockerEnabled) "Disable ad blocker" else "Enable ad blocker")
            add(0, 3, 2, "Refresh")
            add(0, 4, 3, "Open in browser")
            add(0, 5, 4, "Copy URL")
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> toggleDesktopMode()
                2 -> toggleAdBlocker()
                3 -> { webView.reload(); snack("Refreshing...") }
                4 -> openInBrowser()
                5 -> copyUrl()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    private fun toggleDesktopMode() {
        isDesktopMode = !isDesktopMode
        webView.settings.apply {
            userAgentString     = if (isDesktopMode) DESKTOP_UA else MOBILE_UA
            useWideViewPort     = isDesktopMode
            loadWithOverviewMode = isDesktopMode
        }
        // FIX 3: actually scales the page down via setInitialScale instead of just the UserAgent
        // Desktop = 100% (normal) | Mobile = 0 (lets the WebView decide — usually smaller)
        webView.setInitialScale(if (isDesktopMode) 100 else 0)
        snack(if (isDesktopMode) getString(R.string.desktop_mode) else getString(R.string.mobile_mode))
        webView.reload()
    }

    private fun toggleAdBlocker() {
        isAdBlockerEnabled = !isAdBlockerEnabled
        snack(if (isAdBlockerEnabled) "Ad blocker enabled" else "Ad blocker disabled")
        webView.reload()
    }

    private fun openInBrowser() {
        val url = webView.url?.takeIf { it.isNotBlank() } ?: return
        com.plate.launcher.utils.AppUtils.openUrl(requireContext(), url)
    }

    private fun copyUrl() {
        val url = webView.url?.takeIf { it.isNotBlank() } ?: return
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("url", url))
        snack("URL copied")
    }

    // ── WebView ────────────────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.settings.apply {
            javaScriptEnabled                      = true
            domStorageEnabled                      = true
            databaseEnabled                        = true
            userAgentString                        = DESKTOP_UA
            useWideViewPort                        = true
            loadWithOverviewMode                   = true
            builtInZoomControls                    = true
            displayZoomControls                    = false
            setSupportZoom(true)
            cacheMode                              = WebSettings.LOAD_DEFAULT
            allowContentAccess                     = true
            @Suppress("DEPRECATION") allowFileAccess = true
            mediaPlaybackRequiresUserGesture       = false
            mixedContentMode                       = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            javaScriptCanOpenWindowsAutomatically  = true
            setSupportMultipleWindows(false)
        }

        webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? {
                if (isAdBlockerEnabled && isAdUrl(request.url.toString()))
                    return WebResourceResponse("text/plain", "utf-8",
                        java.io.ByteArrayInputStream(ByteArray(0)))
                return null
            }

            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                if (isAdBlockerEnabled && isAdUrl(url)) return true
                if (url.startsWith("intent://") || url.startsWith("market://")) {
                    return try { startActivity(Intent.parseUri(url, Intent.URI_INTENT_SCHEME)); true }
                    catch (e: Exception) { true }
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                loadingState.visibility = View.VISIBLE
                webView.visibility      = View.INVISIBLE
                // Patched: "الصفحة أحيانًا ما تحمل" — page sometimes never finishes
                // loading. onPageFinished only fires once the WebView considers
                // the page's network activity settled. If the ad blocker's
                // shouldInterceptRequest above blocks something the page is
                // actually waiting on to finish rendering (a false-positive
                // domain match, or a genuinely broken/slow ad script the page
                // never gives up on), that settle point can simply never
                // arrive — onPageFinished never fires, onReceivedError never
                // fires either (nothing actually errored, it just never
                // finishes), and the loading GIF is left spinning forever with
                // no way out. This timeout is the backstop: if loading is
                // still showing after LOAD_TIMEOUT_MS, stop waiting and show
                // the retry state instead of hanging indefinitely.
                pageLoadTimeoutRunnable?.let { loadTimeoutHandler.removeCallbacks(it) }
                val runnable = Runnable {
                    if (!isAdded) return@Runnable
                    webView.stopLoading()
                    loadingState.visibility = View.GONE
                    showEmpty(getString(R.string.workshop_page_load_timeout))
                }
                pageLoadTimeoutRunnable = runnable
                loadTimeoutHandler.postDelayed(runnable, LOAD_TIMEOUT_MS)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                pageLoadTimeoutRunnable?.let { loadTimeoutHandler.removeCallbacks(it) }
                loadingState.visibility = View.GONE
                webView.visibility      = View.VISIBLE
                swipeRefresh.isRefreshing = false
                if (isAdBlockerEnabled) view.evaluateJavascript(jsAdBlocker(), null)
                view.evaluateJavascript(jsBlobInterceptor(), null)
                // FIX: "Switch to desktop" only ever changed the UserAgent
                // string + WebView-level useWideViewPort/setInitialScale.
                // Neither of those overrides a site's own
                // <meta name="viewport" content="width=device-width"> tag —
                // that tag is what actually controls layout width, and most
                // real sites have one, so the page kept rendering at phone
                // width/DPI regardless of what the WebView-level settings
                // said (this is documented Android WebView behavior: the
                // page's own <meta viewport> tag takes precedence — see
                // developer.android.com/develop/ui/views/layout/webapps/targeting).
                // Force a real desktop-width viewport by directly rewriting
                // (or creating, if the page has none) that meta tag to a
                // fixed desktop-class width, only while Desktop mode is on.
                if (isDesktopMode) view.evaluateJavascript(jsDesktopViewport(), null)
                if (url?.contains("fonts.google.com") == true)
                    view.evaluateJavascript(jsGoogleFonts(), null)
                if (url?.contains("moewalls.com") == true)
                    view.evaluateJavascript(jsVideoSiteHook(), null)
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    pageLoadTimeoutRunnable?.let { loadTimeoutHandler.removeCallbacks(it) }
                    loadingState.visibility = View.GONE
                    showEmpty("Could not load page")
                }
            }

            override fun onReceivedSslError(
                view: WebView, handler: SslErrorHandler, error: android.net.http.SslError
            ) = handler.proceed()
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) =
                request.grant(request.resources)
            override fun onJsAlert(view: WebView, url: String?, msg: String?,
                result: JsResult): Boolean { result.cancel(); return true }
            override fun onJsConfirm(view: WebView, url: String?, msg: String?,
                result: JsResult): Boolean { result.cancel(); return true }
        }

        swipeRefresh.setOnRefreshListener {
            if (!isDownloadsMode && currentWebUrl.isNotBlank()) webView.reload()
            else swipeRefresh.isRefreshing = false
        }

        webView.addJavascriptInterface(BlobInterface(), "YoukiBlob")

        webView.setDownloadListener { url, _, cd, mime, _ ->
            when {
                url.startsWith("data:") -> handleDataUrl(url, cd, mime)
                url.startsWith("blob:") -> webView.evaluateJavascript("""
                    fetch('$url').then(r=>r.blob()).then(blob=>{
                        var fr=new FileReader();
                        fr.onloadend=function(){
                            YoukiBlob.receive(fr.result.split(',')[1],
                                'dl_'+Date.now()+'.'+ ('$mime'.split('/')[1]||'bin'));
                        };
                        fr.readAsDataURL(blob);
                    }).catch(e=>YoukiBlob.error(e.toString()));
                """.trimIndent(), null)
                else -> handleHttpDownload(url, cd, mime)
            }
        }
    }

    // ── JS injections ──────────────────────────────────────────────────────
    /**
     * Rewrites (or creates) the page's <meta name="viewport"> tag to a
     * fixed desktop-class width instead of whatever the site itself
     * requests (almost always "width=device-width" — the phone-sized
     * default). That tag, not the WebView's own useWideViewPort/
     * setInitialScale settings, is what actually controls the page's
     * rendered layout width — see the fix note at this function's call
     * site in onPageFinished for why the WebView-level settings alone
     * weren't enough ("desktop mode" was flipping the UserAgent but the
     * page still laid out at phone width/DPI).
     *
     * 1280 matches this app's own DESKTOP_UA string (see its own comment —
     * chosen to match a common real desktop browser width) so the
     * UserAgent and the actual rendered width agree, instead of a site
     * seeing a desktop UA but a phone-width layout (or vice versa), which
     * is exactly the kind of mismatch that makes sites serve broken/
     * inconsistent layouts.
     */
    private fun jsDesktopViewport() = """
        (function(){
            var DESKTOP_WIDTH = 1280;
            var m = document.querySelector('meta[name="viewport"]');
            if (!m) {
                m = document.createElement('meta');
                m.name = 'viewport';
                document.head.appendChild(m);
            }
            m.setAttribute('content', 'width=' + DESKTOP_WIDTH + ', initial-scale=1');
        })();
    """.trimIndent()

    private fun jsAdBlocker() = """
        (function(){
            if(window.__ykAd__)return; window.__ykAd__=true;

            var HOST = location.hostname;
            var isWallpaperSite = HOST.includes('moewalls.com');

            // ── 1) Remove ad elements from the DOM ─────────────────────────────
            var s=['ins.adsbygoogle','div[id*="google_ads"]','iframe[src*="doubleclick"]',
                   'iframe[src*="googlesyndication"]','div[id*="taboola"]','div[class*="taboola"]',
                   'div[id*="outbrain"]','[id*="adsense"]','[class*="adsense"]',
                   'div[id*="AdDiv"]','div[class*="ad-container"]','div[class*="ad-wrap"]',
                   'div[id*="sponsor"]','div[class*="sponsor"]','#onesignal-slidedown-container',
                   '.popup-overlay','#popup','#modal-ad','.modal-backdrop:not([data-keep])'];
            function rm(){s.forEach(function(q){try{document.querySelectorAll(q)
                .forEach(function(e){e.remove();});}catch(e){}});}
            rm();
            // page slow to load / feels sluggish — fixed below: this used to call rm()
            // on every single DOM mutation anywhere on the page
            // (new MutationObserver(rm).observe(...)). Ad-heavy sites mutate
            // the DOM constantly — lazy-loaded images, ad refreshes, tracking
            // pixels — so on a busy page this could fire the full 15-selector
            // querySelectorAll scan dozens of times per second, competing with
            // the page's own rendering work for the same main thread and
            // making the page feel like it's crawling (or, combined with the
            // load-timeout fix in WorkshopFragment.kt, contributing to pages
            // that felt like they'd never finish). Debouncing to run at most
            // once every 300ms keeps the same end result — ad elements still
            // get removed shortly after they appear — without re-scanning the
            // whole page on every individual mutation event.
            var rmTimer=null;
            function rmDebounced(){
                if(rmTimer)return;
                rmTimer=setTimeout(function(){rmTimer=null;rm();},300);
            }
            new MutationObserver(rmDebounced).observe(document.body,{childList:true,subtree:true});

            // ── 2) Block popups ────────────────────────────
            var _open = window.open;
            window.open = function(u, n, sp){
                try{
                    if(!u || u==='' || u==='about:blank') return null;
                    var h = new URL(u, location.href).hostname;
                    // Same domain = allowed
                    if(h === HOST) return _open(u, n, sp);
                    // Wallpaper sites = allowed (for the download button)
                    if(isWallpaperSite) return _open(u, n, sp);
                }catch(e){}
                return null;
            };

            // ── 3) Block auto redirect ─────────────────────
            var _assign   = location.assign.bind(location);
            var _replace  = location.replace.bind(location);
            function isSameSite(u){
                try{ return new URL(u, location.href).hostname === HOST; }catch(e){ return true; }
            }
            Object.defineProperty(location, 'assign', { value: function(u){
                if(isSameSite(u) || isWallpaperSite) _assign(u); }, configurable:true });
            Object.defineProperty(location, 'replace', { value: function(u){
                if(isSameSite(u) || isWallpaperSite) _replace(u); }, configurable:true });

            // ── 4) Block links that open a new tab for ads ──────────────────
            document.addEventListener('click', function(e){
                var el = e.target.closest('a[target="_blank"]');
                if(!el) return;
                var href = el.href || '';
                if(!href || href.startsWith('javascript')) return;
                try{
                    var h = new URL(href).hostname;
                    if(h === HOST) return; // Same domain = allowed
                    if(isWallpaperSite) return; // Wallpaper sites = allowed
                    // External domain = we block it
                    e.preventDefault(); e.stopImmediatePropagation();
                }catch(e){}
            }, true);

            // ── 5) Block onbeforeunload and pushState popups ──────────────────────
            window.onbeforeunload = null;
            Object.defineProperty(window, 'onbeforeunload', { set:function(){}, get:function(){ return null; }, configurable:true });

        })();
    """.trimIndent()

    private fun jsBlobInterceptor() = """
        (function(){
            if(window.__ykBlob__)return; window.__ykBlob__=true;
            var _c=URL.createObjectURL.bind(URL);
            URL.createObjectURL=function(obj){
                var url=_c(obj);
                if(obj instanceof Blob){
                    var m=obj.type||'';
                    // Only fonts and explicit files — no automatic video (that comes from DownloadListener)
                    var isFont=m.includes('font')||m.includes('ttf')||m.includes('otf');
                    var isZip=m.includes('zip');
                    if((isFont||isZip)&&obj.size>1024){
                        var ext=(m.split('/')[1]||'bin').split(';')[0];
                        var fr=new FileReader();
                        fr.onloadend=function(){
                            if(fr.result&&fr.result.includes(','))
                                try{YoukiBlob.receive(fr.result.split(',')[1],'yk_'+Date.now()+'.'+ext);}catch(e){}
                        };
                        fr.readAsDataURL(obj);
                    }
                }
                return url;};
        })();
    """.trimIndent()

    private fun jsGoogleFonts() = """
        (function(){
            if(window.__ykGF__)return; window.__ykGF__=true;
            var _f=window.fetch.bind(window);
            window.fetch=function(input,init){
                var url=typeof input==='string'?input:(input&&input.url)||'';
                return _f(input,init).then(function(resp){
                    if(url.includes('fonts.gstatic.com')||url.includes('.ttf')||url.includes('.otf')){
                        return resp.clone().arrayBuffer().then(function(buf){
                            var b64=btoa(new Uint8Array(buf).reduce(function(d,b){return d+String.fromCharCode(b);},''));
                            var name=url.split('/').pop().split('?')[0]||('gf_'+Date.now()+'.ttf');
                            try{YoukiBlob.receive(b64,name);}catch(e){}
                            return resp;});
                    }
                    return resp;});};
            var _xo=XMLHttpRequest.prototype.open, _xs=XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open=function(m,u){
                if(typeof u==='string'&&(u.includes('fonts.gstatic.com')||u.includes('.ttf')||u.includes('.otf')))
                    this.__fu__=u;
                return _xo.apply(this,arguments);};
            XMLHttpRequest.prototype.send=function(){
                if(this.__fu__){
                    var fu=this.__fu__; this.responseType='arraybuffer';
                    this.addEventListener('load',function(){
                        try{var b64=btoa(new Uint8Array(this.response).reduce(function(d,b){return d+String.fromCharCode(b);},''));
                            YoukiBlob.receive(b64,fu.split('/').pop().split('?')[0]||'gf.ttf');}catch(e){}});}
                return _xs.apply(this,arguments);};
        })();
    """.trimIndent()

    // ── Video site hook — MoeWalls ────────────────────────────────────────
    //
    // How this site works:
    //
    // MoeWalls:
    //   The video link is in <video src="/wp-content/uploads/preview/YEAR/NAME.webm">
    //   The "DOWNLOAD WALLPAPER" button is <a href="#"> that runs JS. No link on the button.
    //   Fix: intercept the button click → read video.src → send it to Kotlin via the interface.
    //
    // Note: anchor.click() from JS does not trigger setDownloadListener in Android WebView.
    //   The correct approach: send the link straight to Kotlin via @JavascriptInterface.
    //
    private fun jsVideoSiteHook() = """
        (function(){
            if(window.__ykVid4__)return; window.__ykVid4__=true;

            var HOST=location.hostname;
            var isMoeWalls=HOST.includes('moewalls.com');

            function buildTitle(){
                return (document.title||'wallpaper')
                    .replace(/\s*[-|].*${'$'}/,'').replace(/[^\w\s]/g,'')
                    .trim().replace(/\s+/g,'_').substring(0,60) || 'wallpaper';
            }

            function sendToKotlin(url, filename){
                if(!url || url.__ykSent__) return;
                url.__ykSent__ = true;
                try{ YoukiBlob.onVideoUrl(url, filename||'wallpaper'); }
                catch(e){ console.warn('YK:', e); }
            }

            // ══════════════ MoeWalls ══════════════════════════════════════════
            // The site fires an AJAX request after the download button is clicked — intercept the response
            function setupMoeWalls(){
                // Intercept fetch
                if(!window.__ykFetchHooked__){
                    window.__ykFetchHooked__ = true;
                    var _f = window.fetch.bind(window);
                    window.fetch = function(input, init){
                        var url = typeof input==='string' ? input : (input&&input.url)||'';
                        return _f(input, init).then(function(resp){
                            // Any response containing a video link in JSON
                            if(url.includes('admin-ajax') || url.includes('wp-json') || url.includes('download')){
                                resp.clone().text().then(function(text){
                                    try{
                                        var match = text.match(/"(https?:[^"]+\.(?:mp4|webm|mov)[^"]*)"/i);
                                        if(match) sendToKotlin(match[1], buildTitle()+'.'+match[1].split('.').pop().split('?')[0]);
                                    }catch(e){}
                                });
                            }
                            return resp;
                        });
                    };
                }

                // Intercept XHR
                if(!window.__ykXHRHooked__){
                    window.__ykXHRHooked__ = true;
                    var _open = XMLHttpRequest.prototype.open;
                    var _send = XMLHttpRequest.prototype.send;
                    XMLHttpRequest.prototype.open = function(m, u){
                        this.__ykUrl__ = u||'';
                        return _open.apply(this, arguments);
                    };
                    XMLHttpRequest.prototype.send = function(){
                        var u = this.__ykUrl__||'';
                        if(u.includes('admin-ajax') || u.includes('wp-json') || u.includes('download')){
                            this.addEventListener('load', function(){
                                try{
                                    var text = this.responseText||'';
                                    var match = text.match(/"(https?:[^"]+\.(?:mp4|webm|mov)[^"]*)"/i);
                                    if(match) sendToKotlin(match[1], buildTitle()+'.'+match[1].split('.').pop().split('?')[0]);
                                }catch(e){}
                            });
                        }
                        return _send.apply(this, arguments);
                    };
                }

                // Also intercept navigations to direct video links
                document.addEventListener('click', function(e){
                    var el = e.target.closest('a[href]');
                    if(!el) return;
                    var href = el.href||'';
                    var ext = (href.split('?')[0].split('.').pop()||'').toLowerCase();
                    if(['mp4','webm','mov','mkv'].includes(ext)){
                        e.preventDefault(); e.stopImmediatePropagation();
                        sendToKotlin(href, buildTitle()+'.'+ext);
                    }
                }, true);
            }

            if(isMoeWalls) setupMoeWalls();
        })();
    """.trimIndent()

    // ── textparse helpers (was Rust) ────────────────────────────────────────

    private val adDomains = arrayOf(
        "googlesyndication.com", "doubleclick.net", "googletagmanager.com",
        "googletagservices.com", "adservice.google.com", "pagead2.googlesyndication.com",
        "taboola.com", "outbrain.com", "popads.net", "popcash.net",
        "propellerads.com", "adsterra.com", "exoclick.com", "trafficjunky.net",
        "juicyads.com", "adskeeper.co.uk", "mgid.com", "revcontent.com",
        "onesignal.com", "pushcrew.com", "pushassist.com", "izooto.com",
        "ad.plus", "ads.yahoo.com", "adsymptotic.com", "criteo.com",
        "smartadserver.com", "cdn.syndication.twimg.com",
    )

    private fun isAdUrl(url: String): Boolean {
        val lower = url.lowercase()
        return adDomains.any { lower.contains(it) }
    }

    private fun sanitizeFilenameBase(base: String): String {
        val cleaned = base.map { c ->
            if (c in charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')) '_' else c
        }.joinToString("")
        val capped = cleaned.trim().take(80)
        return capped.trim().ifEmpty { "file" }
    }

    private fun mimeToExt(m: String) = when {
        m.contains("mp4")                       -> "mp4"
        m.contains("webm")                       -> "webm"
        m.contains("quicktime")                  -> "mov"
        m.contains("zip")                        -> "zip"
        m.contains("ttf") || m.contains("font")  -> "ttf"
        m.contains("otf")                        -> "otf"
        else                                     -> ""
    }

    private fun nameFromCd(cd: String): String {
        val lower = cd.lowercase()
        if (!lower.contains("filename=")) return ""
        val idx = lower.indexOf("filename=")
        val after = cd.substring(idx + "filename=".length)
        val trimmed = after.trim('"', ' ', '\'')
        val beforeSemi = trimmed.substringBefore(';')
        val afterLastSlash = beforeSemi.substringAfterLast('/', beforeSemi)
        val beforeLastDot = afterLastSlash.substringBeforeLast('.', afterLastSlash)
        val result = StringBuilder(beforeLastDot.length)
        var lastWasSep = false
        for (c in beforeLastDot) {
            if (c == '_' || c == '-') {
                if (!lastWasSep) result.append(' ')
                lastWasSep = true
            } else {
                result.append(c)
                lastWasSep = false
            }
        }
        return result.toString().trim()
    }

    private fun fmtSize(b: Long): String {
        if (b < 1024) return "$b B"
        val kb = b / 1024.0
        if (kb < 1024.0) return "${formatTrimmed(kb, 1)} KB"
        val mb = kb / 1024.0
        return "${formatTrimmed(mb, 2)} MB"
    }

    /** Rounds [value] to [decimals] places and drops trailing zeros (matches Java DecimalFormat "#.#"/"#.##"). */
    private fun formatTrimmed(value: Double, decimals: Int): String {
        val factor = Math.pow(10.0, decimals.toDouble())
        val rounded = Math.round(value * factor) / factor
        var s = "%.${decimals}f".format(rounded)
        s = s.trimEnd('0').trimEnd('.')
        return s.ifEmpty { "0" }.let { if (it == "-") "0" else it }
    }

        // ── Blob interface ─────────────────────────────────────────────────────
    inner class BlobInterface {
        @JavascriptInterface
        fun receive(base64: String, fileName: String) {
            viewLifecycleOwner.lifecycleScope.launch {
                setProgress(true, getString(R.string.processing), -1)
                val result = withContext(Dispatchers.IO) {
                    try {
                        val bytes = Base64.decode(base64, Base64.DEFAULT)
                        if (bytes.isEmpty()) return@withContext null
                        val ext = fileName.substringAfterLast('.', "bin").lowercase()
                        val isFont = ext in listOf("ttf","otf","woff","woff2","zip")
                        val out = uniqueFile(if (isFont) fontsDir() else wallpapersDir(),
                            fileName.substringBeforeLast('.'), ext)
                        out.writeBytes(bytes)
                        if (isFont) installFont(out, out.nameWithoutExtension)
                        out
                    } catch (e: Exception) { null }
                }
                if (!isAdded) return@launch
                requireActivity().runOnUiThread {
                    setProgress(false, "", 0)
                    snack(if (result != null) "Saved: ${result.name}" else "Failed")
                }
            }
        }
        @JavascriptInterface
        fun error(msg: String) {
            if (!isAdded) return
            requireActivity().runOnUiThread { setProgress(false, "", 0); snack("Failed: $msg") }
        }

        @JavascriptInterface
        fun onProgress(step: String, pct: Int) {
            if (!isAdded) return
            requireActivity().runOnUiThread { setProgress(true, step, pct) }
        }

        /**
         * Called from JS when it finds a direct video link (MoeWalls).
         * We download inside the WebView itself using fetch() — with all cookies and
         * session, exactly like a real browser, without OkHttp.
         */
        @JavascriptInterface
        fun onVideoUrl(url: String, filename: String) {
            if (!isAdded || url.isBlank()) return
            // Pass straight to OkHttp — no base64 in RAM
            // cookies/session aren't needed for MoeWalls (the link is direct from the CDN)
            requireActivity().runOnUiThread {
                val cd = if (filename.isNotBlank()) "filename=\"$filename\"" else ""
                val mime = when {
                    filename.endsWith(".webm", true) -> "video/webm"
                    filename.endsWith(".mp4",  true) -> "video/mp4"
                    else -> "video/mp4"
                }
                handleHttpDownload(url, cd, mime)
            }
        }
    }

    // ── Download handlers ──────────────────────────────────────────────────
    private fun handleDataUrl(url: String, cd: String, mime: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            setProgress(true, getString(R.string.processing), -1)
            val result = withContext(Dispatchers.IO) {
                try {
                    val idx = url.indexOf(','); if (idx < 0) return@withContext null
                    val bytes = Base64.decode(url.substring(idx + 1), Base64.DEFAULT)
                    val ext = mimeToExt(mime).ifEmpty { "bin" }
                    val isFont = mime.contains("font") || mime.contains("ttf") || mime.contains("otf") || mime.contains("zip") || ext == "zip"
                    val out = uniqueFile(if (isFont) fontsDir() else wallpapersDir(),
                        nameFromCd(cd).ifBlank { "file_${System.currentTimeMillis()}" }, ext)
                    out.writeBytes(bytes)
                    out
                } catch (e: Exception) { null }
            }
            if (!isAdded) return@launch
            requireActivity().runOnUiThread {
                setProgress(false, "", 0)
                snack(if (result != null) getString(R.string.saved_file, result.name) else getString(R.string.failed))
            }
        }
    }

    private fun handleHttpDownload(url: String, cd: String, mime: String) {
        if (url.isBlank()) return

        // Read everything from the WebView here — Main Thread
        val pageUrl = webView.url ?: ""
        val title = nameFromCd(cd).ifBlank {
            webView.title?.substringBefore("|")?.trim()?.takeIf { it.length in 2..80 }
                ?: url.substringAfterLast('/').substringBeforeLast('.')
        }
        val validExts = setOf("mp4","webm","mov","mkv","avi","gif","jpg","jpeg","png","ttf","otf","woff","woff2","zip")
        val rawExt  = url.substringBefore('?').substringAfterLast('.', "").lowercase().take(5)
        val ext     = if (rawExt in validExts) rawExt
                      else mimeToExt(mime).ifEmpty { "mp4" }
        val isFont  = mime.contains("font") || ext in listOf("ttf","otf","woff","woff2","zip")
        val destDir = if (isFont) fontsDir() else wallpapersDir()

        setProgress(true, getString(R.string.downloading), -1)

        viewLifecycleOwner.lifecycleScope.launch {
            val result: File? = withContext(Dispatchers.IO) {
                val tempFile = File(destDir, "youkidex_tmp_${System.currentTimeMillis()}")
                try {
                    val referer = pageUrl.ifBlank {
                        runCatching {
                            val u = java.net.URL(url)
                            "${u.protocol}://${u.host}/"
                        }.getOrElse { "" }
                    }
                    // Fetch cookies from the WebView to work around the HTTP 307 redirect issue
                    val cookieStr = runCatching {
                        android.webkit.CookieManager.getInstance().getCookie(url) ?: ""
                    }.getOrElse { "" }

                    val client = sharedHttpClient
                    val request = okhttp3.Request.Builder()
                        .url(url)
                        .header("User-Agent", DESKTOP_UA)
                        .header("Accept", "*/*")
                        .apply { if (referer.isNotBlank()) header("Referer", referer) }
                        .apply { if (cookieStr.isNotBlank()) header("Cookie", cookieStr) }
                        .build()

                    client.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            if (isAdded) requireActivity().runOnUiThread {
                                snack("Failed: HTTP ${resp.code}")
                            }
                            return@withContext null
                        }
                        val body  = resp.body ?: return@withContext null
                        val total = body.contentLength().takeIf { it > 0 }
                        var written = 0L

                        FileOutputStream(tempFile).use { out ->
                            val buf = ByteArray(256 * 1024)
                            var n: Int
                            while (body.byteStream().read(buf).also { n = it } != -1) {
                                out.write(buf, 0, n)
                                written += n
                                if (total != null && isAdded) {
                                    val pct = (written * 90 / total).toInt().coerceIn(5, 95)
                                    requireActivity().runOnUiThread {
                                        setProgress(true, "${written/1024}KB / ${total/1024}KB", pct)
                                    }
                                }
                            }
                        }

                        val finalFile = uniqueFile(destDir, title, ext)
                        tempFile.renameTo(finalFile)
                        if (isFont) installFont(finalFile, finalFile.nameWithoutExtension)
                        finalFile
                    }
                } catch (e: Exception) {
                    tempFile.delete()
                    if (isAdded) requireActivity().runOnUiThread { snack("Failed: ${e.message}") }
                    null
                }
            }
            if (!isAdded) return@launch
            requireActivity().runOnUiThread {
                setProgress(false, "", 0)
                if (result != null) snack("Downloaded: ${result.name}")
            }
        }
    }

    // ── Ad domains ─────────────────────────────────────────────────────────
    
    // ── Chips ──────────────────────────────────────────────────────────────
    private fun setupTypeChips() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Seed the default sources if the database is empty
            try {
                if (workshopDao.count() == 0) {
                    workshopDao.insertAll(com.plate.launcher.workshop.data.DefaultSources.list())
                }
            } catch (_: Exception) {}

            val types = try {
                workshopDao.getAllEnabled().map { it.contentType }.distinct()
            } catch (e: Exception) { listOf("WALLPAPER","FONT") }
            if (!isAdded) return@launch
            typeChipGroup.removeAllViews()
            val ordered = listOf("WALLPAPER","FONT","PLUGIN").filter { it in types } +
                          types.filter { it !in listOf("WALLPAPER","FONT","PLUGIN") }
            ordered.forEachIndexed { i, t ->
                typeChipGroup.addView(Chip(requireContext()).apply {
                    text = typeLabel(t); isCheckable = true
                    isChecked = i == 0; id = View.generateViewId(); tag = t
                })
            }
            loadSources(ordered.firstOrNull() ?: "WALLPAPER")
            typeChipGroup.setOnCheckedStateChangeListener { g, ids ->
                val chip = ids.firstOrNull()?.let { g.findViewById<Chip>(it) } ?: return@setOnCheckedStateChangeListener
                loadSources(chip.tag as? String ?: return@setOnCheckedStateChangeListener)
            }
        }
    }

    private fun typeLabel(t: String) = when(t) {
        "WALLPAPER" -> "Wallpapers"; "FONT" -> "Fonts"; "PLUGIN" -> "Plugins"; else -> t
    }

    private fun loadSources(type: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val sources = try {
                workshopDao.getAllEnabled().filter { it.contentType == type }
            } catch (e: Exception) { emptyList() }
            if (!isAdded) return@launch
            currentSources = sources
            buildSourceChips(sources)
        }
    }

    private fun buildSourceChips(sources: List<WorkshopSource>) {
        sourceChipGroup.removeAllViews()
        // Downloads chip
        sourceChipGroup.addView(Chip(requireContext()).apply {
            text = "Downloads"; isCheckable = true
            id = View.generateViewId(); tag = "downloads"
        })
        // Source chips (no File Manager chip anymore)
        sources.forEach { s ->
            sourceChipGroup.addView(Chip(requireContext()).apply {
                text = s.displayName; isCheckable = true
                id = View.generateViewId(); tag = s
            })
        }

        // Select first source chip if available, else Downloads
        val firstChip = sourceChipGroup.getChildAt(1) as? Chip
            ?: sourceChipGroup.getChildAt(0) as? Chip
        firstChip?.let { chip ->
            chip.isChecked = true
            when (chip.tag) {
                "downloads"       -> showDownloads()
                is WorkshopSource -> openSource(chip.tag as WorkshopSource)
            }
        }

        sourceChipGroup.setOnCheckedStateChangeListener { g, ids ->
            val chip = ids.firstOrNull()?.let { g.findViewById<Chip>(it) } ?: return@setOnCheckedStateChangeListener
            when (chip.tag) {
                "downloads"       -> showDownloads()
                is WorkshopSource -> openSource(chip.tag as WorkshopSource)
            }
        }
    }

    // ── Navigation ─────────────────────────────────────────────────────────
    private fun openSource(source: WorkshopSource) {
        isDownloadsMode = false
        currentWebUrl = source.browseUrlTemplate?.takeIf { it.isNotBlank() }
            ?: source.searchUrlTemplate.replace(Regex("%s.*"), "").trimEnd('/')
        swipeRefresh.visibility = View.GONE
        emptyState.visibility   = View.GONE
        webView.visibility      = View.INVISIBLE
        loadingState.visibility = View.VISIBLE
        menuButton.visibility   = View.VISIBLE
        webView.loadUrl(currentWebUrl)
    }

    private fun showDownloads() {
        isDownloadsMode = true
        webView.visibility      = View.GONE
        loadingState.visibility = View.GONE
        emptyState.visibility   = View.GONE
        menuButton.visibility   = View.VISIBLE
        menuButton.setProgress(if (isGridLayout) 1f else 0f, animate = false)

        // ── Read the downloads folder: Downloads/YoukiDex/ ─────────────────────
        val files = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            DOWNLOADS_ROOT
        ).let { root ->
            if (root.exists())
                root.walkTopDown()
                    .filter { it.isFile }
                    .sortedByDescending { it.lastModified() }
                    .toList()
            else emptyList()
        }

        if (files.isEmpty()) {
            swipeRefresh.visibility = View.GONE
            showEmpty("Nothing downloaded yet.")
        } else {
            emptyState.visibility   = View.GONE
            swipeRefresh.visibility = View.VISIBLE
            recyclerView.visibility = View.VISIBLE
            applyLayoutManager()
            recyclerView.adapter = FilesAdapter(files)
        }
    }

    private fun applyLayoutManager() {
        val margin = if (isGridLayout) 6 else 12
        recyclerView.layoutManager = if (isGridLayout)
            GridLayoutManager(requireContext(), 2)
        else
            LinearLayoutManager(requireContext())
        // Rebuild the adapter so the viewType changes (list ↔ grid)
        (recyclerView.adapter as? FilesAdapter)?.let {
            recyclerView.adapter = FilesAdapter(it.currentItems())
        }
    }

    private fun showEmpty(msg: String) {
        emptyState.visibility   = View.VISIBLE
        emptyMessage.text       = msg
        recyclerView.visibility = View.GONE
        webView.visibility      = View.GONE
    }

    // ── Downloads adapter ──────────────────────────────────────────────────
    inner class FilesAdapter(files: List<File>) :
        RecyclerView.Adapter<FilesAdapter.VH>() {

        private val items: MutableList<File> = files.toMutableList()

        // Two types: 0 = list, 1 = grid
        override fun getItemViewType(position: Int) = if (isGridLayout) 1 else 0

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView  = v.findViewById(R.id.download_item_thumbnail)
            val icon: TextView    = v.findViewById(R.id.download_item_icon)
            val name: TextView    = v.findViewById(R.id.download_item_name)
            val size: TextView    = v.findViewById(R.id.download_item_size)
            val folder: TextView  = v.findViewById(R.id.download_item_folder)
            val menuBtn: com.google.android.material.button.MaterialButton = v.findViewById(R.id.download_item_menu)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val layout = if (t == 1) R.layout.item_downloaded_file_grid
                         else        R.layout.item_downloaded_file
            return VH(LayoutInflater.from(p.context).inflate(layout, p, false))
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val f   = items[pos]
            val ext = f.extension.lowercase()
            val isVideo = ext in listOf("mp4", "webm", "mov", "mkv")
            val isFont  = ext in listOf("ttf", "otf", "woff", "woff2")
            val isZip   = ext == "zip"

            h.icon.text = when {
                isVideo -> "▶"; isFont -> "F"; isZip -> "Z"; else -> "?"
            }
            h.name.text   = f.nameWithoutExtension
            h.size.text   = fmtSize(f.length())
            h.folder.text = f.parentFile?.name ?: ""
            h.thumb.visibility = View.GONE
            h.icon.visibility  = View.VISIBLE

            // Thumbnail for the video
            if (isVideo) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val bmp: Bitmap? = withContext(Dispatchers.IO) {
                        // FIX: use 'use' instead of 'let' so MediaMetadataRetriever closes
                        // automatically even if an exception occurs — this was the cause of the
                        // TimeoutException, because finalize() used to wait for
                        // native_finalize() after 30 seconds
                        // FIX: we use ThumbnailLoader.extractVideoThumbnailSafely via the same
                        // FutureTask mechanism instead of withTimeoutOrNull —
                        // withTimeoutOrNull doesn't stop a blocking native call.
                        try { com.plate.launcher.utils.ThumbnailLoader.loadVideoFrameSync(f) }
                        catch (_: Exception) { null }
                    }
                    if (bmp != null && isAdded) {
                        h.thumb.setImageBitmap(bmp)
                        h.thumb.visibility = View.VISIBLE
                        h.icon.visibility  = View.GONE
                    }
                }
            }

            // ── Click: play internally with ExoPlayer or open the file ──────────────
            h.itemView.setOnClickListener {
                when {
                    isVideo -> playVideoInternal(f)
                    isFont  -> shareFile(f)
                    else    -> shareFile(f)
                }
            }

            // The options button right next to the item
            h.menuBtn.setOnClickListener {
                showFileOptions(f, pos, h.menuBtn) // pass the button itself as the anchor
            }
            // Long press on the whole card opens the same menu
            h.itemView.setOnLongClickListener {
                showFileOptions(f, pos, h.menuBtn)
                true
            }
        }

        fun removeAt(pos: Int) {
            items.removeAt(pos)
            notifyItemRemoved(pos)
        }

        fun currentItems(): List<File> = items.toList()
    }

    // ── Unified video player — opens UnifiedVideoPlayerActivity via VideoUtils ─
    //  Just one line — all the logic lives in VideoUtils.play()
    private fun playVideoInternal(f: File) = VideoUtils.play(requireContext(), f)

    private fun openWithExternal(f: File, uri: android.net.Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("title", f.nameWithoutExtension)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.open_with)))
        } catch (e: Exception) {
            snack(getString(R.string.no_video_player_installed))
        }
    }

    // ── Share / open file ──────────────────────────────────────────────────
    private fun shareFile(f: File) {
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(), "${requireContext().packageName}.provider", f)
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_VIEW).apply {
                    data = uri
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, getString(R.string.open_with)))
        } catch (e: Exception) { snack(getString(R.string.cannot_open_file)) }
    }

    // ── Options menu on long press ──────────────────────────────────
    private fun showFileOptions(f: File, pos: Int, anchor: View) {
        val ext = f.extension.lowercase()
        val isVideo = ext in listOf("mp4", "webm", "mov", "mkv")

        val popup = PopupMenu(requireContext(), anchor) // anchor = the button itself
        popup.menu.apply {
            if (isVideo) add(0, 1, 0, getString(R.string.play))
            add(0, 2, 1, getString(R.string.rename))
            add(0, 3, 2, getString(R.string.share))
            add(0, 4, 3, getString(R.string.copy_path))
            add(0, 5, 4, getString(R.string.delete))
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> playVideoInternal(f)
                2 -> showRenameDialog(f, pos)
                3 -> shareFileExternal(f)
                4 -> copyPath(f)
                5 -> confirmDelete(f, pos)
            }
            true
        }
        popup.show()
    }

    // ── Rename ──────────────────────────────────────────────────────
    private fun showRenameDialog(f: File, pos: Int) {
        // TextInputLayout = normal borders with no long underline
        val ctx = requireContext()
        val inputLayout = com.google.android.material.textfield.TextInputLayout(
            ctx,
            null,
            com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            hint = getString(R.string.file_name)
            boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
        }
        val input = com.google.android.material.textfield.TextInputEditText(ctx).apply {
            setText(f.nameWithoutExtension)
            selectAll()
            isSingleLine = true
        }
        inputLayout.addView(input)

        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.rename))
            .setView(inputLayout)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isBlank()) { snack(getString(R.string.name_is_empty)); return@setPositiveButton }
                val newFile = File(f.parent, "$newName.${f.extension}")
                if (f.renameTo(newFile)) {
                    snack(getString(R.string.renamed_successfully))
                    showDownloads()
                } else {
                    snack(getString(R.string.rename_failed))
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ── External share ──────────────────────────────────────────────────────
    private fun shareFileExternal(f: File) {
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(), "${requireContext().packageName}.provider", f)
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, getString(R.string.share_via)))
        } catch (e: Exception) { snack(getString(R.string.share_failed)) }
    }

    // ── Copy path ────────────────────────────────────────────────────────
    private fun copyPath(f: File) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("path", f.absolutePath))
        snack(getString(R.string.path_copied))
    }

    // ── Confirm delete ───────────────────────────────────────────────────────
    private fun confirmDelete(f: File, pos: Int) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.delete_file))
            .setMessage(getString(R.string.delete_file_confirm_message, f.name))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                if (f.delete()) {
                    (recyclerView.adapter as? FilesAdapter)?.removeAt(pos)
                    snack(getString(R.string.deleted_successfully))
                } else {
                    snack(getString(R.string.delete_failed))
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ── Font install ───────────────────────────────────────────────────────
    internal fun installFont(file: File, name: String): File? {
        // If the file is a ZIP, extract it first and pull out the fonts
        if (file.extension.lowercase() == "zip") {
            return installFontFromZip(file)
        }
        return installFontFile(file, name)
    }

    /** Extracts a ZIP and installs the first valid font (ttf/otf), deleting the rest */
    private fun installFontFromZip(zipFile: File): File? {
        val fontsDestDir = fontsDir()
        var installedFont: File? = null

        try {
            val zip = java.util.zip.ZipFile(zipFile)
            val fontEntries = zip.entries().asSequence()
                .filter { !it.isDirectory }
                .filter { it.name.substringAfterLast('.').lowercase() in setOf("ttf", "otf") }
                .toList()

            if (fontEntries.isEmpty()) {
                snack(getString(R.string.no_fonts_in_archive))
                zip.close()
                return null
            }

            // Extract the first valid font
            val entry = fontEntries.first()
            val fontName = entry.name.substringAfterLast('/').substringBeforeLast('.')
            val tempFont = File(fontsDestDir, "${fontName}_tmp.${entry.name.substringAfterLast('.')}")

            zip.getInputStream(entry).use { input ->
                tempFont.outputStream().use { output -> input.copyTo(output) }
            }
            zip.close()

            // Install the extracted font
            installedFont = installFontFile(tempFont, fontName)

            // Delete the temp font if it differs from the installed one
            if (tempFont.exists() && tempFont.canonicalPath != installedFont?.canonicalPath) {
                tempFont.delete()
            }

        } catch (e: Exception) {
            snack(getString(R.string.extraction_failed, e.message ?: ""))
        } finally {
            // Always delete the ZIP file when done
            zipFile.delete()
        }

        return installedFont
    }

    /** Installs a direct font file (ttf/otf) */
    private fun installFontFile(file: File, name: String): File? = try {
        val tf     = android.graphics.Typeface.createFromFile(file)
        val script = com.plate.launcher.utils.FontManager.detectScript(tf)
        val dir    = File(requireContext().filesDir, "fonts/${script.name.lowercase()}").also {
            it.mkdirs(); it.listFiles()?.forEach { f -> f.delete() }
        }
        val dest = File(dir, "$name.${file.extension}").also { file.copyTo(it, overwrite = true) }
        com.plate.launcher.utils.FontManager.saveFont(requireContext(), dest.absolutePath, name, script)
        snack(getString(R.string.font_installed, name)); dest
    } catch (e: Exception) { null }

    // ── Progress ───────────────────────────────────────────────────────────
    private fun setProgress(show: Boolean, msg: String, pct: Int) {
        if (!isAdded || view == null) return
        downloadBar.visibility          = if (show) View.VISIBLE else View.GONE
        downloadProgressBar.visibility  = if (show) View.VISIBLE else View.GONE
        downloadProgressText.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        downloadProgressText.text = msg
        if (pct < 0) downloadProgressBar.isIndeterminate = true
        else { downloadProgressBar.isIndeterminate = false; downloadProgressBar.progress = pct.coerceIn(0, 100) }
    }

    // ── Helpers ────────────────────────────────────────────────────────────
    private fun snack(msg: String) = try { Snackbar.make(requireView(), msg, Snackbar.LENGTH_SHORT).show() } catch (e: Exception) {}

    private fun wallpapersDir() = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "$DOWNLOADS_ROOT/$WALLPAPERS_FOLDER"
    ).also { it.mkdirs() }

    private fun fontsDir() = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "$DOWNLOADS_ROOT/$FONTS_FOLDER"
    ).also { it.mkdirs() }

    private fun uniqueFile(dir: File, base: String, ext: String): File {
        val clean = sanitizeFilenameBase(base)
        var f = File(dir, "$clean.$ext"); var n = 1
        while (f.exists()) { f = File(dir, "${clean}_$n.$ext"); n++ }
        return f
    }

    
    
    
    override fun onDestroyView() {
        // Cancel any pending page-load-timeout callback — it captures
        // webView/loadingState/showEmpty, all of which are about to become
        // invalid once this Fragment's view is torn down.
        pageLoadTimeoutRunnable?.let { loadTimeoutHandler.removeCallbacks(it) }
        // Stop the loading GIF's animation before the view is torn down —
        // AnimatedImageDrawable keeps decoding frames on a background
        // thread as long as it's running, and there's no reason for that
        // to keep going once this Fragment's view no longer exists.
        try {
            (loadingGif.drawable as? android.graphics.drawable.AnimatedImageDrawable)?.stop()
        } catch (e: Exception) {}
        // Memory Leak / Crash risk — fixed below: the correct order for destroying the WebView matters:
        // 1) Stop any in-progress load (stopLoading) — prevents onPageFinished/JS callback
        //    from running on the WebView after the Fragment has died (this sometimes caused
        //    "IllegalStateException: WebView.destroy() called while still attached")
        // 2) Detach it from the parent ViewGroup first — destroy() alone doesn't detach
        //    the View from the view tree, leaving a dangling reference that GC might pick up late
        // 3) Explicitly clear the JavascriptInterface — addJavascriptInterface keeps
        //    a strong reference to BlobInterface (an inner class that implicitly holds
        //    a reference to the entire Fragment) even after the WebView itself is destroyed
        // 4) Finally, destroy()
        try {
            webView.stopLoading()
            webView.removeJavascriptInterface("YoukiBlob")
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.destroy()
        } catch (_: Exception) {}
        super.onDestroyView()
    }
}
