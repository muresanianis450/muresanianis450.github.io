package ro.muresanianis.sdcardbackup;

/**
 * A one-shot value delivered through LiveData.
 *
 * LiveData replays its last value to every new observer, which is what we want for
 * state (status text, button enablement) and exactly what we don't want for actions.
 * Without this, rotating the device after an archive finishes would re-open the save
 * picker, and re-show the failure dialog, every single time.
 */
final class Event<T> {

    private final T content;
    private boolean handled = false;

    Event(T content) {
        this.content = content;
    }

    /** Returns the payload the first time only; null on every later call. */
    T getIfNotHandled() {
        if (handled) return null;
        handled = true;
        return content;
    }
}
