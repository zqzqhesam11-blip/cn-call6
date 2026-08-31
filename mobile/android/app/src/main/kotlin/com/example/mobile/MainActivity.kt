package com.example.mobile

import android.content.Intent
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
        // Registration does not enable a managed account; the user does that
        // in Phone accounts settings before Telecom can bind this service.
        TelecomHelper.register(this)
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
                        val callerName = call.argument<String>("callerName").orEmpty().trim()
                        result.success(
                            try {
                                TelecomHelper.addIncomingCall(
                                    this, callerId, callerName.ifEmpty { "CN CALL" }, callId
                                )
                            } catch (error: SecurityException) {
                                println("CN CALL Telecom: managed account is unavailable: $error")
                                false
                            }
                        )
                    }
                    "placeOutgoingTelecomCall" -> {
                        val targetId = call.argument<String>("targetId").orEmpty().trim()
                        if (targetId.isEmpty()) {
                            result.success(false)
                        } else if (hasPhoneAccountPermission() && hasCallPhonePermission()) {
                            result.success(placeOutgoingTelecomCall(targetId))
                        } else {
                            pendingOutgoingTarget = targetId
                            pendingOutgoingResult = result
                            requestPhoneNumbersPermissionOrFail()
                        }
                    }
                    "disconnectTelecomCall" -> {
                        val callId = call.argument<String>("callId").orEmpty().trim()
                        if (callId.isEmpty()) result.success(false)
                        else {
                            CallConnectionService.disconnectCall(callId, notifyFlutter = false)
                            result.success(true)
                        }
                    }
                    "activateTelecomCall" -> {
                        result.success(CallConnectionService.activateCall(
                            call.argument<String>("callId").orEmpty().trim()
                        ))
                    }
                    "failTelecomCall" -> {
                        result.success(CallConnectionService.failCall(
                            call.argument<String>("callId").orEmpty().trim()
                        ))
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
        TelecomHelper.register(this)
        if (!hasPhoneAccountPermission()) return false
        val manager = getSystemService(TELECOM_SERVICE) as TelecomManager
        val account = TelecomHelper.getHandle(this)
        val enabled = try { manager.getPhoneAccount(account)?.isEnabled == true } catch (_: SecurityException) { false }
        if (!enabled || !manager.isOutgoingCallPermitted(account)) {
            println("CN CALL Telecom: managed account unavailable for outgoing call")
            return false
        }
        return try {
            manager.placeCall(Uri.fromParts("tel", targetId, null), Bundle().apply {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account)
            })
            true
        } catch (error: SecurityException) {
            println("CN CALL Telecom: unable to place managed call: $error")
            false
        }
    }
}
