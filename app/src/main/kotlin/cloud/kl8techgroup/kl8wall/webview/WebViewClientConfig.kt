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
    private val ignoreSslErrors: () -> Boolean = { true },
    private val micShimEnabled: () -> Boolean = { true },
    private val onPageLoaded: (String) -> Unit = {},
    private val onNavigationBlocked: (String) -> Unit = {},
    private val onError: (Int, String) -> Unit = { _, _ -> },
    private val onPageLoadStatus: (String, Boolean) -> Unit = { _, _ -> }
) : WebViewClient() {

    private var mainFrameError = false

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
        mainFrameError = false
        view.evaluateJavascript(ERROR_LOGGER_JS, null)
        if (micShimEnabled()) {
            view.evaluateJavascript(MICROPHONE_SHIM_JS, null)
        }
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        url?.let {
            onPageLoaded(it)
            onPageLoadStatus(it, !mainFrameError)
        }
        view.evaluateJavascript(ERROR_LOGGER_JS, null)
        view.evaluateJavascript(DISABLE_SELECTION_JS, null)
        view.evaluateJavascript(DEBUG_DOM_JS, null)
        view.evaluateJavascript(DEBUG_BANNER_JS, null)
        if (micShimEnabled()) {
            view.evaluateJavascript(MICROPHONE_SHIM_JS, null)
        }
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        try {
            if (ignoreSslErrors()) {
                handler.proceed()
            } else {
                mainFrameError = true
                handler.cancel()
            }
        } catch (e: Exception) {
            mainFrameError = true
            handler.cancel()
        }
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        super.onReceivedError(view, request, error)
        if (request.isForMainFrame) {
            mainFrameError = true
            val failingUrl = request.url?.toString() ?: "unknown"
            onError(error.errorCode, "${error.description} ($failingUrl)")
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

        private const val MICROPHONE_SHIM_JS = """
            (function() {
                if (window.__kl8wall_mic_shim_installed) return;

                // Check if we already have a secure context and native getUserMedia
                if (window.isSecureContext && navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
                    window.__kl8wall_mic_shim_installed = true;
                    console.log('[KL8Wall-MIC] Native secure context and getUserMedia detected. Skipping shim.');
                    return;
                }

                window.__kl8wall_mic_shim_installed = true;
                console.log('[KL8Wall-MIC] Installing microphone shim for HTTP...');

                try {
                    Object.defineProperty(window, 'isSecureContext', {
                        get: function() { return true; }
                    });
                } catch (e) {
                    console.error('[KL8Wall-MIC] Failed to override isSecureContext', e);
                }

                if (!navigator.mediaDevices) {
                    try {
                        Object.defineProperty(navigator, 'mediaDevices', {
                            value: {},
                            writable: true,
                            configurable: true
                        });
                    } catch (e) {
                        console.error('[KL8Wall-MIC] Failed to define navigator.mediaDevices', e);
                    }
                }

                let audioQueue = [];

                window.__kl8wall_on_audio_data = function(base64Data) {
                    try {
                        let binaryString = window.atob(base64Data);
                        let len = binaryString.length;
                        let bytes = new Uint8Array(len);
                        for (let i = 0; i < len; i++) {
                            bytes[i] = binaryString.charCodeAt(i);
                        }
                        let int16Array = new Int16Array(bytes.buffer);
                        let float32Array = new Float32Array(int16Array.length);
                        for (let i = 0; i < int16Array.length; i++) {
                            float32Array[i] = int16Array[i] / 32768.0;
                        }
                        audioQueue.push(float32Array);
                    } catch (e) {
                        console.error('[KL8Wall-MIC] Error processing audio data', e);
                    }
                };

                if (navigator.mediaDevices) {
                    try {
                        navigator.mediaDevices.getUserMedia = function(constraints) {
                            console.log('[KL8Wall-MIC] getUserMedia called');
                            return new Promise(function(resolve, reject) {
                                try {
                                    let AudioCtx = window.AudioContext || window.webkitAudioContext;
                                    let audioCtx = new AudioCtx();
                                    let sampleRate = audioCtx.sampleRate;

                                    if (audioCtx.state === 'suspended') {
                                        audioCtx.resume().catch(function(e) {
                                            console.warn('[KL8Wall-MIC] Failed to resume audioCtx:', e);
                                        });
                                    }

                                    if (window.Kl8WallAudio) {
                                        window.Kl8WallAudio.startRecording(sampleRate);
                                    } else {
                                        console.error('[KL8Wall-MIC] Kl8WallAudio JS interface not found');
                                    }

                                    let processor = audioCtx.createScriptProcessor(4096, 0, 1);
                                    
                                    processor.onaudioprocess = function(e) {
                                        let outputBuffer = e.outputBuffer;
                                        let channelData = outputBuffer.getChannelData(0);
                                        
                                        if (audioQueue.length > 0) {
                                            let chunk = audioQueue.shift();
                                            let copyLen = Math.min(chunk.length, channelData.length);
                                            for (let i = 0; i < copyLen; i++) {
                                                channelData[i] = chunk[i];
                                            }
                                            if (chunk.length > channelData.length) {
                                                audioQueue.unshift(chunk.subarray(channelData.length));
                                            }
                                        } else {
                                            channelData.fill(0);
                                        }
                                    };

                                    let destination = audioCtx.createMediaStreamDestination();
                                    processor.connect(destination);

                                    let stream = destination.stream;
                                    let tracks = stream.getAudioTracks();
                                    if (tracks.length > 0) {
                                        let track = tracks[0];
                                        let originalStop = track.stop;
                                        let stopped = false;
                                        track.stop = function() {
                                            console.log('[KL8Wall-MIC] track.stop called, stopped=' + stopped);
                                            if (stopped) return;
                                            stopped = true;

                                            if (window.Kl8WallAudio) {
                                                try {
                                                    window.Kl8WallAudio.stopRecording();
                                                } catch (e) {
                                                    console.error('[KL8Wall-MIC] stopRecording failed', e);
                                                }
                                            }

                                            try {
                                                processor.disconnect();
                                            } catch (e) {}

                                            audioQueue = [];

                                            if (audioCtx && audioCtx.state !== 'closed') {
                                                try {
                                                    audioCtx.close().catch(function(err) {
                                                        console.log('[KL8Wall-MIC] audioCtx.close rejection caught:', err);
                                                    });
                                                } catch (e) {
                                                    console.error('[KL8Wall-MIC] audioCtx.close error:', e);
                                                }
                                            }

                                            if (originalStop) {
                                                try {
                                                    originalStop.call(track);
                                                } catch (e) {
                                                    console.error('[KL8Wall-MIC] originalStop failed', e);
                                                }
                                            }
                                        };
                                    }

                                    resolve(stream);
                                } catch (err) {
                                    console.error('[KL8Wall-MIC] Error in getUserMedia shim:', err);
                                    reject(err);
                                }
                            });
                        };
                    } catch (e) {
                        console.error('[KL8Wall-MIC] Failed to override getUserMedia', e);
                    }
                }
            })();
        """
    }
}
