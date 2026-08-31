package com.example.mobile

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager

object TelecomHelper {

    private const val ACCOUNT_ID = "cn_call"

    fun register(context: Context) {
        val telecomManager =
            context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager

        val component = ComponentName(
            context,
            CallConnectionService::class.java
        )

        val handle = PhoneAccountHandle(
            component,
            ACCOUNT_ID
        )

        val account = PhoneAccount.builder(
            handle,
            "CN CALL"
        )
            .setCapabilities(
                PhoneAccount.CAPABILITY_CALL_PROVIDER
            )
            .addSupportedUriScheme("tel")
            .build()

        telecomManager.registerPhoneAccount(account)
    }

    fun isEnabled(context: Context): Boolean {
        if (!hasPhoneAccountPermission(context)) return false
        val telecomManager =
            context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        return try {
            telecomManager.getPhoneAccount(getHandle(context))?.isEnabled == true
        } catch (error: SecurityException) {
            println("CN CALL Telecom: PhoneAccount access denied: $error")
            false
        }
    }

    /**
     * Reports a network call to the system-managed Telecom stack.  Registration
     * alone is not sufficient: managed accounts must be enabled by the user in
     * the Phone app before Telecom is permitted to bind the ConnectionService.
     */
    fun addIncomingCall(
        context: Context,
        callerId: String,
        callerName: String,
        callId: String,
    ): Boolean {
        if (callId.isBlank() || callerId.isBlank()) return false
        if (!hasPhoneAccountPermission(context)) {
            println("CN CALL Telecom: READ_PHONE_NUMBERS is not granted")
            return false
        }
        register(context)

        val telecomManager =
            context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager

        val handle = getHandle(context)

        val account = try {
            telecomManager.getPhoneAccount(handle)
        } catch (error: SecurityException) {
            println("CN CALL Telecom: PhoneAccount access denied: $error")
            return false
        }
        if (account?.isEnabled != true) {
            println("CN CALL Telecom: PhoneAccount is not enabled by the user")
            return false
        }

        if (!telecomManager.isIncomingCallPermitted(handle)) {
            println("CN CALL Telecom: incoming call is not permitted")
            return false
        }

        val extras = Bundle().apply {
            putParcelable(
                TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                Uri.fromParts("tel", callerId, null)
            )
            putString(CallConnectionService.EXTRA_CALL_ID, callId)
            putString(CallConnectionService.EXTRA_CALLER_ID, callerId)
            putString(CallConnectionService.EXTRA_CALLER_NAME, callerName)
        }

        return try {
            telecomManager.addNewIncomingCall(handle, extras)
            true
        } catch (error: SecurityException) {
            println("CN CALL Telecom: incoming call permission denied: $error")
            false
        }
    }

    fun getHandle(context: Context): PhoneAccountHandle {
        val component = ComponentName(
            context,
            CallConnectionService::class.java
        )

        return PhoneAccountHandle(
            component,
            ACCOUNT_ID
        )
    }

    fun hasPhoneAccountPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.checkSelfPermission(android.Manifest.permission.READ_PHONE_NUMBERS) ==
                PackageManager.PERMISSION_GRANTED
}
