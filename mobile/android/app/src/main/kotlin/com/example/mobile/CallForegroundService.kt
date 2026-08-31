package com.example.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class CallForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "cn_call_ongoing_call"
        private const val NOTIFICATION_ID = 24001

        fun start(
            context: Context,
            callId: String,
            callerId: String,
            callerName: String,
        ) {
            val intent = Intent(
                context.applicationContext,
                CallForegroundService::class.java
            ).apply {
                putExtra("call_id", callId)
                putExtra("caller_id", callerId)
                putExtra("caller_name", callerName)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.applicationContext.startForegroundService(intent)
            } else {
                context.applicationContext.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(
                    context.applicationContext,
                    CallForegroundService::class.java
                )
            )
        }
    }

    override fun onCreate() {
        super.onCreate()

        createChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        val callId =
            intent?.getStringExtra("call_id")
                ?.trim()
                .orEmpty()

        val callerName =
            intent?.getStringExtra("caller_name")
                ?: "CN CALL"

        val callerId =
            intent?.getStringExtra("caller_id")
                ?: ""

        if (callId.isEmpty()) {
            println("CN CALL Telecom: refusing foreground service without callId")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val notification =
            Notification.Builder(
                this,
                CHANNEL_ID
            )
                .setSmallIcon(
                    android.R.drawable.sym_call_incoming
                )
                .setCategory(
                    Notification.CATEGORY_CALL
                )
                .setVisibility(
                    Notification.VISIBILITY_PUBLIC
                )
                .setContentTitle("CN CALL")
                .setContentText("Call in progress with ${callerId.ifEmpty { callerName }}")
                // Samsung InCallUI is the sole call UI.  This notification only
                // keeps the process eligible to run while the Telecom call is active.
                .setOngoing(true)
                .setAutoCancel(false)
                .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }

        println("CN CALL Telecom: phoneCall foreground service active")

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Incoming Calls",
                NotificationManager.IMPORTANCE_HIGH
            )

            channel.description =
                "CN CALL incoming calls"

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager.createNotificationChannel(channel)
        }
    }
}
