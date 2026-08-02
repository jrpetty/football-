package net.minecraft.core;
public class BlockPos {
    private static final int XL=26,ZL=26,YL=12; private static final long XM=(1L<<XL)-1,YM=(1L<<YL)-1,ZM=(1L<<ZL)-1;
    private static final int ZO=YL,XO=YL+ZL;
    protected int x,y,z;
    public BlockPos(int x,int y,int z){this.x=x;this.y=y;this.z=z;}
    public int getX(){return x;} public int getY(){return y;} public int getZ(){return z;}
    public BlockPos immutable(){return new BlockPos(x,y,z);}
    public long asLong(){return asLong(x,y,z);}
    public static long asLong(int x,int y,int z){return (((long)x&XM)<<XO)|(((long)y&YM))|(((long)z&ZM)<<ZO);}
    public static int getX(long p){return (int)(p<<(64-XO-XL)>>(64-XL));}
    public static int getY(long p){return (int)(p<<(64-YL)>>(64-YL));}
    public static int getZ(long p){return (int)(p<<(64-ZO-ZL)>>(64-ZL));}
    @Override public String toString(){return x+","+y+","+z;}
    public static class MutableBlockPos extends BlockPos {
        public MutableBlockPos(){super(0,0,0);}
        public MutableBlockPos set(int x,int y,int z){this.x=x;this.y=y;this.z=z;return this;}
        @Override public BlockPos immutable(){return new BlockPos(x,y,z);}
    }
}
