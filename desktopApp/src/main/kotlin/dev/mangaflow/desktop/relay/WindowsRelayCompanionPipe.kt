package dev.mangaflow.desktop.relay

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Advapi32
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.KnownFolders
import com.sun.jna.platform.win32.Shell32Util
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT.HANDLE
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.platform.win32.Winsvc
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import dev.mangaflow.net.framedWindowsRelayCompanionClient
import dev.mangaflow.net.installWindowsRelayCompanion
import dev.mangaflow.net.installWindowsRelayActivation
import dev.mangaflow.net.installWindowsRelaySocketPeerVerifier
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val SERVICE = "RelayCenterAgent"
private const val PIPE = "\\\\.\\pipe\\RelayCenter.MangaFlow.Companion.v2"
private const val SERVICE_IMAGE = "RelayCenter.Agent.exe"
private const val FRAME_LIMIT = 4096
private const val OPERATION_MS = 2500L

private fun relayInstallRoot(): File =
    File(Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_ProgramFiles), "Relay Center")

/** Startup registers Windows transport; the live service is authenticated at each exchange. */
internal fun registerWindowsRelayCompanion() {
    installWindowsRelaySocketPeerVerifier(::matchesWindowsRelaySocketPeer)
    installWindowsRelayActivation { runCatching { File(relayInstallRoot(), "RelayCenter.exe").takeIf { it.isFile } }.getOrNull() }
    installWindowsRelayCompanion(framedWindowsRelayCompanionClient { request ->
        val context = currentCoroutineContext()
        val deadline = Deadline(context)
        try {
            WindowsPipe().exchange(request, deadline)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }
    })
}

private class Deadline(private val context: CoroutineContext) {
    private val expires = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(OPERATION_MS)

    fun remainingMillis(): Int {
        context.ensureActive()
        val remaining = expires - System.nanoTime()
        check(remaining > 0)
        return TimeUnit.NANOSECONDS.toMillis(remaining).toInt().coerceAtLeast(1)
    }
}

/** Raw HANDLE/OVERLAPPED mappings omitted by the installed JNA Kernel32 convenience interface. */
private interface PipeKernel : StdCallLibrary {
    fun CreateFileW(path: WString, access: Int, share: Int, security: Pointer?, disposition: Int,
        flags: Int, template: HANDLE?): HANDLE?
    fun WaitNamedPipeW(name: WString, timeout: Int): Boolean
    fun GetNamedPipeServerProcessId(pipe: HANDLE, pid: IntByReference): Boolean
    fun QueryFullProcessImageNameW(process: HANDLE, flags: Int, path: CharArray, length: IntByReference): Boolean
    fun GetDriveTypeW(root: WString): Int
    fun GetFinalPathNameByHandleW(file: HANDLE, path: CharArray, capacity: Int, flags: Int): Int
    fun ReadFile(handle: HANDLE, buffer: Pointer, length: Int, transferred: IntByReference,
        overlapped: Pointer?): Boolean
    fun WriteFile(handle: HANDLE, buffer: Pointer, length: Int, transferred: IntByReference,
        overlapped: Pointer): Boolean
    fun GetOverlappedResult(handle: HANDLE, overlapped: Pointer, transferred: IntByReference, wait: Boolean): Boolean
    fun CancelIoEx(handle: HANDLE, overlapped: Pointer): Boolean
}

private class WindowsPipe {
    private val kernel = Kernel32.INSTANCE
    private val serviceApi = Advapi32.INSTANCE
    private val pipeApi = Native.load("kernel32", PipeKernel::class.java)

    fun exchange(request: ByteArray, deadline: Deadline): ByteArray? {
        if (request.size !in 1..FRAME_LIMIT) return null
        deadline.remainingMillis()
        val scm = serviceApi.OpenSCManager(null, null, Winsvc.SC_MANAGER_CONNECT) ?: return null
        try {
            val service = serviceApi.OpenService(scm, SERVICE, Winsvc.SERVICE_QUERY_STATUS) ?: return null
            try {
                return exchangeWithService(service, request, deadline)
            } finally {
                serviceApi.CloseServiceHandle(service)
            }
        } finally {
            serviceApi.CloseServiceHandle(scm)
        }
    }

    private fun exchangeWithService(service: Winsvc.SC_HANDLE, request: ByteArray, deadline: Deadline): ByteArray? {
        val account = Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE,
            "SYSTEM\\CurrentControlSet\\Services\\RelayCenterAgent", "ObjectName")
        if (!account.equals("LocalSystem", ignoreCase = true)) return null
        val servicePid = runningPid(service) ?: return null
        // No GENERIC_WRITE: FILE_APPEND_DATA aliases FILE_CREATE_PIPE_INSTANCE for pipes.
        val access = FILE_READ_DATA or FILE_WRITE_DATA or FILE_READ_ATTRIBUTES or SYNCHRONIZE
        val flags = FILE_FLAG_OVERLAPPED or SECURITY_SQOS_PRESENT or SECURITY_IDENTIFICATION
        val pipe = open(PIPE, access, flags = flags) ?: run {
            if (!pipeApi.WaitNamedPipeW(WString(PIPE), minOf(400, deadline.remainingMillis()))) return null
            open(PIPE, access, flags = flags)
        } ?: return null
        try {
            if (serverPid(pipe) != servicePid) return null
            val process = kernel.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION or SYNCHRONIZE, false, servicePid)
                ?.takeUnless { it == WinBase.INVALID_HANDLE_VALUE } ?: return null
            try {
                fun sameServiceProcess() = runningPid(service) == servicePid &&
                    kernel.WaitForSingleObject(process, 0) == WAIT_TIMEOUT
                if (!sameServiceProcess()) return null
                val image = trustedServiceImage(process) ?: return null
                try {
                    if (serverPid(pipe) != servicePid || !sameServiceProcess()) return null
                    val reply = sendFrame(pipe, request, deadline) ?: return null
                    var keepReply = false
                    try {
                        // The held pipe cannot rebind to a new server. The authentic peer may close
                        // after writing the frame, so recheck the held service process, not that endpoint.
                        if (!sameServiceProcess()) return null
                        deadline.remainingMillis()
                        keepReply = true
                        return reply
                    } finally {
                        if (!keepReply) reply.fill(0)
                    }
                } finally {
                    kernel.CloseHandle(image)
                }
            } finally {
                kernel.CloseHandle(process)
            }
        } finally {
            kernel.CloseHandle(pipe)
        }
    }

    private fun runningPid(service: Winsvc.SC_HANDLE): Int? {
        val info = Winsvc.SERVICE_STATUS_PROCESS()
        if (!serviceApi.QueryServiceStatusEx(service, Winsvc.SC_STATUS_TYPE.SC_STATUS_PROCESS_INFO, info, info.size(), IntByReference()))
            return null
        return info.dwProcessId.takeIf {
            it != 0 && info.dwCurrentState == Winsvc.SERVICE_RUNNING && info.dwServiceType == SERVICE_WIN32_OWN_PROCESS
        }
    }

    private fun serverPid(pipe: HANDLE): Int? {
        val id = IntByReference()
        return if (pipeApi.GetNamedPipeServerProcessId(pipe, id)) id.value.takeIf { it != 0 } else null
    }

    private fun trustedServiceImage(process: HANDLE): HANDLE? {
        val root = relayInstallRoot()
        if (pipeApi.GetDriveTypeW(WString(root.toPath().root.toString())) != WinBase.DRIVE_FIXED) return null
        val imageName = File(root, SERVICE_IMAGE).absolutePath
        val processPath = CharArray(32768)
        val processLength = IntByReference(processPath.size)
        if (!pipeApi.QueryFullProcessImageNameW(process, 0, processPath, processLength) ||
            processLength.value !in 1 until processPath.size ||
            !String(processPath, 0, processLength.value).equals(imageName, ignoreCase = true)) return null
        val image = open(imageName, FILE_READ_ATTRIBUTES, share = FILE_SHARE_READ) ?: return null
        if (atNativePath(image, imageName)) return image
        kernel.CloseHandle(image)
        return null
    }

    private fun atNativePath(handle: HANDLE, expected: String): Boolean {
        val chars = CharArray(1024)
        val length = pipeApi.GetFinalPathNameByHandleW(handle, chars, chars.size, 0)
        return length in 1 until chars.size && String(chars, 0, length) == "\\\\?\\$expected"
    }

    private fun open(path: String, access: Int, share: Int = 0, flags: Int = 0): HANDLE? =
        pipeApi.CreateFileW(WString(path), access, share, null, OPEN_EXISTING, flags, null)
            ?.takeUnless { it == WinBase.INVALID_HANDLE_VALUE }

    private fun sendFrame(pipe: HANDLE, request: ByteArray, deadline: Deadline): ByteArray? {
        val frame = ByteBuffer.allocate(4 + request.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(request.size).put(request).array()
        Memory(frame.size.toLong()).use { out ->
            out.write(0, frame, 0, frame.size)
            if (!transferExact(pipe, out, frame.size, write = true, deadline)) return null
        }
        Memory(4).use { header ->
            if (!transferExact(pipe, header, 4, write = false, deadline)) return null
            val len = ByteBuffer.wrap(header.getByteArray(0, 4)).order(ByteOrder.LITTLE_ENDIAN).int
            if (len !in 1..FRAME_LIMIT) return null
            return Memory(len.toLong()).use { body ->
                try {
                    if (transferExact(pipe, body, len, write = false, deadline)) body.getByteArray(0, len) else null
                } finally {
                    body.clear()
                }
            }
        }
    }

    private fun transferExact(pipe: HANDLE, data: Memory, length: Int, write: Boolean, deadline: Deadline): Boolean {
        var offset = 0
        while (offset < length) {
            deadline.remainingMillis()
            val count = transfer(pipe, data.share(offset.toLong()), length - offset, write, deadline)
            if (count !in 1..(length - offset)) return false
            offset += count
        }
        deadline.remainingMillis()
        return true
    }

    private fun transfer(pipe: HANDLE, data: Pointer, length: Int, write: Boolean, deadline: Deadline): Int {
        val event = kernel.CreateEvent(null, true, false, null) ?: return 0
        try {
            val overlapped = WinBase.OVERLAPPED().apply { hEvent = event; this.write() }
            val native = overlapped.pointer
            val count = IntByReference()
            var pending = false
            try {
                val started = if (write) pipeApi.WriteFile(pipe, data, length, count, native)
                    else pipeApi.ReadFile(pipe, data, length, count, native)
                if (!started) {
                    if (Native.getLastError() != ERROR_IO_PENDING) return 0
                    pending = true
                    while (true) {
                        when (kernel.WaitForSingleObject(event, minOf(25, deadline.remainingMillis()))) {
                            WAIT_OBJECT_0 -> break
                            WAIT_TIMEOUT -> continue
                            else -> return 0
                        }
                    }
                }
                val completed = pipeApi.GetOverlappedResult(pipe, native, count, false)
                pending = !completed && Native.getLastError() == ERROR_IO_INCOMPLETE
                return if (completed) count.value else 0
            } finally {
                // CancelIoEx only requests cancellation. Wait for terminal completion before freeing
                // native OVERLAPPED storage, the event or the caller's buffer.
                if (pending) {
                    pipeApi.CancelIoEx(pipe, native)
                    pipeApi.GetOverlappedResult(pipe, native, count, true)
                }
                java.lang.ref.Reference.reachabilityFence(overlapped)
            }
        } finally {
            kernel.CloseHandle(event)
        }
    }
}

private fun wideString(capacity: Int, read: (IntByReference, CharArray) -> Boolean): String? {
    val chars = CharArray(capacity)
    val length = IntByReference(chars.size)
    if (!read(length, chars) || length.value !in 2..chars.size || chars[length.value - 1] != '\u0000') return null
    return String(chars, 0, length.value - 1).takeUnless { '\u0000' in it }
}

private const val OPEN_EXISTING = 3
private const val FILE_READ_DATA = 0x1
private const val FILE_WRITE_DATA = 0x2
private const val FILE_READ_ATTRIBUTES = 0x80
private const val FILE_SHARE_READ = 0x1
private const val SYNCHRONIZE = 0x00100000
private const val FILE_FLAG_OVERLAPPED = 0x40000000
private const val SECURITY_SQOS_PRESENT = 0x00100000
private const val SECURITY_IDENTIFICATION = 0x00010000
private const val PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
private const val SERVICE_WIN32_OWN_PROCESS = 0x10
private const val ERROR_IO_PENDING = 997
private const val ERROR_IO_INCOMPLETE = 996
private const val WAIT_OBJECT_0 = 0
private const val WAIT_TIMEOUT = 258
