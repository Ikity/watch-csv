package dev.watchcsv.viewer;

import org.json.*;
import java.util.*;
import java.util.regex.*;

public final class ViewSpec {
    public boolean[] visible;
    public String[] filters;
    public boolean[] regex;
    /** Display positions mapped to immutable source-column IDs. */
    public int[] order;
    public int sort = -1;
    public boolean descending;

    public ViewSpec(int columns) {
        visible = new boolean[columns]; Arrays.fill(visible, true);
        filters = new String[columns]; Arrays.fill(filters, "");
        regex = new boolean[columns];
        order = new int[columns]; for (int i = 0; i < columns; i++) order[i] = i;
    }
    public ViewSpec copy() {
        ViewSpec v = new ViewSpec(visible.length);
        v.visible = visible.clone(); v.filters = filters.clone(); v.regex = regex.clone();
        v.order = order.clone();
        v.sort = sort; v.descending = descending; return v;
    }
    public int visibleCount() { int n = 0; for (boolean b : visible) if (b) n++; return n; }
    public int positionOf(int column) {
        for (int i = 0; i < order.length; i++) if (order[i] == column) return i;
        return -1;
    }
    public void moveColumn(int column, int destination) {
        validateOrder();
        int from = positionOf(column);
        if (from < 0 || destination < 0 || destination >= order.length) throw new IllegalArgumentException("Invalid column position");
        if (from < destination) System.arraycopy(order, from + 1, order, from, destination - from);
        else if (from > destination) System.arraycopy(order, destination, order, destination + 1, from - destination);
        order[destination] = column;
    }
    public void resetOrder() { for (int i = 0; i < order.length; i++) order[i] = i; }
    public void validateOrder() {
        if (order == null || order.length != visible.length) throw new IllegalArgumentException("Column order does not match this file");
        boolean[] seen = new boolean[visible.length];
        for (int column : order) {
            if (column < 0 || column >= seen.length || seen[column]) throw new IllegalArgumentException("Column order must contain each column exactly once");
            seen[column] = true;
        }
    }
    public Pattern[] patterns() {
        Pattern[] p = new Pattern[filters.length];
        for (int i = 0; i < filters.length; i++) if (!filters[i].isEmpty())
            p[i] = Pattern.compile(regex[i] ? filters[i] : Pattern.quote(filters[i]), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        return p;
    }
    public JSONObject json() throws JSONException {
        JSONObject o = new JSONObject();
        validateOrder();
        JSONArray a = new JSONArray(), f = new JSONArray(), r = new JSONArray(), positions = new JSONArray();
        for (int i = 0; i < visible.length; i++) { a.put(visible[i]); f.put(filters[i]); r.put(regex[i]); positions.put(order[i]); }
        return o.put("visible", a).put("filters", f).put("regex", r).put("sort", sort).put("descending", descending).put("order", positions);
    }
    public static ViewSpec from(JSONObject o, int columns) throws JSONException {
        return parse(o, columns, true);
    }
    /** Phone drafts may temporarily have no selected columns or an unfinished regex. */
    public static ViewSpec fromDraft(JSONObject o, int columns) throws JSONException {
        return parse(o, columns, false);
    }
    private static ViewSpec parse(JSONObject o, int columns, boolean applicable) throws JSONException {
        ViewSpec v = new ViewSpec(columns);
        JSONArray a = o.getJSONArray("visible"), f = o.getJSONArray("filters"), r = o.getJSONArray("regex");
        if (a.length() != columns || f.length() != columns || r.length() != columns) throw new JSONException("Preset columns do not match this file");
        for (int i = 0; i < columns; i++) { v.visible[i] = a.getBoolean(i); v.filters[i] = f.getString(i); v.regex[i] = r.getBoolean(i); }
        // Presets written by versions 1.0/1.1 have no order and retain file order.
        if (o.has("order")) {
            JSONArray positions = o.getJSONArray("order");
            if (positions.length() != columns) throw new JSONException("Preset column order does not match this file");
            for (int i = 0; i < columns; i++) v.order[i] = positions.getInt(i);
            try { v.validateOrder(); } catch (IllegalArgumentException e) { throw new JSONException(e.getMessage()); }
        }
        v.sort = o.optInt("sort", -1); v.descending = o.optBoolean("descending", false);
        if (v.sort < -1 || v.sort >= columns || (applicable && v.visibleCount() == 0)) throw new JSONException("Invalid view settings");
        if (applicable) v.patterns(); return v;
    }
}
