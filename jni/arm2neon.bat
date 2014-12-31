@echo off & setlocal enabledelayedexpansion

rem prosess Android.mk
for /f "tokens=*" %%j in (Android.mk) do (
    if "%%j"=="" (echo.) else (set "line=%%j" & call :2neon_lib)
)>>new_Android.mk
copy /Y new_Android.mk Android.mk
del /F new_Android.mk

echo 2 neon done!
@pause
rem @pause
@exit

:2neon_lib
set "line=!line:libx264.a=libx264_neon.a!" 
echo !line!
goto :eof