package dev.mangaflow.desktop.relay

import com.sun.jna.Memory
import com.sun.jna.platform.win32.IPHlpAPI
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.ptr.IntByReference
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket

private const val PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
private const val SYNCHRONIZE = 0x00100000
private const val WAIT_TIMEOUT = 258
private const val AF_INET = 2
private const val ESTABLISHED = 5
private const val ERROR_INSUFFICIENT_BUFFER = 122
private const val TABLE_HEADER = 4
private const val ROW_BYTES = 24 // MIB_TCPROW_OWNER_PID has six DWORD fields.
private const val MAX_TABLE_BYTES = 4 * 1024 * 1024

internal fun matchesWindowsRelaySocketPeer(socket: Socket, ownerPid: Int, expectedFileTime: Long): Boolean {
    if (ownerPid <= 0 || expectedFileTime <= 0 || socket.isClosed || !socket.isConnected ||
        socket.isInputShutdown || socket.isOutputShutdown ||
        !isV4Loopback(socket.localAddress) || !isV4Loopback(socket.inetAddress)) return false
    val clientPort = socket.localPort
    val servicePort = socket.port
    if (clientPort !in 1..65535 || servicePort !in 1..65535 || clientPort == servicePort) return false
    return try {
        val kernel = Kernel32.INSTANCE
        val process = kernel.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION or SYNCHRONIZE, false, ownerPid)
            ?.takeUnless { it == WinBase.INVALID_HANDLE_VALUE } ?: return false
        try {
            val creation = WinBase.FILETIME()
            kernel.WaitForSingleObject(process, 0) == WAIT_TIMEOUT &&
                kernel.GetProcessTimes(process, creation, WinBase.FILETIME(), WinBase.FILETIME(), WinBase.FILETIME()) &&
                ((creation.dwHighDateTime.toLong() shl 32) or (creation.dwLowDateTime.toLong() and 0xffff_ffffL)) == expectedFileTime &&
                ownsConnectedPeer(clientPort, servicePort, ownerPid) &&
                kernel.WaitForSingleObject(process, 0) == WAIT_TIMEOUT &&
                !socket.isClosed && !socket.isInputShutdown && !socket.isOutputShutdown
        } finally {
            kernel.CloseHandle(process)
        }
    } catch (_: Exception) {
        false
    } catch (_: LinkageError) {
        false
    }
}

private fun ownsConnectedPeer(clientPort: Int, servicePort: Int, ownerPid: Int): Boolean {
    val api = IPHlpAPI.INSTANCE
    val bytes = IntByReference()
    val type = IPHlpAPI.TCP_TABLE_CLASS.TCP_TABLE_OWNER_PID_CONNECTIONS
    if (api.GetExtendedTcpTable(null, bytes, false, AF_INET, type, 0) != ERROR_INSUFFICIENT_BUFFER) return false
    // The fixed local table can grow while the requested buffer is allocated. Retry only its size,
    // on this same connected socket; never connect to other local addresses/ports.
    repeat(2) {
        val capacity = bytes.value
        if (capacity !in TABLE_HEADER..MAX_TABLE_BYTES) return false
        Memory(capacity.toLong()).use { data ->
            when (api.GetExtendedTcpTable(data, bytes, false, AF_INET, type, 0)) {
                0 -> return bytes.value in TABLE_HEADER..capacity &&
                    data.hasUniqueTcpPeer(bytes.value, clientPort, servicePort, ownerPid)
                ERROR_INSUFFICIENT_BUFFER -> if (bytes.value <= capacity) return false
                else -> return false
            }
        }
    }
    return false
}

private fun Memory.hasUniqueTcpPeer(size: Int, clientPort: Int, servicePort: Int, ownerPid: Int): Boolean {
    val rows = getInt(0)
    if (rows < 1 || rows > (size - TABLE_HEADER) / ROW_BYTES) return false
    var matched = false
    for (i in 0 until rows) {
        val offset = TABLE_HEADER + i.toLong() * ROW_BYTES
        if (getInt(offset) != ESTABLISHED || !isV4LoopbackAt(offset + 4) ||
            readNetworkPort(offset + 8) != servicePort || !isV4LoopbackAt(offset + 12) ||
            readNetworkPort(offset + 16) != clientPort) continue
        // The table row must describe the reverse (server→our socket) connection, not a listener
        // at the same port or the local client's own outbound row.
        if (matched || getInt(offset + 20) != ownerPid) return false
        matched = true
    }
    return matched
}

private fun Memory.isV4LoopbackAt(offset: Long): Boolean =
    getByte(offset).toInt() == 127 && getByte(offset + 1).toInt() == 0 &&
        getByte(offset + 2).toInt() == 0 && getByte(offset + 3).toInt() == 1

// Only the low two bytes of the native DWORD contain the port; they are already in wire order.
private fun Memory.readNetworkPort(offset: Long): Int =
    ((getByte(offset).toInt() and 0xff) shl 8) or (getByte(offset + 1).toInt() and 0xff)

private val IPV4_LOOPBACK = byteArrayOf(127, 0, 0, 1)
private fun isV4Loopback(address: InetAddress?): Boolean =
    address is Inet4Address && address.address.contentEquals(IPV4_LOOPBACK)
