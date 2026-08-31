package com.example.mobile

import org.json.JSONArray
import org.json.JSONObject
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class CallFirebaseService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {

        val type = message.data["type"]

            if (type == "call_cancelled" || type == "call_reject" || type == "hangup" || type == "timeout" || type == "disconnected") {
                val callId = message.data["call_id"] ?: return

                CallConnectionService.disconnectCall(callId)

                markCallEnded(callId)

                println(
                    "CN CALL Telecom: FCM terminal event handled " +
                        "type=$type callId=$callId"
                )

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

        // System-managed ConnectionService is the only incoming-call path.
        try {
            val submitted = TelecomHelper.addIncomingCall(
                context = this,
                callerId = callerId,
                callerName = callerName,
                callId = callId,
            )

            if (submitted) {
                // Keep native FCM and Flutter on the same per-call guard. This
                // prevents a second incoming call from replacing the Samsung
                // InCallUI call while Flutter is terminated.
                getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
                    .edit()
                    .putString("flutter.cn_call_active_call_id", callId)
                    .putLong("flutter.cn_call_active_call_at", System.currentTimeMillis())
                    .apply()
            }

            println("[CN CALL][CALL UI RINGING] submitted=$submitted call_id=$callId callerId=$callerId")
            return
        } catch (e: Exception) {
            println(
                "CN CALL Telecom: incoming call failed. " +
                    "error=$e"
            )
        }
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
