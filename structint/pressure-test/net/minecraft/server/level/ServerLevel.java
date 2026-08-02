package net.minecraft.server.level;
import java.util.*; import net.minecraft.core.particles.ParticleOptions; import net.minecraft.world.level.Level;
public class ServerLevel extends Level {
    public final List<ServerPlayer> players=new ArrayList<>();
    public List<ServerPlayer> players(){return players;}
    public <T extends ParticleOptions> int sendParticles(T t,double x,double y,double z,int c,double a,double b,double d,double s){return 0;}
}
