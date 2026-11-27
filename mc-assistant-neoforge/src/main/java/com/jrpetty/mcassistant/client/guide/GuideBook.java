package com.jrpetty.mcassistant.client.guide;

import com.jrpetty.mcassistant.village.ThemeJson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * [guide31] The Guide's whole book: its categories, in the order the browse page shows them, and every article, read from
 * assets/mc_assistant/guide (index.json names the categories and the files; each file holds a category's articles). The
 * reader is handed in, so the game reads the files through its resources and the unit tests straight off the disk; a
 * file that is not JSON, or an article with no id, title or answer, is refused with its name, never skipped silently.
 */
public final class GuideBook {

    /** Where the book lives, under the resources. */
    public static final String ROOT = "assets/mc_assistant/guide/";

    /** A category: its id, its name on the browse page, its icon (an item id) and a line saying what is in it. */
    public record Category(String id, String title, String icon, String blurb) {}

    private final List<Category> categories;
    private final List<GuideEntry> entries;
    private final Map<String, GuideEntry> byId;

    private GuideBook(List<Category> categories, List<GuideEntry> entries) {
        this.categories = List.copyOf(categories);
        this.entries = List.copyOf(entries);
        Map<String, GuideEntry> m = new LinkedHashMap<>();
        for (GuideEntry e : entries) {
            if (m.put(e.id(), e) != null) throw new IllegalArgumentException("guide: two articles called " + e.id());
        }
        this.byId = Collections.unmodifiableMap(m);
    }

    public List<Category> categories() {
        return categories;
    }

    public List<GuideEntry> entries() {
        return entries;
    }

    /** An article by its id, or null. */
    public GuideEntry get(String id) {
        return id == null ? null : byId.get(id);
    }

    /** A category by its id, or null. */
    public Category category(String id) {
        for (Category c : categories) if (c.id().equals(id)) return c;
        return null;
    }

    /** The articles of a category, in their order in the files. */
    public List<GuideEntry> in(String category) {
        List<GuideEntry> out = new ArrayList<>();
        for (GuideEntry e : entries) if (e.category().equals(category)) out.add(e);
        return out;
    }

    /** The book, its files read by {@code read} (a path under {@link #ROOT}, e.g. "index.json"; null if missing). */
    @SuppressWarnings("unchecked")
    public static GuideBook read(Function<String, String> read) {
        String indexText = read.apply("index.json");
        if (indexText == null) throw new IllegalArgumentException("guide: no index.json");
        Map<String, Object> index = (Map<String, Object>) ThemeJson.parse(indexText);
        List<Category> cats = new ArrayList<>();
        for (Object o : list(index.get("categories"))) {
            Map<String, Object> c = (Map<String, Object>) o;
            cats.add(new Category(str(c, "id"), str(c, "title"), str(c, "icon"), str(c, "blurb")));
        }
        List<GuideEntry> all = new ArrayList<>();
        for (Object f : list(index.get("files"))) {
            String file = String.valueOf(f);
            String text = read.apply(file);
            if (text == null) throw new IllegalArgumentException("guide: " + file + " is missing");
            Object parsed;
            try {
                parsed = ThemeJson.parse(text);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("guide: " + file + ": " + e.getMessage(), e);
            }
            Map<String, Object> m = (Map<String, Object>) parsed;
            String cat = str(m, "category");
            for (Object o : list(m.get("entries"))) all.add(entry((Map<String, Object>) o, cat, file));
        }
        return new GuideBook(cats, all);
    }

    private static GuideEntry entry(Map<String, Object> m, String fileCategory, String file) {
        String id = str(m, "id");
        String title = str(m, "title");
        String answer = str(m, "answer");
        if (id.isEmpty() || title.isEmpty() || answer.isEmpty()) {
            throw new IllegalArgumentException("guide: " + file + ": an article needs an id, a title and an answer (" + id + ")");
        }
        String cat = str(m, "category");
        String kind = str(m, "kind");
        return new GuideEntry(id, title, cat.isEmpty() ? fileCategory : cat, str(m, "icon"), kind.isEmpty() ? GuideEntry.WHAT : kind,
            strs(m.get("q")), strs(m.get("kw")), answer, strs(m.get("more")), strs(m.get("steps")), strs(m.get("controls")), strs(m.get("items")),
            strs(m.get("tips")), strs(m.get("warn")), strs(m.get("related")), strs(m.get("covers")),
            Boolean.TRUE.equals(m.get("op")), str(m, "notown"));
    }

    private static List<Object> list(Object o) {
        return o instanceof List<?> l ? new ArrayList<>(l) : List.of();
    }

    private static List<String> strs(Object o) {
        List<String> out = new ArrayList<>();
        for (Object x : list(o)) out.add(String.valueOf(x));
        return List.copyOf(out);
    }

    private static String str(Map<String, Object> m, String k) {
        Object o = m.get(k);
        return o == null ? "" : String.valueOf(o);
    }
}
