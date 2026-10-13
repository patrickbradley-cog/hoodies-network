package com.gap.hoodies_network.sample

data class Greeting(val message: String, val requestNumber: Int)

data class Note(val title: String, val body: String)

data class EchoResponse(val method: String, val received: Note)

data class ClockReading(val hit: Int, val generatedAt: String)

data class SecureStatus(val status: String)
