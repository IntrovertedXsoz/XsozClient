using System.Collections.ObjectModel;
using System.Collections.Specialized;
using System.ComponentModel;

namespace Xsoz.Launcher.ViewModels;

/// <summary>
/// An <see cref="ObservableCollection{T}"/> whose contents can be replaced as one atomic operation.
///
/// Refilling through <c>Clear()</c> followed by a run of <c>Add</c> calls raises one Remove event
/// per old row and one Add event per new row, and every one of them re-runs the item container
/// generator that is watching the list. That is wasted layout on a screen with a thousand rows, and
/// it is also the exact shape of state the generator refuses to accept: it has been told about a
/// series of individual changes and is being walked while the list underneath it is being rebuilt,
/// which is what "An ItemsControl is inconsistent with its items source" reports.
///
/// Replacing the backing <see cref="Items"/> directly and raising a single Reset instead says "the
/// contents changed, look again" in one notification. Reset is the one action the generator can
/// always apply correctly, because it makes no claim about which rows are where - so a rebuild
/// becomes atomic from the collection's point of view rather than a long sequence that a layout
/// pass can catch half way through.
/// </summary>
public sealed class ResetableCollection<T> : ObservableCollection<T>
{
    /// <summary>Replaces every item with <paramref name="items"/> in a single Reset notification.</summary>
    public void ReplaceAll(IEnumerable<T> items)
    {
        ArgumentNullException.ThrowIfNull(items);

        // Materialised first, because the caller may be enumerating this very collection while
        // handing it to us. Enumerating a live list that we are about to clear would otherwise
        // throw halfway through and leave the collection in a state neither old nor new.
        List<T> replacement = items as List<T> ?? [.. items];

        // A Reset that changes nothing still throws the generator away and rebuilds every
        // container, so it is only raised when the contents really are different.
        if (replacement.Count == Count && replacement.SequenceEqual(Items))
        {
            return;
        }

        Items.Clear();
        foreach (var item in replacement)
        {
            Items.Add(item);
        }

        // Count and the indexer, because those are the property notifications an ObservableCollection
        // raises for every one of the mutations being skipped, and a binding that watches either one
        // has to hear about the change exactly as it would have.
        OnPropertyChanged(new PropertyChangedEventArgs(nameof(Count)));
        OnPropertyChanged(new PropertyChangedEventArgs("Item[]"));
        OnCollectionChanged(new NotifyCollectionChangedEventArgs(NotifyCollectionChangedAction.Reset));
    }
}