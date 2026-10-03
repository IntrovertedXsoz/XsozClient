using System.Runtime.InteropServices;
using System.Text;

namespace Xsoz.Launcher.Services;

/// <summary>
/// Reads another process's command line without WMI and without any NuGet package.
///
/// WHY THIS EXISTS, AND WHY IT IS NOT A LAUNCHER-META DEPENDENCY. The safety rule that decides
/// whether this product is allowed to write into the official launcher's directory turns on
/// knowing whether that launcher is open. Fabric's own installer asks the same question through
/// <c>MojangLauncherHelperWrapper.isMojangLauncherOpen()</c>. Getting the answer reliably needs
/// the command line, and the only supported managed route to a command line is WMI through
/// <c>System.Management</c> - which is a NuGet package this project does not take and should not
/// take, because the package would be a shipping dependency bought to answer one question.
///
/// So the PEB is read directly. It is a documented-by-consensus 64-bit layout: the process
/// parameters pointer sits at PEB+0x20, and the command line is a UNICODE_STRING at
/// parameters+0x70 (16-bit length, 16-bit capacity, 32-bit-aligned pointer at +0x78). The reads
/// need PROCESS_QUERY_INFORMATION | PROCESS_VM_READ against a process owned by the same Windows
/// user, which is exactly the launcher case; anything else returns false and the caller falls
/// back to a weaker signal rather than guessing.
///
/// 32-bit is not implemented and is not needed: the product publishes win-x64 only. On a
/// non-64-bit host every read below fails cleanly and the fallback applies.
/// </summary>
internal static class ProcessCommandLine
{
    private const int ProcessBasicInformation = 0;
    private const int ProcessQueryLimitedInformation = 0x1000;
    private const int ProcessVmRead = 0x0010;

    private const uint PebProcessParametersOffset64 = 0x20;
    private const uint ParametersCommandLineOffset64 = 0x70;

    [StructLayout(LayoutKind.Sequential)]
    private struct ProcessBasicInformationStruct
    {
        public IntPtr ExitStatusAndPadding;
        public IntPtr PebBaseAddress;
        public IntPtr ReservedAffinityMask;
        public IntPtr ReservedBasePriority;
        public IntPtr ReservedUniqueProcessId;
        public IntPtr ReservedInheritedProcessId;
    }

    [DllImport("ntdll.dll")]
    private static extern int NtQueryInformationProcess(
        IntPtr processHandle,
        int informationClass,
        ref ProcessBasicInformationStruct information,
        int informationLength,
        out int returnLength);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr OpenProcess(uint desiredAccess, bool inheritHandle, int processId);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool ReadProcessMemory(
        IntPtr processHandle,
        IntPtr baseAddress,
        byte[] buffer,
        int size,
        out IntPtr bytesRead);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CloseHandle(IntPtr handle);

    /// <summary>The longest command line this will read. Windows caps it far below this.</summary>
    private const int MaxCommandLineChars = 2048;

    /// <summary>
    /// The command line of a process, or false when it could not be read. Never throws: a process
    /// that exits between enumeration and this read is an ordinary race, not an error.
    /// </summary>
    public static bool TryRead(int processId, out string commandLine)
    {
        commandLine = string.Empty;

        var handle = OpenProcess(ProcessQueryLimitedInformation | ProcessVmRead, false, processId);
        if (handle == IntPtr.Zero)
        {
            return false;
        }

        try
        {
            var info = default(ProcessBasicInformationStruct);
            var status = NtQueryInformationProcess(
                handle, ProcessBasicInformation, ref info, Marshal.SizeOf<ProcessBasicInformationStruct>(), out _);

            if (status != 0 || info.PebBaseAddress == IntPtr.Zero)
            {
                return false;
            }

            if (!TryReadPointer(handle, info.PebBaseAddress + (int)PebProcessParametersOffset64, out var parameters)
                || parameters == IntPtr.Zero)
            {
                return false;
            }

            // The UNICODE_STRING head and the buffer pointer together.
            var head = new byte[16];
            if (!ReadExact(handle, parameters + (int)ParametersCommandLineOffset64, head, head.Length))
            {
                return false;
            }

            var lengthInBytes = BitConverter.ToUInt16(head, 0);
            if (lengthInBytes == 0)
            {
                return false;
            }

            var chars = Math.Min(lengthInBytes / 2, MaxCommandLineChars);
            if (chars <= 0)
            {
                return false;
            }

            var bufferAddress = new IntPtr(BitConverter.ToInt64(head, 8));
            if (bufferAddress == IntPtr.Zero)
            {
                return false;
            }

            var text = new byte[chars * 2];
            if (!ReadExact(handle, bufferAddress, text, text.Length))
            {
                return false;
            }

            commandLine = Encoding.Unicode.GetString(text);
            return commandLine.Length > 0;
        }
        catch (Exception)
        {
            return false;
        }
        finally
        {
            CloseHandle(handle);
        }
    }

    private static bool TryReadPointer(IntPtr handle, IntPtr address, out IntPtr value)
    {
        value = IntPtr.Zero;
        var buffer = new byte[IntPtr.Size];
        if (!ReadExact(handle, address, buffer, buffer.Length))
        {
            return false;
        }

        value = new IntPtr(BitConverter.ToInt64(buffer, 0));
        return true;
    }

    private static bool ReadExact(IntPtr handle, IntPtr address, byte[] buffer, int size)
    {
        return ReadProcessMemory(handle, address, buffer, size, out var read)
               && read == (IntPtr)size;
    }
}