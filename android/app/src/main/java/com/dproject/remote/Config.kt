package com.dproject.remote

object Config {
    // For real device on same WiFi: ws://192.168.1.114:3000
    // For Android emulator:         ws://10.0.2.2:3000
    // For production:               wss://your-deployed-server.com
    const val SERVER_WS   = "wss://dproject-server.onrender.com"
    const val SERVER_HTTP = "https://dproject-server.onrender.com"
}
