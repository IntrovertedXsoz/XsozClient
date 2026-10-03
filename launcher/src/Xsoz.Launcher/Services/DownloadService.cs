using System.Net.Http;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using Xsoz.Launcher.Core;

namespace Xsoz.Launcher.Services;

/// <summary>Per-file transfer state, reported from a download.</summary>
/// <param name="Url">The source URL.</param>
/// <param name="FileName">The local file name being written.</param>
/// <param name="ReceivedBytes">Bytes written so far.</param>
/// <param name="TotalBytes">Total bytes, or -1 when the server did not say.</param>
/// <param name="BytesPerSecond">Rolling transfer rate.</param>
public readonly record struct TransferProgress(
    string Url, string FileName, long ReceivedBytes, long TotalBytes, double BytesPerSecond);

/// <summary>
/// The single HTTP path every download in the launcher goes through.
///
/// Deliberately BCL-only: one static HttpClient, Range-resume on a .part sidecar, SHA-1
/// verification against the upstream manifest, and an atomic rename into place. A cancelled or
/// failed download never leaves a partial file at the final path, so the idempotency check on
/// the next run never has to trust half of anything.
/// </summary>
public sealed class DownloadService
{
    private static readonly HttpClient Shared = CreateClient();

    private static HttpClient CreateClient()
    {
        var handler = new HttpClientHandler
        {
            AutomaticDecompression = System.Net.DecompressionMethods.All,
            MaxConnectionsPerServer = 12,
        };

        var client = new HttpClient(handler)
        {
            // Some CDN endpoints (the Adoptium redirect target in particular) dribble data in
            // slowly enough that the framework's 100 s default fires mid-file. None of the
            // downloads this launcher makes are faster than the link, so the limit is a leak
            // detector, not a scheduler.
            Timeout = TimeSpan.FromMinutes(30),
        };

        // Modrinth's labrinth instance rate-limits request batches without a named UA. The
        // header names the product and the contact surface, which is the documented ask.
        client.DefaultRequestHeaders.UserAgent.ParseAdd(
            "XsozClient/" + (typeof(DownloadService).Assembly.GetName().Version?.ToString(3) ?? "0.1.0")
            + " (github.com/xsozclient; launcher bootstrap)");
        client.DefaultRequestHeaders.Accept.ParseAdd("application/json, */*;q=0.8");
        return client;
    }

    /// <summary>Runs a GET that must succeed, with one retry after a short delay.</summary>
    public async Task<string> GetStringAsync(string url, CancellationToken ct)
    {
        Exception? last = null;
        for (var attempt = 1; attempt <= 3; attempt++)
        {
            try
            {
                using var response = await Shared.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct)
                    .ConfigureAwait(false);
                var body = await response.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
                if (!response.IsSuccessStatusCode)
                {
                    throw new HttpRequestException(
                        $"GET {HostOf(url)} returned {(int)response.StatusCode} {response.ReasonPhrase}.", null, response.StatusCode);
                }

                return body;
            }
            catch (Exception ex) when (ex is not OperationCanceledException && attempt < 3)
            {
                last = ex;
            }

            await Task.Delay(TimeSpan.FromMilliseconds(400 * attempt), ct).ConfigureAwait(false);
        }

        throw last ?? new InvalidOperationException("Unreachable.");
    }

    /// <summary>Runs a GET for bytes that must succeed, with retries.</summary>
    public async Task<byte[]> GetBytesAsync(string url, CancellationToken ct)
    {
        Exception? last = null;
        for (var attempt = 1; attempt <= 3; attempt++)
        {
            try
            {
                using var response = await Shared.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct)
                    .ConfigureAwait(false);
                var body = await response.Content.ReadAsByteArrayAsync(ct).ConfigureAwait(false);
                if (!response.IsSuccessStatusCode)
                {
                    throw new HttpRequestException(
                        $"GET {HostOf(url)} returned {(int)response.StatusCode} {response.ReasonPhrase}.", null, response.StatusCode);
                }

                return body;
            }
            catch (Exception ex) when (ex is not OperationCanceledException && attempt < 3)
            {
                last = ex;
            }

            await Task.Delay(TimeSpan.FromMilliseconds(400 * attempt), ct).ConfigureAwait(false);
        }

        throw last ?? new InvalidOperationException("Unreachable.");
    }

    /// <summary>
    /// Downloads one file to <paramref name="path"/> with resume and verification.
    ///
    /// Returns immediately without any network traffic when the destination already exists with
    /// the expected size and hash — this is what makes a re-run of the installer a no-op. A
    /// leftover <c>.part</c> file resumes where it stopped when the server supports ranges.
    /// </summary>
    /// <returns>True when bytes were transferred; false when the file was already complete.</returns>
    public async Task<bool> DownloadAsync(
        string url,
        string path,
        long expectedSizeBytes,
        string? expectedSha1Hex,
        Action<TransferProgress>? progress,
        CancellationToken ct)
    {
        if (await IsCompleteAsync(path, expectedSizeBytes, expectedSha1Hex, ct).ConfigureAwait(false))
        {
            return false;
        }

        var partPath = path + ".part";
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);

        const int maxAttempts = 4;
        Exception? last = null;

        for (var attempt = 1; attempt <= maxAttempts; attempt++)
        {
            ct.ThrowIfCancellationRequested();
            try
            {
                await TransferAsync(url, partPath, progress, ct).ConfigureAwait(false);

                var info = new FileInfo(partPath);
                if (expectedSizeBytes > 0 && info.Length != expectedSizeBytes)
                {
                    last = new HttpRequestException(
                        $"Downloaded {info.Length} bytes for {Path.GetFileName(path)}; expected {expectedSizeBytes}.");
                    continue; // resume from the partial file on the next attempt
                }

                if (!string.IsNullOrEmpty(expectedSha1Hex))
                {
                    var actual = await Sha1HexAsync(partPath, ct).ConfigureAwait(false);
                    if (!string.Equals(actual, expectedSha1Hex, StringComparison.OrdinalIgnoreCase))
                    {
                        // A corrupt file cannot be resumed; it has to start over.
                        TryDelete(partPath);
                        last = new HttpRequestException(
                            $"Checksum mismatch for {Path.GetFileName(path)}: got {actual}, wanted {expectedSha1Hex}. The file was discarded.");
                        continue;
                    }
                }

                File.Move(partPath, path, overwrite: true);
                return true;
            }
            catch (OperationCanceledException)
            {
                throw;
            }
            catch (Exception ex)
            {
                last = ex;
                progress?.Invoke(new TransferProgress(url, Path.GetFileName(path), 0, 0, 0));
            }
        }

        throw last ?? new HttpRequestException("The download could not be completed.");
    }

    /// <summary>True when the destination already matches the expected size and hash.</summary>
    public static async Task<bool> IsCompleteAsync(
        string path, long expectedSizeBytes, string? expectedSha1Hex, CancellationToken ct)
    {
        try
        {
            var info = new FileInfo(path);
            if (!info.Exists || (expectedSizeBytes > 0 && info.Length != expectedSizeBytes))
            {
                return false;
            }

            if (!string.IsNullOrEmpty(expectedSha1Hex))
            {
                var actual = await Sha1HexAsync(path, ct).ConfigureAwait(false);
                return string.Equals(actual, expectedSha1Hex, StringComparison.OrdinalIgnoreCase);
            }

            return true;
        }
        catch (Exception)
        {
            return false;
        }
    }

    private static async Task TransferAsync(
        string url, string partPath, Action<TransferProgress>? report, CancellationToken ct)
    {
        var resumeFrom = 0L;
        try
        {
            var info = new FileInfo(partPath);
            if (info.Exists)
            {
                resumeFrom = info.Length;
            }
        }
        catch (Exception)
        {
            resumeFrom = 0;
        }

        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        if (resumeFrom > 0)
        {
            request.Headers.Range = new RangeHeaderValue(resumeFrom, null);
        }

        using var response = await Shared
            .SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct)
            .ConfigureAwait(false);

        if (!response.IsSuccessStatusCode && response.StatusCode != System.Net.HttpStatusCode.PartialContent)
        {
            throw new HttpRequestException(
                $"GET {HostOf(url)} returned {(int)response.StatusCode} {response.ReasonPhrase}.", null, response.StatusCode);
        }

        // A server that ignores the Range header answers 200 and sends the whole file; trusting
        // the .part length in that case would bootstrap a corrupt file from a valid resume point.
        var acceptedRange = response.StatusCode == System.Net.HttpStatusCode.PartialContent;
        var append = resumeFrom > 0 && acceptedRange;

        var total = response.Content.Headers.ContentLength is { } length
            ? length + (append ? resumeFrom : 0)
            : -1L;

        var fileName = Path.GetFileName(partPath).Replace(".part", string.Empty, StringComparison.Ordinal);
        var received = append ? resumeFrom : 0L;

        await using (var input = await response.Content.ReadAsStreamAsync(ct).ConfigureAwait(false))
        await using (var output = new FileStream(
                         partPath,
                         append ? FileMode.Append : FileMode.Create,
                         FileAccess.Write,
                         FileShare.None,
                         1 << 16,
                         FileOptions.Asynchronous))
        {
            var buffer = new byte[1 << 16];
            var windowStart = Environment.TickCount64;
            var windowBytes = 0L;
            var lastReport = 0L;

            while (true)
            {
                var read = await input.ReadAsync(buffer, ct).ConfigureAwait(false);
                if (read <= 0)
                {
                    break;
                }

                await output.WriteAsync(buffer.AsMemory(0, read), ct).ConfigureAwait(false);
                received += read;
                windowBytes += read;

                // Twenty reports a second is plenty for a progress bar and keeps the UI thread
                // from drowning in PropertyChanged on a fast link.
                var now = Environment.TickCount64;
                if (report is not null && now - lastReport >= 50)
                {
                    lastReport = now;
                    var windowSeconds = Math.Max(1, now - windowStart) / 1000.0;
                    report(new TransferProgress(url, fileName, received, total, windowBytes / windowSeconds));
                    windowStart = now;
                    windowBytes = 0;
                }
            }
        }

        report?.Invoke(new TransferProgress(url, fileName, received, total, 0));
    }

    /// <summary>SHA-1 of a file, lowercase hex.</summary>
    public static async Task<string> Sha1HexAsync(string path, CancellationToken ct)
    {
        await using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 1 << 20, FileOptions.Asynchronous);
        var hash = await SHA1.HashDataAsync(stream, ct).ConfigureAwait(false);
        return Convert.ToHexStringLower(hash);
    }

    /// <summary>Deletes a file if present, never throwing.</summary>
    public static void TryDelete(string path)
    {
        try
        {
            if (File.Exists(path))
            {
                File.Delete(path);
            }
        }
        catch (Exception)
        {
            // A locked .part file is annoying, not fatal: the next run resumes or rehashes it.
        }
    }

    private static string HostOf(string url)
    {
        try
        {
            return new Uri(url).Host;
        }
        catch (Exception)
        {
            return "the remote host";
        }
    }
}
