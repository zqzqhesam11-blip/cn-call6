package com.example.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Notification helper for Core-Telecom.
 *
 * This is intentionally independent from CallForegroundService for now.
 * It will become the single call notification once Core-Telecom is live.
 */
object CoreTelecomNotification {

    private const val CHANNEL_ID = "cn_call_core_telecom_calls"


    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager =
            context.getSystemService(NotificationManager::class.java)

        val channel = NotificationChannel(
            CHANNEL_ID,
            "CN CALL",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "CN CALL voice calls"
            setShowBadge(true)
        }

        manager.createNotificationChannel(channel)
    }

    fun showIncoming(
        context: Context,
        callId: String,
        callerName: String,
        callerId: String,
    ) {
        createChannel(context)

        val appContext = context.applicationContext

        val answerIntent = PendingIntent.getBroadcast(
            appContext,
            requestCode(callId, 1),
            Intent(
                appContext,
                CoreTelecomActionReceiver::class.java,
            ).apply {
                action = CoreTelecomActionReceiver.ACTION_ACCEPT
                putExtra(CoreTelecomActionReceiver.EXTRA_CALL_ID, callId)
                putExtra(CoreTelecomActionReceiver.EXTRA_PEER_ID, callerId)
                putExtra(CoreTelecomActionReceiver.EXTRA_NAME, callerName)
            },
            pendingIntentFlags(),
        )

        val declineIntent = PendingIntent.getBroadcast(
            appContext,
            requestCode(callId, 2),
            Intent(
                appContext,
                CoreTelecomActionReceiver::class.java,
            ).apply {
                action = CoreTelecomActionReceiver.ACTION_REJECT
                putExtra(CoreTelecomActionReceiver.EXTRA_CALL_ID, callId)
                putExtra(CoreTelecomActionReceiver.EXTRA_PEER_ID, callerId)
                putExtra(CoreTelecomActionReceiver.EXTRA_NAME, callerName)
            },
            pendingIntentFlags(),
        )

        val person = android.app.Person.Builder()
            .setName(callerName.ifBlank { callerId })
            .build()

        val builder = Notification.Builder(
            appContext,
            CHANNEL_ID,
        )
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setStyle(
                Notification.CallStyle.forIncomingCall(
                    person,
                    declineIntent,
                    answerIntent,
                )
            )
        }

        appContext
            .getSystemService(NotificationManager::class.java)
            .notify(
                notificationId(callId, 1),
                builder.build(),
            )

        println(
            "[CN CALL][CORE TELECOM] INCOMING CALLSTYLE SHOWN " +
                "call_id=$callId"
        )
    }

    fun showOngoing(
        context: Context,
        callId: String,
        remoteName: String,
    ) {
        createChannel(context)

        val appContext = context.applicationContext

        val hangupIntent = PendingIntent.getBroadcast(
            appContext,
            requestCode(callId, 3),
            Intent(
                appContext,
                CoreTelecomActionReceiver::class.java,
            ).apply {
                action = CoreTelecomActionReceiver.ACTION_DISCONNECT
                putExtra(CoreTelecomActionReceiver.EXTRA_CALL_ID, callId)
                putExtra(
                    CoreTelecomActionReceiver.EXTRA_PEER_ID,
                    remoteName,
                )
            },
            pendingIntentFlags(),
        )

        val person = android.app.Person.Builder()
            .setName(remoteName)
            .build()

        val builder = Notification.Builder(
            appContext,
            CHANNEL_ID,
        )
            .setSmallIcon(android.R.drawable.sym_call_outgoing)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setStyle(
                Notification.CallStyle.forOngoingCall(
                    person,
                    hangupIntent,
                )
            )
        }

        appContext
            .getSystemService(NotificationManager::class.java)
            .notify(
                notificationId(callId, 2),
                builder.build(),
            )

        println(
            "[CN CALL][CORE TELECOM] ONGOING CALLSTYLE SHOWN " +
                "call_id=$callId"
        )
    }

    fun cancelIncoming(
        context: Context,
        callId: String,
    ) {
        context.applicationContext
            .getSystemService(NotificationManager::class.java)
            .cancel(notificationId(callId, 1))
    }

    fun cancelOngoing(
        context: Context,
        callId: String,
    ) {
        context.applicationContext
            .getSystemService(NotificationManager::class.java)
            .cancel(notificationId(callId, 2))
    }

    fun cancelAll(context: Context) {
        val manager =
            context.applicationContext
                .getSystemService(NotificationManager::class.java)

        /*
         * Notification IDs are per-call, so there is no single global
         * incoming/ongoing notification ID to cancel safely.
         */
    }

    private fun notificationId(
        callId: String,
        type: Int,
    ): Int {
        var value = callId.hashCode() and 0x7fffffff
        value = (value % 1000000) * 10 + type
        return value
    }

    private fun requestCode(
        callId: String,
        action: Int,
    ): Int {
        var value = callId.hashCode() and 0x7fffffff
        value = (value % 100000) * 10 + action
        return value
    }

    private fun pendingIntentFlags(): Int {
        return PendingIntent.FLAG_UPDATE_CURRENT or
            PendingIntent.FLAG_IMMUTABLE
    }
}
