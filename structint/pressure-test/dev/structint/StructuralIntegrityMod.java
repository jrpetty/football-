package dev.structint;
import org.slf4j.Logger;
public final class StructuralIntegrityMod {
    public static final Logger LOGGER = new Logger(){
        public void info(String s){System.out.println("INFO "+s);}
        public void warn(String s,Throwable t){System.out.println("WARN "+s+" "+t);}
        public void error(String s,Throwable t){System.out.println("ERROR "+s+" "+t); t.printStackTrace();}
        public void debug(String s,Object a,Object b){}
    };
}
