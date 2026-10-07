package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [batchF] The town's affairs set out for the pictures ("/village civic stage", tools/realworld/smoke.py's
 * townlife_stage): beside the town, on ground levelled for them, a post office with its sign by the door
 * and a statue with its givers' names before it (from a palette, not the stores, as the showcase's are; the
 * givers are the town's own folk, by name, and no coin changes hands); a petition got up by whoever has a
 * grievance; and the town meeting called now. Returns "CIVIC x y z" and the views, "VIEW name x y z ax ay az":
 * the post office from the street, the statue and its sign, and the meeting from behind the crowd.
 */
final class CivicStage {

    private CivicStage() {}

    static List<String> set(ServerLevel level, Villages.Village v, BlockPos at) {
        UUID id = v.id();
        int x = at.getX(), z = at.getZ();
        level.getChunk(x >> 4, z >> 4);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        Showcase.stage(level, x - 14, x + 14, z - 9, z + 14, y);
        // The post office, its door to the south, and its name on the sign by the door.
        BlockPos post = new BlockPos(x - 6, y, z);
        BuildGoal.stamp(level, Post.STRUCTURE, post, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(id, Post.STRUCTURE, post, Direction.NORTH);
        Ledger.Building pb = new Ledger.Building(Post.STRUCTURE, post, Direction.NORTH);
        TownLife.addressSign(level, id, v.centre(), pb, null, true);
        // The statue, and its givers: the town's folk, as if each had given to it.
        BlockPos statue = new BlockPos(x + 7, y, z);
        BuildGoal.stamp(level, PublicFund.STRUCTURE, statue, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(id, PublicFund.STRUCTURE, statue, Direction.NORTH);
        CompoundTag fund = PublicFund.fund(id);
        CompoundTag donors = Civics.sub(fund, "donors");
        int raised = 0, i = 0;
        for (VillageFolkEntity f : Civics.grown(id)) {
            int n = 3 + (i++ * 7) % 9;
            donors.putInt(f.displayNameCap(), donors.getInt(f.displayNameCap()) + n);
            raised += n;
        }
        fund.putInt("raised", Math.max(fund.getInt("raised"), raised));
        if (fund.getLong("funded") == 0) fund.putLong("funded", Civics.day(level) + 1);
        Civics.changed();
        PublicFund.plaque(level, v, new Ledger.Building(PublicFund.STRUCTURE, statue, Direction.NORTH), true);
        List<String> out = new ArrayList<>();
        out.add("CIVIC " + x + " " + y + " " + z);
        out.add("PETITION " + Petitions.raiseNow(level, v));
        boolean meeting = Assemblies.startNow(level, v, Assemblies.Kind.MEETING);
        out.add("MEETING " + (meeting ? "called" : "nobody to hold it"));
        out.add("VIEW civic-1-post-office " + (post.getX() + 4) + " " + (y + 2) + " " + (post.getZ() + 10) + " " + post.getX() + " " + (y + 2) + " " + post.getZ());
        out.add("VIEW civic-2-statue " + (statue.getX() - 3) + " " + (y + 1) + " " + (statue.getZ() + 8) + " " + statue.getX() + " " + (y + 2) + " " + statue.getZ());
        Assemblies.Assembly a = TownMeeting.assembly(level, v, Civics.day(level));
        BlockPos f = a.focus;
        Direction d = a.audience;
        BlockPos cam = f.relative(d, 12).above(5);
        out.add("VIEW civic-3-meeting " + cam.getX() + " " + cam.getY() + " " + cam.getZ() + " " + f.getX() + " " + (f.getY() + 1) + " " + f.getZ());
        return out;
    }
}
