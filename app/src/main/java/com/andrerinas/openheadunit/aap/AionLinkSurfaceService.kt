package com.andrerinas.openheadunit.aap

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyEvent
import android.view.Surface
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.protocol.messages.TouchEvent
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.decoder.audio.AionLinkAudioSink
import com.andrerinas.openheadunit.decoder.video.WarmRelaunchKeyframePolicy
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.HeadUnitScreenConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Bound only with our signature permission; the caller explicitly supplies its own video surface. */
class AionLinkSurfaceService : Service() {
    companion object {
        @Volatile var active = false
            private set
        private const val ATTACH = 1
        private const val DETACH = 2
        private const val TOUCH = 3
        private const val KEY = 4
        private const val STATUS = 10
        /**
         * A client that went away without coming back within this time ends the Android Auto session. Measured on
         * 2026-11-10: with no client, Android Auto, this app and the compositor kept about 33 % CPU (of 800 %)
         * encoding and decoding video for nobody, warming the phone until it was stopped by hand. A minute still
         * covers a car Wi-Fi drop, which reattaches to the live session.
         */
        private const val IDLE_STOP_MS = 60_000L

        // Process-wide: the bound service is destroyed right after its client unbinds, and a reconnect may create
        // a new instance, so the timer must outlive one instance and be cancelled by whichever attaches next.
        private val idleHandler = Handler(Looper.getMainLooper())
        private var idleStop: Runnable? = null

        private fun scheduleIdleStop(context: android.content.Context) {
            cancelIdleStop()
            val app = context.applicationContext
            idleStop = Runnable {
                idleStop = null
                if (active) return@Runnable
                AppLog.i("AionLinkSurfaceService: no client for ${IDLE_STOP_MS / 1000} s; stopping the Android Auto session")
                try {
                    app.startService(Intent(app, AapService::class.java).setAction(AapService.ACTION_STOP_SERVICE))
                } catch (error: RuntimeException) {
                    AppLog.w("AionLinkSurfaceService: could not stop the idle session: ${error.message}")
                }
            }.also { idleHandler.postDelayed(it, IDLE_STOP_MS) }
        }

        private fun cancelIdleStop() { idleStop?.let(idleHandler::removeCallbacks); idleStop = null }
    }

    private var surface: Surface? = null
    private var owner: IBinder? = null
    private var death: IBinder.DeathRecipient? = null
    private var focusGeneration = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            ATTACH -> {
                @Suppress("DEPRECATION")
                val next = message.data.getParcelable<Surface>("surface")
                @Suppress("DEPRECATION")
                val audioPipe = message.data.getParcelable<ParcelFileDescriptor>("audio")
                val client = message.replyTo
                if (next == null || !next.isValid || client == null || surface != null) {
                    next?.release()
                    try { audioPipe?.close() } catch (_: java.io.IOException) { }
                    reply(client, false)
                } else {
                    val token = client.binder
                    val watcher = IBinder.DeathRecipient {
                        Handler(Looper.getMainLooper()).post { if (owner == token) detach() }
                    }
                    try {
                        token.linkToDeath(watcher, 0)
                        cancelIdleStop()
                        owner = token; death = watcher; surface = next
                        App.provide(this).videoDecoder.setSurface(next)
                        active = true
                        // Optional: the client's pipe for decoded PCM, so sound reaches the car instead of the phone.
                        audioPipe?.let { AionLinkAudioSink.attach(it) }
                        scope.launch { advance(App.provide(this@AionLinkSurfaceService).commManager.connectionState.value) }
                        reply(client, true, audio = audioPipe != null)
                    } catch (_: Exception) {
                        detach(); next.release(); try { audioPipe?.close() } catch (_: java.io.IOException) { }
                        reply(client, false)
                    }
                }
                true
            }
            DETACH -> { if (message.replyTo?.binder == owner) detach(); true }
            TOUCH -> {
                if (active && message.replyTo?.binder == owner) handleTouch(message.data)
                else AppLog.i("AionLinkSurfaceService: bridge touch dropped at gate: active=$active ownerMatch=${message.replyTo?.binder == owner}")
                true
            }
            KEY -> {
                if (active && message.replyTo?.binder == owner) handleKey(message.data)
                else AppLog.i("AionLinkSurfaceService: bridge key dropped at gate: active=$active ownerMatch=${message.replyTo?.binder == owner}")
                true
            }
            else -> false
        }
    }
    private val messenger = Messenger(handler)

    override fun onCreate() {
        super.onCreate()
        val comm = App.provide(this).commManager
        scope.launch {
            comm.connectionState.collect { state ->
                advance(state)
            }
        }
    }

    private suspend fun advance(state: CommManager.ConnectionState) {
        if (!active) return
        val comm = App.provide(this).commManager
        when (state) {
            is CommManager.ConnectionState.Connected -> comm.startHandshake()
            is CommManager.ConnectionState.HandshakeComplete -> comm.startReading()
            is CommManager.ConnectionState.TransportStarted -> requestVideoFocus()
            else -> Unit
        }
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder
    override fun onUnbind(intent: Intent?): Boolean { detach(); return false }
    override fun onDestroy() { detach(); scope.cancel(); super.onDestroy() }

    private fun detach() {
        if (surface != null) scheduleIdleStop(this)
        AionLinkAudioSink.detach()
        active = false
        focusGeneration++
        owner?.let { token -> death?.let { token.unlinkToDeath(it, 0) } }
        owner = null; death = null
        surface?.let {
            App.provide(this).videoDecoder.detachSurfaceIfCurrent(it, "aionlink_surface_detached")
            it.release()
        }
        surface = null
    }

    /**
     * A fresh session starts its stream once focus is gained. A client that attaches to a session already
     * projecting (AionLink reconnecting) gets no keyframe from a plain gain, because Android Auto already
     * holds focus: measured 2026-11-10, the picture stayed black until the transport's own escalation cycled
     * focus about 60 s later. So the retries, which only run while nothing has rendered on this surface,
     * do a full release/regain cycle, the same one the warm relaunch path uses.
     */
    private fun requestVideoFocus() {
        val generation = ++focusGeneration
        val component = App.provide(this)
        val handler = Handler(Looper.getMainLooper())
        fun current() = active && generation == focusGeneration &&
            component.commManager.connectionState.value is CommManager.ConnectionState.TransportStarted
        handler.post { if (current()) component.commManager.retakeVideoFocusForKeyframe() }
        for (delay in longArrayOf(1000, 3000)) {
            handler.postDelayed({
                if (current() && component.videoDecoder.lastFrameRenderedMs == 0L) {
                    val cycle = component.commManager.releaseVideoFocusForKeyframe() ?: return@postDelayed
                    handler.postDelayed({
                        if (!component.commManager.retakeVideoFocusForKeyframe(cycle)) cycle.transport.endFocusCycle()
                    }, WarmRelaunchKeyframePolicy.FOCUS_CYCLE_GAP_MS)
                }
            }, delay)
        }
    }

    // Temporary trace for the AionLink car test: log every bridge touch (declared frame, negotiated
    // size, mapper verdict, mapped pointers) so a tap can be followed from the car to Android Auto.
    // Tap positions are screen coordinates only; nothing about the driver is logged.
    private fun handleTouch(data: Bundle) {
        val ids = data.getIntArray("ids") ?: return
        val xs = data.getIntArray("xs") ?: return
        val ys = data.getIntArray("ys") ?: return
        val action = data.getInt("action")
        val declaredW = data.getInt("width")
        val declaredH = data.getInt("height")
        val negotiatedW = HeadUnitScreenConfig.getNegotiatedWidth()
        val negotiatedH = HeadUnitScreenConfig.getNegotiatedHeight()
        val mapped = AionLinkTouchMapper.map(action, ids, xs, ys, declaredW, declaredH, negotiatedW, negotiatedH)
        if (mapped == null) {
            AppLog.i("AionLinkSurfaceService: bridge touch rejected by mapper: action=$action pointers=${ids.size} declared=${declaredW}x${declaredH} negotiated=${negotiatedW}x${negotiatedH}")
            return
        }
        AppLog.i("AionLinkSurfaceService: bridge touch action=${mapped.action} index=${mapped.index} pointers=${mapped.pointers} negotiated=${negotiatedW}x${negotiatedH}")
        App.provide(this).commManager.send(TouchEvent(SystemClock.elapsedRealtime(), mapped.action, mapped.index, mapped.pointers))
    }

    // Car steering-wheel transport control (next/previous/play/pause) from the AionLink bridge.
    // Forwarded to Android Auto the same way a head unit's own keys are: CommManager.sendKey
    // applies the media-key routing setting, remapping and debounce. The AionLink side only
    // sends media keys over the bridge, so nothing else arrives here.
    private fun handleKey(data: Bundle) {
        val action = data.getInt("action")
        val keyCode = data.getInt("keyCode")
        AppLog.i("AionLinkSurfaceService: bridge key action=$action keyCode=$keyCode")
        App.provide(this).commManager.sendKey(keyCode, action == KeyEvent.ACTION_DOWN, null, "aionlink-bridge-key")
    }

    private fun reply(target: Messenger?, ok: Boolean, audio: Boolean = false) {
        try { target?.send(Message.obtain(null, STATUS).apply { data = Bundle().apply {
            putBoolean("attached", ok); putBoolean("audio", ok && audio)
        } }) }
        catch (_: android.os.RemoteException) { detach() }
    }
}
