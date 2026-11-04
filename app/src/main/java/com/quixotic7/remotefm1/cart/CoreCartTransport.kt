// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 RemoteFM1 contributors
// Ported from RemoteFM1IOS d629aa0 RFMKit/Sources/RFMKit/Cart/CoreCartTransport.swift (dc771c8)
// The RemoteFM1 firmware running in the app as a cartridge target: what the website's RemoteFM1 "editor" is. The
// SysEx goes into the core (AppMidi.managerSend), its replies come back from the core's MIDI out (managerTake, polled
// every 10 ms while the cartridge page uses it: open() / close()).
//   CoreCartTransport      "RemoteFM1 in this app"
//   CombinedCartTransport  the app's targets together: RemoteFM1 in the app (while it runs) and the FM-1s found
package com.quixotic7.remotefm1.cart

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** what the transport needs of the running core (AppMidi in the app) */
interface CoreMidi {
    fun attach(on: Boolean)
    suspend fun send(bytes: ByteArray): Boolean
    fun take(): List<ByteArray>
}

class CoreCartTransport(private val core: () -> CoreMidi?) : CartTransport {
    companion object {
        val PORT = CartPort("core", "RemoteFM1 in this app", false)
        const val FIRMWARE_ID = "remotefm1"
        /** the app runs the RemoteFM1 firmware now (set by the app when the firmware changes) */
        @Volatile var running = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var handler: ((ByteArray) -> Unit)? = null
    private var poll: Job? = null
    private var attached: CoreMidi? = null

    override fun ports(): List<CartPort> = listOf(PORT)

    override suspend fun send(bytes: ByteArray, port: CartPort) {
        val ok = withContext(Dispatchers.Main) {
            open()                                          // (its replies read from the first request on)
            core()?.send(bytes) ?: false
        }
        if (!ok) throw java.io.IOException("RemoteFM1 in this app didn't take it")
    }

    override fun listen(handler: (ByteArray) -> Unit) {
        this.handler = handler
    }

    /** the core's replies read while the page uses it (the main thread) */
    fun open() {
        if (poll != null) return
        val c = core() ?: return
        attached = c
        c.attach(true)
        poll = scope.launch {
            while (isActive) {
                delay(10)
                for (m in c.take()) handler?.invoke(m)
            }
        }
    }

    /** (the main thread) */
    fun close() {
        poll?.cancel()
        poll = null
        attached?.attach(false)
        attached = null
    }
}

/** the app's cartridge targets together: RemoteFM1 running in the app (while it is the running firmware) and the FM-1s
 *  the device transport finds. Each send goes to its port's transport; replies come from both */
class CombinedCartTransport(
    val core: CoreCartTransport,
    val device: CartTransport,
    private val coreRunning: () -> Boolean = { CoreCartTransport.running },
) : CartTransport {
    override fun ports(): List<CartPort> = (if (coreRunning()) listOf(CoreCartTransport.PORT) else emptyList()) + device.ports()

    override suspend fun send(bytes: ByteArray, port: CartPort) =
        if (port.id == CoreCartTransport.PORT.id) core.send(bytes, port) else device.send(bytes, port)

    override fun listen(handler: (ByteArray) -> Unit) {
        core.listen(handler)
        device.listen(handler)
    }

    override val sharesPanelPort: Boolean get() = device.sharesPanelPort

    override fun done() = device.done()
}
