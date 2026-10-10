package io.github.gowanginc.terncompanion;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/**
 * Trust-boundary checks for {@link SessionGroups} against synthetic {@code tern ls --json}
 * payloads: grouping, live-block filtering, ordering, focus labels, and the conservative
 * Flow/background classification.
 *
 * Run on the JVM with org.json on the classpath together with the app sources, or on device
 * with app_process and the built APK on CLASSPATH (as checks/QrCheck.java does).
 */
public final class SessionCheck {

    public static void main(String[] args) throws Exception {
        names();
        grouping();
        malformed();
        flags();
        System.out.println("Session grouping checks passed.");
    }

    /** Flow as a whole word is background; flow as a substring of a real name is not. */
    private static void names() {
        expectBackground("Flow", true);
        expectBackground("FLOW", true);
        expectBackground("  flow  ", true);
        expectBackground("Flow - demo", true);
        expectBackground("flow: build", true);
        expectBackground("flow-demo", true);
        expectBackground("flow – em dash", true);
        expectBackground("flow · flow title", true);

        expectBackground("airflow", false);
        expectBackground("workflow", false);
        expectBackground("flowchart", false);
        expectBackground("flowable", false);
        expectBackground("flow_metrics", false);
        expectBackground("Flowmetrics", false);
        expectBackground("my flow", false);
        expectBackground(" flowchart - x", false);
        expectBackground("flow-", false);
        expectBackground("flow ", true);
        expectBackground("devflow", false);
        expectBackground("", false);
        expectBackground(null, false);
    }

    private static void grouping() throws JSONException {
        JSONArray alphaTabs = new JSONArray();
        alphaTabs.put(tab(null,
                live(11, "Build", "/bin/zsh", "/srv/alpha", false),
                live(12, null, "/bin/zsh", "/srv/alpha", true),
                dead(13)));
        alphaTabs.put(tab("logs", live(21, null, "/usr/bin/tail", "/srv/alpha/logs", false)));

        JSONArray sessions = new JSONArray();
        sessions.put(session("alpha", alphaTabs));
        sessions.put(session("beta", tabs(tab(null, live(31, "Server", "/usr/bin/python3", "/srv/beta", false)))));
        sessions.put(session("Flow - deploy", tabs(tab(null, live(41, "Deploy", "/bin/sh", "/srv/deploy", false)))));
        sessions.put(session("airflow", tabs(tab(null, live(51, "Worker", "/bin/sh", "/srv/airflow", false)))));
        sessions.put(session("workflow", tabs(tab(null, live(61, "Runner", "/bin/sh", "/srv/workflow", false)))));
        sessions.put(session("empty-tab", tabs(tab(null, dead(71)))));

        List<SessionGroups.Group> groups = SessionGroups.group(sessions);
        check(groups.size() == 6, "session count");
        for (int i = 0; i < groups.size(); i++) check(groups.get(i).sourceIndex == i, "source order preserved");

        SessionGroups.Group alpha = groups.get(0);
        check(alpha.liveCount() == 3, "non-live block included or live block dropped: " + alpha.liveCount());
        check(ids(alpha.blocks).equals("[11, 12, 21]"), "live blocks filtered or reordered: " + ids(alpha.blocks));
        check(alpha.blocks.get(0).label.equals("Build"), "title label");
        check(alpha.blocks.get(1).label.equals("zsh"), "program fallback label");
        check(alpha.blocks.get(2).label.equals("tail"), "second tab block label");
        check(alpha.blocks.get(0).tab.isEmpty(), "JSON null tab name not treated as text");
        check(alpha.blocks.get(2).tab.equals("logs"), "tab name carried to its block");
        check(alpha.blocks.get(2).cwd.equals("/srv/alpha/logs"), "block cwd");
        check(alpha.blocks.get(1).focused && !alpha.blocks.get(0).focused, "focused block flag");
        check(alpha.current().blockId.equals("12") && alpha.currentLabel().equals("zsh"), "focused block is current");

        SessionGroups.Group beta = groups.get(1);
        check(beta.blocks.get(0).label.equals("Server") && beta.currentLabel().equals("Server"), "single-block session");
        check(alpha.blocks.get(0).session.equals("alpha") && beta.blocks.get(0).session.equals("beta"),
                "block knows its session");

        SessionGroups.Group empty = groups.get(5);
        check(empty.liveCount() == 0 && empty.blocks.isEmpty(), "session without live blocks");
        check(empty.current() == null && empty.currentLabel().equals("empty-tab"), "empty session current label");

        List<SessionGroups.Group> normal = SessionGroups.normal(groups);
        List<SessionGroups.Group> background = SessionGroups.background(groups);
        check(normal.size() == 5 && background.size() == 1, "normal/background partition");
        check(background.get(0).name.equals("Flow - deploy"), "generated flow session hidden");
        check(contains(normal, "airflow") && contains(normal, "workflow"), "flow substring names stay visible");
        check(background.get(0).blocks.get(0).session.equals("Flow - deploy"), "background blocks grouped");
        for (int i = 1; i < normal.size(); i++)
            check(normal.get(i).sourceIndex > normal.get(i - 1).sourceIndex, "normal order preserved");
        check(normal.size() + background.size() == groups.size(), "no group lost or duplicated");
    }

    /** Malformed payloads are skipped without shifting the remaining source indexes. */
    private static void malformed() throws JSONException {
        JSONArray sessions = new JSONArray();
        sessions.put(JSONObject.NULL);
        sessions.put(new JSONObject().put("name", "no-tabs"));
        sessions.put(session("blank-tab", tabs(new JSONObject())));
        sessions.put(new JSONObject().put("name", "Flow"));
        sessions.put(new JSONObject().put("tabs", tabs()));

        List<SessionGroups.Group> groups = SessionGroups.group(sessions);
        check(groups.size() == 4, "malformed entries skipped");
        check(groups.get(0).sourceIndex == 1 && groups.get(3).sourceIndex == 4, "source indexes not renumbered");
        check(groups.get(0).liveCount() == 0 && groups.get(1).liveCount() == 0, "missing tabs yield no blocks");
        check(groups.get(2).background, "bare Flow session hidden");
        check(groups.get(3).name.equals("session"), "nameless session fallback");
        check(SessionGroups.group(new JSONArray()).isEmpty(), "empty payload");
        check(SessionGroups.group(null).isEmpty(), "null payload");
        check(SessionGroups.normal(null).isEmpty() && SessionGroups.background(null).isEmpty(), "null partitions");
    }

    /** Explicit session flags classify without touching legitimate project names. */
    private static void flags() throws JSONException {
        JSONObject hidden = new JSONObject().put("name", "Workflow Runner").put("hidden", true);
        check(SessionGroups.isBackground(hidden), "hidden flag");
        check(!SessionGroups.isBackgroundName("Workflow Runner"), "flag does not come from the name");
        check(SessionGroups.isBackground(new JSONObject().put("name", "plain").put("flow", true)), "flow flag");
        check(SessionGroups.isBackground(new JSONObject().put("name", "plain").put("background", true)), "background flag");
        check(!SessionGroups.isBackground(new JSONObject().put("name", "workflow")), "plain project stays visible");
        check(!SessionGroups.isBackground(null), "null session");
    }

    private static JSONObject live(long id, String title, String program, String cwd, boolean focused) throws JSONException {
        return new JSONObject().put("id", id).put("title", title == null ? JSONObject.NULL : title)
                .put("program", program).put("cwd", cwd).put("live", true).put("focused", focused);
    }

    private static JSONObject dead(long id) throws JSONException {
        return new JSONObject().put("id", id).put("live", false);
    }

    private static JSONArray tabs(Object... entries) {
        JSONArray array = new JSONArray();
        for (Object entry : entries) array.put(entry);
        return array;
    }

    private static JSONObject session(String name, JSONArray sessionTabs) throws JSONException {
        return new JSONObject().put("name", name).put("tabs", sessionTabs);
    }

    private static JSONObject tab(String name, Object... blocks) throws JSONException {
        return new JSONObject().put("name", name == null ? JSONObject.NULL : name).put("blocks", tabs(blocks));
    }

    private static String ids(List<SessionGroups.Block> blocks) {
        List<String> out = new ArrayList<>();
        for (SessionGroups.Block block : blocks) out.add(block.blockId);
        return out.toString();
    }

    private static boolean contains(List<SessionGroups.Group> groups, String name) {
        for (SessionGroups.Group group : groups) if (group.name.equals(name)) return true;
        return false;
    }

    private static void expectBackground(String name, boolean expected) {
        check(SessionGroups.isBackgroundName(name) == expected, "name classification: " + name);
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("Session grouping changed: " + what);
    }
}
