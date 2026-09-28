package com.dproject.remote.webrtc

import android.util.Log
import com.dproject.remote.service.RemoteInputService
import okhttp3.*
import org.json.JSONObject

class SignallingClient(
    private val sessionId: String,
    private val role: String,
    private val serverUrl: String,
    private val webRTCManager: WebRTCManager,
) {
    private val TAG = "SignallingClient"
    private val client = OkHttpClient()
    private var ws: WebSocket? = null

    fun connect() {
        val request = Request.Builder()
            .url("$serverUrl?session=$sessionId&role=$role")
            .build()

        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WS open as $role")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(JSONObject(text))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WS failure: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WS closed: $reason")
            }
        })
    }

    private fun handleMessage(msg: JSONObject) {
        when (msg.getString("type")) {
            "peer-joined" -> {
                // Browser joined → phone is answerer, wait for offer
                Log.d(TAG, "Peer joined: ${msg.getString("role")}")
            }

            "offer" -> {
                val sdp = msg.getString("sdp")
                webRTCManager.handleOffer(sdp) { answerSdp ->
                    send(JSONObject().apply {
                        put("type", "answer")
                        put("sdp", answerSdp)
                    })
                }
            }

            "ice" -> {
                val candidate = msg.getJSONObject("candidate")
                webRTCManager.addIceCandidate(
                    candidate.getString("candidate"),
                    candidate.getString("sdpMid"),
                    candidate.getInt("sdpMLineIndex")
                )
            }

            // Control events from browser
            "tap"  -> RemoteInputService.performTap(
                msg.getDouble("x").toFloat(),
                msg.getDouble("y").toFloat()
            )
            "swipe" -> RemoteInputService.performSwipe(
                msg.getDouble("x1").toFloat(), msg.getDouble("y1").toFloat(),
                msg.getDouble("x2").toFloat(), msg.getDouble("y2").toFloat(),
                msg.getLong("duration")
            )
            "nav"  -> RemoteInputService.performNav(msg.getString("action"))
            "text" -> RemoteInputService.performText(msg.getString("text"))

            "peer-left" -> Log.d(TAG, "Browser disconnected")
        }
    }

    fun sendIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        send(JSONObject().apply {
            put("type", "ice")
            put("candidate", JSONObject().apply {
                put("candidate", candidate)
                put("sdpMid", sdpMid)
                put("sdpMLineIndex", sdpMLineIndex)
            })
        })
    }

    private fun send(obj: JSONObject) {
        ws?.send(obj.toString())
    }

    fun disconnect() {
        ws?.close(1000, "Session ended")
    }
}
