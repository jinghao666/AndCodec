@echo off & setlocal enabledelayedexpansion

rem prosess Application.mk
for /f "tokens=*" %%i in (Application.mk) do (
    if "%%i"=="" (echo.) else (set "line=%%i" & call :2x86)
)>>new_Application.mk
copy /Y new_Application.mk Application.mk
del /F new_Application.mk

echo to x86 done!
@pause
rem @pause
@exit

:2x86
set "line=!line:armeabi=x86!"
echo !line!
goto :eof
