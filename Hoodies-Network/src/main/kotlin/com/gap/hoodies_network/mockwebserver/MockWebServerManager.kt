package com.gap.hoodies_network.mockwebserver

import android.util.Log
import com.gap.hoodies_network.utils.Generated
import com.sun.net.httpserver.Headers
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.net.ServerSocketFactory

private const val TAG = "MockWebServerManager"

/**
 * This class manages the MockWebServer
 */
@Generated
class MockWebServerManager(builder: Builder) {

    private val port = builder.port
    private val contexts = builder.context.toList().sortedByDescending { it.first.length }
    private val serverSocket = AddressRecordingServerSocket()
    private val dispatchLock = Any()

    private val server = MockWebServer().apply {
        serverSocketFactory = SingleServerSocketFactory(serverSocket)
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                synchronized(dispatchLock) { handle(request) }
        }
    }

    /**
     * Starts the MockWebServer
     */
    fun start() = apply {
        server.start(InetSocketAddress(port).address, port)
    }

    /**
     * Stops the MockWebServer
     */
    fun stop() {
        try {
            server.close()
        } catch (e: AssertionError) {
            // Raised when a handler is still running after the listening socket has been released
            Log.w(TAG, "MockWebServer stopped while a request was still being handled", e)
        }
    }

    private fun handle(request: RecordedRequest): MockResponse {
        val uri = URI(request.target)
        val handler = contexts.firstOrNull { uri.path.orEmpty().startsWith(it.first) }?.second
            ?: return notFound()

        val addresses = serverSocket.connections[request.connectionIndex]
        val requestHeaders = Headers()
        for ((name, value) in request.headers) requestHeaders.add(name, value)

        val exchange = MockHttpExchange(
            requestMethod = request.method,
            requestURI = uri,
            protocol = request.version,
            requestHeaders = requestHeaders,
            requestBody = request.body?.toByteArray() ?: ByteArray(0),
            remoteAddress = addresses?.remote ?: InetSocketAddress(0),
            localAddress = addresses?.local ?: InetSocketAddress(port),
        )

        return try {
            handler.internalHandler.handle(exchange)
            exchange.toMockResponse()
                ?: MockResponse.Builder().onResponseStart(SocketEffect.Stall).build()
        } catch (e: Exception) {
            Log.w(TAG, "Handler for ${request.target} threw, closing the connection", e)
            MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()
        }
    }

    private fun notFound(): MockResponse =
        MockResponse.Builder()
            .status("HTTP/1.1 404 Not Found")
            .body("<h1>404 Not Found</h1>No context found for request")
            .build()

    private class ConnectionAddresses(val remote: InetSocketAddress, val local: InetSocketAddress)

    /**
     * Records the addresses of every open connection, keyed by mockwebserver3's connection index
     */
    private class AddressRecordingServerSocket : ServerSocket() {
        val connections = ConcurrentHashMap<Int, ConnectionAddresses>()
        private var nextConnectionIndex = 0

        override fun accept(): Socket {
            val index = nextConnectionIndex++
            val socket = object : Socket() {
                override fun close() {
                    connections.remove(index)
                    super.close()
                }
            }
            implAccept(socket)
            connections[index] = ConnectionAddresses(
                socket.remoteSocketAddress as InetSocketAddress,
                socket.localSocketAddress as InetSocketAddress,
            )
            return socket
        }
    }

    private class SingleServerSocketFactory(private val serverSocket: ServerSocket) : ServerSocketFactory() {
        override fun createServerSocket(): ServerSocket = serverSocket

        override fun createServerSocket(port: Int): ServerSocket =
            serverSocket.apply { bind(InetSocketAddress(port)) }

        override fun createServerSocket(port: Int, backlog: Int): ServerSocket =
            serverSocket.apply { bind(InetSocketAddress(port), backlog) }

        override fun createServerSocket(port: Int, backlog: Int, ifAddress: InetAddress?): ServerSocket =
            serverSocket.apply { bind(InetSocketAddress(ifAddress, port), backlog) }
    }

    /**
     * Builder for the MockWebServer
     */
    class Builder {
        internal val context: HashMap<String, WebServerHandler> = HashMap()
        internal var port: Int = 6969

        /**
         * Called to add API endpoints to be served by the MockWebServer
         * For more details, see the WebServerHandler documentation
         */
        fun addContext(key: String, value: WebServerHandler) = apply {
            context[key] = value
        }

        /**
         * Specifies the port the MockWebServer should use. Default is 6969
         */
        fun usePort(port: Int) = apply {
            this.port = port
        }

        /**
         * Starts the MockWebServer and returns a MockWebServerManager object
         * To stop the MockWebServer, call the MockWebServerManager's stop() method
         */
        fun start() : MockWebServerManager {
            return MockWebServerManager(this).start()
        }
    }
}
