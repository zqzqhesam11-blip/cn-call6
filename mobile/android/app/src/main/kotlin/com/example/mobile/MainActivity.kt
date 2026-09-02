package com.example.mobile

import android.content.Intent
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.telecom.TelecomManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val channel = "cn_call/call"
    private val readPhoneNumbersRequestCode = 9000
    private val callPhoneRequestCode = 9001
    private var pendingOutgoingTarget: String? = null
    private var pendingOutgoingResult: MethodChannel.Result? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.RECORD_AUDIO),
                9100
            )
        }

        // Register the VoIP app with Core-Telecom during app setup.
        // Existing Telecom call code remains untouched for now.
        CoreTelecomManager.register(this)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        // Telecom actions must target this already-running Flutter isolate.
        // Without this cache entry the receiver starts a second isolate, which
        // logs in again and creates a competing WebSocket for the same user.
        FlutterEngineCache.getInstance().put("cn_call_telecom_background_engine", flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channel)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "openTelecomSettings" -> {
                        startActivity(Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS))
                        result.success(true)
                    }
                    "isTelecomAccountEnabled" -> result.success(TelecomHelper.isEnabled(this))
                    "addIncomingTelecomCall" -> {
                        val callId = call.argument<String>("callId").orEmpty().trim()
                        val callerId = call.argument<String>("callerId").orEmpty().trim()
                        val callerName =
                            call.argument<String>("callerName").orEmpty().trim()

                        if (callId.isEmpty() || callerId.isEmpty()) {
                            result.success(false)
                            return@setMethodCallHandler
                        }

                        lifecycleScope.launch {
                            try {
                                CoreTelecomCallBridge.submitIncoming(
                                    context = this@MainActivity,
                                    callId = callId,
                                    callerId = callerId,
                                    callerName = callerName.ifEmpty { "CN CALL" },
                                )
                                result.success(true)
                            } catch (error: Throwable) {
                                println(
                                    "[CN CALL][CORE TELECOM] " +
                                        "incoming submit failed " +
                                        "call_id=$callId error=$error"
                                )
                                result.success(false)
                            }
                        }
                    }
                    "placeOutgoingTelecomCall" -> {
                        val targetId =
                            call.argument<String>("targetId").orEmpty().trim()
                        val callId =
                            call.argument<String>("callId").orEmpty().trim()
                        val targetName =
                            call.argument<String>("targetName").orEmpty().trim()

                        if (targetId.isEmpty() || callId.isEmpty()) {
                            result.success(false)
                            return@setMethodCallHandler
                        }

                        lifecycleScope.launch {
                            try {
                                CoreTelecomCallBridge.submitOutgoing(
                                    context = this@MainActivity,
                                    callId = callId,
                                    targetId = targetId,
                                    targetName = targetName.ifEmpty { targetId },
                                )

                                result.success(true)
                            } catch (error: Throwable) {
                                println(
                                    "[CN CALL][CORE TELECOM] " +
                                        "outgoing submit failed " +
                                        "call_id=$callId error=$error"
                                )
                                result.success(false)
                            }
                        }
                    }
                    "disconnectTelecomCall" -> {
                        val callId =
                            call.argument<String>("callId").orEmpty().trim()

                        if (callId.isEmpty()) {
                            result.success(false)
                            return@setMethodCallHandler
                        }

                        lifecycleScope.launch {
                            result.success(
                                CoreTelecomCallBridge.disconnectCall(
                                    callId = callId,
                                    reason = "ended",
                                )
                            )
                        }
                    }

                    "activateTelecomCall" -> {
                        val callId =
                            call.argument<String>("callId").orEmpty().trim()

                        if (callId.isEmpty()) {
                            result.success(false)
                            return@setMethodCallHandler
                        }

                        lifecycleScope.launch {
                            result.success(
                                CoreTelecomCallBridge.activateCall(callId)
                            )
                        }
                    }

                    "failTelecomCall" -> {
                        val callId =
                            call.argument<String>("callId").orEmpty().trim()

                        if (callId.isEmpty()) {
                            result.success(false)
                            return@setMethodCallHandler
                        }

                        lifecycleScope.launch {
                            result.success(
                                CoreTelecomCallBridge.disconnectCall(
                                    callId = callId,
                                    reason = "failed",
                                )
                            )
                        }
                    }

                    "coreTelecomFlutterReady" -> {
                        CoreTelecomFlutterDispatcher.markFlutterReady(
                            context = this@MainActivity,
                            engine = flutterEngine,
                        )
                        result.success(true)
                    }
                    else -> result.notImplemented()
                }
            }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == readPhoneNumbersRequestCode) {
            if (hasPhoneAccountPermission()) {
                if (hasCallPhonePermission()) finishPendingOutgoing()
                else requestPermissions(arrayOf(android.Manifest.permission.CALL_PHONE), callPhoneRequestCode)
            } else {
                finishPendingOutgoing()
            }
            return
        }
        if (requestCode != callPhoneRequestCode) return
        finishPendingOutgoing()
    }

    private fun finishPendingOutgoing() {
        val targetId = pendingOutgoingTarget
        val result = pendingOutgoingResult
        pendingOutgoingTarget = null
        pendingOutgoingResult = null
        result?.success(targetId != null && hasPhoneAccountPermission() && hasCallPhonePermission() && placeOutgoingTelecomCall(targetId))
    }

    private fun hasCallPhonePermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            checkSelfPermission(android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    private fun hasPhoneAccountPermission(): Boolean = TelecomHelper.hasPhoneAccountPermission(this)

    private fun requestPhoneNumbersPermissionOrFail() {
        if (hasPhoneAccountPermission()) {
            requestPermissions(arrayOf(android.Manifest.permission.CALL_PHONE), callPhoneRequestCode)
            return
        }
        val prefs = getSharedPreferences("cn_call_permissions", MODE_PRIVATE)
        if (prefs.getBoolean("read_phone_numbers_requested", false)) {
            finishPendingOutgoing()
            return
        }
        prefs.edit().putBoolean("read_phone_numbers_requested", true).apply()
        requestPermissions(arrayOf(android.Manifest.permission.READ_PHONE_NUMBERS), readPhoneNumbersRequestCode)
    }

    private fun placeOutgoingTelecomCall(targetId: String): Boolean {
        CoreTelecomManager.register(this)
        if (!hasPhoneAccountPermission()) return false
        val manager = getSystemService(TELECOM_SERVICE) as TelecomManager
        val account = TelecomHelper.getHandle(this)
        val enabled = try { manager.getPhoneAccount(account)?.isEnabled == true } catch (_: SecurityException) { false }
        if (!enabled || !manager.isOutgoingCallPermitted(account)) {
            println("CN CALL Telecom: managed account unavailable for outgoing call")
            return false
        }
        return try {
            manager.placeCall(Uri.fromParts("cncall", targetId, null), Bundle().apply {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account)
            })
            true
        } catch (error: SecurityException) {
            println("CN CALL Telecom: unable to place managed call: $error")
            false
        }
    }
}
