package au.com.setec.rvmaster.ota.appota

import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal class Tls12SocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        enableTLSOnSocket(delegate.createSocket(s, host, port, autoClose))

    override fun createSocket(host: String, port: Int): Socket =
        enableTLSOnSocket(delegate.createSocket(host, port))

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        enableTLSOnSocket(delegate.createSocket(host, port, localHost, localPort))

    override fun createSocket(host: InetAddress, port: Int): Socket =
        enableTLSOnSocket(delegate.createSocket(host, port))

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        enableTLSOnSocket(delegate.createSocket(address, port, localAddress, localPort))

    private fun enableTLSOnSocket(socket: Socket): Socket {
        if (socket is SSLSocket) {
            val supportedProtocols = socket.supportedProtocols
            if (!supportedProtocols.isNullOrEmpty()) {
                val enabledProtocols = supportedProtocols.filter {
                    it == "TLSv1.2" || it == "TLSv1.3" || it == "TLSv1.1" || it == "TLSv1"
                }.toTypedArray()
                if (enabledProtocols.isNotEmpty()) {
                    socket.enabledProtocols = enabledProtocols
                }
            }
        }
        return socket
    }
}
