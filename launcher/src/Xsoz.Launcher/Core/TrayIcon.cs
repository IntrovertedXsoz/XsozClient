using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace Xsoz.Launcher.Core;

/// <summary>
/// The notification-area icon that "close to tray" needs. Without it, closing to tray hid the
/// window with no way back: the process kept running invisibly, and starting the launcher again
/// created a second invisible copy.
/// <para>
/// Shell_NotifyIcon via P/Invoke (no WinForms, no NuGet). Left-click or double-click opens the
/// window; right-click offers Open and Quit. The icon is drawn in code - the same dark square and
/// green mark as the in-game mod's icon - so no image asset is shipped.
/// </para>
/// </summary>
public sealed class TrayIcon : IDisposable
{
    private const int WmApp = 0x8000;
    private const int CallbackMessage = WmApp + 1;
    private const int WmLButtonUp = 0x0202;
    private const int WmLButtonDblClk = 0x0203;
    private const int WmRButtonUp = 0x0205;
    private const int NimAdd = 0x0;
    private const int NimDelete = 0x2;
    private const int NifMessage = 0x1;
    private const int NifIcon = 0x2;
    private const int NifTip = 0x4;

    private readonly HwndSource _source;
    private readonly IntPtr _icon;
    private readonly Action _open;
    private readonly Action _quit;
    private readonly int _taskbarCreated;
    private bool _added;
    private bool _disposed;

    /// <summary>Creates the icon. It is not visible until <see cref="Show"/>.</summary>
    public TrayIcon(Action open, Action quit)
    {
        _open = open;
        _quit = quit;
        _source = new HwndSource(new HwndSourceParameters("XsozClientTray") { Width = 0, Height = 0, WindowStyle = 0 });
        _source.AddHook(WndProc);
        _icon = CreateIcon();
        _taskbarCreated = RegisterWindowMessage("TaskbarCreated");
    }

    /// <summary>Adds the icon to the notification area (idempotent).</summary>
    public void Show()
    {
        if (_added || _disposed)
        {
            return;
        }

        var data = NewData();
        data.uFlags = NifMessage | NifIcon | NifTip;
        data.uCallbackMessage = CallbackMessage;
        data.hIcon = _icon;
        data.szTip = "XsozClient - click to open";
        _added = Shell_NotifyIcon(NimAdd, ref data);
        if (!_added)
        {
            AppLog.Shared.Warn("The tray icon could not be added; the window will minimise instead of hiding.");
        }
    }

    /// <summary>True when the icon is actually in the notification area.</summary>
    public bool IsVisible => _added;

    /// <summary>Removes the icon from the notification area.</summary>
    public void Hide()
    {
        if (!_added)
        {
            return;
        }

        var data = NewData();
        Shell_NotifyIcon(NimDelete, ref data);
        _added = false;
    }

    private NotifyIconData NewData() => new()
    {
        cbSize = Marshal.SizeOf<NotifyIconData>(),
        hWnd = _source.Handle,
        uID = 1,
        szTip = string.Empty,
        szInfo = string.Empty,
        szInfoTitle = string.Empty,
    };

    private IntPtr WndProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg == CallbackMessage)
        {
            switch (lParam.ToInt32() & 0xFFFF)
            {
                case WmLButtonUp:
                case WmLButtonDblClk:
                    _open();
                    handled = true;
                    break;
                case WmRButtonUp:
                    ShowMenu();
                    handled = true;
                    break;
            }
        }
        else if (msg == _taskbarCreated && _added)
        {
            // Explorer restarted: the shell forgot every icon. Put ours back.
            _added = false;
            Show();
        }

        return IntPtr.Zero;
    }

    private void ShowMenu()
    {
        var open = new MenuItem { Header = "Open XsozClient" };
        open.Click += (_, _) => _open();
        var quit = new MenuItem { Header = "Quit" };
        quit.Click += (_, _) => _quit();
        var menu = new ContextMenu { Placement = System.Windows.Controls.Primitives.PlacementMode.MousePoint };
        menu.Items.Add(open);
        menu.Items.Add(new Separator());
        menu.Items.Add(quit);
        menu.IsOpen = true;
    }

    /// <summary>A 32x32 icon drawn in code: dark rounded square, green X (the mod's mark).</summary>
    private static IntPtr CreateIcon()
    {
        const int size = 32;
        var visual = new DrawingVisual();
        using (var dc = visual.RenderOpen())
        {
            dc.DrawRoundedRectangle(new SolidColorBrush(Color.FromRgb(0x15, 0x17, 0x1C)), null, new Rect(1, 1, 30, 30), 7, 7);
            var pen = new Pen(new SolidColorBrush(Color.FromRgb(0x4C, 0xD7, 0x65)), 4.5) { StartLineCap = PenLineCap.Round, EndLineCap = PenLineCap.Round };
            dc.DrawLine(pen, new Point(10, 10), new Point(22, 22));
            dc.DrawLine(pen, new Point(22, 10), new Point(10, 22));
        }

        var bitmap = new RenderTargetBitmap(size, size, 96, 96, PixelFormats.Pbgra32);
        bitmap.Render(visual);
        var pixels = new byte[size * size * 4];
        bitmap.CopyPixels(pixels, size * 4, 0);

        var color = CreateBitmap(size, size, 1, 32, pixels);
        var mask = CreateBitmap(size, size, 1, 1, new byte[size * size / 8]);
        var info = new IconInfo { fIcon = true, hbmColor = color, hbmMask = mask };
        var icon = CreateIconIndirect(ref info);
        DeleteObject(color);
        DeleteObject(mask);
        return icon;
    }

    /// <inheritdoc />
    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        Hide();
        _disposed = true;
        _source.RemoveHook(WndProc);
        _source.Dispose();
        if (_icon != IntPtr.Zero)
        {
            DestroyIcon(_icon);
        }
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct NotifyIconData
    {
        public int cbSize;
        public IntPtr hWnd;
        public int uID;
        public int uFlags;
        public int uCallbackMessage;
        public IntPtr hIcon;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)]
        public string szTip;
        public int dwState;
        public int dwStateMask;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)]
        public string szInfo;
        public int uVersion;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)]
        public string szInfoTitle;
        public int dwInfoFlags;
        public Guid guidItem;
        public IntPtr hBalloonIcon;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct IconInfo
    {
        [MarshalAs(UnmanagedType.Bool)]
        public bool fIcon;
        public int xHotspot;
        public int yHotspot;
        public IntPtr hbmMask;
        public IntPtr hbmColor;
    }

    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool Shell_NotifyIcon(int message, ref NotifyIconData data);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int RegisterWindowMessage(string name);

    [DllImport("gdi32.dll")]
    private static extern IntPtr CreateBitmap(int width, int height, uint planes, uint bitsPerPixel, byte[] bits);

    [DllImport("gdi32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DeleteObject(IntPtr handle);

    [DllImport("user32.dll")]
    private static extern IntPtr CreateIconIndirect(ref IconInfo info);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DestroyIcon(IntPtr icon);
}
