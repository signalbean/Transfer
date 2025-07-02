package com.matanh.transfer

sealed class ServerState {
    object Starting : ServerState()
    data class Running(val ip: String, val port: Int) : ServerState()
    object Stopped : ServerState()
    data class Error(val message: String) : ServerState()
}