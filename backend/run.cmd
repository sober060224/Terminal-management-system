@echo off
REM Run backend directly with java, bypassing spring-boot-maven-plugin
REM (offline env lacks plugin deps).
REM Prereq: mvnw.cmd -o compile and dependency:build-classpath already run.
REM The classpath is ~11KB: FOR /F drops over-long lines, so node writes a
REM java argfile instead of passing -cp on the command line.
setlocal
node -e "const fs=require('fs');fs.writeFileSync('target/run.args','-cp target/classes;'+fs.readFileSync('target/cp.txt','utf8').trim()+'\ncom.gdou.ferry.FerryApplication\n')"
java @target/run.args %*
endlocal
