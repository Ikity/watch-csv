package dev.watchcsv.viewer;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.json.*;

public final class ViewSpecTest {
    private static int assertions;
    private static void eq(Object expected, Object actual) {
        assertions++; if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    private static void order(ViewSpec v, int... expected) { eq(Arrays.toString(expected), Arrays.toString(v.order)); }
    private static void rejectsOrder(JSONObject base, Object value) throws Exception {
        JSONObject bad = new JSONObject(base.toString()); bad.put("order", value);
        boolean rejected = false;
        try { ViewSpec.from(bad, 4); } catch (JSONException expected) { rejected = true; }
        eq(true, rejected);
    }
    // Traverse cells exactly in display order; the production matcher must use rank,
    // not immutable source-column IDs, when comparing the previous occurrence.
    private static TextSearch.Hit searchRow(ViewSpec v, String[] cells, TextSearch.Hit after, boolean forward) throws Exception {
        Pattern needle = TextSearch.literal("cat"); AtomicBoolean token = new AtomicBoolean();
        for (int step = 0; step < v.order.length; step++) {
            int rank = forward ? step : v.order.length - 1 - step;
            int column = v.order[rank]; if (!v.visible[column]) continue;
            TextSearch.Hit hit = TextSearch.inCell(needle, cells[column], 0, column, after, forward, token, rank, after == null ? -1 : v.positionOf(after.column));
            if (hit != null) return hit;
        }
        return null;
    }
    public static void main(String[] args) throws Exception {
        ViewSpec v = new ViewSpec(4); order(v, 0, 1, 2, 3);
        v.filters[2] = "^ABC"; v.regex[2] = true; v.sort = 2; v.descending = true; v.visible[1] = false;
        v.moveColumn(3, 0); order(v, 3, 0, 1, 2);
        v.moveColumn(0, 3); order(v, 3, 1, 2, 0);
        v.moveColumn(2, 1); order(v, 3, 2, 1, 0);
        v.moveColumn(2, 1); order(v, 3, 2, 1, 0);
        eq("^ABC", v.filters[2]); eq(true, v.regex[2]); eq(2, v.sort); eq(true, v.descending); eq(false, v.visible[1]);
        eq(1, v.positionOf(2)); eq(-1, v.positionOf(19));
        ViewSpec copy = v.copy(); copy.moveColumn(0, 0); copy.filters[2] = "different"; copy.visible[1] = true;
        order(v, 3, 2, 1, 0); eq("^ABC", v.filters[2]); eq(false, v.visible[1]);
        ViewSpec restored = ViewSpec.from(new JSONObject(v.json().toString()), 4);
        order(restored, 3, 2, 1, 0); eq("^ABC", restored.filters[2]); eq(true, restored.regex[2]);
        eq(2, restored.sort); eq(true, restored.descending); eq(false, restored.visible[1]);
        JSONObject legacy = v.json(); legacy.remove("order");
        ViewSpec old = ViewSpec.from(new JSONObject(legacy.toString()), 4);
        order(old, 0, 1, 2, 3); eq("^ABC", old.filters[2]); eq(false, old.visible[1]); eq(2, old.sort);
        rejectsOrder(v.json(), new JSONArray("[0,1,2]"));
        rejectsOrder(v.json(), new JSONArray("[0,1,2,2]"));
        rejectsOrder(v.json(), new JSONArray("[-1,1,2,3]"));
        rejectsOrder(v.json(), new JSONArray("[0,1,2,4]"));
        rejectsOrder(v.json(), "not an array"); rejectsOrder(v.json(), JSONObject.NULL);
        for (int[] move : new int[][]{{-1, 0}, {4, 0}, {0, -1}, {0, 4}}) {
            boolean rejected = false;
            try { v.moveColumn(move[0], move[1]); } catch (IllegalArgumentException expected) { rejected = true; }
            eq(true, rejected); order(v, 3, 2, 1, 0);
        }
        v.resetOrder(); order(v, 0, 1, 2, 3); eq(false, v.visible[1]); eq("^ABC", v.filters[2]); eq(2, v.sort);

        // Reordered traversal must cross columns whose source IDs move backwards,
        // include repeated occurrences, and skip hidden columns in both directions.
        ViewSpec search = new ViewSpec(4); search.moveColumn(2, 0); search.visible[1] = false;
        String[] cells = {"cat cat", "cat hidden", "CAT", "cat last"};
        TextSearch.Hit a = searchRow(search, cells, null, true); eq(2, a.column); eq(0, a.start);
        TextSearch.Hit b = searchRow(search, cells, a, true); eq(0, b.column); eq(0, b.start);
        TextSearch.Hit c = searchRow(search, cells, b, true); eq(0, c.column); eq(4, c.start);
        TextSearch.Hit d = searchRow(search, cells, c, true); eq(3, d.column);
        eq(null, searchRow(search, cells, d, true));
        eq(4, searchRow(search, cells, d, false).start);
        eq(0, searchRow(search, cells, c, false).start);
        eq(2, searchRow(search, cells, b, false).column);
        eq(null, searchRow(search, cells, a, false));
        eq(3, searchRow(search, cells, null, false).column);
        // Check stable row priority after a permutation, not just within one row.
        TextSearch.Hit nextRow = TextSearch.inCell(TextSearch.literal("cat"), "cat", 1, 2, d, true, new AtomicBoolean(), 0, 3);
        eq(1L, nextRow.position); eq(2, nextRow.column);
        ViewSpec single = new ViewSpec(1); single.moveColumn(0, 0); order(single, 0);
        System.out.println("PASS: " + assertions + " column-order, preset migration, and ordered-search assertions.");
    }
}
