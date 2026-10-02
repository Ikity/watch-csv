package dev.watchnotes;

import org.json.JSONObject;
import java.util.UUID;

/** Immutable on the wire: each save creates a new revision, never overwriting another device's edit. */
public final class Note {
    public String id = uuid(), revision = uuid(), parent = "", title = "", body = "", category = "", tags = "";
    public long created = System.currentTimeMillis(), updated = created, clock = 1;
    public boolean todo, done, deleted;
    static String uuid() { return UUID.randomUUID().toString(); }
    public JSONObject json() throws Exception {
        return new JSONObject().put("v", 1).put("id", id).put("revision", revision).put("parent", parent)
            .put("title", title).put("body", body).put("category", category).put("tags", tags)
            .put("created", created).put("updated", updated).put("clock", clock)
            .put("todo", todo).put("done", done).put("deleted", deleted);
    }
    public static Note from(JSONObject j) throws Exception {
        if (j.getInt("v") != 1) throw new IllegalArgumentException("Unsupported note version");
        Note n = new Note();
        n.id = j.getString("id"); n.revision = j.getString("revision"); n.parent = j.getString("parent");
        validId(n.id); validId(n.revision); if (!n.parent.isEmpty()) validId(n.parent);
        n.title = j.getString("title"); n.body = j.getString("body"); n.category = j.getString("category"); n.tags = j.getString("tags");
        n.created = j.getLong("created"); n.updated = j.getLong("updated"); n.clock = j.getLong("clock");
        n.todo = j.getBoolean("todo"); n.done = j.getBoolean("done"); n.deleted = j.getBoolean("deleted");
        n.validate(); return n;
    }
    public void validate() {
        if (title.length() > 1000 || body.length() > 200000 || category.length() > 1000 || tags.length() > 4000
                || clock < 1 || clock >= Long.MAX_VALUE - 1 || created < 0 || updated < 0)
            throw new IllegalArgumentException("Note exceeds limits (body: 200,000 characters)");
    }
    private static void validId(String id) {
        if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("Invalid note identifier");
    }
    public Note copy() throws Exception { return from(json()); }
    public static int compare(Note a, Note b) {
        int c = Long.compare(a.clock, b.clock);
        return c == 0 ? a.revision.compareTo(b.revision) : c;
    }
}
