package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.net.WorkNetwork;
import com.jrpetty.mcassistant.village.ServiceHours;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [wpb] Where the player's work is, always on screen once they are hired (client/WorkWayClient draws it).
 * <ul>
 * <li><b>The workplace</b>: a compass arrow at the top of the screen pointing to it, its name and the distance ("Hartwell
 *     Farm, North field  42 m"), and a beam at the exact spot: the field (with its outline on the ground), the mine face,
 *     the counter, the post, the water. Before and during shifts.</li>
 * <li><b>The way in</b>: a mine's face is reached by its head and the stair (or the cage); while the player is above and
 *     away from the shaft the arrow leads to the head, saying so, then down to the face.</li>
 * <li><b>The task</b>: the current task's spot (the plot, the chest, the sheep, the marked blocks) gets a second, smaller
 *     arrow and beam, so the player is never just walking about.</li>
 * <li><b>Show me</b>: any page's "Show me" puts a waypoint on a place for two minutes.</li>
 * <li><b>The toast</b>: "Your shift at Hartwell Farm starts in 10 min" once a day before the hours open.</li>
 * <li>The arrow can be switched off on the jobs page (on by default); "Show me" still shows.</li>
 * </ul>
 */
public final class WorkWay {

    private WorkWay() {}

    static final long SHOW_FOR = 2400L;
    private static final Map<UUID, CompoundTag> LAST = new ConcurrentHashMap<>();
    private static final Map<UUID, Object[]> SHOW = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> TOLD = new ConcurrentHashMap<>();

    static boolean off(Player p) {
        return Earnings.data(p).getBoolean("wpb/arrowOff");
    }

    /** Switch the arrow on or off; returns the new state's words. */
    static String toggle(ServerPlayer p) {
        boolean now = !off(p);
        Earnings.data(p).putBoolean("wpb/arrowOff", now);
        refresh(p);
        return now ? "The work arrow and beam are off. \"Show me\" still marks places." : "The work arrow and beam are on.";
    }

    /** A place shown for two minutes (any page's "Show me"). */
    public static void showMe(ServerPlayer p, BlockPos at, String label) {
        SHOW.put(p.getUUID(), new Object[]{ at.immutable(), label, p.serverLevel().getGameTime() + SHOW_FOR });
        refresh(p);
    }

    static void clearTask(ServerPlayer p) {
        refresh(p);
    }

    static int[] arr(BlockPos b) {
        return new int[]{ b.getX(), b.getY(), b.getZ() };
    }

    /** The way as it stands for this player now. */
    public static CompoundTag way(ServerPlayer p) {
        ServerLevel level = p.serverLevel();
        CompoundTag t = new CompoundTag();
        t.putBoolean("on", !off(p));
        Object[] sh = SHOW.get(p.getUUID());
        if (sh != null && level.getGameTime() < (long) sh[2]) {
            CompoundTag m = new CompoundTag();
            m.putIntArray("at", arr((BlockPos) sh[0]));
            m.putString("l", (String) sh[1]);
            m.putString("k", "show");
            t.put("show", m);
        } else if (sh != null) SHOW.remove(p.getUUID());
        CompoundTag j = PlayerJobs.job(p);
        if (j.contains("role") && j.hasUUID("town")) {
            CompoundTag main = workplace(level, p, j);
            if (main != null) t.put("main", main);
        }
        Shifts.Shift s = Shifts.of(p);
        if (s != null && s.task != null && s.task.target != null) {
            CompoundTag task = new CompoundTag();
            task.putIntArray("at", arr(s.task.target));
            task.putString("l", "Task: " + s.task.kind.replaceAll("ed$", "") + " here");
            task.putString("k", "task");
            t.put("task", task);
        } else if (s != null && !t.contains("main")) {
            CompoundTag main = new CompoundTag();                           // a day's shift: its post
            main.putIntArray("at", arr(s.post));
            main.putString("l", s.title() + ": " + s.role.place);
            main.putString("k", "post");
            t.put("main", main);
        }
        return t;
    }

    /** The job's workplace: where, its name, its kind, a field's outline, a mine's way in. */
    @Nullable
    static CompoundTag workplace(ServerLevel level, ServerPlayer p, CompoundTag j) {
        UUID town = j.getUUID("town");
        Villages.Village v = Villages.get(town);
        ShiftRole r = ShiftRole.of(j.getString("role"));
        if (v == null || r == null) return null;
        CompoundTag m = new CompoundTag();
        String name = j.getString("employerName");
        String e = j.getString("employer");
        m.putString("k", r.id);
        if (e.startsWith("farm:")) {
            PlayerFarms.Farm f = PlayerFarms.byId(e.substring(5));
            if (f != null && !f.fields.isEmpty()) {
                int[] a = f.fields.get(0);
                m.putIntArray("at", new int[]{ (a[0] + a[2]) / 2, a[4], (a[1] + a[3]) / 2 });
                m.putIntArray("rect", new int[]{ a[0], a[1], a[2], a[3], a[4] });
                m.putString("l", f.name + ", its field");
                return m;
            }
        }
        if (r == ShiftRole.MINE) {
            WorkShifts.Face f = WorkShifts.face(level, v);
            if (f == null) return null;
            m.putIntArray("at", arr(f.at()));
            m.putString("l", name + ", " + f.name() + " face");
            if (f.head() != null) {
                m.putIntArray("via", arr(f.head()));
                m.putString("vl", "Mine head: down " + f.via() + " to " + f.name());
            }
            return m;
        }
        ShiftTasks.Post post = ShiftTasks.post(level, v, r, p.blockPosition());
        if (post == null) return null;
        m.putIntArray("at", arr(post.pos()));
        m.putString("l", name + ", " + r.place);
        if (r == ShiftRole.FARM) {
            for (AssistantEntity a : Villages.folkOf(town)) {
                WorkZone z = a.workZone();
                if (a.stationTask() != AssistantEntity.StationTask.FARM || z == null) continue;
                if (post.pos().getX() >= z.min().getX() - 2 && post.pos().getX() <= z.max().getX() + 2 && post.pos().getZ() >= z.min().getZ() - 2 && post.pos().getZ() <= z.max().getZ() + 2) {
                    m.putIntArray("rect", new int[]{ z.min().getX(), z.min().getZ(), z.max().getX(), z.max().getZ(), post.pos().getY() });
                    break;
                }
            }
        }
        return m;
    }

    /** Send the way now if it changed. */
    public static void refresh(Player pl) {
        if (!(pl instanceof ServerPlayer p)) return;
        CompoundTag t = way(p);
        CompoundTag was = LAST.get(p.getUUID());
        if (t.equals(was)) return;
        LAST.put(p.getUUID(), t);
        WorkNetwork.send(p, t);
    }

    /** Every five seconds: the way kept true; the shift-start toast once a day. */
    static void tick(ServerLevel level, ServerPlayer p) {
        CompoundTag j = PlayerJobs.job(p);
        if (j.contains("role") || SHOW.containsKey(p.getUUID()) || Shifts.onShift(p) || LAST.containsKey(p.getUUID())) refresh(p);
        if (!j.contains("role") || Shifts.onShift(p)) return;
        ShiftRole r = ShiftRole.of(j.getString("role"));
        if (r == null) return;
        ServiceHours h = r.hours(null);
        long time = level.getDayTime() % 24000L, day = level.getDayTime() / 24000L;
        long until = Math.floorMod(h.open() - time, 24000L);
        if (until > 0 && until <= 200 && !Long.valueOf(day).equals(TOLD.get(p.getUUID())) && !WorkLife.onLeave(j, day)) {
            TOLD.put(p.getUUID(), day);
            WorkLife.toast(p, "Your shift at " + j.getString("employerName") + " starts in 10 min. Follow the arrow.");
        }
    }

    /** Tests: the way as last computed. */
    public static CompoundTag wayForTests(ServerPlayer p) {
        return way(p);
    }
}
