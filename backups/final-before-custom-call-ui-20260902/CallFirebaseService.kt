package com.example.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CallFirebaseService : FirebaseMessagingService() {

    private val serviceScope =
        CoroutineScope(Dispatchers.Default)

    override fun onMessageReceived(message: RemoteMessage) {

        val type = message.data["type"]

        if (
            type == "call_cancelled" ||
            type == "call_reject" ||
            type == "hangup" ||
            type == "timeout" ||
            type == "disconnected"
        ) {
            val callId =
                message.data["call_id"]
                    ?.trim()
                    .orEmpty()

            if (callId.isEmpty()) return

            // The native service only records a terminal tombstone. Flutter
            // reconciles signalling/media when it is active; no Telecom call
            // exists to disconnect here.
            serviceScope.launch {
                  try {
                      markCallEnded(callId)

                      println(
                          "[CN CALL][FCM] " +
                              "FCM TERMINAL HANDLED " +
                              "type=$type call_id=$callId"
                      )

                  } catch (error: Throwable) {
                      println("[CN CALL][FCM] terminal persistence failed call_id=$callId error=$error")
                  }
              }

return
        }

        if (type != "incoming_call") {
            return
        }

        val callerName =
            message.data["caller_name"]
                ?: "CN CALL"

        val callerId =
            message.data["caller_id"]
                ?: message.data["from_id"]
                ?: ""

        if (callerId.isEmpty()) {
            return
        }

        val callId = message.data["call_id"]?.trim().orEmpty()
        if (callId.isEmpty()) return
        if (isCallEnded(callId)) {
            println("CN CALL: ignored stale incoming FCM. callId=$callId")
            return
        }

        // Persist the payload and launch Flutter's own call screen. This path
        // intentionally does not create a Telecom or CallStyle UI.
        try {
            getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
                .edit()
                .putString("flutter.pending_incoming_call", JSONObject().apply {
                    put("type", "incoming_call")
                    put("call_id", callId)
                    put("caller_id", callerId)
                    put("from_id", callerId)
                    put("caller_name", callerName)
                }.toString())
                .apply()

            showIncomingWakeNotification(callId, callerId, callerName)

            println("[CN CALL][FCM] Flutter incoming launched call_id=$callId")
        } catch (e: Exception) {
            println("[CN CALL][FCM] incoming launch failed call_id=$callId error=$e")
        }

        return
    }

    private fun isCallEnded(callId: String): Boolean {
        val prefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
        return endedCallIds(prefs).contains(callId)
    }

    private fun markCallEnded(callId: String) {
        getSystemService(NotificationManager::class.java)
            .cancel(incomingNotificationId(callId))
        val prefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
        val endedIds = endedCallIds(prefs).toMutableList()
        endedIds.remove(callId)
        endedIds.add(callId)
        if (endedIds.size > 32) {
            endedIds.subList(0, endedIds.size - 32).clear()
        }

        val editor = prefs.edit().putString(
            "flutter.cn_call_ended_call_ids_v2",
            JSONArray(endedIds).toString()
        )
        val pending = prefs.getString("flutter.pending_incoming_call", null)
        val pendingId = try {
            JSONObject(pending ?: "{}").optString("call_id")
        } catch (_: Exception) {
            ""
        }
        if (pendingId == callId) {
            editor.remove("flutter.pending_incoming_call")
        }
        if (prefs.getString("flutter.cn_call_active_call_id", null) == callId) {
            editor.remove("flutter.cn_call_active_call_id")
            editor.remove("flutter.cn_call_active_call_at")
        }
        editor.apply()
    }

    private fun endedCallIds(prefs: android.content.SharedPreferences): List<String> {
        val encoded = prefs.getString("flutter.cn_call_ended_call_ids_v2", "[]")
        return try {
            val values = JSONArray(encoded ?: "[]")
            List(values.length()) { index -> values.optString(index).trim() }
                .filter { it.isNotEmpty() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Android blocks arbitrary background activity starts. A full-screen
     * PendingIntent is the supported wake path for a time-sensitive call; it
     * opens MainActivity, whose Flutter UI renders all controls. This is a
     * plain application notification, not Telecom/CallStyle/InCallUI.
     */
    private fun showIncomingWakeNotification(
        callId: String,
        callerId: String,
        callerName: String,
    ) {
        val channelId = "cn_call_incoming_wake"
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "CN CALL incoming calls",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    // The default device ringtone is started explicitly by
                    // Flutter after the activity is shown; avoid a duplicate
                    // notification sound here.
                    setSound(null, null)
                },
            )
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_INCOMING_CALL
            putExtra("call_id", callId)
            putExtra("caller_id", callerId)
            putExtra("caller_name", callerName)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            incomingNotificationId(callId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val allowed = manager.canUseFullScreenIntent()
            println("[CN CALL][FULLSCREEN] allowed=$allowed")
        }

        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(callerName.ifBlank { "CN CALL" })
            .setContentText("مكالمة واردة عبر CN CALL")
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(Notification.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .build()
        manager.notify(incomingNotificationId(callId), notification)
    }

    private fun incomingNotificationId(callId: String): Int =
        41000 + (callId.hashCode() and 0x0fff)
}
