# Watch Notes implementation

The two manifests select watch-required vs watch-optional hardware and different launcher labels. Both launch `dev.watchnotes.NotesActivity`. No watch-csv classes, data paths, package names or build scripts are used at runtime or required at build time.

## Components

- `Note`: validated version-1 JSON record, note/revision UUIDs, parent reference, logical clock, text, category, tags, timestamps, task flags and tombstone flag.
- `NotesStore`: SQLite revision history, deterministic current-version query, transactional imports, durable outbox markers, consistent `VACUUM INTO` backups and validated read-only backup import.
- `NotesActivity`: programmatic watch/phone UI, worker-executor I/O, draft persistence, document picker imports/exports and swipe actions.
- `NotesSync`: Wear Data Layer assets, bounded payload reads, history publication/reconciliation and watch-database snapshots.
- `NotesListener`: manifest-registered `WearableListenerService`; saves incoming records on its callback worker thread. Errors are persisted for display/retry through Sync now.
- `MarkdownNotes`: portable Joplin Markdown/front-matter subset, ZIP interchange and resource bounds. It never extracts archive paths onto the filesystem.
- `ShareNotes`: handles Joplin Android's optional leading 32-hex note ID, following title/body `ACTION_SEND` plain text, optional subject, and Markdown/front-matter text.
- `MarkdownPreview`: CommonMark Java 0.27.1 renderer with Joplin's table/task/strikethrough/autolink/footnote extensions, sanitized via jsoup 1.18.3 before WebView display; supports safe HTTPS/inline images and public HTTPS `srcset` fallback for inaccessible Joplin resource IDs. WebView runs with JavaScript, file and content access disabled; image display is a per-device preference in the long-press menu. KaTeX, Mermaid, ABC and other plugin-specific renderers display their original fenced source rather than running JavaScript.
- `NativePreview`: watch-only Android `TextView`/`Html.fromHtml` adapter for sanitized Markdown. WebView is only constructed on phones: Wear OS may lack a usable WebView provider, and all three reported watch crash actions previously constructed one. The watch adapter flattens tables, downloads at most four small HTTPS image alternatives on the activity worker (bounded request timeouts, disabled redirects, 256 KB response limit) or decodes bounded inline images, then downscales bitmaps before creating drawables. The image-off path makes no network requests. The native renderer has a Markdown-source fallback if Android HTML spans cannot render a note.

## Storage

`databases/notes.db` schema version 1:

```sql
CREATE TABLE revisions (
    revision TEXT PRIMARY KEY,
    note_id TEXT NOT NULL,
    clock INTEGER NOT NULL,
    payload TEXT NOT NULL,
    sent INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX note_history ON revisions(note_id, clock);
```

The maximum `(clock, revision)` per `note_id` is current. SQLite UUID text ordering matches Java comparison because IDs are canonical lowercase ASCII. Wall time controls library display order, not conflict convergence. Saving any historical/draft revision advances beyond the largest locally observed clock for that note, with a new UUID. The parent points at the edited revision; for a brand-new unsaved draft it is a non-persisted UUID. All observed revisions remain available.

Database access and snapshot replacement are serialized by a process-wide lock. Imports have a single transaction, so validation/write failures roll back the batch. Live database connections use the platform default rollback journal; backups are standalone SQLite files, not copies of a potentially active main database file. `VACUUM INTO` is available on the minimum supported Android 11 SQLite version.

The editor draft is stored in `SharedPreferences("draft")` when pausing/saving activity state. The last listener/upload error is in `notes-status`. Incoming watch database backups are written to a temporary file, checked for the SQLite header, then atomically replaced at `files/watch-notes.db`. This file never overwrites the phone's own `databases/notes.db`.

## Transport

Immutable note revision assets use `/watchnotes/v1/revision/<revision-uuid>`. The DataMap field `payload` is a UTF-8 JSON Asset, capped at 1.5 MB on receipt. Note fields impose smaller character limits. Duplicate revision delivery is ignored by SQLite primary key. Records received from the peer are marked sent; locally created ones stay pending until Data Layer accepts their publication. An explicit full sync re-publishes history to repair missing peer data.

Database assets use `/watchnotes/v1/database/<watch-node-id>` with a `payload` Asset and a changing timestamp. Only phone installations save these snapshots. A queued asset is not an application-level peer receipt. Disconnected reception is delegated to Google Play services; ordinary API waits are bounded at 30 seconds, snapshot publication at 60 seconds. There is no custom Bluetooth stack, cloud server, permanent deletion, or history pruning.

## Interchange and tests

Markdown interchange follows Joplin's documented YAML-front-matter subset. The parser handles quoted scalars, block tags and simple flow tag lists; it is not a general YAML parser. Unsupported metadata and attachments are not modeled. ZIP exports exclude tombstones. SQLite backups retain identities, history and tombstones and can be merged back without duplication.

The phone activity accepts Android `ACTION_SEND` text/Markdown/image and `ACTION_SEND_MULTIPLE` images. It reads granted streams on its worker and opens an editable draft; no import is committed until Save. Supported shared images (up to 120 KB each, within the note body limit) become self-contained Markdown data URIs that also sync to the watch. ZIP imports optionally replace Joplin `:/<32-hex-ID>` image references with inline data when matching PNG/JPEG/GIF/WebP resource files are present. Android's text-only share does not contain Joplin's private image bytes; unknown refs are retained and shown as unavailable. Sending a note uses `ACTION_SEND text/plain` with subject and body so Joplin Android appears in the Android chooser.

`test.sh` runs portable model/interchange tests. `build-tests.sh` builds Android instrumentation for real SQLite behavior. Production code uses platform Android views/SQLite/JSON plus Google Wearable 19.0.0, with no runtime dependency on the test JSON JAR.
