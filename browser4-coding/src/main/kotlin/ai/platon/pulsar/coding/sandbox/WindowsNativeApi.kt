package ai.platon.pulsar.coding.sandbox

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

/**
 * Declarations for the Windows token launch (plan P1-2).
 *
 * Internal on purpose: the only supported entry point is [WindowsTokenLauncher], so the native
 * surface (structures, constants, handle ownership) stays in one place. The library is resolved
 * lazily, so `browser4-coding` still starts on Linux/macOS without loading JNA natives.
 */
internal object Win32 {

    const val LOGON32_LOGON_INTERACTIVE = 2
    const val LOGON32_PROVIDER_DEFAULT = 0

    const val CREATE_UNICODE_ENVIRONMENT = 0x00000400
    const val CREATE_NO_WINDOW = 0x08000000

    const val STARTF_USESTDHANDLES = 0x00000100

    const val HANDLE_FLAG_INHERIT = 0x00000001

    const val TOKEN_ALL_ACCESS = 0x000F01FF
    const val TOKEN_ADJUST_PRIVILEGES = 0x00000020
    const val TOKEN_QUERY = 0x00000008
    const val SE_PRIVILEGE_ENABLED = 0x00000002

    const val WAIT_OBJECT_0 = 0x00000000
    const val WAIT_TIMEOUT = 0x00000102
    const val WAIT_FAILED = -1
    const val INFINITE = -1

    const val STILL_ACTIVE = 259

    const val ERROR_BROKEN_PIPE = 109
    const val ERROR_HANDLE_EOF = 38
    const val ERROR_PRIVILEGE_NOT_HELD = 1314

    val advapi32: Advapi32 by lazy { Native.load("advapi32", Advapi32::class.java) }
    val kernel32: Kernel32 by lazy { Native.load("kernel32", Kernel32::class.java) }
    val userenv: Userenv by lazy { Native.load("userenv", Userenv::class.java) }

    interface Advapi32 : Library {
        fun LogonUserW(
            user: WString,
            domain: WString,
            password: WString,
            logonType: Int,
            logonProvider: Int,
            token: PointerByReference,
        ): Boolean

        fun CreateProcessAsUserW(
            token: Pointer,
            applicationName: WString?,
            commandLine: Pointer,
            processAttributes: SecurityAttributes?,
            threadAttributes: SecurityAttributes?,
            inheritHandles: Boolean,
            creationFlags: Int,
            environment: Pointer?,
            currentDirectory: WString?,
            startupInfo: StartupInfo,
            processInformation: ProcessInformation,
        ): Boolean

        fun OpenProcessToken(processHandle: Pointer, desiredAccess: Int, token: PointerByReference): Boolean

        fun LookupPrivilegeValueW(system: WString?, name: WString, luid: Luid): Boolean

        fun AdjustTokenPrivileges(
            token: Pointer,
            disableAll: Boolean,
            newState: TokenPrivileges?,
            bufferLength: Int,
            previousState: Pointer?,
            returnLength: IntByReference?,
        ): Boolean
    }

    interface Kernel32 : Library {
        fun CreatePipe(
            readPipe: PointerByReference,
            writePipe: PointerByReference,
            attributes: SecurityAttributes?,
            size: Int,
        ): Boolean

        fun SetHandleInformation(handle: Pointer, mask: Int, flags: Int): Boolean

        fun CloseHandle(handle: Pointer): Boolean

        fun ReadFile(
            handle: Pointer,
            buffer: Pointer,
            bytesToRead: Int,
            bytesRead: IntByReference,
            overlapped: Pointer?,
        ): Boolean

        fun WriteFile(
            handle: Pointer,
            buffer: Pointer,
            bytesToWrite: Int,
            bytesWritten: IntByReference,
            overlapped: Pointer?,
        ): Boolean

        fun WaitForSingleObject(handle: Pointer, milliseconds: Int): Int

        fun GetExitCodeProcess(handle: Pointer, exitCode: IntByReference): Boolean

        fun TerminateProcess(handle: Pointer, exitCode: Int): Boolean

        fun GetCurrentProcess(): Pointer
    }

    interface Userenv : Library {
        fun CreateEnvironmentBlock(block: PointerByReference, token: Pointer, inherit: Boolean): Boolean

        fun DestroyEnvironmentBlock(block: Pointer): Boolean
    }

    @Structure.FieldOrder(
        "cb", "lpReserved", "lpDesktop", "lpTitle", "dwX", "dwY", "dwXSize", "dwYSize",
        "dwXCountChars", "dwYCountChars", "dwFillAttribute", "dwFlags", "wShowWindow",
        "cbReserved2", "lpReserved2", "hStdInput", "hStdOutput", "hStdError",
    )
    class StartupInfo : Structure() {
        @JvmField var cb: Int = 0
        @JvmField var lpReserved: Pointer? = null
        @JvmField var lpDesktop: Pointer? = null
        @JvmField var lpTitle: Pointer? = null
        @JvmField var dwX: Int = 0
        @JvmField var dwY: Int = 0
        @JvmField var dwXSize: Int = 0
        @JvmField var dwYSize: Int = 0
        @JvmField var dwXCountChars: Int = 0
        @JvmField var dwYCountChars: Int = 0
        @JvmField var dwFillAttribute: Int = 0
        @JvmField var dwFlags: Int = 0
        @JvmField var wShowWindow: Short = 0
        @JvmField var cbReserved2: Short = 0
        @JvmField var lpReserved2: Pointer? = null
        @JvmField var hStdInput: Pointer? = null
        @JvmField var hStdOutput: Pointer? = null
        @JvmField var hStdError: Pointer? = null

        init {
            cb = size()
        }
    }

    @Structure.FieldOrder("hProcess", "hThread", "dwProcessId", "dwThreadId")
    class ProcessInformation : Structure() {
        @JvmField var hProcess: Pointer? = null
        @JvmField var hThread: Pointer? = null
        @JvmField var dwProcessId: Int = 0
        @JvmField var dwThreadId: Int = 0
    }

    @Structure.FieldOrder("nLength", "lpSecurityDescriptor", "bInheritHandle")
    class SecurityAttributes : Structure() {
        @JvmField var nLength: Int = 0
        @JvmField var lpSecurityDescriptor: Pointer? = null
        @JvmField var bInheritHandle: Int = 0

        init {
            nLength = size()
        }
    }

    @Structure.FieldOrder("LowPart", "HighPart")
    class Luid : Structure() {
        @JvmField var LowPart: Int = 0
        @JvmField var HighPart: Int = 0
    }

    @Structure.FieldOrder("Luid", "Attributes")
    class LuidAndAttributes : Structure() {
        @JvmField var Luid: Luid = Luid()
        @JvmField var Attributes: Int = 0
    }

    @Structure.FieldOrder("PrivilegeCount", "Privileges")
    class TokenPrivileges : Structure() {
        @JvmField var PrivilegeCount: Int = 0
        @JvmField var Privileges: Array<LuidAndAttributes> = arrayOf(LuidAndAttributes())
    }
}
