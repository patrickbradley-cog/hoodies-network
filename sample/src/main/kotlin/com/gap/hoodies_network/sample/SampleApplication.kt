package com.gap.hoodies_network.sample

import android.app.Application
import com.gap.hoodies_network.core.HoodiesNetworkClient
import com.gap.hoodies_network.sample.server.SampleMockServer

class SampleApplication : Application() {

    val mockServer = SampleMockServer()

    lateinit var client: HoodiesNetworkClient
        private set

    lateinit var interceptor: SampleInterceptor
        private set

    lateinit var interceptedClient: HoodiesNetworkClient
        private set

    override fun onCreate() {
        super.onCreate()
        mockServer.start()

        client = HoodiesNetworkClient.Builder()
            .baseUrl(mockServer.baseUrl)
            .addHeader("Accept", "application/json")
            .build()

        interceptor = SampleInterceptor(this)
        interceptedClient = HoodiesNetworkClient.Builder()
            .baseUrl(mockServer.baseUrl)
            .addInterceptor(interceptor)
            .build()
    }
}
