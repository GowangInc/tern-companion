package io.github.gowanginc.terncompanion;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Groups the {@code tern ls --json} session list for display: sessions keep the
 * order Tern returns, live blocks stay under their session, and Flow/background
 * sessions are only classified here — nothing is ever stopped, killed or removed.
 * Hiding is a phone-only view filter: {@link #visible} drops hidden sessions and
 * blocks from the list this phone draws, and the PC is never contacted.
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
        /** Stable identity: Tern's session id when it sends one, else the name. */
        public final String id;
        public final String name;
        public final boolean background;
        public final List<Block> blocks;
        /** Live blocks this phone hides inside this session; never sent anywhere. */
        public final int hiddenBlocks;
        public final int sourceIndex;

        Group(String id, String name, boolean background, List<Block> blocks, int hiddenBlocks, int sourceIndex) {
            this.id = id;
            this.name = name;
            this.background = background;
            this.blocks = Collections.unmodifiableList(blocks);
            this.hiddenBlocks = hiddenBlocks;
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
            String id = string(session, "id");
            if (id.isEmpty()) id = name;
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
            groups.add(new Group(id, name, isBackground(session), blocks, 0, i));
        }
        return groups;
    }

    /** Preferences key holding one PC's hidden session ids. */
    private static final String HIDDEN_SESSIONS_PREFIX = "hidden_sessions|";
    /** Preferences key holding one PC's hidden block keys. */
    private static final String HIDDEN_BLOCKS_PREFIX = "hidden_blocks|";

    /**
     * The {@code tern_ui} key for one PC's hidden sessions. Every key carries the
     * saved base URL, so hiding on one PC never leaks to another.
     */
    public static String hiddenSessionsKey(String baseUrl) {
        return HIDDEN_SESSIONS_PREFIX + (baseUrl == null ? "" : baseUrl);
    }

    /** The {@code tern_ui} key for one PC's hidden blocks; same per-PC scoping. */
    public static String hiddenBlocksKey(String baseUrl) {
        return HIDDEN_BLOCKS_PREFIX + (baseUrl == null ? "" : baseUrl);
    }

    /** One block's stable hide key: the same entry across refreshes and reconnects. */
    public static String blockKey(String sessionId, String blockId) {
        return sessionId + "/" + blockId;
    }

    /** Sessions that are shown in the main list. */
    public static List<Group> normal(List<Group> groups) {
        return visible(groups, false, Collections.emptySet(), Collections.emptySet());
    }

    /** Flow/background sessions, hidden unless the caller reveals them. */
    public static List<Group> background(List<Group> groups) {
        return visible(groups, true, Collections.emptySet(), Collections.emptySet());
    }

    /**
     * One list (foreground or background) as this phone draws it: a hidden session
     * disappears whole and a hidden block is dropped from its session and counted
     * in {@link Group#hiddenBlocks}. Pure — the inputs are never modified, order is
     * kept, and nothing is stopped or removed on the PC.
     */
    public static List<Group> visible(List<Group> groups, boolean background,
                                      Set<String> hiddenSessions, Set<String> hiddenBlocks) {
        List<Group> out = new ArrayList<>();
        if (groups == null) return out;
        for (Group group : groups) {
            if (group == null || group.background != background) continue;
            if (hiddenSessions != null && hiddenSessions.contains(group.id)) continue;
            if (hiddenBlocks == null || hiddenBlocks.isEmpty()) {
                out.add(group);
                continue;
            }
            List<Block> blocks = new ArrayList<>();
            for (Block block : group.blocks) {
                if (hiddenBlocks != null && hiddenBlocks.contains(blockKey(group.id, block.blockId))) continue;
                blocks.add(block);
            }
            out.add(new Group(group.id, group.name, group.background, blocks,
                    group.hiddenBlocks + group.blocks.size() - blocks.size(), group.sourceIndex));
        }
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
