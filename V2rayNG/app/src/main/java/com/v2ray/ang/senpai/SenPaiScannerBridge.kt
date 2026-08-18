package com.v2ray.ang.senpai

import com.matinsenpai.senpaiscanner.Callback
import com.matinsenpai.senpaiscanner.Mobile

object SenPaiScannerBridge {

    fun startScan(configJson: String, callback: Callback) {
        Mobile.startScan(configJson, callback)
    }

    fun stopScan() {
        Mobile.stopScan()
    }

    fun isRunning(): Boolean {
        return Mobile.isRunning()
    }
}
