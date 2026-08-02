package org.slf4j;
public interface Logger { void info(String s); void warn(String s,Throwable t); void error(String s,Throwable t); void debug(String s,Object a,Object b); }
