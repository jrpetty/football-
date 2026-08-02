package harness;
import java.util.*;
/** Deterministic fake world: a material per position, plus which blocks a player placed. */
public final class W {
    public static final Map<Long,String> B = new HashMap<>();
    public static final Set<Long> MANAGED = new HashSet<>();
    public static final List<String> BROKEN = new ArrayList<>();
    public static final List<String> REQUEUED = new ArrayList<>();
    public static int minY = -64, maxY = 320;

    public static void reset(){ B.clear(); MANAGED.clear(); BROKEN.clear(); REQUEUED.clear(); }
    public static long k(int x,int y,int z){ return net.minecraft.core.BlockPos.asLong(x,y,z); }
    public static String at(int x,int y,int z){ return B.getOrDefault(k(x,y,z),"air"); }
    public static void set(int x,int y,int z,String m,boolean managed){
        B.put(k(x,y,z),m); if(managed) MANAGED.add(k(x,y,z)); else MANAGED.remove(k(x,y,z));
    }
    /** Fill a box. */
    public static void box(int x0,int y0,int z0,int x1,int y1,int z1,String m,boolean managed){
        for(int x=x0;x<=x1;x++) for(int y=y0;y<=y1;y++) for(int z=z0;z<=z1;z++) set(x,y,z,m,managed);
    }
}
