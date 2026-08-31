package com.example.mobile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The application's single, system-managed Telecom ConnectionService. */
class CallConnectionService : ConnectionService() {
    companion object {
        const val ACTION_CALL_ACCEPT = "com.example.mobile.CN_CALL_ACCEPT"
        const val ACTION_CALL_REJECT = "com.example.mobile.CN_CALL_REJECT"
        const val ACTION_CALL_DISCONNECT = "com.example.mobile.CN_CALL_DISCONNECT"
        const val ACTION_CALL_OUTGOING = "com.example.mobile.CN_CALL_OUTGOING"
        const val ACTION_CALL_MUTE_CHANGED = "com.example.mobile.CN_CALL_MUTE_CHANGED"

        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_CALLER_ID = "caller_id"
        const val EXTRA_CALLER_NAME = "caller_name"

        private val connections = ConcurrentHashMap<String, Connection>()
        private val peers = ConcurrentHashMap<String, String>()
        @Volatile private var appContext: Context? = null

        fun disconnectCall(callId: String, notifyFlutter: Boolean = true) {
            val id = callId.trim()
            val connection = connections.remove(id) ?: return
            val peerId = peers.remove(id).orEmpty()
            connection.setDisconnected(DisconnectCause(DisconnectCause.REMOTE))
            connection.destroy()
            appContext?.let { context ->
                stopForegroundIfIdle(context)
                if (notifyFlutter && peerId.isNotEmpty()) {
                    context.sendBroadcast(Intent(context, TelecomCallActionReceiver::class.java).apply {
                        action = ACTION_CALL_DISCONNECT
                        putExtra(EXTRA_CALL_ID, id)
                        putExtra(EXTRA_CALLER_ID, peerId)
                    })
                }
            }
        }

        fun activateCall(callId: String): Boolean {
            val id = callId.trim()
            val connection = connections[id] ?: return false

            println("[CN CALL][TELECOM ACTIVE] call_id=$id")

            appContext?.let { context ->
                CallForegroundService.start(
                    context,
                    id,
                    peers[id].orEmpty(),
                    "CN CALL",
                )
                println("[CN CALL][FGS START] call_id=$id")
            }

            connection.setRingbackRequested(false)
            connection.setActive()
            return true
        }

        fun failCall(callId: String): Boolean {
            val id = callId.trim()
            val connection = connections.remove(id) ?: return false
            peers.remove(id)
            connection.setRingbackRequested(false)
            connection.setDisconnected(DisconnectCause(DisconnectCause.ERROR))
            connection.destroy()
            appContext?.let(::stopForegroundIfIdle)
            return true
        }

        private fun stopForegroundIfIdle(context: Context) {
            if (connections.isEmpty()) CallForegroundService.stop(context)
        }
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle,
        request: ConnectionRequest
    ): Connection {
        val extras: Bundle = request.extras ?: Bundle.EMPTY
        val callId = extras.getString(EXTRA_CALL_ID).orEmpty().trim()
        val callerId = (request.address?.schemeSpecificPart
            ?: extras.getString(EXTRA_CALLER_ID)).orEmpty().trim()
        val callerName = extras.getString(EXTRA_CALLER_NAME).orEmpty().trim()
            .ifEmpty { callerId.ifEmpty { "CN CALL" } }

        if (callId.isEmpty() || callerId.isEmpty()) {
            return Connection.createFailedConnection(
                DisconnectCause(DisconnectCause.ERROR, "Missing incoming call identity")
            )
        }

        println("CN CALL Telecom: creating incoming callId=$callId callerId=$callerId")
        return createConnection(callId, callerId, callerName, request.address).also {
            it.setRinging()
        }
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle,
        request: ConnectionRequest
    ): Connection {
        val targetId = request.address?.schemeSpecificPart.orEmpty().trim()
        if (targetId.isEmpty()) {
            return Connection.createFailedConnection(
                DisconnectCause(DisconnectCause.ERROR, "Missing outgoing target")
            )
        }

        // A dialer-originated request has no server id; create one before
        // handing the call to Flutter and LiveKit.
        val callId = UUID.randomUUID().toString()
        println("CN CALL Telecom: creating outgoing callId=$callId targetId=$targetId")
        return createConnection(callId, targetId, targetId, request.address).also {
            it.setDialing()
            // Managed Telecom/InCallUI supplies the real network ringback.
            it.setRingbackRequested(false)
            sendCallAction(ACTION_CALL_OUTGOING, callId, targetId)
        }
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle,
        request: ConnectionRequest
    ) {
        super.onCreateIncomingConnectionFailed(connectionManagerPhoneAccount, request)
        println("CN CALL Telecom: incoming connection denied by Telecom")
        val extras = request.extras ?: Bundle.EMPTY
        val callId = extras.getString(EXTRA_CALL_ID).orEmpty().trim()
        val peerId = (request.address?.schemeSpecificPart
            ?: extras.getString(EXTRA_CALLER_ID)).orEmpty().trim()
        // Telecom rejected the UI before a Connection existed. Tell the same
        // Flutter lifecycle to reject the server call; otherwise the caller
        // would remain ringing until timeout.
        if (callId.isNotEmpty() && peerId.isNotEmpty()) {
            sendCallAction(ACTION_CALL_REJECT, callId, peerId)
        }
    }

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle,
        request: ConnectionRequest
    ) {
        super.onCreateOutgoingConnectionFailed(connectionManagerPhoneAccount, request)
        println("CN CALL Telecom: outgoing connection denied by Telecom")
    }

    private fun createConnection(
        callId: String,
        peerId: String,
        displayName: String,
        address: android.net.Uri?,
    ): Connection {
        val connection = object : Connection() {
            override fun onAnswer() {
                super.onAnswer()
                // Do not advertise ACTIVE until Flutter has connected LiveKit,
                // published the microphone and explicitly activates this call.
                println("[CN CALL][CALL ANSWER RECEIVED] call_id=$callId")
                sendCallAction(ACTION_CALL_ACCEPT, callId, peerId)
            }

            override fun onReject() {
                println("[CN CALL][CALL TERMINAL] rejected call_id=$callId")
                sendCallAction(ACTION_CALL_REJECT, callId, peerId)
                finish(DisconnectCause.REJECTED)
            }

            override fun onAbort() {
                sendCallAction(ACTION_CALL_DISCONNECT, callId, peerId)
                finish(DisconnectCause.LOCAL)
            }

            override fun onDisconnect() {
                println("[CN CALL][CALL TERMINAL] disconnected call_id=$callId")
                sendCallAction(ACTION_CALL_DISCONNECT, callId, peerId)
                finish(DisconnectCause.LOCAL)
            }

            override fun onMuteStateChanged(isMuted: Boolean) {
                super.onMuteStateChanged(isMuted)
                sendMuteChanged(callId, isMuted)
            }

            @Suppress("DEPRECATION")
            override fun onCallAudioStateChanged(state: CallAudioState) {
                super.onCallAudioStateChanged(state)
                sendMuteChanged(callId, state.isMuted)
            }

            private fun finish(cause: Int) {
                if (connections.remove(callId) != null) {
                    peers.remove(callId)
                    setRingbackRequested(false)
                    setDisconnected(DisconnectCause(cause))
                    destroy()
                    stopForegroundIfIdle(applicationContext)
                }
            }
        }

        connections[callId] = connection
        peers[callId] = peerId
        connection.setAudioModeIsVoip(true)
        connection.connectionCapabilities = Connection.CAPABILITY_MUTE
        connection.setAddress(address, TelecomManager.PRESENTATION_ALLOWED)
        connection.setCallerDisplayName(displayName, TelecomManager.PRESENTATION_ALLOWED)
        return connection
    }

    private fun sendCallAction(action: String, callId: String, peerId: String) {
        sendBroadcast(Intent(this, TelecomCallActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(EXTRA_CALLER_ID, peerId)
        })
    }

    private fun sendMuteChanged(callId: String, isMuted: Boolean) {
        sendBroadcast(Intent(this, TelecomCallActionReceiver::class.java).apply {
            action = ACTION_CALL_MUTE_CHANGED
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(TelecomCallActionReceiver.EXTRA_IS_MUTED, isMuted)
        })
    }
}
