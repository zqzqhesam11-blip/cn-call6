package com.example.mobile

import android.content.Context
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugins.GeneratedPluginRegistrant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Owns the headless Core-Telecom bridge engine; it is never MainActivity's engine. */
object BackgroundFlutterEngine {
    const val ENGINE_ID = "cn_call_telecom_background_engine"
    private const val CALL_CHANNEL = "cn_call/call"

    @Synchronized
    fun ensureStarted(context: Context): FlutterEngine {
        val cache = FlutterEngineCache.getInstance()
        cache.get(ENGINE_ID)?.let { return it }
        val appContext = context.applicationContext
        val engine = FlutterEngine(appContext)
        GeneratedPluginRegistrant.registerWith(engine)
        MethodChannel(engine.dartExecutor.binaryMessenger, CALL_CHANNEL).setMethodCallHandler { call, result ->
            val callId = call.argument<String>("callId").orEmpty().trim()
            when (call.method) {
                "disconnectTelecomCall", "failTelecomCall" -> CoroutineScope(Dispatchers.Default).launch {
                    result.success(CoreTelecomCallBridge.disconnectCall(callId, if (call.method == "failTelecomCall") "failed" else "ended"))
                }
                "activateTelecomCall" -> CoroutineScope(Dispatchers.Default).launch {
                    result.success(CoreTelecomCallBridge.activateCall(callId))
                }
                "coreTelecomFlutterReady" -> {
                    CoreTelecomFlutterDispatcher.markFlutterReady(appContext, engine)
                    result.success(true)
                }
                "clearCoreTelecomAnswer" -> result.success(
                    CoreTelecomCallBridge.clearAnswerRequested(
                        appContext,
                        callId,
                    ),
                )
                else -> result.notImplemented()
            }
        }
        cache.put(ENGINE_ID, engine)
        engine.dartExecutor.executeDartEntrypoint(DartExecutor.DartEntrypoint("flutter_assets", "package:mobile/main.dart", "telecomBackgroundMain"))
        return engine
    }
}
