@echo off
REM Run backend directly with java, bypassing spring-boot-maven-plugin
REM (offline env lacks plugin deps).
REM Prereq: mvnw.cmd -o compile and dependency:build-classpath already run.
REM The classpath is ~11KB: FOR /F drops over-long lines, so PowerShell writes
REM a java argfile instead of passing -cp on the command line.
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -Command "$cp = Get-Content -LiteralPath 'target/cp.txt' -Raw -Encoding UTF8; $text = '-cp target/classes;' + $cp.Trim() + [Environment]::NewLine + 'com.gdou.ferry.FerryApplication' + [Environment]::NewLine; [IO.File]::WriteAllText('target/run.args', $text, [Text.Encoding]::Default)"
java @target/run.args %*
endlocal
