package io.github.gowanginc.terncompanion;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Trust-boundary checks for {@link SessionGroups} against synthetic {@code tern ls --json}
 * payloads: grouping, live-block filtering, ordering, focus labels, the conservative
 * Flow/background classification, and the phone-only hidden session/agent filter.
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
        hidden();
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

    /**
     * Phone-only hiding: one agent, one whole session, per-PC keys, and nothing
     * else moves. Hiding is a pure view filter — the payload is never rewritten.
     */
    private static void hidden() throws JSONException {
        JSONArray defaultTabs = tabs(tab(null,
                live(11, "Build", "/bin/zsh", "/srv/alpha", false),
                live(12, null, "/bin/zsh", "/srv/alpha", true),
                live(13, "Tests", "/bin/zsh", "/srv/alpha", false)));

        JSONArray sessions = new JSONArray();
        sessions.put(new JSONObject().put("id", "sess-default").put("name", "Default").put("tabs", defaultTabs));
        sessions.put(session("Other", tabs(tab(null, live(21, "Server", "/usr/bin/python3", "/srv/other", false)))));
        sessions.put(session("Flow - deploy", tabs(tab(null, live(31, "Deploy", "/bin/sh", "/srv/deploy", false)))));
        sessions.put(new JSONObject().put("name", "null-id").put("id", JSONObject.NULL)
                .put("tabs", tabs(tab(null, live(41, "Shell", "/bin/sh", "/srv/x", false)))));

        List<SessionGroups.Group> groups = SessionGroups.group(sessions);
        check(groups.size() == 4, "hidden fixture sessions: " + groups.size());
        SessionGroups.Group def = groups.get(0);
        check(def.id.equals("sess-default"), "session id used when present");
        check(groups.get(1).id.equals("Other"), "session name is the id fallback");
        check(groups.get(3).id.equals("null-id"), "JSON null id falls back to the name");
        check(def.hiddenBlocks == 0 && SessionGroups.normal(groups).size() == 3, "nothing hidden until asked");

        Set<String> hiddenBlocks = new HashSet<>();
        hiddenBlocks.add(SessionGroups.blockKey(def.id, "12"));
        List<SessionGroups.Group> oneHidden = SessionGroups.visible(groups, false, Collections.emptySet(), hiddenBlocks);
        check(oneHidden.size() == 3, "hiding one agent removes no session: " + oneHidden.size());
        SessionGroups.Group scoped = oneHidden.get(0);
        check(scoped.liveCount() == 2 && scoped.hiddenBlocks == 1, "only the hidden agent is dropped");
        check(ids(scoped.blocks).equals("[11, 13]"), "sibling agents stay: " + ids(scoped.blocks));
        check(scoped.current().blockId.equals("11") && scoped.currentLabel().equals("Build"),
                "current falls back to the first visible agent");
        check(def.liveCount() == 3 && def.hiddenBlocks == 0, "filtering never mutates its input");

        Set<String> hiddenSessions = new HashSet<>();
        hiddenSessions.add("Other");
        List<SessionGroups.Group> withoutOther = SessionGroups.visible(groups, false, hiddenSessions, hiddenBlocks);
        check(withoutOther.size() == 2 && !contains(withoutOther, "Other"), "a hidden session leaves whole");
        check(contains(withoutOther, "Default"), "hiding a sibling session leaves Default");

        Set<String> everyBlock = new HashSet<>();
        everyBlock.add(SessionGroups.blockKey(def.id, "11"));
        everyBlock.add(SessionGroups.blockKey(def.id, "12"));
        everyBlock.add(SessionGroups.blockKey(def.id, "13"));
        SessionGroups.Group emptied = SessionGroups.visible(groups, false, Collections.emptySet(), everyBlock).get(0);
        check(emptied.liveCount() == 0 && emptied.hiddenBlocks == 3, "all agents hidden keeps the session for restore");
        check(emptied.current() == null && emptied.currentLabel().equals("Default"), "all hidden: the session name is the label");
        check(SessionGroups.visible(groups, false, hiddenSessions, everyBlock).size() == 2,
                "session and agent hides apply together");

        List<SessionGroups.Group> background = SessionGroups.visible(groups, true, Collections.emptySet(), Collections.emptySet());
        check(background.size() == 1 && background.get(0).name.equals("Flow - deploy"), "background filter still works");
        Set<String> hiddenFlow = new HashSet<>();
        hiddenFlow.add("Flow - deploy");
        check(SessionGroups.visible(groups, true, hiddenFlow, Collections.emptySet()).isEmpty(),
                "a hidden background session drops out of its own list");

        check(SessionGroups.visible(null, false, null, null).isEmpty(), "null payload");
        check(SessionGroups.visible(groups, false, null, null).size() == 3, "null hidden sets are tolerated");
        check(SessionGroups.normal(groups).size()
                == SessionGroups.visible(groups, false, Collections.emptySet(), Collections.emptySet()).size(),
                "normal() is visible() with nothing hidden");

        check(SessionGroups.blockKey(def.id, "12").equals("sess-default/12"), "block key is session-scoped and stable");
        check(!SessionGroups.blockKey(def.id, "12").equals(SessionGroups.blockKey("Other", "12")),
                "the same block id in two sessions stays distinct");
        check(SessionGroups.hiddenSessionsKey("https://pc-a:9260").equals(SessionGroups.hiddenSessionsKey("https://pc-a:9260")),
                "one PC keeps one hidden session set");
        check(!SessionGroups.hiddenSessionsKey("https://pc-a:9260").equals(SessionGroups.hiddenSessionsKey("https://pc-b:9260")),
                "hidden sessions never leak between saved PCs");
        check(!SessionGroups.hiddenBlocksKey("https://pc-a:9260").equals(SessionGroups.hiddenSessionsKey("https://pc-a:9260")),
                "hidden sessions and hidden agents use separate keys");
        check(!SessionGroups.hiddenBlocksKey("https://pc-a:9260").equals(SessionGroups.hiddenBlocksKey("https://pc-b:9260")),
                "hidden agents never leak between saved PCs");
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
