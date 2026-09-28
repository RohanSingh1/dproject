package com.dproject.remote.webrtc

import android.content.Context
import android.util.Log
import org.webrtc.*

class WebRTCManager(
    context: Context,
    private val onVideoTrackReady: (VideoTrack) -> Unit,
) {
    private val TAG = "WebRTCManager"

    private val eglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var videoTrack: VideoTrack? = null
    private var onAnswerReady: ((String) -> Unit)? = null
    private var signallingClient: SignallingClient? = null

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .createPeerConnectionFactory()

        setupVideoTrack(context)
    }

    fun setSignallingClient(client: SignallingClient) {
        signallingClient = client
    }

    private fun setupVideoTrack(context: Context) {
        val surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        val screenSource = factory.createVideoSource(true)

        // The actual surface is connected by StreamingService via VirtualDisplay
        videoTrack = factory.createVideoTrack("video0", screenSource)

        onVideoTrackReady(videoTrack!!)
    }

    private fun buildPeerConnection(): PeerConnection {
        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            )
        )

        return factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                signallingClient?.sendIceCandidate(
                    candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex
                )
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "ICE state: $state")
            }
            override fun onSignalingChange(s: PeerConnection.SignalingState?) = Unit
            override fun onIceConnectionReceivingChange(b: Boolean) = Unit
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) = Unit
            override fun onIceCandidatesRemoved(c: Array<IceCandidate>?) = Unit
            override fun onAddStream(s: MediaStream?) = Unit
            override fun onRemoveStream(s: MediaStream?) = Unit
            override fun onDataChannel(d: DataChannel?) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(r: RtpReceiver?, streams: Array<MediaStream>?) = Unit
        })!!
    }

    fun handleOffer(sdp: String, onAnswer: (String) -> Unit) {
        onAnswerReady = onAnswer
        peerConnection = buildPeerConnection()

        videoTrack?.let { track ->
            peerConnection?.addTrack(track, listOf("stream0"))
        }

        val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                peerConnection?.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(answer: SessionDescription) {
                        peerConnection?.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() { onAnswer(answer.description) }
                            override fun onSetFailure(e: String?) = Unit
                            override fun onCreateSuccess(s: SessionDescription?) = Unit
                            override fun onCreateFailure(e: String?) = Unit
                        }, answer)
                    }
                    override fun onCreateFailure(error: String?) = Log.e(TAG, "createAnswer fail: $error")
                    override fun onSetSuccess() = Unit
                    override fun onSetFailure(e: String?) = Unit
                }, MediaConstraints())
            }
            override fun onSetFailure(e: String?) = Log.e(TAG, "setRemote fail: $e")
            override fun onCreateSuccess(s: SessionDescription?) = Unit
            override fun onCreateFailure(e: String?) = Unit
        }, sessionDescription)
    }

    fun addIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        peerConnection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
    }

    fun dispose() {
        peerConnection?.close()
        factory.dispose()
        eglBase.release()
    }
}
