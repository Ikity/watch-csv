package dev.watchnotes;

import android.app.Instrumentation;
import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import java.io.File;
import java.util.*;

/** Real Android SQLite checks using isolated database names, never the user's notes.db. */
public final class StoreInstrumentation extends Instrumentation {
    private int count;
    private void check(boolean condition, String message) {
        count++; if (!condition) throw new AssertionError(message);
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try { runTests(); result.putString("stream", "Notes storage: " + count + " assertions passed\n"); finish(Activity.RESULT_OK,result); }
        catch (Throwable t) { result.putString("stream",android.util.Log.getStackTraceString(t)); finish(Activity.RESULT_CANCELED,result); }
    }
    private void runTests() throws Exception {
        Context c=getTargetContext();
        String aName="notes-test-a.db", bName="notes-test-b.db";
        c.deleteDatabase(aName); c.deleteDatabase(bName);
        File snapshot=new File(c.getCacheDir(),"notes-test-snapshot.db");
        try (NotesStore a=new NotesStore(c,aName); NotesStore b=new NotesStore(c,bName)) {
            Note draft=new Note(); draft.title="Original"; draft.body="First body";
            Note first=a.save(draft);
            check(a.current().size()==1,"first save appears");
            check(a.revisions(true).size()==1,"saved note in durable outbox");
            b.insert(first,true); b.insert(first,true);
            check(b.revisions(false).size()==1,"duplicate receipt idempotent");
            check(b.revisions(true).isEmpty(),"received data not automatically echoed");
            Note left=first.copy(); left.body="Watch offline edit"; left=a.save(left);
            Note right=first.copy(); right.body="Phone offline edit"; right=b.save(right);
            check(left.clock==right.clock,"concurrent revisions have equal logical clocks");
            a.insert(right,true); b.insert(left,true);
            check(a.current().get(0).revision.equals(b.current().get(0).revision),"arrival-order independent convergence");
            check(a.revisions(false).size()==3 && b.revisions(false).size()==3,"both concurrent edits preserved");
            Note deletion=a.current().get(0).copy(); deletion.deleted=true; deletion=a.save(deletion); b.insert(deletion,true);
            check(a.current().get(0).deleted && b.current().get(0).deleted,"deletion tombstone synchronizes");
            b.insert(first,true);
            check(b.current().get(0).deleted,"stale delivery cannot resurrect deleted note");
            Note restored=b.save(first); a.insert(restored,true);
            check(!a.current().get(0).deleted && a.current().get(0).body.equals(first.body),"historical restoration is a new current revision");
            check(restored.clock>deletion.clock,"restored revision advances logical clock");
            a.sent(left.revision);
            boolean pending=false;
            for (Note n:a.revisions(true)) if (n.revision.equals(left.revision)) pending=true;
            check(!pending,"acknowledged outbox record marked sent");
            a.resendAll(); check(a.revisions(true).size()==a.revisions(false).size(),"explicit re-publish includes all history");
            int before=a.revisions(false).size();
            Note valid=new Note(), invalid=new Note(); invalid.clock=0;
            boolean rejected=false;
            try { a.importNotes(Arrays.asList(valid,invalid)); } catch (Exception expected) { rejected=true; }
            check(rejected && a.revisions(false).size()==before,"invalid import rolls back all notes");
            a.snapshot(snapshot);
            List<Note> backup=NotesStore.readBackup(snapshot);
            check(backup.size()==before,"standalone SQLite snapshot contains complete history");
            b.importNotes(backup);
            check(b.current().get(0).revision.equals(a.current().get(0).revision),"backup merge converges");
            check(b.revisions(false).size()==before,"backup merge does not duplicate history");
            a.snapshot(snapshot);
            check(NotesStore.readBackup(snapshot).size()==before,"repeated snapshot safely replaces file");
        } finally { c.deleteDatabase(aName); c.deleteDatabase(bName); snapshot.delete(); }
    }
}
