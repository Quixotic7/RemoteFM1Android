// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS d629aa0 RFMKit/Sources/RFMKit/Managers/ManagerPage.swift (f47cdd4, eb3455c, e60d78a,
// 14c9b96, dc771c8, bd0711a, 41e8471)
// A firmware's own web editor in the app, at its author's page, in a WebView with a whole Web MIDI injected at
// document start (iOS's script; androidx.webkit's addDocumentStartJavaScript, every https page; where the WebView
// can't, it is injected when the page starts). The page's MIDI comes to ManagerBridge (a @JavascriptInterface
// "fm1mgr" that answers only while the main frame is an https page): to the running firmware (AppMidi.managerSend, in
// the page's order); the firmware's back to the page (managerTake, every 10 ms). Opening it keeps a safety copy first
// ("before manager write"): an editor may write a whole bank. The page's alert / confirm / prompt are real dialogs
// (WebChromeClient: without them confirm() was a Cancel, so "Place all" did nothing); its file inputs open the app's
// picker in the folder last picked from (PickerFolder.EDITOR); its downloads (blob: and data: too) go to the share
// sheet; links to new windows open in the browser; its console errors go to the log ("RFMManager"); it may play sound
// without a tap (the screen reader's voice). Android: the system back gesture closes it, as Done does.
package com.quixotic7.remotefm1.managers

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Base64
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.quixotic7.remotefm1.AppModel
import com.quixotic7.remotefm1.PickerFolder
import com.quixotic7.remotefm1.data.FlashHistory
import com.quixotic7.remotefm1.ui.sheet.SheetButton
import com.quixotic7.remotefm1.ui.sheet.ui
import com.quixotic7.remotefm1.ui.theme.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

@Composable
fun ManagerPage(model: AppModel, url: String, port: String, firmware: String, title: String? = null, done: () -> Unit) {
    val bridge = remember(url) { ManagerBridge(model, port, firmware) }
    DisposableEffect(bridge) {
        bridge.attach()
        onDispose { bridge.detach() }
    }
    Column(Modifier.fillMaxSize().background(Palette.sheetBackground).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().background(Palette.sheetBackground).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title ?: "$firmware editor", style = ui(17, 700))
            Spacer(Modifier.weight(1f))
            SheetButton("Done", accent = true, tag = "manager-done", action = done)
        }
        AndroidView(factory = { ctx -> bridge.makeView(ctx, url) }, modifier = Modifier.fillMaxSize().testTag("manager-web"))
    }
}

/** the page's player: the Web MIDI script, the bridge both ways (the main thread, except the JS calls) */
class ManagerBridge(private val model: AppModel, private val port: String, private val firmware: String) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var web: WebView? = null
    @Volatile private var trusted = false
    private val queue = ArrayDeque<ByteArray>()
    private var sending = false
    /** tests: what the bridge saw (hello: the page asked for MIDI; sent, received: message counts) */
    @Volatile var midiAsked = false
        private set
    @Volatile var sent = 0
        private set
    @Volatile var received = 0
        private set
    /** tests: the page's dialogs shown (alert / confirm / prompt) and its last download's name */
    @Volatile var dialogs = 0
        private set
    @Volatile var lastDownload: String? = null
        private set
    /** tests: the page's dialog on screen, to answer it */
    @Volatile var lastDialog: AlertDialog? = null
        private set
    private var injected = false
    private var cacheDir: File? = null
    private var choosing: ValueCallback<Array<Uri>>? = null

    private val poll = object : Runnable {
        override fun run() {
            deliver()
            main.postDelayed(this, 10)
        }
    }

    fun attach() {
        model.safetyCopyNow(FlashHistory.Reason.BeforeManagerWrite)
        model.midi.managerAttach(true)
        main.postDelayed(poll, 10)
        current = this
    }

    fun detach() {
        main.removeCallbacks(poll)
        model.midi.managerAttach(false)
        scope.cancel()
        choosing?.onReceiveValue(null)
        choosing = null
        web?.let {
            it.removeJavascriptInterface("fm1mgr")
            it.destroy()
        }
        web = null
        if (current === this) current = null
    }

    private val script: String
        get() = WEB_MIDI.replace("%STATE%", JSONObject().put("port", port).put("firmware", firmware).toString())

    @SuppressLint("SetJavaScriptEnabled")
    fun makeView(ctx: Context, url: String): WebView {
        val w = WebView(ctx)
        web = w
        cacheDir = ctx.cacheDir
        w.setBackgroundColor(android.graphics.Color.BLACK)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.allowFileAccess = false
        w.settings.allowContentAccess = true                 // (the picker's content: files for the page's inputs)
        w.settings.mediaPlaybackRequiresUserGesture = false  // (the screen reader speaks what MIDI brings, untapped)
        w.settings.setSupportMultipleWindows(true)           // (new windows: onCreateWindow, the browser)
        w.addJavascriptInterface(Js(), "fm1mgr")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(w, script, setOf("*"))
            injected = true
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, r: WebResourceRequest): Boolean {
                if (ManagerList.trusted(r.url.toString())) return false
                openOutside(ctx, r.url)                       // (not https: mailto:, intents, ...)
                return true
            }

            override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) {
                trusted = ManagerList.trusted(u)
                if (trusted && !injected) view.evaluateJavascript(script, null)
            }

            override fun doUpdateVisitedHistory(view: WebView, u: String?, isReload: Boolean) {
                trusted = ManagerList.trusted(u)
            }
        }
        w.webChromeClient = Chrome(ctx)
        w.setDownloadListener { u, _, disposition, mime, _ ->
            val name = URLUtil.guessFileName(u, disposition, mime).ifEmpty { "download" }
            when {
                u.startsWith("blob:") || u.startsWith("data:") ->   // (an editor's backup made in the page)
                    w.evaluateJavascript(fetchScript(u, name), null)
                u.startsWith("https:") -> openOutside(ctx, Uri.parse(u))
            }
        }
        trusted = ManagerList.trusted(url)
        w.loadUrl(url)
        return w
    }

    private fun openOutside(ctx: Context, u: Uri) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** the page's dialogs, file inputs, new windows and console */
    private inner class Chrome(private val ctx: Context) : WebChromeClient() {
        private fun dialog(): AlertDialog.Builder? = runCatching { AlertDialog.Builder(ctx) }.getOrNull()?.also { dialogs++ }

        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            val b = dialog() ?: return false
            b.setMessage(message).setPositiveButton("OK") { _, _ -> result.confirm() }
                .setOnCancelListener { result.confirm() }.show().also { lastDialog = it }
            return true
        }

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            val b = dialog() ?: return false
            b.setMessage(message).setPositiveButton("OK") { _, _ -> result.confirm() }
                .setNegativeButton("Cancel") { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }.show().also { lastDialog = it }
            return true
        }

        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
            val b = dialog() ?: return false
            val field = EditText(ctx).apply { setText(defaultValue ?: ""); setSingleLine() }
            b.setMessage(message).setView(field)
                .setPositiveButton("OK") { _, _ -> result.confirm(field.text.toString()) }
                .setNegativeButton("Cancel") { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }.show().also { lastDialog = it }
            return true
        }

        // (a page's file input: the app's picker, in the folder last picked from, any file)
        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            val p = picker ?: return false
            choosing?.onReceiveValue(null)
            choosing = callback
            p(params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) { uris ->
                val c = choosing ?: return@p
                choosing = null
                if (uris.isNotEmpty()) PickerFolder.of(ctx, PickerFolder.EDITOR).remember(uris)
                c.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
            }
            return true
        }

        // (a link to a new window: the browser. The new window's first load says where it goes)
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val probe = WebView(ctx)
            probe.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    openOutside(ctx, r.url)
                    v.destroy()
                    return true
                }
            }
            (resultMsg.obj as? WebView.WebViewTransport)?.webView = probe
            resultMsg.sendToTarget()
            return true
        }

        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
            if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR || m.messageLevel() == ConsoleMessage.MessageLevel.WARNING)
                Log.w(TAG, "page ${m.messageLevel().name.lowercase()}: ${m.message().take(600)} (${m.sourceId()}:${m.lineNumber()})")
            return true
        }
    }

    /** tests: a script's result on the page (the main thread) */
    fun evaluate(js: String, done: (String?) -> Unit) {
        val w = web ?: return done(null)
        w.evaluateJavascript(js) { done(it) }
    }

    // the firmware's messages to the page
    private fun deliver() {
        val msgs = model.midi.managerTake()
        val w = web ?: return
        if (msgs.isEmpty() || !trusted) return
        val a = JSONArray()
        for (m in msgs) a.put(JSONArray().apply { m.forEach { put(it.toInt() and 0xFF) } })
        received += msgs.size
        w.evaluateJavascript("window.__fm1player && window.__fm1player.midi($a)", null)
    }

    // the page's messages, in its order (a SysEx waiting for room must not be overtaken)
    private fun enqueue(b: ByteArray) {
        queue.addLast(b)
        if (sending) return
        sending = true
        scope.launch {
            while (queue.isNotEmpty()) {
                val m = queue.removeFirst()
                if (model.midi.managerSend(m)) sent++
            }
            sending = false
        }
    }

    /** a download the page made (base64): written to the cache's share/ and offered to the share sheet */
    private fun saved(name: String, b64: String) {
        val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: return
        if (bytes.size > MAX_DOWNLOAD) return
        val safe = name.replace(Regex("[/\\\\:]"), "_").ifEmpty { "download" }
        val dir = File(cacheDir ?: return, "share").apply { mkdirs() }
        val f = File(dir, safe)
        runCatching { f.writeBytes(bytes) }.onFailure { Log.w(TAG, "download: $it"); return }
        lastDownload = safe
        share?.invoke(f, "application/octet-stream")
    }

    /** the page's side: as small as it can be, and only for https pages */
    private inner class Js {
        @JavascriptInterface
        fun postMessage(json: String) {
            if (!trusted) return
            val a = runCatching { JSONArray(json) }.getOrNull() ?: return
            if (a.length() == 0 || a.length() > 64 * 1024) return
            val b = ByteArray(a.length())
            for (i in 0 until a.length()) {
                val v = a.optInt(i, -1)
                if (v !in 0..255) return
                b[i] = v.toByte()
            }
            main.post { enqueue(b) }
        }

        @JavascriptInterface
        fun hello() {
            if (trusted) midiAsked = true
        }

        @JavascriptInterface
        fun download(name: String, b64: String) {
            if (!trusted) return
            main.post { saved(name, b64) }
        }
    }

    companion object {
        const val TAG = "RFMManager"
        const val MAX_DOWNLOAD = 64 shl 20
        /** tests: the bridge on screen now */
        @Volatile var current: ManagerBridge? = null
            private set
        /** (MainActivity) the document picker for a page's file input: multiple, then the picked files (none: cancelled) */
        var picker: ((multiple: Boolean, done: (List<Uri>) -> Unit) -> Unit)? = null
        /** (MainActivity) the share sheet for a file the page saved */
        var share: ((File, String) -> Unit)? = null

        /** the page fetches its own blob:/data: download and hands it over as base64 */
        fun fetchScript(url: String, name: String): String {
            val u = JSONObject.quote(url)
            val n = JSONObject.quote(name)
            return """(function(){fetch($u).then(r=>r.blob()).then(b=>{const f=new FileReader();""" +
                """f.onload=()=>{const s=String(f.result);window.fm1mgr.download($n,s.slice(s.indexOf(',')+1));};""" +
                """f.readAsDataURL(b);}).catch(e=>console.error('download: '+e));})();"""
        }

        /** a whole Web MIDI for the page (iOS's ManagerWebView.webMIDI, after RemoteFM1's web/managers/fm1-shim.js:
         *  set as window.__fm1shim first so that one stands aside): one input and one output named as the firmware's
         *  USB port, always connected, sysex granted; what the page sends goes to the app (fm1mgr), the app's
         *  __fm1player.midi(list) comes in on the input. Any editor page works. (Android: the post goes through
         *  window.fm1mgr as JSON; requestMIDIAccess also says hello; file inputs need no help: onShowFileChooser) */
        val WEB_MIDI = """
(function (G) {
  "use strict";
  if (G.__fm1app || !G.fm1mgr) return;
  const state = %STATE%, NAME = state.port || "FM-1";
  const post = (bytes) => { try { G.fm1mgr.postMessage(JSON.stringify(Array.from(bytes))); } catch (_) {} };
  const msgEvent = (data) => { try { return new MessageEvent("midimessage", { data }); } catch (_) {
    const e = new Event("midimessage"); Object.defineProperty(e, "data", { value: data }); return e; } };
  const connEvent = (port) => { const e = new Event("statechange"); Object.defineProperty(e, "port", { value: port }); return e; };
  class Port extends EventTarget {
    constructor(type, id) { super(); this._type = type; this._id = id; this._conn = "closed"; this._onstate = null;
      this.addEventListener("statechange", (e) => { if (typeof this._onstate === "function") this._onstate.call(this, e); }); }
    get id() { return this._id; } get name() { return NAME; } get manufacturer() { return "RemoteFM1"; }
    get version() { return ""; } get type() { return this._type; } get state() { return "connected"; }
    get connection() { return this._conn; }
    get onstatechange() { return this._onstate; } set onstatechange(f) { this._onstate = f; }
    open() { if (this._conn !== "open") { this._conn = "open"; changed(this); } return Promise.resolve(this); }
    close() { if (this._conn !== "closed") { this._conn = "closed"; changed(this); } return Promise.resolve(this); }
  }
  class Input extends Port {
    constructor() { super("input", "remotefm1-in"); this._onmsg = null;
      super.addEventListener("midimessage", (e) => { if (typeof this._onmsg === "function") this._onmsg.call(this, e); }); }
    get onmidimessage() { return this._onmsg; } set onmidimessage(f) { this._onmsg = f; if (f) this.open(); }
    addEventListener(t, f, o) { super.addEventListener(t, f, o); if (t === "midimessage") this.open(); }
  }
  class Output extends Port {
    constructor() { super("output", "remotefm1-out"); }
    send(data, ts) {
      const bytes = Uint8Array.from(data || [], (v) => { if (!Number.isInteger(v) || v < 0 || v > 255) throw new TypeError("MIDIOutput.send: bytes 0..255"); return v; });
      if (!bytes.length) return; if (this._conn !== "open") this.open();
      const delay = typeof ts === "number" && ts > 0 ? ts - performance.now() : 0;
      if (delay > 1) setTimeout(() => post(bytes), delay); else post(bytes);
    }
    clear() {}
  }
  const input = new Input(), output = new Output(), accesses = new Set();
  const inputs = new Map([[input.id, input]]), outputs = new Map([[output.id, output]]);
  class Access extends EventTarget {
    constructor() { super(); this._onstate = null;
      this.addEventListener("statechange", (e) => { if (typeof this._onstate === "function") this._onstate.call(this, e); }); }
    get inputs() { return inputs; } get outputs() { return outputs; } get sysexEnabled() { return true; }
    get onstatechange() { return this._onstate; } set onstatechange(f) { this._onstate = f; }
  }
  function changed(port) { port.dispatchEvent(connEvent(port)); for (const a of accesses) a.dispatchEvent(connEvent(port)); }
  function requestMIDIAccess() { try { G.fm1mgr.hello(); } catch (_) {} const a = new Access(); accesses.add(a); return Promise.resolve(a); }
  Object.defineProperty(G.navigator, "requestMIDIAccess", { value: requestMIDIAccess, configurable: true, writable: true });
  if (G.navigator.permissions && G.navigator.permissions.query) {
    const q = G.navigator.permissions.query.bind(G.navigator.permissions);
    G.navigator.permissions.query = (d) => (d && d.name === "midi") ? Promise.resolve({ state: "granted", onchange: null }) : q(d);
  }
  // (a download the page makes, <a download> with a blob: or data: link: fetched here and handed to the app with its
  // name, for the share sheet; Android's download listener knows no name for a blob)
  const save = (a) => { const name = a.download || "download";
    fetch(a.href).then((r) => r.blob()).then((b) => { const f = new FileReader();
      f.onload = () => { const s = String(f.result); G.fm1mgr.download(name, s.slice(s.indexOf(",") + 1)); }; f.readAsDataURL(b); })
      .catch((e) => console.error("download: " + e)); };
  const isSave = (a) => a && a.hasAttribute && a.hasAttribute("download") && /^(blob|data):/.test(a.href || "");
  const aClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () { if (isSave(this)) { save(this); return; } return aClick.apply(this, arguments); };
  G.addEventListener("click", (e) => { const a = e.target && e.target.closest ? e.target.closest("a") : null;
    if (isSave(a)) { e.preventDefault(); e.stopPropagation(); save(a); } }, true);
  G.__fm1player = { midi(list) { if (input._conn !== "open") return; for (const b of list) input.dispatchEvent(msgEvent(Uint8Array.from(b))); } };
  G.__fm1app = true;
  G.__fm1shim = { app: true, manager: state.firmware, framed: false };
})(window);
"""
    }
}

/** the editor (or, [reader], the screen reader) for the running firmware, or nothing (the Firmware page offers it
 *  only when there is one) */
@Composable
fun ManagerFor(model: AppModel, reader: Boolean = false, done: () -> Unit) {
    val c = model.current
    val details = model.fw.store.details
    val url = c?.let { if (reader) ManagerList.READER else ManagerList.editor(it.firmwareID, details) }
    if (c == null || url == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { done() }
        return
    }
    Log.i(ManagerBridge.TAG, "opening $url for ${c.name}")
    ManagerPage(model, url, ManagerList.port(c.firmwareID, c.name, details), c.name,
                title = if (reader) "Screen reader" else null, done = done)
}
