package com.dproject.remote.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import org.webrtc.*

class WebRTCManager(context: Context) {
    private val TAG = "WebRTCManager"

    private val appContext = context.applicationContext
    private val eglBase = EglBase.create()
    private val factory: PeerConnectionFactory

    private val surfaceHelper: SurfaceTextureHelper
    private val videoSource: VideoSource
    private var videoTrack: VideoTrack? = null
    private var screenCapturer: ScreenCapturerAndroid? = null

    private var peerConnection: PeerConnection? = null
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

        // isScreencast = true tells the encoder to favour sharpness over frame rate.
        surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        videoSource = factory.createVideoSource(true)
        videoTrack = factory.createVideoTrack("video0", videoSource)
    }

    fun setSignallingClient(client: SignallingClient) {
        signallingClient = client
    }

    /**
     * Start mirroring the screen. [permissionData] is the Intent returned by the
     * MediaProjection permission dialog. ScreenCapturerAndroid builds the
     * MediaProjection and VirtualDisplay itself and feeds frames into videoSource.
     */
    fun startScreenCapture(permissionData: Intent, width: Int, height: Int, fps: Int = 15) {
        val capturer = ScreenCapturerAndroid(permissionData, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped by system/user")
            }
        })
        capturer.initialize(surfaceHelper, appContext, videoSource.capturerObserver)
        capturer.startCapture(width, height, fps)
        screenCapturer = capturer
        Log.d(TAG, "Screen capture started at ${width}x${height} @ ${fps}fps")
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
        try { screenCapturer?.stopCapture() } catch (_: InterruptedException) {}
        screenCapturer?.dispose()
        peerConnection?.close()
        videoSource.dispose()
        surfaceHelper.dispose()
        factory.dispose()
        eglBase.release()
    }
}
