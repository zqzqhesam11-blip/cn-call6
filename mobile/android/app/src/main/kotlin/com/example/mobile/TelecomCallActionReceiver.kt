package com.example.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugins.GeneratedPluginRegistrant
import org.json.JSONArray
import org.json.JSONObject

/** Bridges commands from the system InCall UI to the one Flutter/LiveKit path. */
class TelecomCallActionReceiver : BroadcastReceiver() {
    companion object {
        private const val ENGINE_ID = "cn_call_telecom_background_engine"
        const val EXTRA_IS_MUTED = "is_muted"
        private const val EVENTS_CHANNEL = "cn_call/telecom_events"
        private const val ACTION_QUEUE_KEY = "flutter.cn_call_telecom_actions_v1"
        private const val CALL_CHANNEL = "cn_call/call"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val event = intent ?: return
        val action = when (event.action) {
            CallConnectionService.ACTION_CALL_ACCEPT -> "accept"
            CallConnectionService.ACTION_CALL_REJECT -> "reject"
            CallConnectionService.ACTION_CALL_DISCONNECT -> "ended"
            CallConnectionService.ACTION_CALL_OUTGOING -> "outgoing"
            CallConnectionService.ACTION_CALL_MUTE_CHANGED -> "mute"
            else -> return
        }
        val callId = event.getStringExtra(CallConnectionService.EXTRA_CALL_ID).orEmpty().trim()
        val peerId = event.getStringExtra(CallConnectionService.EXTRA_CALLER_ID).orEmpty().trim()
        if (callId.isEmpty() || (action != "mute" && peerId.isEmpty())) {
            println("CN CALL Telecom: ignored $action without call identity")
            return
        }

        val prefs = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        // Mute is an independent Telecom callback.  It must never overwrite a
        // pending accept/outgoing/reject/end action while a cold Dart engine is
        // starting, otherwise the call would reach InCallUI without LiveKit.
        if (action == "mute") {
            prefs.edit()
                .putString("flutter.cn_call_telecom_mute_call_id", callId)
                .putBoolean("flutter.cn_call_telecom_is_muted", event.getBooleanExtra(EXTRA_IS_MUTED, false))
                .apply()
            dispatchMuteToFlutter(context, callId, event.getBooleanExtra(EXTRA_IS_MUTED, false))
            return
        }

        enqueueAction(prefs, action, callId, peerId)
        println("[CN CALL][CALL ACTION QUEUED] action=$action call_id=$callId")

        when (action) {
            "accept", "outgoing" -> {
                // Incoming ACCEPT may need the foreground service immediately
                // after the user answers from Samsung InCallUI. Outgoing calls
                // stay in Telecom DIALING while the app-managed ringback plays;
                // the phone-call FGS is started later when Telecom becomes ACTIVE.
                if (action == "accept") {
                    CallForegroundService.start(context, callId, peerId, "CN CALL")
                }

                val existingEngine = FlutterEngineCache.getInstance().get(ENGINE_ID)
                startBackgroundFlutter(context)
                existingEngine?.let { dispatchActionToFlutter(it, action, callId, peerId) }
            }
            "reject", "ended" -> {
                CallForegroundService.stop(context)
                val existingEngine = FlutterEngineCache.getInstance().get(ENGINE_ID)
                startBackgroundFlutter(context)
                existingEngine?.let { dispatchActionToFlutter(it, action, callId, peerId) }
            }
        }
    }

    private fun startBackgroundFlutter(context: Context) {
        val cache = FlutterEngineCache.getInstance()
        if (cache.get(ENGINE_ID) != null) return
        val engine = FlutterEngine(context.applicationContext)
        GeneratedPluginRegistrant.registerWith(engine)
        installNativeCallChannel(engine)
        cache.put(ENGINE_ID, engine)
        engine.dartExecutor.executeDartEntrypoint(
            DartExecutor.DartEntrypoint("flutter_assets", "package:mobile/main.dart", "telecomBackgroundMain")
        )
        println("CN CALL Telecom: headless Flutter engine started")
    }

    /** The cached headless engine is not MainActivity's engine.  It therefore
     * needs the same native call channel, otherwise cleanup/activation throws
     * MissingPluginException after Telecom ANSWERED. */
    private fun installNativeCallChannel(engine: FlutterEngine) {
        MethodChannel(engine.dartExecutor.binaryMessenger, CALL_CHANNEL)
            .setMethodCallHandler { call, result ->
                val callId = call.argument<String>("callId").orEmpty().trim()
                when (call.method) {
                    "disconnectTelecomCall" -> {
                        CallConnectionService.disconnectCall(callId, notifyFlutter = false)
                        result.success(true)
                    }
                    "activateTelecomCall" -> result.success(CallConnectionService.activateCall(callId))
                    "failTelecomCall" -> result.success(CallConnectionService.failCall(callId))
                    else -> result.notImplemented()
                }
            }
    }

    private fun enqueueAction(
        prefs: android.content.SharedPreferences,
        action: String,
        callId: String,
        peerId: String,
    ) {
        val queue = try { JSONArray(prefs.getString(ACTION_QUEUE_KEY, "[]")) } catch (_: Exception) { JSONArray() }
        // A repeated Telecom callback must not produce a second accept/reject.
        for (index in 0 until queue.length()) {
            val existing = queue.optJSONObject(index) ?: continue
            if (existing.optString("action") == action && existing.optString("callId") == callId) return
        }
        queue.put(JSONObject().apply {
            put("action", action)
            put("callId", callId)
            put("peerId", peerId)
        })
        prefs.edit().putString(ACTION_QUEUE_KEY, queue.toString()).apply()
    }

    private fun dispatchMuteToFlutter(context: Context, callId: String, isMuted: Boolean) {
        val engine = FlutterEngineCache.getInstance().get(ENGINE_ID)
        if (engine == null) {
            startBackgroundFlutter(context)
            return
        }
        MethodChannel(engine.dartExecutor.binaryMessenger, EVENTS_CHANNEL)
            .invokeMethod("muteChanged", mapOf("callId" to callId, "isMuted" to isMuted))
    }

    private fun dispatchActionToFlutter(
        engine: FlutterEngine,
        action: String,
        callId: String,
        peerId: String
    ) {
        MethodChannel(engine.dartExecutor.binaryMessenger, EVENTS_CHANNEL).invokeMethod(
            "callAction",
            mapOf("action" to action, "callId" to callId, "peerId" to peerId)
        )
    }
}
