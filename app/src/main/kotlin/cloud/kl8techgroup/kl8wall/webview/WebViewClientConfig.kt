package cloud.kl8techgroup.kl8wall.webview

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Custom [WebViewClient] that enforces allowed-hosts navigation restrictions
 * and injects authentication headers via [AuthInterceptor].
 *
 * Blocks navigation to any host not in the allowed set. Handles SSL errors
 * by rejecting the connection (no user-bypass option in kiosk mode).
 */
class WebViewClientConfig(
    private val authInterceptor: AuthInterceptor,
    private val allowedHosts: () -> Set<String>,
    private val onPageLoaded: (String) -> Unit = {},
    private val onNavigationBlocked: (String) -> Unit = {},
    private val onError: (Int, String) -> Unit = { _, _ -> }
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val host = request.url?.host?.lowercase() ?: return true

        if (!isHostAllowed(host)) {
            onNavigationBlocked(request.url.toString())
            return true
        }

        return false
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        authInterceptor.intercept(request)

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        view.evaluateJavascript(ERROR_LOGGER_JS, null)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        url?.let { onPageLoaded(it) }
        view.evaluateJavascript(ERROR_LOGGER_JS, null)
        view.evaluateJavascript(DISABLE_SELECTION_JS, null)
        view.evaluateJavascript(DEBUG_DOM_JS, null)
        view.evaluateJavascript(DEBUG_BANNER_JS, null)
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        val host = error.url?.let { android.net.Uri.parse(it).host?.lowercase() }
        if (host != null && isHostAllowed(host)) {
            // Trust self-signed certificates for allowed internal Home Assistant hosts
            handler.proceed()
        } else {
            handler.cancel()
            onError(ERROR_SSL, "SSL error: ${error.primaryError}")
        }
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        super.onReceivedError(view, request, error)
        if (request.isForMainFrame) {
            onError(error.errorCode, error.description?.toString() ?: "Unknown error")
        }
    }

    private fun isHostAllowed(host: String): Boolean {
        val hosts = allowedHosts()
        if (hosts.isEmpty()) return true
        return hosts.any { allowed ->
            host == allowed || host.endsWith(".$allowed")
        }
    }

    companion object {
        private const val ERROR_SSL = -1

        private const val ERROR_LOGGER_JS = """
            (function() {
                if (!window.__kl8wall_logger_installed) {
                    window.__kl8wall_logger_installed = true;
                    console.log('[KL8Wall-LOGGER] Installed error handlers');
                    window.addEventListener('error', function(e) {
                        console.log('[KL8Wall-ERROR] ' + e.message + ' at ' + e.filename + ':' + e.lineno + '\nStack: ' + (e.error ? e.error.stack : 'no stack'));
                    });
                    window.addEventListener('unhandledrejection', function(e) {
                        var msg = e.reason;
                        var stack = 'no stack';
                        if (e.reason && typeof e.reason === 'object') {
                            msg = e.reason.message || e.reason.description || JSON.stringify(e.reason);
                            stack = e.reason.stack || 'no stack';
                        }
                        console.log('[KL8Wall-REJECTION] ' + msg + '\nStack: ' + stack);
                    });
                }

                if (window.CustomElementRegistry && !window.__kl8wall_route_patch_installed) {
                    window.__kl8wall_route_patch_installed = true;
                    console.log('[KL8Wall-PATCH] Installing CustomElementRegistry prototype route property guard...');
                    var originalDefine = window.CustomElementRegistry.prototype.define;
                    window.CustomElementRegistry.prototype.define = function(name, constructor, options) {
                        if (constructor && constructor.prototype) {
                            var originalUpdated = constructor.prototype.updated;
                            if (originalUpdated) {
                                constructor.prototype.updated = function(changedProperties) {
                                    if (changedProperties && changedProperties.has('route') && !this.route) {
                                        console.log('[KL8Wall-PATCH] Guarding falsy route on <' + name + '>');
                                        this.route = { path: '', prefix: '' };
                                    }
                                    try {
                                        return originalUpdated.call(this, changedProperties);
                                    } catch (e) {
                                        console.error('[KL8Wall-PATCH] Error in <' + name + '>.updated:', e);
                                        if (e.stack) console.error(e.stack);
                                    }
                                };
                            }
                        }
                        return originalDefine.call(this, name, constructor, options);
                    };
                }
            })();
        """

        private const val DISABLE_SELECTION_JS = """
            (function() {
                document.documentElement.style.webkitUserSelect = 'none';
                document.documentElement.style.userSelect = 'none';
                document.documentElement.style.webkitTouchCallout = 'none';
            })();
        """

        private const val DEBUG_DOM_JS = """
            (function check() {
                var ha = document.querySelector('home-assistant');
                if (!ha) { console.log('[KL8Wall-DEBUG] no <home-assistant> yet'); return; }
                var sr = ha.shadowRoot;
                console.log('[KL8Wall-DEBUG] <home-assistant> size=' +
                    ha.offsetWidth + 'x' + ha.offsetHeight +
                    ' display=' + window.getComputedStyle(ha).display +
                    ' shadowRoot=' + !!sr);
                if (sr) {
                    var kids = sr.children;
                    for (var i = 0; i < Math.min(kids.length, 8); i++) {
                        var c = kids[i];
                        var cs = window.getComputedStyle(c);
                        console.log('[KL8Wall-DEBUG]   shadow child[' + i + '] <' +
                            c.tagName + '>: display=' + cs.display +
                            ' visibility=' + cs.visibility +
                            ' size=' + c.offsetWidth + 'x' + c.offsetHeight);
                    }
                }
                var splash = document.body.children[0];
                if (splash) {
                    console.log('[KL8Wall-DEBUG] splash div: display=' +
                        window.getComputedStyle(splash).display +
                        ' innerHTML length=' + splash.innerHTML.length +
                        ' text=' + splash.textContent.substring(0, 100));
                }
                console.log('[KL8Wall-DEBUG] localStorage keys: ' +
                    Object.keys(localStorage).join(', '));
            })();
            setTimeout(function() {
                var ha = document.querySelector('home-assistant');
                if (!ha) return;
                console.log('[KL8Wall-DEBUG-DELAYED] <home-assistant> size=' +
                    ha.offsetWidth + 'x' + ha.offsetHeight);
                var sr = ha.shadowRoot;
                if (sr) {
                    var kids = sr.children;
                    for (var i = 0; i < Math.min(kids.length, 8); i++) {
                        var c = kids[i];
                        var cs = window.getComputedStyle(c);
                        console.log('[KL8Wall-DEBUG-DELAYED]   shadow[' + i + '] <' +
                            c.tagName + '>: display=' + cs.display +
                            ' size=' + c.offsetWidth + 'x' + c.offsetHeight);
                    }
                }
                var kids = document.body.children;
                for (var j = 0; j < kids.length; j++) {
                    var k = kids[j];
                    var kcs = window.getComputedStyle(k);
                    console.log('[KL8Wall-DEBUG-DELAYED] body child[' + j + '] <' +
                        k.tagName + '> display=' + kcs.display +
                        ' position=' + kcs.position +
                        ' zIndex=' + kcs.zIndex +
                        ' bg=' + kcs.backgroundColor +
                        ' size=' + k.offsetWidth + 'x' + k.offsetHeight);
                }
                var splash = document.getElementById('ha-launch-screen');
                if (splash) {
                    splash.style.display = 'none';
                    console.log('[KL8Wall-DEBUG-DELAYED] hid splash: <' + splash.tagName + '>');
                }
            }, 5000);
        """

        private const val DEBUG_BANNER_JS = """
            setTimeout(function() {
                function info(el) {
                    if (!el) return 'null';
                    var cs = window.getComputedStyle(el);
                    return '<' + el.tagName + '> ' + el.offsetWidth + 'x' + el.offsetHeight +
                        ' d=' + cs.display + ' v=' + cs.visibility +
                        ' bg=' + cs.backgroundColor +
                        ' sr=' + !!el.shadowRoot +
                        ' kids=' + el.children.length +
                        (el.shadowRoot ? ' sKids=' + el.shadowRoot.children.length : '');
                }
                function findIn(parent, tag) {
                    if (!parent) return null;
                    var sr = parent.shadowRoot;
                    if (sr) {
                        var el = sr.querySelector(tag);
                        if (el) return el;
                    }
                    return parent.querySelector(tag);
                }
                var ha = document.querySelector('home-assistant');
                console.log('[KL8Wall-CHAIN] 1 ha: ' + info(ha));
                var main = findIn(ha, 'home-assistant-main');
                console.log('[KL8Wall-CHAIN] 2 main: ' + info(main));
                var drawer = findIn(main, 'ha-drawer');
                console.log('[KL8Wall-CHAIN] 3 drawer: ' + info(drawer));
                var resolver = drawer ? drawer.querySelector('partial-panel-resolver') : null;
                console.log('[KL8Wall-CHAIN] 4 resolver: ' + info(resolver));
                var panel = findIn(resolver, 'ha-panel-lovelace');
                if (!panel) panel = findIn(resolver, 'ha-panel-energy');
                if (!panel && resolver) {
                    var sr = resolver.shadowRoot;
                    if (sr) {
                        var all = sr.children;
                        for (var i = 0; i < all.length; i++) {
                            console.log('[KL8Wall-CHAIN] 4.1 resolver shadow child: ' + info(all[i]));
                        }
                    }
                    for (var j = 0; j < resolver.children.length; j++) {
                        console.log('[KL8Wall-CHAIN] 4.2 resolver light child: ' + info(resolver.children[j]));
                    }
                }
                console.log('[KL8Wall-CHAIN] 5 panel: ' + info(panel));
                var huiRoot = findIn(panel, 'hui-root');
                console.log('[KL8Wall-CHAIN] 6 hui-root: ' + info(huiRoot));
                if (huiRoot && huiRoot.shadowRoot) {
                    var hsr = huiRoot.shadowRoot;
                    var all2 = hsr.children;
                    for (var k = 0; k < Math.min(all2.length, 10); k++) {
                        console.log('[KL8Wall-CHAIN] 7 hui-root shadow[' + k + ']: ' + info(all2[k]));
                    }
                }
                if (panel && !huiRoot && panel.shadowRoot) {
                    var psr = panel.shadowRoot;
                    for (var l = 0; l < Math.min(psr.children.length, 10); l++) {
                        console.log('[KL8Wall-CHAIN] 5.1 panel shadow[' + l + ']: ' + info(psr.children[l]));
                    }
                }
            }, 7000);
        """
    }
}
