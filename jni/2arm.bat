@echo off & setlocal enabledelayedexpansion

rem prosess Application.mk
for /f "tokens=*" %%i in (Application.mk) do (
    if "%%i"=="" (echo.) else (set "line=%%i" & call :2arm)
)>>new_Application.mk
copy /Y new_Application.mk Application.mk
del /F new_Application.mk

echo to arm done!
@pause
rem @pause
@exit

:2arm
set "line=!line:x86=armeabi!"
echo !line!
goto :eof
