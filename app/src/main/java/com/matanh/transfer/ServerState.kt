package com.matanh.transfer

sealed class ServerState {
    data class Stopped(val isFirst: Boolean) : ServerState()

    data class Running(val ip: String, val port: Int) : ServerState()
    data class Error(val message: String) : ServerState()
}
