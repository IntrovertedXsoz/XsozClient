using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;

namespace Xsoz.Launcher.Views;

/// <summary>
/// One native call, for one reason.
///
/// A chrome-less WPF window with <c>System.Windows.Shell.WindowChrome</c> already handles the
/// resize border, the drag region, double-click-to-maximise and snap layouts correctly, because
/// WPF owns that calculation in the framework. Intercepting WM_NCHITTEST by hand as well makes
/// the two disagree, and the observable result is a fast-fail inside win32u the moment the window
/// is maximised. So the hit-testing is not here; only the non-client dark-mode attribute is,
/// which WPF has no API for.
/// </summary>
internal static class WindowChromeNative
{
    private const int DwmwaUseImmersiveDarkMode = 20;
    private const int DwmwaBorderColor = 34;
    private const int DwmwaCaptionButtonSize = 37;

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);

    /// <summary>
    /// Tells DWM to draw its own borders dark and to size the (unused, since we draw our own
    /// buttons) caption buttons sensibly. Purely cosmetic, and safe on an OS build that does not
    /// know the attribute.
    /// </summary>
    public static void ApplyDarkChrome(Window window)
    {
        try
        {
            var handle = new WindowInteropHelper(window).Handle;
            if (handle == IntPtr.Zero)
            {
                return;
            }

            var on = 1;
            _ = DwmSetWindowAttribute(handle, DwmwaUseImmersiveDarkMode, ref on, sizeof(int));
            _ = DwmSetWindowAttribute(handle, DwmwaCaptionButtonSize, ref on, sizeof(int));

            // Window border in the app's hairline colour, so the frame matches the content.
            // DWM takes COLORREF, which is 0x00BBGGRR - the bytes of #2A3040 reversed.
            var border = unchecked((int)0xFF40302A);
            _ = DwmSetWindowAttribute(handle, DwmwaBorderColor, ref border, sizeof(int));
        }
        catch (Exception)
        {
            // These are cosmetic. An older Windows build without
            // DWMWA_USE_IMMERSIVE_DARK_MODE still gets a correct window, just with a light
            // system border.
        }
    }
}
