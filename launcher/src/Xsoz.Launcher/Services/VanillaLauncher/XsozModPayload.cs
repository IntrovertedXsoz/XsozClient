using System.Reflection;
using System.Security.Cryptography;
using Xsoz.Launcher.Core;

namespace Xsoz.Launcher.Services;

/// <summary>
/// The Xsoz in-game mod jar, embedded in the launcher and written into an instance's own
/// <c>mods</c> folder. Only ever an isolated instance under <c>%LOCALAPPDATA%\XsozClient</c> -
/// never the user's <c>.minecraft\mods</c>.
/// </summary>
public static class XsozModPayload
{
    /// <summary>The file name the jar is installed under. Older copies (xsozclient-*.jar) are replaced.</summary>
    public const string FileName = "xsozclient.jar";

    private const string ResourceName = "XsozMod.jar";

    /// <summary>The embedded jar, or null when this build of the launcher was made without one.</summary>
    public static byte[]? Read()
    {
        using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream(ResourceName);
        if (stream is null)
        {
            return null;
        }

        using var buffer = new MemoryStream();
        stream.CopyTo(buffer);
        return buffer.ToArray();
    }

    /// <summary>Installs through the guarded writer (the vanilla-launcher provisioning path).</summary>
    public static async Task<string?> InstallAsync(IGameDirectoryWriter writer, string gameDir, CancellationToken ct)
    {
        var payload = Read();
        if (payload is null)
        {
            AppLog.Shared.Warn("This launcher build carries no Xsoz mod jar; the instance will run without the in-game client.");
            return null;
        }

        var modsDir = Path.Combine(gameDir, "mods");
        writer.CreateDirectory(modsDir);
        var target = Path.Combine(modsDir, FileName);

        foreach (var stale in StaleCopies(modsDir, target))
        {
            writer.DeleteFile(stale);
        }

        if (SameContent(target, payload))
        {
            return target;
        }

        await writer.WriteBytesAsync(target, payload, ct).ConfigureAwait(false);
        AppLog.Shared.Info($"Xsoz mod installed: {target} ({payload.Length / 1024} KB).");
        return target;
    }

    /// <summary>Installs directly (the self-provisioned instance, which InstallService owns outright).</summary>
    public static void InstallDirect(string modsDir)
    {
        var payload = Read();
        if (payload is null)
        {
            return;
        }

        Directory.CreateDirectory(modsDir);
        var target = Path.Combine(modsDir, FileName);
        foreach (var stale in StaleCopies(modsDir, target))
        {
            File.Delete(stale);
        }

        if (!SameContent(target, payload))
        {
            var temp = target + ".tmp";
            File.WriteAllBytes(temp, payload);
            File.Move(temp, target, overwrite: true);
            AppLog.Shared.Info($"Xsoz mod installed: {target} ({payload.Length / 1024} KB).");
        }
    }

    private static IEnumerable<string> StaleCopies(string modsDir, string target)
    {
        if (!Directory.Exists(modsDir))
        {
            return [];
        }

        return Directory.EnumerateFiles(modsDir, "xsozclient*.jar")
            .Where(p => !string.Equals(Path.GetFullPath(p), Path.GetFullPath(target), StringComparison.OrdinalIgnoreCase))
            .ToList();
    }

    private static bool SameContent(string path, byte[] payload)
    {
        if (!File.Exists(path) || new FileInfo(path).Length != payload.Length)
        {
            return false;
        }

        return SHA256.HashData(File.ReadAllBytes(path)).AsSpan().SequenceEqual(SHA256.HashData(payload));
    }
}
