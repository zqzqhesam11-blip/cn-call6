package com.example.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.media.Ringtone
import android.media.RingtoneManager
import android.view.WindowManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** A dedicated Flutter host for CN CALL's custom incoming and active-call UI. */
class CNCallIncomingActivity : FlutterActivity() {
    private var ringtone: Ringtone? = null
    private val terminalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { finish() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 9200)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(terminalReceiver, IntentFilter(ACTION_TERMINAL), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(terminalReceiver, IntentFilter(ACTION_TERMINAL))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onDestroy() { ringtone?.stop(); unregisterReceiver(terminalReceiver); super.onDestroy() }

    override fun configureFlutterEngine(engine: FlutterEngine) {
        super.configureFlutterEngine(engine)
        MethodChannel(engine.dartExecutor.binaryMessenger, "cn_call/call").setMethodCallHandler { call, result ->
            when (call.method) {
                "incomingCallBootstrap" -> {
                    val callId = intent?.getStringExtra("call_id")?.trim().orEmpty()
                    val callerId = intent?.getStringExtra("caller_id")?.trim().orEmpty()
                    val callerName = intent?.getStringExtra("caller_name")?.trim().orEmpty()
                    if (callId.isEmpty() || callerId.isEmpty()) {
                        result.error("missing_call_identity", "CN CALL incoming Activity has no call identity", null)
                    } else {
                        result.success(mapOf("callId" to callId, "callerId" to callerId, "callerName" to callerName))
                    }
                }
                "activateTelecomCall" -> CoroutineScope(Dispatchers.Default).launch { result.success(CoreTelecomCallBridge.activateCall(call.argument<String>("callId").orEmpty())) }
                "startActiveCallForegroundService" -> result.success(CoreTelecomForegroundService.start(this, call.argument<String>("callId").orEmpty()))
                "disconnectTelecomCall" -> CoroutineScope(Dispatchers.Default).launch { result.success(CoreTelecomCallBridge.disconnectCall(call.argument<String>("callId").orEmpty(), "ended")) }
                "playDefaultRingtone" -> {
                    ringtone?.stop()
                    val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    ringtone = RingtoneManager.getRingtone(this, uri)?.also { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.isLooping = true; it.play() }
                    result.success(true)
                }
                "stopDefaultRingtone" -> { ringtone?.stop(); ringtone = null; result.success(true) }
                else -> result.notImplemented()
            }
        }
    }

    override fun getDartEntrypointFunctionName(): String = "incomingCallUiMain"

    companion object { const val ACTION_TERMINAL = "com.example.mobile.CN_CALL_TERMINAL" }
}
