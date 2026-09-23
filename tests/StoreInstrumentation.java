package dev.watchcsv.viewer;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import java.io.StringWriter;
import java.io.PrintWriter;

/** Run in an installed app context, with Android's real SQLite and JSON implementation. */
public final class StoreInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            StoreTest.main(new String[]{getTargetContext().getCacheDir().getPath()});
            RemoteStoreTest.run(getTargetContext());
            result.putString("stream", "Android SQLite and remote-backend integration tests passed.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            StringWriter message = new StringWriter(); error.printStackTrace(new PrintWriter(message));
            result.putString("stream", message.toString()); finish(Activity.RESULT_CANCELED, result);
        }
    }
}
