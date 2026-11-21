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

    // --- Low-integrity launch (windows-low-il, plan P1) --------------------------------
    //
    // A restricted derivative of our own primary token is what makes the write boundary real
    // without an account: "read like the user, write only where the object's label allows".
    // `CreateProcessAsUserW` does not need SeAssignPrimaryTokenPrivilege for a *restricted* copy of
    // the caller's token, which is why this stays a no-administrator path.

    /** `CreateRestrictedToken` flag: strip every privilege the caller holds. */
    const val DISABLE_MAX_PRIVILEGE = 0x0001

    const val TOKEN_ASSIGN_PRIMARY = 0x0001
    const val TOKEN_DUPLICATE = 0x0002
    const val TOKEN_ADJUST_DEFAULT = 0x0080

    /** `TOKEN_INFORMATION_CLASS.TokenIntegrityLevel`. */
    const val TOKEN_INTEGRITY_LEVEL = 25

    /** `SID_AND_ATTRIBUTES.Attributes`: this SID is the token's integrity label. */
    const val SE_GROUP_INTEGRITY = 0x00000020

    /** `ConvertStringSidToSidW`: the Low mandatory level (`sddl` `LW`). */
    const val SID_LOW_INTEGRITY = "S-1-16-4096"

    /** `ConvertStringSidToSidW`: the Medium mandatory level — what a default object carries. */
    const val SID_MEDIUM_INTEGRITY = "S-1-16-8192"

    // --- Mandatory label on an object (credentials must stay unreadable) ---------------

    const val ACL_REVISION = 2
    const val SE_FILE_OBJECT = 1
    const val LABEL_SECURITY_INFORMATION = 0x00000010
    const val OBJECT_INHERIT_ACE = 0x1
    const val CONTAINER_INHERIT_ACE = 0x2

    /** `SYSTEM_MANDATORY_LABEL_ACE` policy: a *lower* integrity subject may not read it up. */
    const val SYSTEM_MANDATORY_LABEL_NO_READ_UP = 0x2

    // --- Job object (process-tree lifetime) -------------------------------------------

    /** `JOBOBJECT_EXTENDED_LIMIT_INFORMATION` information class. */
    const val JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9

    /** Terminate every process in the job when the last job handle closes. */
    const val JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000

    const val PROCESS_TERMINATE = 0x0001
    const val PROCESS_SET_QUOTA = 0x0100
    const val PROCESS_QUERY_LIMITED_INFORMATION = 0x1000

    // --- AppContainer (windows-appcontainer, browse mode) -----------------------------

    /** `CreateProcessW` flag: `lpStartupInfo` points at a `STARTUPINFOEX`. */
    const val EXTENDED_STARTUPINFO_PRESENT = 0x00080000

    /** `UpdateProcThreadAttribute` attribute id for `SECURITY_CAPABILITIES`. */
    const val PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES = 0x00020009

    /**
     * `HRESULT_FROM_WIN32(ERROR_ALREADY_EXISTS)` = `0x800700B7` — the container profile already exists,
     * so its SID is derived instead of created. Never trust a hand-computed decimal here: the wrong
     * constant turns a routine "already there" into a hard failure.
     */
    const val HRESULT_ALREADY_EXISTS = 0x800700B7.toInt()

    const val ERROR_INSUFFICIENT_BUFFER = 122

    /** Stable container name: per-user, no administrator needed to create or derive it. */
    const val APP_CONTAINER_NAME = "browser4.agent.browse"

    const val WAIT_OBJECT_0 = 0x00000000
    const val WAIT_TIMEOUT = 0x00000102
    const val WAIT_FAILED = -1
    const val INFINITE = -1

    const val STILL_ACTIVE = 259

    const val ERROR_BROKEN_PIPE = 109
    const val ERROR_HANDLE_EOF = 38
    const val ERROR_PRIVILEGE_NOT_HELD = 1314
    const val ERROR_ACCESS_DENIED = 5

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

        /**
         * Builds a restricted copy of [existingTokenHandle].
         *
         * Passing `DISABLE_MAX_PRIVILEGE` with no SID lists yields a token with the same identity and
         * no privileges; the integrity level is then lowered with [setTokenIntegrity] on the copy, so
         * the backend's own token is never touched.
         */
        fun CreateRestrictedToken(
            existingTokenHandle: Pointer,
            flags: Int,
            disableSidCount: Int,
            sidsToDisable: Pointer?,
            deletePrivilegeCount: Int,
            privilegesToDelete: Pointer?,
            restrictedSidCount: Int,
            sidsToRestrict: Pointer?,
            newTokenHandle: PointerByReference,
        ): Boolean

        fun SetTokenInformation(
            tokenHandle: Pointer,
            tokenInformationClass: Int,
            tokenInformation: Pointer,
            tokenInformationLength: Int,
        ): Boolean

        fun ConvertStringSidToSidW(stringSid: WString, sid: PointerByReference): Boolean

        /** `*S-1-15-2-…` form, as `icacls /grant *<sid>` expects. Free the string with `LocalFree`. */
        fun ConvertSidToStringSidW(sid: Pointer, stringSid: PointerByReference): Boolean

        fun FreeSid(sid: Pointer): Pointer

        fun InitializeAcl(pAcl: Pointer, aclLength: Int, aclRevision: Int): Boolean

        fun AddMandatoryAce(
            pAcl: Pointer,
            aceRevision: Int,
            aceFlags: Int,
            mandatoryPolicy: Int,
            labelSid: Pointer,
        ): Boolean

        /** Returns `0` (`ERROR_SUCCESS`) when the label/ACL was written. */
        fun SetNamedSecurityInfoW(
            objectName: WString,
            objectType: Int,
            securityInfo: Int,
            owner: Pointer?,
            group: Pointer?,
            dacl: Pointer?,
            sacl: Pointer?,
        ): Int
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

        fun CreateJobObjectW(attributes: Pointer?, name: WString?): Pointer?

        fun SetInformationJobObject(job: Pointer, infoClass: Int, info: Pointer, length: Int): Boolean

        fun AssignProcessToJobObject(job: Pointer, process: Pointer): Boolean

        fun OpenProcess(desiredAccess: Int, inheritHandle: Boolean, processId: Int): Pointer?

        /**
         * The AppContainer launch path (`CreateProcessAsUserW` is for the token path).
         *
         * With [EXTENDED_STARTUPINFO_PRESENT], `startupInfo` is a `STARTUPINFOEX` pointer.
         */
        fun CreateProcessW(
            applicationName: WString?,
            commandLine: Pointer,
            processAttributes: SecurityAttributes?,
            threadAttributes: SecurityAttributes?,
            inheritHandles: Boolean,
            creationFlags: Int,
            environment: Pointer?,
            currentDirectory: WString?,
            startupInfo: Pointer,
            processInformation: ProcessInformation,
        ): Boolean

        fun InitializeProcThreadAttributeList(
            attributeList: Pointer?,
            attributeCount: Int,
            flags: Int,
            size: Pointer,
        ): Boolean

        fun UpdateProcThreadAttribute(
            attributeList: Pointer,
            flags: Int,
            attribute: Int,
            value: Pointer,
            size: Int,
            previousValue: Pointer?,
            returnSize: Pointer?,
        ): Boolean

        fun DeleteProcThreadAttributeList(attributeList: Pointer)

        fun GetCurrentProcess(): Pointer

        /** `LocalFree` lives in kernel32, not advapi32 — a wrong DLL here is an UnsatisfiedLinkError. */
        fun LocalFree(memory: Pointer): Pointer
    }

    interface Userenv : Library {
        fun CreateEnvironmentBlock(block: PointerByReference, token: Pointer, inherit: Boolean): Boolean

        fun DestroyEnvironmentBlock(block: Pointer): Boolean

        /**
         * Creates (or reports) the per-user container profile.
         *
         * `HRESULT` return: `0` on success, [HRESULT_ALREADY_EXISTS] when the name is taken (derive
         * the SID instead). Needs no administrator: the profile lives under the user's registry hive.
         */
        fun CreateAppContainerProfile(
            name: WString,
            displayName: WString,
            description: WString,
            capabilities: Pointer?,
            capabilityCount: Int,
            sid: PointerByReference,
        ): Int

        fun DeriveAppContainerSidFromAppContainerName(name: WString, sid: PointerByReference): Int

        fun DeleteAppContainerProfile(name: WString): Int
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

    @Structure.FieldOrder("PerProcessUserTimeLimit", "PerJobUserTimeLimit", "LimitFlags", "MinimumWorkingSetSize", "MaximumWorkingSetSize", "ActiveProcessLimit", "Affinity", "PriorityClass", "SchedulingClass")
    class JobObjectBasicLimitInformation : Structure() {
        @JvmField var PerProcessUserTimeLimit: Long = 0
        @JvmField var PerJobUserTimeLimit: Long = 0
        @JvmField var LimitFlags: Int = 0
        @JvmField var MinimumWorkingSetSize: Long = 0
        @JvmField var MaximumWorkingSetSize: Long = 0
        @JvmField var ActiveProcessLimit: Int = 0
        @JvmField var Affinity: Long = 0
        @JvmField var PriorityClass: Int = 0
        @JvmField var SchedulingClass: Int = 0
    }

    @Structure.FieldOrder("ReadOperationCount", "WriteOperationCount", "OtherOperationCount", "ReadTransferCount", "WriteTransferCount", "OtherTransferCount")
    class IoCounters : Structure() {
        @JvmField var ReadOperationCount: Long = 0
        @JvmField var WriteOperationCount: Long = 0
        @JvmField var OtherOperationCount: Long = 0
        @JvmField var ReadTransferCount: Long = 0
        @JvmField var WriteTransferCount: Long = 0
        @JvmField var OtherTransferCount: Long = 0
    }

    @Structure.FieldOrder("BasicLimitInformation", "IoInfo", "ProcessMemoryLimit", "JobMemoryLimit", "PeakProcessMemoryUsed", "PeakJobMemoryUsed")
    class JobObjectExtendedLimitInformation : Structure() {
        @JvmField var BasicLimitInformation: JobObjectBasicLimitInformation = JobObjectBasicLimitInformation()
        @JvmField var IoInfo: IoCounters = IoCounters()
        @JvmField var ProcessMemoryLimit: Long = 0
        @JvmField var JobMemoryLimit: Long = 0
        @JvmField var PeakProcessMemoryUsed: Long = 0
        @JvmField var PeakJobMemoryUsed: Long = 0
    }

    /**
     * `SECURITY_CAPABILITIES`: the container a `STARTUPINFOEX` asks `CreateProcessW` for.
     *
     * Empty [Capabilities] is the point of browse mode: without `internetClient` the container has no
     * egress, and without `privateNetworkClientServer` it cannot reach the LAN either.
     */
    @Structure.FieldOrder("AppContainerSid", "Capabilities", "CapabilityCount", "Reserved")
    class SecurityCapabilities : Structure() {
        @JvmField var AppContainerSid: Pointer? = null
        @JvmField var Capabilities: Pointer? = null
        @JvmField var CapabilityCount: Int = 0
        @JvmField var Reserved: Int = 0
    }

    /** `STARTUPINFOEX`: a `STARTUPINFO` plus the attribute list the extended launch reads. */
    @Structure.FieldOrder("StartupInfo", "lpAttributeList")
    class StartupInfoEx : Structure() {
        @JvmField var StartupInfo: StartupInfo = StartupInfo()
        @JvmField var lpAttributeList: Pointer? = null
    }

    /**
     * `SID_AND_ATTRIBUTES`, as `TokenIntegrityLevel` expects it.
     *
     * [Attributes] must carry [SE_GROUP_INTEGRITY]; without it `SetTokenInformation` rejects the
     * buffer with `ERROR_INVALID_PARAMETER`, which is the failure mode worth recognising by name.
     */
    @Structure.FieldOrder("Sid", "Attributes")
    class SidAndAttributes : Structure() {
        @JvmField var Sid: Pointer? = null
        @JvmField var Attributes: Int = 0
    }
}
