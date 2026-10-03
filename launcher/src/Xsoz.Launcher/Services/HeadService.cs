using System.Collections.Concurrent;
using System.Net;
using System.Net.Http;
using System.Text.Json;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Xsoz.Launcher.Core;

namespace Xsoz.Launcher.Services;

/// <summary>
/// One head, and everything a caller (or a support harness) needs to say about where it came from.
/// </summary>
/// <param name="Image">
/// A frozen image source, never null. When nothing could be fetched this is
/// <see cref="HeadService.DefaultHead"/>, which is drawn in code rather than downloaded.
/// </param>
/// <param name="Source">"default", "cache", "mcheads" or "crafatar".</param>
/// <param name="Uuid">The resolved Mojang UUID, or null when the primary path answered by name.</param>
/// <param name="CachePath">The cache file that was read or written, or empty when there is none.</param>
/// <param name="Bytes">PNG byte count of what was decoded.</param>
/// <param name="Width">Decoded width in pixels, after any nearest-neighbour rescale.</param>
/// <param name="Height">Decoded height in pixels, after any nearest-neighbour rescale.</param>
/// <param name="Status">HTTP status of the request that produced the bytes, or null for cache/default.</param>
public sealed record HeadImage(
    ImageSource Image,
    string Source,
    string? Uuid,
    string CachePath,
    long Bytes,
    int Width,
    int Height,
    HttpStatusCode? Status);

/// <summary>
/// The account chip's skin head, and nothing else. It is cosmetic: it is a picture of a face, it
/// is not a sign-in, and a failure to draw it is not an error the user is ever shown.
///
/// WHY IT IS A SEPARATE SERVICE AND NOT A LINE IN THE VIEW MODEL. Three reasons, each of which
/// would be a bug from somewhere else. It talks to the network, so it needs one client with one
/// timeout for the process rather than a client per request. It writes to disk, so it needs one
/// place that owns the cache layout. And it produces a <see cref="BitmapSource"/>, which is a
/// dispatcher-affine object, so the decode has to happen off the UI thread and the result has to
/// come back frozen. All three are easier to get right once, in one file, than spread across a
/// view model and a view.
///
/// THE TWO ENDPOINTS, AND WHY THERE ARE TWO.
/// <list type="bullet">
///   <item><description>
///     <b>mcheads.org is the primary.</b> It takes the username directly
///     (<c>https://api.mcheads.org/head/{name}/{size}</c>), so one request answers the question and
///     no name-to-UUID lookup is needed. It needs no key, publishes no rate limit, is CORS-enabled
///     and states that it is not affiliated with Mojang or Microsoft.
///   </description></item>
///   <item><description>
///     <b>crafatar.com is the fallback.</b> It is the documented alternative, and it rejects
///     usernames: it takes a UUID only (<c>https://crafatar.com/avatars/{uuid}?size={size}</c>).
///     So the fallback path is two requests - the Mojang session profile lookup to turn a name into
///     a UUID, then Crafatar for the head.
///   </description></item>
/// </list>
///
/// WHAT IS NEVER DONE HERE. No credential is read, no access token exists, no authentication
/// endpoint is contacted, and no name is invented. If this launcher does not already know a
/// username from a source the user typed into, the answer is
/// <see cref="DefaultHead"/> and no request is made at all - see
/// <see cref="AccountOwnership.UnknownPlayerName"/>.
///
/// THREADING. <see cref="GetAsync"/> is the whole public surface and it never runs on the caller's
/// thread: the body is dispatched to the thread pool, every HTTP call is asynchronous, and the
/// image is decoded and rescaled there. What comes back is frozen, so handing it to the UI thread
/// is a reference assignment rather than a cross-thread mutation.
/// </summary>
public static class HeadService
{
    /// <summary>
    /// The size asked of the render endpoint. 128px rather than 32 because the chip is 32 DIP and a
    /// 150% display turns that into 48 device pixels: fetching the head once at a size that covers
    /// every scale keeps the cache key the account and not the monitor.
    /// </summary>
    public const int RequestSize = 128;

    /// <summary>
    /// How old a cached head may get before the next refresh cycle is allowed to go to the
    /// network. One request per half hour per account is the whole of this feature's network
    /// behaviour.
    /// </summary>
    public static readonly TimeSpan RefreshInterval = TimeSpan.FromMinutes(30);

    private const string PrimaryHeadUrl = "https://api.mcheads.org/head/";
    private const string MojangProfileUrl = "https://api.mojang.com/users/profiles/minecraft/";
    private const string CrafatarHeadUrl = "https://crafatar.com/avatars/";

    /// <summary>Guards against a hostile or broken response decoding into a gigantic bitmap.</summary>
    private const int MaxHeadPixels = 4 * 1024 * 1024;

    private static readonly HttpClient Client = CreateClient();

    /// <summary>
    /// Name to UUID, remembered for the life of the process once the Mojang lookup has answered.
    /// In memory only, and it is not a credential: a public profile id for a name the user typed.
    /// </summary>
    private static readonly ConcurrentDictionary<string, string> KnownUuids = new(StringComparer.OrdinalIgnoreCase);

    private static readonly Lock DefaultGate = new();
    private static ImageSource? _defaultHead;

    /// <summary>
    /// Whether this process may reach the network for a head at all.
    /// <para>
    /// Set exactly once, by <c>App.OnStartup</c>, and only for a bare interactive launch. Every
    /// diagnostic mode - the headless capture above all - runs with it false, which means those
    /// modes read the disk cache if there is one and draw <see cref="DefaultHead"/> if there is not.
    /// That is what keeps a capture fast and deterministic, and it is a property of the flag rather
    /// than of a timeout somebody hopes is long enough.
    /// </para>
    /// </summary>
    public static bool NetworkAllowed { get; set; } = true;

    /// <summary>
    /// Test seam for the fallback drill: when set, this base replaces the primary head endpoint, so
    /// a harness can point the primary at a host that does not exist and watch the Mojang lookup
    /// and Crafatar do the work instead. Never set by the interactive path - the same shape as
    /// <see cref="MojangApi.ManifestUrlOverride"/>.
    /// </summary>
    public static string? PrimaryHeadUrlOverride { get; set; }

    /// <summary>The cache directory. Created lazily, on the first head that is written.</summary>
    public static string CacheRoot => AppPaths.Heads;

    /// <summary>
    /// The cache file for one account. Keyed by the resolved UUID when there is one, because that
    /// is the stable identity of a player; the primary endpoint answers by name without ever
    /// producing a UUID, so its file is keyed by the lower-cased name instead. Either way the key
    /// is stable for a given player, which is what stops the same head being downloaded twice.
    /// </summary>
    public static string CachePathFor(string uuidOrName) =>
        Path.Combine(CacheRoot, Slug(uuidOrName) + ".png");

    /// <summary>
    /// The head shown when the launcher does not know who the player is, and the head shown when
    /// both endpoints failed.
    /// <para>
    /// Drawn in code, as eight by eight whole-pixel rectangles in the shape of the default
    /// Steve head, rather than shipped as a PNG. That is the same decision the rest of the design
    /// makes - geometry instead of assets, like every icon in it - and it has a second, sharper
    /// reason: this project bundles no Mojang texture of any kind, so a Steve head cannot be a
    /// file in the repository. Being geometry, it is also resolution-independent, which is exactly
    /// what a pixel-art face wants at 125% and 150% DPI.
    /// </para>
    /// </summary>
    public static ImageSource DefaultHead
    {
        get
        {
            lock (DefaultGate)
            {
                return _defaultHead ??= BuildDefaultHead();
            }
        }
    }

    /// <summary>
    /// Resolves the head for a player name, from cache when the cache is good enough and from the
    /// network otherwise.
    /// <para>
    /// Never throws, never returns null and never blocks the caller's thread. Everything it does -
    /// reading the cache file, the two HTTP round trips, decoding the PNG and rescaling it - runs
    /// on the thread pool, and the frozen image comes back to be assigned on whichever thread is
    /// waiting for it.
    /// </para>
    /// </summary>
    /// <param name="username">A name the user typed, or null. Never derived from a credential.</param>
    /// <param name="targetPixels">
    /// The exact device-pixel size the chip will draw it at - normally the chip's 32 DIP multiplied
    /// by the current display scale. The image is rescaled to it with a nearest-neighbour point
    /// sample rather than being handed to WPF's scaler, so the face is never blurred; pass 0 to
    /// keep the fetched size.
    /// </param>
    /// <param name="ct">Cancels the request. Cancellation is not a failure.</param>
    public static Task<HeadImage> GetAsync(string? username, int targetPixels, CancellationToken ct) =>
        Task.Run(() => LoadAsync(username, targetPixels, ct), ct);

    /// <summary>
    /// The Mojang name-to-UUID lookup, exposed because it is a distinct step worth being able to
    /// see. Public for the support harness; nothing in the interactive path calls it directly.
    /// </summary>
    public static async Task<string?> ResolveUuidAsync(string username, CancellationToken ct)
    {
        using var response = await Client
            .GetAsync(MojangProfileUrl + Uri.EscapeDataString(username), HttpCompletionOption.ResponseHeadersRead, ct)
            .ConfigureAwait(false);

        if (!response.IsSuccessStatusCode)
        {
            AppLog.Shared.Info($"Mojang has no profile for '{username}': HTTP {(int)response.StatusCode}.");
            return null;
        }

        var body = await response.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
        using var document = JsonDocument.Parse(body);
        if (!document.RootElement.TryGetProperty("id", out var id))
        {
            return null;
        }

        var uuid = id.GetString();
        if (!IsUuidShape(uuid))
        {
            return null;
        }

        KnownUuids[username] = uuid!;
        return uuid;
    }

    // -------------------------------------------------------------------------------------------
    // The load. Everything below runs on the thread pool.
    // -------------------------------------------------------------------------------------------

    private static async Task<HeadImage> LoadAsync(string? username, int targetPixels, CancellationToken ct)
    {
        var name = NormaliseName(username);
        if (name is null)
        {
            // No name is not an error and not a request. The chip keeps saying what it says.
            return Fallback("default", null, string.Empty, 0, null);
        }

        var namePath = CachePathFor(name);
        KnownUuids.TryGetValue(name, out var knownUuid);
        var uuidPath = knownUuid is null ? null : CachePathFor(knownUuid);

        // A fresh cache entry answers the question with no request at all. This is the 30-minute
        // rule: it is expressed as the age of the file, so it holds across restarts too.
        if (IsFresh(namePath) || (uuidPath is not null && IsFresh(uuidPath)))
        {
            var cached = FromCache(namePath, uuidPath, targetPixels);
            if (cached is not null)
            {
                return cached;
            }
        }

        if (!NetworkAllowed)
        {
            // A diagnostic mode. Read what is on disk, draw the default if nothing is, and open no
            // socket. A capture therefore cannot stall on somebody else's uptime.
            return FromCache(namePath, uuidPath, targetPixels)
                ?? Fallback("default", null, string.Empty, 0, null);
        }

        byte[]? png = null;
        string source = string.Empty;
        HttpStatusCode? status = null;
        string? uuid = null;
        var cachePath = namePath;

        try
        {
            var primary = await FetchAsync(
                (PrimaryHeadUrlOverride ?? PrimaryHeadUrl) + name + "/" + RequestSize, ct).ConfigureAwait(false);
            png = primary.Bytes;
            status = primary.Status;
            source = "mcheads";
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            return Fallback("default", null, string.Empty, 0, null);
        }
        catch (Exception ex)
        {
            // One line, and then the fallback. Not a retry loop: this cycle gets exactly one more
            // attempt, through a different provider.
            AppLog.Shared.Warn($"mcheads.org did not return a head for '{name}': {ex.Message}. Trying Crafatar.");
        }

        if (png is null)
        {
            try
            {
                uuid = knownUuid ?? await ResolveUuidAsync(name, ct).ConfigureAwait(false);
                if (uuid is not null)
                {
                    var bytes = await FetchAsync(
                        CrafatarHeadUrl + uuid + "?size=" + RequestSize, ct).ConfigureAwait(false);
                    if (bytes.Bytes.Length > 0)
                    {
                        png = bytes.Bytes;
                        source = "crafatar";
                        cachePath = CachePathFor(uuid);
                    }
                }
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                return Fallback("default", null, string.Empty, 0, null);
            }
            catch (Exception ex)
            {
                AppLog.Shared.Warn($"Crafatar did not return a head for '{name}': {ex.Message}.");
            }
        }

        if (png is null || png.Length == 0)
        {
            AppLog.Shared.Warn($"No skin head available for '{name}'; the chip keeps the default head. Nothing else changed.");
            return Fallback("default", null, string.Empty, 0, null);
        }

        try
        {
            var written = WriteCache(cachePath, png);
            if (uuid is not null && !string.Equals(written, namePath, StringComparison.OrdinalIgnoreCase))
            {
                // The name key is what the primary path will look at next time, so a head fetched
                // through the fallback is filed under both. One extra 700-byte file, once.
                WriteCache(namePath, png);
            }

            var decoded = Decode(png, targetPixels);
            AppLog.Shared.Info(
                $"Skin head for '{name}' from {source} (HTTP {(int?)status ?? 0}, {png.Length} bytes, {decoded.Width}x{decoded.Height} px) cached at {written}.");
            return new HeadImage(decoded.Image, source, uuid, written, png.Length, decoded.Width, decoded.Height, status);
        }
        catch (Exception ex)
        {
            AppLog.Shared.Warn($"The skin head for '{name}' could not be decoded; the chip keeps the default head. {ex.Message}");
            return Fallback("default", uuid, cachePath, 0, status);
        }
    }

    /// <summary>GETs a URL that must answer 200 with bytes. Returns them, or an empty array.</summary>
    private static async Task<(byte[] Bytes, HttpStatusCode Status)> FetchAsync(string url, CancellationToken ct)
    {
        using var response = await Client.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode)
        {
            return ([], response.StatusCode);
        }

        return (await response.Content.ReadAsByteArrayAsync(ct).ConfigureAwait(false), response.StatusCode);
    }

    /// <summary>
    /// Reads a cached head if one is there, and rescales it on the way out exactly as a freshly
    /// fetched one is.
    /// <para>
    /// That symmetry is load-bearing and it was a real bug before it was written down. The cache
    /// holds the 128px master so it stays valid on any display, which means every read has to be
    /// resampled to the chip's current device size - a cache hit that skipped it would hand the
    /// view a 128px image and let WPF scale it, which is the blur this whole arrangement exists to
    /// avoid, and it would do so on every launch inside the half-hour window.
    /// </para>
    /// Returns null rather than throwing, because a missing, truncated or unreadable cache file is
    /// an ordinary state and not a fault.
    /// </summary>
    private static HeadImage? FromCache(string namePath, string? uuidPath, int targetPixels)
    {
        foreach (var path in new[] { namePath, uuidPath }.OfType<string>())
        {
            try
            {
                if (!File.Exists(path))
                {
                    continue;
                }

                var png = File.ReadAllBytes(path);
                if (png.Length == 0)
                {
                    continue;
                }

                var decoded = Decode(png, targetPixels);
                AppLog.Shared.Info($"Skin head served from the cache: {path} ({png.Length} bytes, {decoded.Width}x{decoded.Height} px).");
                return new HeadImage(decoded.Image, "cache", null, path, png.Length, decoded.Width, decoded.Height, null);
            }
            catch (Exception ex)
            {
                AppLog.Shared.Info($"Cached skin head at {path} was unreadable ({ex.Message}); it will be fetched again.");
                TryDelete(path);
            }
        }

        return null;
    }

    /// <summary>True when the file exists and is younger than <see cref="RefreshInterval"/>.</summary>
    private static bool IsFresh(string path)
    {
        try
        {
            var info = new FileInfo(path);
            return info.Exists && info.Length > 0 && DateTime.UtcNow - info.LastWriteTimeUtc < RefreshInterval;
        }
        catch (Exception)
        {
            return false;
        }
    }

    /// <summary>
    /// Writes the PNG and returns the path it landed at. Atomically, and only ever inside
    /// <see cref="CacheRoot"/>: a name that somehow contained a separator is rejected before it
    /// becomes a path, and the resolved path is checked against the cache root afterwards. A cache
    /// that can be talked into writing outside its own folder is a cache that can be talked into
    /// writing anywhere.
    /// <para>
    /// The temporary file is named per write rather than per target. Two callers writing the same
    /// account's head at the same moment - which the interactive path prevents but the service does
    /// not promise to - used to share one ".part" name, and the loser of the rename threw and threw
    /// away a perfectly good image it had just downloaded.
    /// </para>
    /// </summary>
    private static string WriteCache(string path, byte[] png)
    {
        if (!AppPaths.IsInside(CacheRoot, path))
        {
            throw new InvalidOperationException("Refusing to write a skin head outside the cache folder.");
        }

        Directory.CreateDirectory(CacheRoot);
        var temporary = $"{path}.{Environment.ProcessId}.{Interlocked.Increment(ref WriteCounter):x}.part";
        try
        {
            File.WriteAllBytes(temporary, png);
            File.Move(temporary, path, overwrite: true);
        }
        catch (Exception)
        {
            TryDelete(temporary);
            throw;
        }

        return path;
    }

    private static int WriteCounter;

    private static void TryDelete(string path)
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
            // A locked cache file is annoying, not fatal. It will be overwritten or ignored.
        }
    }

    // -------------------------------------------------------------------------------------------
    // Decoding. Off the UI thread by construction - see GetAsync.
    // -------------------------------------------------------------------------------------------

    private static (ImageSource Image, int Width, int Height) Decode(byte[] png, int targetPixels)
    {
        using var stream = new MemoryStream(png, writable: false);

        var bitmap = new BitmapImage();
        bitmap.BeginInit();
        // OnLoad: the pixels are decoded here and now, off the UI thread, and the stream can be
        // disposed the moment this returns. Without it WPF would keep decoding lazily on whatever
        // thread first asks for a pixel - which is the UI thread.
        bitmap.CacheOption = BitmapCacheOption.OnLoad;
        bitmap.CreateOptions = BitmapCreateOptions.PreservePixelFormat;
        bitmap.StreamSource = stream;
        bitmap.EndInit();

        if ((long)bitmap.PixelWidth * bitmap.PixelHeight > MaxHeadPixels)
        {
            throw new InvalidDataException($"The head render claims {bitmap.PixelWidth}x{bitmap.PixelHeight} pixels.");
        }

        bitmap.Freeze();

        if (targetPixels > 0 && bitmap.PixelWidth != targetPixels)
        {
            return (RescaleNearest(bitmap, targetPixels), targetPixels, RescaledHeight(bitmap, targetPixels));
        }

        return (bitmap, bitmap.PixelWidth, bitmap.PixelHeight);
    }

    private static int RescaledHeight(BitmapSource source, int targetPixels) =>
        Math.Max(1, (int)Math.Round(source.PixelHeight * (double)targetPixels / source.PixelWidth, MidpointRounding.AwayFromZero));

    /// <summary>
    /// Box-free nearest-neighbour rescale: each destination pixel takes the source pixel nearest
    /// the centre of its cell.
    /// <para>
    /// This is the whole of the crispness story. A rendered skin head is eight by eight texels -
    /// genuinely pixel art - and WPF's default bilinear scaler turns a 128px head into a 32px smudge
    /// at exactly the moment it becomes legible. Point-sampling to the exact device size instead
    /// means the chip is a one-to-one blit of the pixels, at 100%, at 125% and at 150%, and the
    /// <c>NearestNeighbor</c> on the image element is then belt to this braces.
    /// </para>
    /// </summary>
    private static BitmapSource RescaleNearest(BitmapSource source, int targetPixels)
    {
        var converted = new FormatConvertedBitmap(source, PixelFormats.Bgra32, null, 0);
        var sourceWidth = converted.PixelWidth;
        var sourceHeight = converted.PixelHeight;
        var sourceStride = sourceWidth * 4;
        var sourcePixels = new byte[checked(sourceStride * sourceHeight)];
        converted.CopyPixels(sourcePixels, sourceStride, 0);

        var targetWidth = Math.Max(1, targetPixels);
        var targetHeight = RescaledHeight(source, targetWidth);
        var targetStride = targetWidth * 4;
        var targetPixels8 = new byte[checked(targetStride * targetHeight)];

        for (var y = 0; y < targetHeight; y++)
        {
            var sourceY = Math.Clamp((int)((y + 0.5) * sourceHeight / targetHeight), 0, sourceHeight - 1);
            for (var x = 0; x < targetWidth; x++)
            {
                var sourceX = Math.Clamp((int)((x + 0.5) * sourceWidth / targetWidth), 0, sourceWidth - 1);
                Buffer.BlockCopy(
                    sourcePixels,
                    (sourceY * sourceStride) + (sourceX * 4),
                    targetPixels8,
                    (y * targetStride) + (x * 4),
                    4);
            }
        }

        var result = new WriteableBitmap(targetWidth, targetHeight, 96, 96, PixelFormats.Bgra32, null);
        result.WritePixels(new Int32Rect(0, 0, targetWidth, targetHeight), targetPixels8, targetStride, 0);
        result.Freeze();
        return result;
    }

    // -------------------------------------------------------------------------------------------
    // The default head.
    // -------------------------------------------------------------------------------------------

    // Eight rows of eight, the front face of the default Steve head at one texel each. H hair,
    // S skin, E eye white, I iris, N nose shadow, B beard. Eyes are two texels wide with the iris
    // on the outer edge, which is what makes it read as a face and not as a mask.
    private static readonly string[] SteveHead =
    [
        "HHHHHHHH",
        "HHHHHHHH",
        "SSHHHHSS",
        "SEEEEEES",
        "SIEEEEIS",
        "SSSSNNSS",
        "SSBBBBSS",
        "BBBBBBBB",
    ];

    private static readonly Dictionary<char, Color> StevePalette = new()
    {
        ['H'] = Color.FromRgb(0x3B, 0x2A, 0x16),
        ['S'] = Color.FromRgb(0xB5, 0x8B, 0x62),
        ['N'] = Color.FromRgb(0x9C, 0x73, 0x50),
        ['E'] = Color.FromRgb(0xDC, 0xDC, 0xDC),
        ['I'] = Color.FromRgb(0x3B, 0x5B, 0xD9),
        ['B'] = Color.FromRgb(0x6B, 0x4A, 0x2A),
    };

    private static ImageSource BuildDefaultHead()
    {
        var group = new DrawingGroup();

        for (var y = 0; y < SteveHead.Length; y++)
        {
            var row = SteveHead[y];
            for (var x = 0; x < row.Length; x++)
            {
                if (!StevePalette.TryGetValue(row[x], out var colour))
                {
                    continue;
                }

                var brush = new SolidColorBrush(colour);
                brush.Freeze();

                group.Children.Add(new GeometryDrawing(
                    brush,
                    null,
                    new RectangleGeometry(new Rect(x, y, 1, 1))));
            }
        }

        group.Freeze();
        var image = new DrawingImage(group);
        image.Freeze();
        return image;
    }

    private static HeadImage Fallback(string source, string? uuid, string cachePath, long bytes, HttpStatusCode? status) =>
        new(DefaultHead, source, uuid, cachePath, bytes, 32, 32, status);

    // -------------------------------------------------------------------------------------------
    // Names and paths.
    // -------------------------------------------------------------------------------------------

    /// <summary>
    /// A username, or null. Mojang names are 3-16 characters of letters, digits and underscore;
    /// anything else is not a name this is willing to put in a URL or a file path.
    /// </summary>
    private static string? NormaliseName(string? raw)
    {
        var trimmed = raw?.Trim();
        if (string.IsNullOrEmpty(trimmed) || trimmed.Length is < 3 or > 16)
        {
            return null;
        }

        foreach (var c in trimmed)
        {
            if (!char.IsAsciiLetterOrDigit(c) && c != '_')
            {
                return null;
            }
        }

        return trimmed;
    }

    /// <summary>Lower-cases a key and reduces anything that is not a letter or digit to an underscore.</summary>
    private static string Slug(string key)
    {
        Span<char> buffer = stackalloc char[key.Length];
        var length = 0;

        foreach (var c in key)
        {
            var lowered = char.ToLowerInvariant(c);
            buffer[length++] = char.IsAsciiLetterOrDigit(lowered) ? lowered : '_';
        }

        return new string(buffer[..length]);
    }

    private static bool IsUuidShape(string? value) =>
        value is { Length: 32 }
        && value.All(c => c is >= '0' and <= '9' or >= 'a' and <= 'f' or >= 'A' and <= 'F');

    private static HttpClient CreateClient()
    {
        var handler = new HttpClientHandler
        {
            AutomaticDecompression = DecompressionMethods.All,
        };

        var client = new HttpClient(handler)
        {
            // Ten seconds, and it is a real bound rather than the framework's hundred. This is a
            // 32-pixel square on a rail; a slow or half-open endpoint must not be able to hold the
            // refresh cycle open, and the caller additionally carries its own cancellation token.
            Timeout = TimeSpan.FromSeconds(10),
        };

        client.DefaultRequestHeaders.UserAgent.ParseAdd(
            "XsozClient/" + (typeof(HeadService).Assembly.GetName().Version?.ToString(3) ?? "0.1.0")
            + " (launcher; skin head render)");
        client.DefaultRequestHeaders.Accept.ParseAdd("image/png, application/json");
        return client;
    }
}