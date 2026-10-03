using System.IO;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Xsoz.Launcher.Core;

/// <summary>
/// JSON persistence for launcher-owned state. Every read degrades to a caller-supplied
/// default on any failure, so a truncated or hand-edited file can never crash startup.
/// Writes are atomic (temp file + replace) so a power cut cannot leave a half-written config.
/// </summary>
public static class JsonStore
{
    private static readonly JsonSerializerOptions Options = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
        WriteIndented = true,
        AllowTrailingCommas = true,
        ReadCommentHandling = JsonCommentHandling.Skip,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    /// <summary>Reads and deserialises a file, returning <paramref name="fallback"/> on any error.</summary>
    public static T Load<T>(string path, T fallback, Action<string>? onDamaged = null)
    {
        try
        {
            if (!File.Exists(path))
            {
                return fallback;
            }

            var json = File.ReadAllText(path, Encoding.UTF8);
            if (string.IsNullOrWhiteSpace(json))
            {
                return fallback;
            }

            return JsonSerializer.Deserialize<T>(json, Options) ?? fallback;
        }
        catch (Exception ex)
        {
            onDamaged?.Invoke(path);
            AppLog.Shared.Warn($"Config at '{Path.GetFileName(path)}' could not be read ({ex.GetType().Name}); using defaults.");
            Quarantine(path);
            return fallback;
        }
    }

    /// <summary>Serialises and writes atomically. Returns false instead of throwing on failure.</summary>
    public static bool Save<T>(string path, T value)
    {
        try
        {
            var dir = Path.GetDirectoryName(path);
            if (!string.IsNullOrEmpty(dir))
            {
                Directory.CreateDirectory(dir);
            }

            var json = JsonSerializer.Serialize(value, Options);
            var temp = path + ".tmp";
            File.WriteAllText(temp, json, Encoding.UTF8);

            if (File.Exists(path))
            {
                // Keep one generation of our own backup so 'restore last known good' is a real restore.
                var backup = path + ".bak";
                try
                {
                    File.Copy(path, backup, overwrite: true);
                }
                catch (Exception)
                {
                    // A missing backup is not a reason to fail the save.
                }
            }

            File.Move(temp, path, overwrite: true);
            return true;
        }
        catch (Exception ex)
        {
            AppLog.Shared.Error($"Could not write '{Path.GetFileName(path)}': {ex.Message}");
            return false;
        }
    }

    /// <summary>Moves an unparseable file aside so the next write starts clean and the user can inspect it.</summary>
    private static void Quarantine(string path)
    {
        try
        {
            if (!File.Exists(path))
            {
                return;
            }

            var target = path + ".damaged";
            File.Move(path, target, overwrite: true);
        }
        catch (Exception)
        {
            // Non-fatal.
        }
    }

    /// <summary>Reads a read-only data file shipped with the app (module registry, rules, versions).</summary>
    public static T LoadPackaged<T>(string fileName, T fallback)
    {
        var path = Path.Combine(AppPaths.AppData, "Data", fileName);
        if (File.Exists(path))
        {
            return Load(path, fallback);
        }

        // Only the exe was copied (sent to a friend): the same file is built into it.
        try
        {
            using var stream = System.Reflection.Assembly.GetExecutingAssembly().GetManifestResourceStream("Data/" + fileName);
            if (stream is null)
            {
                AppLog.Shared.Warn($"Data file '{fileName}' is neither next to the exe nor built in; using defaults.");
                return fallback;
            }

            return JsonSerializer.Deserialize<T>(stream, Options) ?? fallback;
        }
        catch (Exception ex)
        {
            AppLog.Shared.Warn($"Built-in data file '{fileName}' could not be read ({ex.GetType().Name}); using defaults.");
            return fallback;
        }
    }

    /// <summary>
    /// Writes the licence texts that travel inside the exe next to the install, so they are on every
    /// machine the fonts are (OFL) - even when only the exe was copied there.
    /// </summary>
    public static void ExtractLicences(string targetDir)
    {
        try
        {
            var asm = System.Reflection.Assembly.GetExecutingAssembly();
            foreach (var name in asm.GetManifestResourceNames())
            {
                if (!name.StartsWith("Licensing/", StringComparison.Ordinal) && name != "THIRD-PARTY.md")
                {
                    continue;
                }

                var path = Path.Combine(targetDir, name.Replace('/', Path.DirectorySeparatorChar));
                if (File.Exists(path))
                {
                    continue;
                }

                Directory.CreateDirectory(Path.GetDirectoryName(path)!);
                using var src = asm.GetManifestResourceStream(name);
                if (src is null)
                {
                    continue;
                }

                using var dst = File.Create(path);
                src.CopyTo(dst);
            }
        }
        catch (Exception ex)
        {
            AppLog.Shared.Warn($"Licence texts could not be written ({ex.GetType().Name}).");
        }
    }
}
