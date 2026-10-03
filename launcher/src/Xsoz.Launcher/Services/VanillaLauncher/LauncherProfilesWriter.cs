using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Xsoz.Launcher.Core;

namespace Xsoz.Launcher.Services;

/// <summary>The one profile this launcher is allowed to add to the official launcher's store.</summary>
/// <param name="Key">The profile key. A readable name, not a UUID, so it is findable and removable.</param>
/// <param name="Name">The display name.</param>
/// <param name="VersionId">The <c>lastVersionId</c>: a folder under <c>versions\</c> that we own.</param>
/// <param name="GameDir">The isolated game directory. This is what keeps our mods out of theirs.</param>
/// <param name="Created">ISO 8601 creation stamp.</param>
/// <param name="Icon">Optional PNG data URI. Null leaves the icon alone, which is the safest default.</param>
/// <param name="JavaArgs">
/// The profile's <c>javaArgs</c>. The official launcher appends these AFTER the version JSON's
/// <c>arguments.jvm</c>, and when a profile has none it appends its own default (<c>-Xmx2G ...</c>)
/// instead. That default once crashed every launch with exit code 1 ("Initial heap size set to a
/// larger value than the maximum heap size") because the version JSON carried <c>-Xms3G</c>. Heap
/// sizing therefore lives here, and only here.
/// </param>
public sealed record VanillaProfileSpec(
    string Key,
    string Name,
    string VersionId,
    string GameDir,
    string Created,
    string? Icon = null,
    string? JavaArgs = JvmFlagMerge.ProfileJavaArgs);

/// <summary>What a merge actually did, with the counts that prove it did only that.</summary>
public sealed record ProfileMergeOutcome
{
    /// <summary>True when the file now contains our profile and nothing else changed.</summary>
    public required bool Succeeded { get; init; }

    /// <summary>One plain sentence for the interface.</summary>
    public required string Message { get; init; }

    /// <summary>Profile count before the merge.</summary>
    public required int Before { get; init; }

    /// <summary>Profile count after the merge.</summary>
    public required int After { get; init; }

    /// <summary>
    /// How many pre-existing profiles differ from their pre-merge raw text. The only acceptable
    /// value is zero, and it is asserted before the write is allowed to count as a success.
    /// </summary>
    public required int ExistingChanged { get; init; }

    /// <summary>True when the profile was already present and correct, so nothing was written.</summary>
    public required bool AlreadyPresent { get; init; }

    /// <summary>True when the profile was already present but its contents were rewritten.</summary>
    public bool Refreshed { get; init; }

    /// <summary>Path of the pre-edit copy, or null when nothing was written.</summary>
    public string? BackupPath { get; init; }

    /// <summary>
    /// True when the merge wrote nothing because the profile was already exactly right. This is the
    /// idempotent case: running provisioning twice must leave one profile, not two, and must not
    /// rewrite a file that already says the right thing.
    /// </summary>
    public bool NoOp => AlreadyPresent && !Refreshed;
}

/// <summary>
/// Merges exactly one profile into the official launcher's <c>launcher_profiles.json</c>, and does
/// nothing else to that file.
///
/// WHY A TEXT SPLICE AND NOT A RE-SERIALISE. The file has no published schema. It carries fields
/// nobody has documented, written by a launcher we do not control, holding a user's entire
/// installation list. Round-tripping it through a serializer would silently normalise escapes,
/// key order, number formatting and whitespace across every profile in the file - technically
/// valid, semantically identical, and exactly the kind of invisible edit to somebody's data that
/// this product has no business making. So the file is read as bytes, the single member we own is
/// located by byte offset, and only that span is replaced or inserted. Every other byte of the file
/// is carried across untouched, and that is then asserted rather than assumed.
///
/// THE WRITE. Temp file in the same directory, then <see cref="File.Replace"/> with the
/// pre-edit content as the backup argument. That is a single atomic swap on NTFS: a power cut
/// leaves either the old file or the new one, never a third thing - which
/// <c>File.WriteAllText</c> directly onto the live file cannot promise, and which is the failure
/// mode Fabric's installer exists to avoid.
///
/// THE GATE. <c>Func&lt;bool&gt;</c> is called before the file is even read and again immediately
/// before the swap. The window matters: this method is called from a multi-second install, and the
/// user can open the launcher in the middle of one.
/// </summary>
public static class LauncherProfilesWriter
{
    /// <summary>
    /// Merges our profile into <paramref name="profileFilePath"/>.
    /// </summary>
    /// <param name="profileFilePath">The file, already chosen by feature detection.</param>
    /// <param name="spec">The profile to add or refresh.</param>
    /// <param name="writeGate">
    /// Returns false when writing is forbidden right now - which is the launcher-running check.
    /// Called at least twice; the caller must not cache the result.
    /// </param>
    /// <param name="backupDirectory">Where the pre-edit copy goes. Must be inside the launcher's own tree.</param>
    public static ProfileMergeOutcome Merge(
        string profileFilePath,
        VanillaProfileSpec spec,
        Func<bool> writeGate,
        string backupDirectory)
    {
        if (!writeGate())
        {
            return new ProfileMergeOutcome
            {
                Succeeded = false,
                Message = LauncherProcessWatch.RefusalMessage,
                Before = -1,
                After = -1,
                ExistingChanged = 0,
                AlreadyPresent = false,
            };
        }

        byte[] original;
        try
        {
            original = File.ReadAllBytes(profileFilePath);
        }
        catch (Exception ex)
        {
            return new ProfileMergeOutcome
            {
                Succeeded = false,
                Message = "The launcher's profile file could not be read: " + ex.Message,
                Before = -1,
                After = -1,
                ExistingChanged = 0,
                AlreadyPresent = false,
            };
        }

        Dictionary<string, string> before;
        var beforeCount = ReadProfiles(original, out before);

        Locate(original, spec.Key, out int insertionOffset, out int replacementLength);

        // Whether the key is already there is read from the PARSED document, not inferred from the
        // splice. If the file says the key is present but its byte span could not be located, the
        // only safe move is to refuse: inserting anyway is how a profile store ends up with two
        // members of the same name and a parser that picks one at random.
        var keyAlreadyPresent = before.ContainsKey(spec.Key);
        if (keyAlreadyPresent && replacementLength == 0)
        {
            return new ProfileMergeOutcome
            {
                Succeeded = false,
                Message = $"'{spec.Key}' is already in the profile file but its byte span could not be located, "
                          + "so writing would risk a duplicate. Nothing was written.",
                Before = beforeCount,
                After = beforeCount,
                ExistingChanged = 0,
                AlreadyPresent = true,
            };
        }

        var indent = DetectMemberIndent(original, insertionOffset);
        var memberText = BuildMember(spec, indent);
        var memberBytes = Encoding.UTF8.GetBytes(memberText);

        byte[] merged;
        if (replacementLength > 0)
        {
            // Replace exactly the existing member's span. Everything around it - the comma, the
            // indentation, the other members - is carried across byte for byte.
            merged = Concat(original.AsSpan(0, insertionOffset), memberBytes, original.AsSpan(insertionOffset + replacementLength));
        }
        else
        {
            // The new member goes FIRST, immediately after the opening brace, and the separating
            // comma goes AFTER it - before the member the file already had. Getting that backwards
            // yields `{,"key":…`, which is malformed JSON; the verification below rejects it and
            // nothing is written, which is exactly how this was found.
            var separator = ProfilesObjectIsEmpty(original, insertionOffset) ? string.Empty : ",";
            var head = Encoding.UTF8.GetBytes(indent + memberText + separator + "\n");
            merged = Concat(original.AsSpan(0, insertionOffset + 1), head, original.AsSpan(insertionOffset + 1));
        }

        // The gate again, immediately before the swap. This is the check that matters: everything
        // above it is a pure function over bytes and can be re-run for free if the user opened the
        // launcher in the meantime.
        if (!writeGate())
        {
            return new ProfileMergeOutcome
            {
                Succeeded = false,
                Message = LauncherProcessWatch.RefusalMessage,
                Before = beforeCount,
                After = beforeCount,
                ExistingChanged = 0,
                AlreadyPresent = false,
            };
        }

        // Verified against the bytes about to be written, not against an intention.
        //
        // The changed-profile count deliberately EXCLUDES our own key. A count that included it
        // would report this launcher's own profile as "an existing profile changed" on every
        // re-run, which is both untrue and - being a failure condition - would make re-running
        // impossible. Every profile this launcher does not own is still counted, and only those.
        var afterCount = ReadProfiles(merged, out var after);
        var changed = before.Keys.Count(key =>
            !string.Equals(key, spec.Key, StringComparison.Ordinal)
            && (!after.TryGetValue(key, out var raw) || !string.Equals(raw, before[key], StringComparison.Ordinal)));

        // A no-op is a re-run whose content came out identical. Comparing the pre-existing raw text with
        // the text this merge produced is the only way to tell "already correct" from "refreshed" -
        // both leave the count unchanged, and only the second one is worth saying out loud.
        var unchangedNoOp = keyAlreadyPresent
                             && before.TryGetValue(spec.Key, out var previousRaw)
                             && after.TryGetValue(spec.Key, out var rewrittenRaw)
                             && string.Equals(previousRaw, rewrittenRaw, StringComparison.Ordinal);

        var expectedCount = beforeCount + (keyAlreadyPresent ? 0 : 1);
        if (changed > 0 || afterCount != expectedCount || !after.TryGetValue(spec.Key, out var ours))
        {
            // The diagnostics belong in the message. A merge that refuses to write and says only
            // "verification failed" makes the next hour of debugging somebody else's problem.
            var head = Encoding.UTF8.GetString(merged, 0, Math.Min(240, merged.Length));
            return new ProfileMergeOutcome
            {
                Succeeded = false,
                Message = "The merge did not survive verification, so nothing was written. "
                          + $"{changed} existing profile(s) would have changed. "
                          + $"offset={insertionOffset} replaceLength={replacementLength} "
                          + $"profiles {beforeCount}->{afterCount} (expected {expectedCount}) "
                          + $"reparseOk={afterCount >= 0} "
                          + $"head=\"{head.Replace("\r", string.Empty).Replace("\n", "\\n")}\"",
                Before = beforeCount,
                After = afterCount,
                ExistingChanged = changed,
                AlreadyPresent = keyAlreadyPresent,
            };
        }

        string? backupPath = null;
        try
        {
            Directory.CreateDirectory(backupDirectory);
            backupPath = Path.Combine(
                backupDirectory,
                "launcher_profiles." + DateTime.Now.ToString("yyyyMMdd-HHmmss", System.Globalization.CultureInfo.InvariantCulture) + ".pre-xsoz.json");

            var tempPath = profileFilePath + ".xsoz-tmp";

            // File.Replace performs the swap and writes the pre-edit content to the backup path in
            // one operation, so there is no window in which the file is neither old nor new and no
            // window in which the backup and the file disagree.
            File.WriteAllBytes(tempPath, merged);
            File.Replace(tempPath, profileFilePath, backupPath, ignoreMetadataErrors: true);
        }
        catch (Exception ex)
        {
            AppLog.Shared.Error("The profile file could not be swapped in atomically: " + ex.Message);
            return new ProfileMergeOutcome
            {
                Succeeded = false,
                Message = "The launcher's profile file could not be updated: " + ex.Message + " Nothing was changed.",
                Before = beforeCount,
                After = beforeCount,
                ExistingChanged = 0,
                AlreadyPresent = false,
            };
        }

        AppLog.Shared.Info(
            $"Profile '{spec.Key}' {(unchangedNoOp ? "already current in" : "written into")} {Path.GetFileName(profileFilePath)}. "
            + $"Profiles {beforeCount} -> {afterCount}; {changed} profile(s) this launcher does not own changed.");

        return new ProfileMergeOutcome
        {
            Succeeded = true,
            Message = unchangedNoOp
                ? $"The '{spec.Key}' installation was already registered correctly. "
                  + $"Profiles {beforeCount}, {changed} existing changed; nothing needed writing."
                : $"The '{spec.Key}' installation is registered. "
                  + $"Profiles {beforeCount} -> {afterCount}, {changed} existing changed.",
            Before = beforeCount,
            After = afterCount,
            ExistingChanged = changed,
            AlreadyPresent = keyAlreadyPresent,
            Refreshed = keyAlreadyPresent && !unchangedNoOp,
            BackupPath = backupPath,
        };
    }

    /// <summary>
    /// Restores a pre-edit copy. Used by the rollback action and by the diagnostic drill. The gate
    /// is consulted here too: a restore is a write, and it corrupts exactly as easily as a merge.
    /// </summary>
    public static bool TryRestore(string backupPath, string profileFilePath, Func<bool> writeGate, out string message)
    {
        if (!File.Exists(backupPath))
        {
            message = "There is no saved copy to restore from.";
            return false;
        }

        if (!writeGate())
        {
            message = LauncherProcessWatch.RefusalMessage;
            return false;
        }

        try
        {
            File.Copy(backupPath, profileFilePath, overwrite: true);
            message = "The launcher's profile file was restored from the copy taken before this launcher touched it.";
            AppLog.Shared.Success(message);
            return true;
        }
        catch (Exception ex)
        {
            message = "The restore failed: " + ex.Message;
            return false;
        }
    }

    /// <summary>Profile count and per-key raw text of a profile file. Never throws.</summary>
    public static int ReadProfiles(byte[] utf8, out Dictionary<string, string> rawByKey)
    {
        rawByKey = new Dictionary<string, string>(StringComparer.Ordinal);
        try
        {
            using var doc = JsonDocument.Parse(utf8);
            if (!doc.RootElement.TryGetProperty("profiles", out var profiles) || profiles.ValueKind != JsonValueKind.Object)
            {
                return 0;
            }

            foreach (var profile in profiles.EnumerateObject())
            {
                rawByKey[profile.Name] = profile.Value.GetRawText();
            }

            return rawByKey.Count;
        }
        catch (JsonException)
        {
            rawByKey.Clear();
            return -1;
        }
    }

    /// <summary>Profile count and per-key raw text of a profile file on disk.</summary>
    public static int ReadProfiles(string path, out Dictionary<string, string> rawByKey) =>
        ReadProfiles(File.ReadAllBytes(path), out rawByKey);

    /// <summary>
    /// Finds the byte offset this launcher owns inside the file: either the existing member's span,
    /// or the offset of the opening brace of the <c>profiles</c> object.
    /// <para>
    /// The brace offset comes from <see cref="Utf8JsonReader"/>, which does expose exact byte
    /// offsets. The member's END offset does not, because the public reader surface has no way to
    /// ask how many bytes a just-read value occupied - so the span from the property name to the end
    /// of the value is found by a small scanner that understands strings and escapes. Strings matter:
    /// a profile icon is a base64 data URI, and a naive brace counter would stop at the first
    /// <c>}</c> inside one.
    /// </para>
    /// </summary>
    private static void Locate(byte[] utf8, string profileKey, out int offset, out int length)
    {
        offset = -1;
        length = 0;

        var profilesBrace = FindProfilesObjectOffset(utf8);
        if (profilesBrace < 0)
        {
            return;
        }

        var member = FindMemberSpan(utf8, profilesBrace, profileKey);
        if (member is { } span)
        {
            offset = span.Start;
            length = span.End - span.Start;
            return;
        }

        // Not present: the caller inserts immediately after the opening brace.
        offset = profilesBrace;
        length = 0;
    }

    /// <summary>The byte offset of the <c>{</c> that opens the root object's <c>profiles</c> member.</summary>
    private static int FindProfilesObjectOffset(byte[] utf8)
    {
        var reader = new Utf8JsonReader(utf8);
        if (!reader.Read() || reader.TokenType != JsonTokenType.StartObject)
        {
            return -1;
        }

        while (reader.Read() && reader.TokenType == JsonTokenType.PropertyName)
        {
            var propertyName = reader.GetString();
            if (!reader.Read())
            {
                return -1;
            }

            if (string.Equals(propertyName, "profiles", StringComparison.Ordinal))
            {
                return reader.TokenType == JsonTokenType.StartObject ? (int)reader.TokenStartIndex : -1;
            }

            if (!SkipValue(ref reader))
            {
                return -1;
            }
        }

        return -1;
    }

    /// <summary>The byte span of one member of an object, from its opening quote to the end of its value.</summary>
    private static (int Start, int End)? FindMemberSpan(byte[] utf8, int objectBraceOffset, string key)
    {
        var i = objectBraceOffset + 1;
        while (i < utf8.Length && IsJsonWhitespace(utf8[i]))
        {
            i++;
        }

        if (i < utf8.Length && utf8[i] == (byte)'}')
        {
            return null;
        }

        while (i < utf8.Length)
        {
            if (utf8[i] != (byte)'"')
            {
                return null;
            }

            var nameStart = i;
            if (!ScanString(utf8, ref i, out var name))
            {
                return null;
            }

            while (i < utf8.Length && IsJsonWhitespace(utf8[i]))
            {
                i++;
            }

            if (i >= utf8.Length || utf8[i] != (byte)':')
            {
                return null;
            }

            i++;
            while (i < utf8.Length && IsJsonWhitespace(utf8[i]))
            {
                i++;
            }

            var valueStart = i;
            var valueEnd = ScanValue(utf8, ref i);
            if (valueEnd < 0)
            {
                return null;
            }

            i = valueEnd;

            if (string.Equals(name, key, StringComparison.Ordinal))
            {
                return (nameStart, valueEnd);
            }

            while (i < utf8.Length && IsJsonWhitespace(utf8[i]))
            {
                i++;
            }

            if (i < utf8.Length && utf8[i] == (byte)',')
            {
                i++;
                while (i < utf8.Length && IsJsonWhitespace(utf8[i]))
                {
                    i++;
                }

                continue;
            }

            return null;
        }

        return null;
    }

    /// <summary>Advances past a JSON string literal starting at the opening quote.</summary>
    private static bool ScanString(byte[] utf8, ref int i, out string value)
    {
        value = string.Empty;
        if (i >= utf8.Length || utf8[i] != (byte)'"')
        {
            return false;
        }

        var start = i;
        i++;
        while (i < utf8.Length)
        {
            if (utf8[i] == (byte)'\\')
            {
                i += 2;
                continue;
            }

            if (utf8[i] == (byte)'"')
            {
                i++;
                value = Encoding.UTF8.GetString(utf8, start + 1, i - start - 2);
                return true;
            }

            i++;
        }

        return false;
    }

    /// <summary>Advances past any JSON value and returns the offset just after it.</summary>
    private static int ScanValue(byte[] utf8, ref int i)
    {
        if (i >= utf8.Length)
        {
            return -1;
        }

        if (utf8[i] == (byte)'{')
        {
            return ScanContainer(utf8, ref i, (byte)'{', (byte)'}');
        }

        if (utf8[i] == (byte)'[')
        {
            return ScanContainer(utf8, ref i, (byte)'[', (byte)']');
        }

        if (utf8[i] == (byte)'"')
        {
            return ScanString(utf8, ref i, out _) ? i : -1;
        }

        // A bare scalar: number, true, false or null. It ends at a structural character.
        var start = i;
        while (i < utf8.Length && utf8[i] is not ((byte)',' or (byte)'}' or (byte)']') && !IsJsonWhitespace(utf8[i]))
        {
            i++;
        }

        return i > start ? i : -1;
    }

    /// <summary>
    /// Advances past a nested object or array, counting depth and skipping strings. A brace counter
    /// that ignored strings would stop inside a base64 icon, which is exactly the kind of offset
    /// error that corrupts somebody's file.
    /// </summary>
    private static int ScanContainer(byte[] utf8, ref int i, byte open, byte close)
    {
        var depth = 0;
        while (i < utf8.Length)
        {
            var b = utf8[i];

            if (b == (byte)'"')
            {
                if (!ScanString(utf8, ref i, out _))
                {
                    return -1;
                }

                continue;
            }

            if (b == open)
            {
                depth++;
            }
            else if (b == close)
            {
                depth--;
                if (depth == 0)
                {
                    i++;
                    return i;
                }
            }

            i++;
        }

        return -1;
    }

    private static bool IsJsonWhitespace(byte b) => b is (byte)' ' or (byte)'\t' or (byte)'\r' or (byte)'\n';

    /// <summary>Consumes the value the reader is positioned on, however deeply nested.</summary>
    private static bool SkipValue(ref Utf8JsonReader reader)
    {
        if (reader.TokenType is not (JsonTokenType.StartObject or JsonTokenType.StartArray))
        {
            return true;
        }

        var depth = 0;
        do
        {
            if (reader.TokenType is JsonTokenType.StartObject or JsonTokenType.StartArray)
            {
                depth++;
            }
            else if (reader.TokenType is JsonTokenType.EndObject or JsonTokenType.EndArray)
            {
                depth--;
            }

            if (depth == 0)
            {
                return true;
            }
        }
        while (reader.Read());

        return false;
    }

    /// <summary>True when the <c>profiles</c> object opened at <paramref name="braceOffset"/> has no members.</summary>
    private static bool ProfilesObjectIsEmpty(byte[] utf8, int braceOffset)
    {
        for (int i = braceOffset + 1; i < utf8.Length; i++)
        {
            var b = utf8[i];
            if (b is (byte)' ' or (byte)'\t' or (byte)'\r' or (byte)'\n')
            {
                continue;
            }

            return b == (byte)'}';
        }

        return false;
    }

    /// <summary>
    /// The indentation the file already uses for profile members, so an inserted profile is
    /// indistinguishable from a launcher's own. Falls back to the brace's own line plus two spaces.
    /// </summary>
    private static string DetectMemberIndent(byte[] utf8, int braceOffset)
    {
        var lineStart = braceOffset;
        while (lineStart > 0 && utf8[lineStart - 1] != (byte)'\n')
        {
            lineStart--;
        }

        var existing = new StringBuilder();
        for (int i = lineStart; i < braceOffset && utf8[i] is (byte)' ' or (byte)'\t'; i++)
        {
            existing.Append((char)utf8[i]);
        }

        return existing.Length > 0 ? existing + "  " : "  ";
    }

    /// <summary>
    /// The JSON text of the one member this launcher owns, WITHOUT the enclosing braces and
    /// already indented to sit alongside the file's existing members.
    /// <para>
    /// The braces matter. Serialising a wrapper object and slicing its text would emit the
    /// wrapper's own <c>{</c> and <c>}</c>, which nests an object inside the profiles map and
    /// closes it early - malformed JSON that the verification below then refuses to write. The
    /// profile's own value is therefore serialised directly and the key is written in front of it.
    /// </para>
    /// </summary>
    private static string BuildMember(VanillaProfileSpec spec, string indent)
    {
        var profile = new JsonObject
        {
            ["name"] = spec.Name,
            ["type"] = "custom",
            ["created"] = spec.Created,
            ["lastVersionId"] = spec.VersionId,

            // The isolation. Fabric's own profile JSON carries no gameDir - verified on this machine
            // against the version folder the official launcher actually launched - so the game
            // directory has to be carried by the profile, which is the documented home for it.
            ["gameDir"] = spec.GameDir,
        };

        if (!string.IsNullOrWhiteSpace(spec.Icon))
        {
            profile["icon"] = spec.Icon;
        }

        if (!string.IsNullOrWhiteSpace(spec.JavaArgs))
        {
            profile["javaArgs"] = spec.JavaArgs;
        }

        var options = new JsonSerializerOptions
        {
            WriteIndented = true,

            // Without this, System.Text.Json escapes '+' as \u002B. That decodes back to '+' so a
            // conforming parser is unaffected - but it turns every -XX:+UseG1GC in a file a human
            // may need to read into -XX:\u002BUseG1GC, and it defeats any literal comparison against
            // the flag list. The same choice JsonStore already makes for the launcher's own files.
            Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
        };

        var value = profile.ToJsonString(options);

        // Re-indent every continuation line so the inserted member lines up with the file's own
        // members instead of being two spaces in from them.
        var lines = value.Replace("\r\n", "\n").Split('\n');
        var builder = new StringBuilder();
        builder.Append(JsonSerializer.Serialize(spec.Key)).Append(": ").Append(lines[0].TrimEnd());

        for (int i = 1; i < lines.Length; i++)
        {
            builder.Append('\n').Append(indent).Append(lines[i].TrimStart());
        }

        return builder.ToString();
    }

    /// <summary>head + middle + tail, as one array.</summary>
    private static byte[] Concat(ReadOnlySpan<byte> head, ReadOnlySpan<byte> middle, ReadOnlySpan<byte> tail)
    {
        var result = new byte[head.Length + middle.Length + tail.Length];
        head.CopyTo(result);
        middle.CopyTo(result.AsSpan(head.Length));
        tail.CopyTo(result.AsSpan(head.Length + middle.Length));
        return result;
    }
}