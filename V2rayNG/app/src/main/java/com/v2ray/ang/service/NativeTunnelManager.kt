package com.blacktun.hm.service

import com.blacktun.hm.AppConfig
import com.blacktun.hm.util.LogUtil

object NativeTunnelManager {
    @Volatile
    private var initialized = false

    @Volatile
    private var loaded = false

    fun ensureLoaded(): Boolean {
        if (initialized) {
            return loaded
        }

        synchronized(this) {
            if (initialized) {
                return loaded
            }

            try {
                System.loadLibrary("hev-socks5-tunnel")
                loaded = true
            } catch (t: Throwable) {
                loaded = false
                LogUtil.e(AppConfig.TAG, "Failed to load hev-socks5-tunnel", t)
            } finally {
                initialized = true
            }
        }

        return loaded
    }

    fun isLoaded(): Boolean = loaded
}
