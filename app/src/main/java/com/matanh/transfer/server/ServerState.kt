package com.matanh.transfer.server

sealed class ServerState {
    object Starting : ServerState()
    data class Running(val ip: String, val port: Int) : ServerState()
    object UserStopped : ServerState()
    object AwaitNetwork: ServerState()
    data class Error(val message: String) : ServerState()
}