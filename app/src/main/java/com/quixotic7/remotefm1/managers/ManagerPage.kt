// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS edf2039 RFMKit/Sources/RFMKit/Managers/ManagerPage.swift
// The firmware's own web manager in the app: remotefm1.com's hosted page in a full-screen WebView, with a whole Web
// MIDI injected at document start (iOS's script; androidx.webkit's addDocumentStartJavaScript, only for
// https://remotefm1.com; where the WebView can't, it is injected when the page starts). The page's MIDI comes to
// ManagerBridge (a @JavascriptInterface "fm1mgr" that answers only while the page is on remotefm1.com over https):
// to the running firmware (AppMidi.managerSend, in the page's order); the firmware's back to the page
// (managerTake, every 10 ms). Opening it keeps a safety copy first ("before manager write"): a manager may write a
// whole bank. Links to other sites open in the browser.
package com.quixotic7.remotefm1.managers

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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

@Composable
fun ManagerPage(model: AppModel, url: String, port: String, firmware: String, done: () -> Unit) {
    val bridge = remember(url) { ManagerBridge(model, port, firmware) }
    DisposableEffect(bridge) {
        bridge.attach()
        onDispose { bridge.detach() }
    }
    Column(Modifier.fillMaxSize().background(Palette.sheetBackground).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().background(Palette.sheetBackground).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("$firmware editor", style = ui(17, 700))
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
    private var injected = false

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
    fun makeView(ctx: android.content.Context, url: String): WebView {
        val w = WebView(ctx)
        web = w
        w.setBackgroundColor(android.graphics.Color.BLACK)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.allowFileAccess = false
        w.settings.allowContentAccess = false
        w.addJavascriptInterface(Js(), "fm1mgr")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(w, script, setOf("https://${ManagerList.HOST}"))
            injected = true
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, r: WebResourceRequest): Boolean {
                if (ManagerList.trusted(r.url.toString())) return false
                // (another site: the browser)
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, r.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
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
        w.setDownloadListener { u, _, _, _, _ ->
            // (an editor's download with a real address: the browser saves it; blob: downloads are not supported yet)
            if (u.startsWith("https:")) runCatching {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        trusted = ManagerList.trusted(url)
        w.loadUrl(url)
        return w
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

    /** the page's side: as small as it can be, and only for remotefm1.com */
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
    }

    companion object {
        const val TAG = "RFMManager"
        /** tests: the bridge on screen now */
        @Volatile var current: ManagerBridge? = null
            private set

        /** a whole Web MIDI for the page (iOS's ManagerWebView.webMIDI, after RemoteFM1's web/managers/fm1-shim.js,
         *  which the site's hosted pages load: set as window.__fm1shim first so that one stands aside): one input and
         *  one output named as the firmware's USB port, always connected, sysex granted; what the page sends goes to
         *  the app (fm1mgr), the app's __fm1player.midi(list) comes in on the input. (Android: the post goes through
         *  window.fm1mgr as JSON; requestMIDIAccess also says hello) */
        val WEB_MIDI = """
(function (G) {
  "use strict";
  if (G.__fm1app) return;
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
  G.__fm1player = { midi(list) { if (input._conn !== "open") return; for (const b of list) input.dispatchEvent(msgEvent(Uint8Array.from(b))); } };
  G.__fm1app = true;
  G.__fm1shim = { app: true, manager: state.firmware, framed: false };
})(window);
"""
    }
}

/** the manager for the running firmware, or null (the Firmware page offers it only when there is one) */
@Composable
fun ManagerFor(model: AppModel, done: () -> Unit) {
    val c = model.current
    val url = c?.let { ManagerList.url(it.firmwareID) }
    if (c == null || url == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { done() }
        return
    }
    Log.i(ManagerBridge.TAG, "opening $url for ${c.name}")
    ManagerPage(model, url, ManagerList.port(c.firmwareID, c.name), c.name, done)
}
