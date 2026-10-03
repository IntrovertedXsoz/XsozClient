using System.Collections.Specialized;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Windows.Input;

namespace Xsoz.Launcher.ViewModels;

/// <summary>Minimal INotifyPropertyChanged base. Hand-rolled so the app has no MVVM dependency.</summary>
public abstract class ObservableObject : INotifyPropertyChanged
{
    /// <inheritdoc />
    public event PropertyChangedEventHandler? PropertyChanged;

    /// <summary>Raises a change notification for a property.</summary>
    protected void Raise([CallerMemberName] string? propertyName = null) =>
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));

    /// <summary>Assigns a field and raises a change notification when the value actually changed.</summary>
    protected bool Set<T>(ref T field, T value, [CallerMemberName] string? propertyName = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value))
        {
            return false;
        }

        field = value;
        Raise(propertyName);
        return true;
    }
}

/// <summary>
/// A command backed by delegates. <see cref="RaiseCanExecuteChanged"/> is explicit rather than
/// hooked to <see cref="CommandManager.RequerySuggested"/>, because requery-on-focus is a
/// measure-and-arrange storm on a settings screen with this many controls.
/// </summary>
public sealed class RelayCommand : ICommand
{
    private readonly Action<object?> _execute;
    private readonly Predicate<object?>? _canExecute;

    /// <summary>Creates a command from an action and an optional guard.</summary>
    public RelayCommand(Action<object?> execute, Predicate<object?>? canExecute = null)
    {
        _execute = execute;
        _canExecute = canExecute;
    }

    /// <summary>
    /// Creates a command from a parameterless action and guard.
    ///
    /// A factory rather than a second constructor: <see cref="Action{T}"/> and
    /// <see cref="Predicate{T}"/> are both single-argument delegates, so two constructors taking
    /// them would make every lambda call site ambiguous and force a cast at each one.
    /// </summary>
    public static RelayCommand Create(Action execute, Func<bool>? canExecute = null) =>
        new(_ => execute(), canExecute is null ? null : new Predicate<object?>(_ => canExecute()));

    /// <summary>
    /// Creates a command that receives the command parameter. Not a constructor overload, for the
    /// same ambiguity reason as the parameterless factory.
    /// </summary>
    public static RelayCommand CreateWithParameter(Action<object?> execute, Predicate<object?>? canExecute = null) =>
        new(execute, canExecute);

    /// <inheritdoc />
    public event EventHandler? CanExecuteChanged;

    /// <inheritdoc />
    public bool CanExecute(object? parameter) => _canExecute?.Invoke(parameter) ?? true;

    /// <inheritdoc />
    public void Execute(object? parameter) => _execute(parameter);

    /// <summary>Tells the binding layer to re-query the guard.</summary>
    public void RaiseCanExecuteChanged() => CanExecuteChanged?.Invoke(this, EventArgs.Empty);
}
