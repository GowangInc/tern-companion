package io.github.gowanginc.terncompanion;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Groups the {@code tern ls --json} session list for display: sessions keep the
 * order Tern returns, live blocks stay under their session, and Flow/background
 * sessions are only classified here — nothing is ever stopped, killed or removed.
 */
public final class SessionGroups {

    private SessionGroups() { }

    /** One live block inside a session. */
    public static final class Block {
        public final String session;
        public final String blockId;
        public final String label;
        public final String program;
        public final String cwd;
        public final String tab;
        public final boolean focused;

        Block(String session, String blockId, String label, String program, String cwd, String tab, boolean focused) {
            this.session = session;
            this.blockId = blockId;
            this.label = label;
            this.program = program;
            this.cwd = cwd;
            this.tab = tab;
            this.focused = focused;
        }
    }

    /** One session and its live blocks. */
    public static final class Group {
        public final String name;
        public final boolean background;
        public final List<Block> blocks;
        public final int sourceIndex;

        Group(String name, boolean background, List<Block> blocks, int sourceIndex) {
            this.name = name;
            this.background = background;
            this.blocks = Collections.unmodifiableList(blocks);
            this.sourceIndex = sourceIndex;
        }

        public int liveCount() {
            return blocks.size();
        }

        /** The focused block, else the first live block, else null. */
        public Block current() {
            for (Block block : blocks) if (block.focused) return block;
            return blocks.isEmpty() ? null : blocks.get(0);
        }

        /** The current block's label, else the session name. */
        public String currentLabel() {
            Block block = current();
            return block == null ? name : block.label;
        }
    }

    /** A whole-word Flow prefix: {@code Flow - demo}, {@code flow: build}, {@code flow-demo}. */
    private static final Pattern FLOW_PREFIX =
            Pattern.compile("^flow(?:\\s*[-\\u2013\\u2014:\\u00b7]\\s*)\\S");

    /** Groups sessions in source order; malformed entries are skipped, never thrown on. */
    public static List<Group> group(JSONArray sessions) {
        List<Group> groups = new ArrayList<>();
        if (sessions == null) return groups;
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject session = sessions.optJSONObject(i);
            if (session == null) continue;
            String name = string(session, "name");
            if (name.isEmpty()) name = "session";
            List<Block> blocks = new ArrayList<>();
            JSONArray tabs = session.optJSONArray("tabs");
            if (tabs != null) {
                for (int t = 0; t < tabs.length(); t++) {
                    JSONObject tab = tabs.optJSONObject(t);
                    if (tab == null) continue;
                    String tabName = string(tab, "name");
                    JSONArray entries = tab.optJSONArray("blocks");
                    if (entries == null) continue;
                    for (int b = 0; b < entries.length(); b++) {
                        JSONObject block = entries.optJSONObject(b);
                        if (block == null || !block.optBoolean("live", false)) continue;
                        String blockId = String.valueOf(block.optLong("id"));
                        String program = string(block, "program");
                        String title = string(block, "title");
                        String label = title.isEmpty() ? shortProgram(program) : title;
                        if (label.isEmpty()) label = "block " + blockId;
                        boolean focused = block.optBoolean("focused", false)
                                || block.optBoolean("current", false)
                                || block.optBoolean("active", false);
                        blocks.add(new Block(name, blockId, label, program, string(block, "cwd"), tabName, focused));
                    }
                }
            }
            groups.add(new Group(name, isBackground(session), blocks, i));
        }
        return groups;
    }

    /** Sessions that are shown in the main list. */
    public static List<Group> normal(List<Group> groups) {
        return filtered(groups, false);
    }

    /** Flow/background sessions, hidden unless the caller reveals them. */
    public static List<Group> background(List<Group> groups) {
        return filtered(groups, true);
    }

    private static List<Group> filtered(List<Group> groups, boolean background) {
        List<Group> out = new ArrayList<>();
        if (groups == null) return out;
        for (Group group : groups) if (group != null && group.background == background) out.add(group);
        return out;
    }

    /** Explicit flow/background flags on the session, else the conservative name rule. */
    public static boolean isBackground(JSONObject session) {
        if (session == null) return false;
        if (session.optBoolean("flow", false) || session.optBoolean("background", false)
                || session.optBoolean("hidden", false)) return true;
        return isBackgroundName(string(session, "name"));
    }

    /**
     * True only for names that are the bare word Flow or start with Flow as a
     * whole word followed by a separator. Names such as airflow, workflow,
     * flowchart, flowable or flow_metrics are ordinary projects and stay visible.
     */
    public static boolean isBackgroundName(String name) {
        if (name == null) return false;
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return false;
        return normalized.equals("flow") || FLOW_PREFIX.matcher(normalized).find();
    }

    private static String shortProgram(String program) {
        int slash = program.lastIndexOf('/');
        return slash >= 0 ? program.substring(slash + 1) : program;
    }

    /** optString turns a JSON null into the text "null"; treat it as absent. */
    private static String string(JSONObject object, String key) {
        return object.isNull(key) ? "" : object.optString(key, "");
    }
}
