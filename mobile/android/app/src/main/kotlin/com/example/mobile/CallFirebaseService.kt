package com.example.mobile

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

            /*
             * Remote terminal FCM must use the same Core-Telecom owner
             * as every other terminal event.
             *
             * Never call CallConnectionService here. It is rollback-only.
             */
            serviceScope.launch {
                  try {
                      CoreTelecomCallBridge.disconnectCall(
                          callId = callId,
                          reason = when (type) {
                              "call_reject" -> "rejected"
                              "timeout" -> "timeout"
                              "call_cancelled" -> "cancelled"
                              "disconnected" -> "remote"
                              else -> "remote"
                          },
                      )

                      markCallEnded(callId)

                      println(
                          "[CN CALL][CORE TELECOM] " +
                              "FCM TERMINAL HANDLED " +
                              "type=$type call_id=$callId"
                      )

                  } catch (error: Throwable) {
                      println(
                          "[CN CALL][CORE TELECOM] " +
                              "FCM TERMINAL FAILED " +
                              "type=$type call_id=$callId error=$error"
                      )
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

        /*
         * FCM can wake the process while the Flutter application is fully
         * terminated. Core-Telecom must therefore be able to create the
         * incoming system call directly from this Firebase service.
         *
         * Do NOT route this path through TelecomHelper/ConnectionService.
         */
        try {
            CoreTelecomManager.register(this)

            CoreTelecomCallBridge.submitIncoming(
                context = this,
                callId = callId,
                callerId = callerId,
                callerName = callerName,
            )

            getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
                .edit()
                .putString(
                    "flutter.cn_call_active_call_id",
                    callId,
                )
                .putLong(
                    "flutter.cn_call_active_call_at",
                    System.currentTimeMillis(),
                )
                .apply()

            println(
                "[CN CALL][CORE TELECOM][FCM INCOMING SUBMITTED] " +
                    "call_id=$callId callerId=$callerId"
            )
        } catch (e: Exception) {
            println(
                "[CN CALL][CORE TELECOM][FCM INCOMING FAILED] " +
                    "call_id=$callId error=$e"
            )
        }

        return
    }

    private fun isCallEnded(callId: String): Boolean {
        val prefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
        return endedCallIds(prefs).contains(callId)
    }

    private fun markCallEnded(callId: String) {
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
}
