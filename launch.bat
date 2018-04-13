@echo off
@title UmbraMS
set CLASSPATH=.;cores\*
java -Xmx2048m -Dwzpath=wz\ net.server.Server
pause