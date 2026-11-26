package com.jrpetty.mcassistant.entity;

import java.util.UUID;

/**
 * [featlife] A folk's week on its card: whose hours it works and what they are, whether today is a day off, what it is
 * doing with it, and its week in figures (LifeLog.weekLine).
 */
public final class LifeCard {

    private LifeCard() {}

    public static String week(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null || f.isBaby() || Visitors.is(f)) return "";
        long day = f.level().getDayTime() / 24000L;
        StringBuilder sb = new StringBuilder();
        if (f.stationTask() != AssistantEntity.StationTask.NONE && f.stationTask() != AssistantEntity.StationTask.GUARD) {
            Business b = Businesses.employer(village, f);
            Business.Hours h = WorkHours.of(village, f);
            sb.append(b != null ? "Works " + h.words() + " for " + b.name : "Works the town's week, " + h.words());
            if (WorkHours.kept(village, day)) sb.append(WorkHours.dayOff(f) ? "; off today" : "");
            else sb.append(" (a young town works every day)");
            sb.append(". ");
        }
        WeekendLife.Outing o = WeekendLife.now(f);
        if (o != null) sb.append("Today: ").append(o.doing).append(". ");
        sb.append(LifeLog.weekLine(f));
        return sb.toString().trim();
    }
}
