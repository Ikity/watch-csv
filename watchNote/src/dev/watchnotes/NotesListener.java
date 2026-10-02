package dev.watchnotes;

import com.google.android.gms.wearable.*;

/** WearableListenerService dispatches on its worker thread, including while the activity is closed. */
public final class NotesListener extends WearableListenerService {
    @Override public void onDataChanged(DataEventBuffer events) {
        for (DataEvent event : events) {
            if (event.getType() != DataEvent.TYPE_CHANGED) continue;
            try {
                NotesSync.receive(this, event.getDataItem());
                getSharedPreferences("notes-status", 0).edit().remove("error").apply();
            } catch (Exception e) {
                getSharedPreferences("notes-status", 0).edit().putString("error", e.toString()).apply();
            }
        }
    }
}
