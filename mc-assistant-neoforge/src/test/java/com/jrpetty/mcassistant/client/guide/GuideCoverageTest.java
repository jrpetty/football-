package com.jrpetty.mcassistant.client.guide;

import com.jrpetty.mcassistant.entity.TalkTopic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [guide31] The Guide covers the whole mod: every item and block it registers, every key, every screen (the client's
 * *Screen classes), every kind of business and every talk topic is explained by at least one article, named in its
 * {@code covers} list (or its things, or its id); no article points at one that is not there; and nothing in the answers
 * tells a player to type a command (a server may ban them). Read off the source and the resources, as they are.
 */
class GuideCoverageTest {

    private static final String SRC = "src/main/java/com/jrpetty/mcassistant/";
    private static final String RES = "src/main/resources/";

    static Path project() {
        Path here = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path p : List.of(here, here.resolve("mc-assistant-neoforge"))) {
            if (Files.isDirectory(p.resolve(RES + GuideBook.ROOT))) return p;
        }
        throw new AssertionError("no guide under " + here);
    }

    static GuideBook book() {
        Path root = project().resolve(RES + GuideBook.ROOT);
        return GuideBook.read(name -> {
            Path f = root.resolve(name);
            try {
                return Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private static String read(String rel) {
        try {
            return Files.readString(project().resolve(rel), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> javaFiles() {
        try (Stream<Path> s = Files.walk(project().resolve(SRC))) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Everything the book says it covers: covers lists, things named, and ids. */
    private static Set<String> covered(GuideBook book) {
        Set<String> out = new TreeSet<>();
        for (GuideEntry e : book.entries()) {
            out.addAll(e.covers());
            for (String it : e.items()) if (it.startsWith("mc_assistant:")) out.add("item:" + it.substring("mc_assistant:".length()));
            out.add("id:" + e.id());
        }
        return out;
    }

    /** Is {@code want} ("item:rope") covered, by name or by a pattern ("item:*_paper_lantern")? */
    private static boolean covers(Set<String> covered, String want) {
        if (covered.contains(want)) return true;
        for (String c : covered) {
            if (!c.contains("*")) continue;
            String re = Pattern.quote(c).replace("*", "\\E.*\\Q");
            if (want.matches(re)) return true;
        }
        return false;
    }

    /** The mod's items and blocks: every item.mc_assistant.x / block.mc_assistant.x name, and every id registered by literal. */
    static Set<String> items() {
        Set<String> out = new TreeSet<>();
        Matcher m = Pattern.compile("\"(?:item|block)\\.mc_assistant\\.([a-z0-9_]+)\"\\s*:").matcher(read(RES + "assets/mc_assistant/lang/en_us.json"));
        while (m.find()) out.add(m.group(1));
        Pattern reg = Pattern.compile("(?:ITEMS|BLOCKS)\\.(?:register|registerItem|registerSimpleItem|registerBlock|registerSimpleBlock)\\(\\s*\"([a-z0-9_]+)\"");
        for (Path p : javaFiles()) {
            Matcher r = reg.matcher(read(project().relativize(p).toString()));
            while (r.find()) out.add(r.group(1));
        }
        return out;
    }

    static Set<String> keys() {
        Set<String> out = new TreeSet<>();
        Pattern k = Pattern.compile("new KeyMapping\\(\\s*\"key\\.mc_assistant\\.([a-z0-9_]+)\"");
        for (Path p : javaFiles()) {
            Matcher m = k.matcher(read(project().relativize(p).toString()));
            while (m.find()) out.add(m.group(1));
        }
        return out;
    }

    static Set<String> screens() {
        Set<String> out = new TreeSet<>();
        try (Stream<Path> s = Files.list(project().resolve(SRC + "client"))) {
            s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith("Screen.java")).forEach(n -> out.add(n.substring(0, n.length() - 5)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    static Set<String> businesses() {
        Set<String> out = new TreeSet<>();
        Matcher m = Pattern.compile("new BusinessType\\(\\s*\"([a-z]+)\"").matcher(read(SRC + "entity/BusinessTypes.java"));
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static void check(String what, Set<String> covered, Set<String> found, String prefix) {
        assertFalse(found.isEmpty(), "found no " + what + " to check: the reading of the source broke");
        List<String> missing = new ArrayList<>();
        for (String f : found) if (!covers(covered, prefix + f)) missing.add(prefix + f);
        assertTrue(missing.isEmpty(), what + " the Guide does not explain: " + missing);
    }

    @Test
    @DisplayName("Every item and block has an article")
    void everyItem() {
        check("items and blocks", covered(book()), items(), "item:");
    }

    @Test
    @DisplayName("Every key mapping has an article")
    void everyKey() {
        check("keys", covered(book()), keys(), "key:");
    }

    @Test
    @DisplayName("Every screen has an article")
    void everyScreen() {
        check("screens", covered(book()), screens(), "screen:");
    }

    @Test
    @DisplayName("Every kind of business has an article")
    void everyBusiness() {
        check("kinds of business", covered(book()), businesses(), "business:");
    }

    @Test
    @DisplayName("Every talk topic has an article")
    void everyTalkTopic() {
        Set<String> topics = new TreeSet<>();
        for (TalkTopic t : TalkTopic.values()) topics.add(t.name());
        check("talk topics", covered(book()), topics, "talk:");
    }

    @Test
    @DisplayName("No related id dangles, every category is real, every article has phrasings")
    void wellFormed() {
        GuideBook book = book();
        List<String> bad = new ArrayList<>();
        Set<String> cats = new TreeSet<>();
        for (GuideBook.Category c : book.categories()) cats.add(c.id());
        for (GuideEntry e : book.entries()) {
            for (String r : e.related()) if (book.get(r) == null) bad.add(e.id() + " -> related " + r);
            if (!cats.contains(e.category())) bad.add(e.id() + " in no category: " + e.category());
            if (e.questions().size() < 3) bad.add(e.id() + " has only " + e.questions().size() + " phrasings");
            if (e.related().contains(e.id())) bad.add(e.id() + " relates to itself");
        }
        for (String c : cats) if (book.in(c).isEmpty()) bad.add("category " + c + " is empty");
        assertTrue(bad.isEmpty(), "the book is not well formed: " + bad);
    }

    @Test
    @DisplayName("No answer tells a player to type a command")
    void noCommands() {
        List<String> bad = new ArrayList<>();
        Pattern cmd = Pattern.compile("(^|[\\s(\"'])/(village|assistant|gamerule|give|tp|op)\\b");
        for (GuideEntry e : book().entries()) {
            List<String> text = new ArrayList<>();
            text.add(e.answer());
            text.addAll(e.steps());
            text.addAll(e.controls());
            text.addAll(e.tips());
            text.addAll(e.warnings());
            for (String t : text) if (cmd.matcher(t).find()) bad.add(e.id() + ": " + t);
        }
        assertTrue(bad.isEmpty(), "answers that name a command: " + bad);
    }

    @Test
    @DisplayName("The book is big enough")
    void bigEnough() {
        GuideBook book = book();
        int phrasings = 0;
        for (GuideEntry e : book.entries()) phrasings += e.questions().size();
        System.out.println("[guide31] " + book.entries().size() + " articles in " + book.categories().size() + " categories, "
            + phrasings + " phrasings");
        assertTrue(book.entries().size() >= 150, "only " + book.entries().size() + " articles");
    }
}
