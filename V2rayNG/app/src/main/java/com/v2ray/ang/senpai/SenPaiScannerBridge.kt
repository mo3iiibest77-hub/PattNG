package com.v2ray.ang.senpai

import com.senpaiscanner.mobile.Callback
import com.senpaiscanner.mobile.Mobile

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
