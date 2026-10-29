@echo off
setlocal enabledelayedexpansion

set APP_NAME=CodeClip

REM --- NetBeans'in icindeki Maven ve JDK ---
set "NB_MAVEN=C:\Program Files\Apache NetBeans\java\maven\bin\mvn.cmd"
set "NB_JDK=C:\Program Files\Apache NetBeans\jdk"
set "JAVA_HOME=%NB_JDK%"
set "PATH=%NB_JDK%\bin;C:\Program Files\Apache NetBeans\java\maven\bin;%PATH%"

cd /d "%~dp0"

REM --- 1) Calisan CodeClip.exe'yi kapat ---
taskkill /F /T /IM %APP_NAME%.exe >nul 2>&1
timeout /t 2 /nobreak >nul

REM --- 2) Eski build ciktilari silinmeye calisilir (basarisiz olsa da devam) ---
if exist "target\jpackage" rmdir /S /Q "target\jpackage" >nul 2>&1
if exist "dist\%APP_NAME%"   rmdir /S /Q "dist\%APP_NAME%"   >nul 2>&1

REM --- 3) gh CLI var mi ---
where gh >nul 2>&1
if errorlevel 1 (
    echo [ERROR] GitHub CLI ^(gh^) bulunamadi.
    echo Kurulum: https://cli.github.com/
    pause & exit /b 1
)

REM --- 4) Oturum acik mi ---
gh auth status >nul 2>&1
if errorlevel 1 (
    echo [ERROR] GitHub oturumu yok. Once calistir: gh auth login
    pause & exit /b 1
)

REM --- 5) Surum etiketi ---
if "%~1"=="" (
    set /p VERSION="Surum etiketi (or. v1.0.0): "
) else (
    set VERSION=%~1
)
if "%VERSION%"=="" (
    echo [ERROR] Surum etiketi bos olamaz.
    pause & exit /b 1
)

REM --- 6) Build ---
echo.
echo [1/3] Derleniyor...
call "%NB_MAVEN%" clean package
if errorlevel 1 (
    echo [ERROR] Build basarisiz.
    pause & exit /b 1
)

REM --- 7) Uygulama imajini zip'le ---
echo.
echo [2/3] Uygulama imaji zip'leniyor...
if not exist "dist\%APP_NAME%" (
    echo [ERROR] dist\%APP_NAME% bulunamadi.
    pause & exit /b 1
)
set "ZIP_FILE=%TEMP%\%APP_NAME%-%VERSION%.zip"
if exist "%ZIP_FILE%" del "%ZIP_FILE%"
powershell -NoProfile -Command "Compress-Archive -Path 'dist\%APP_NAME%\*' -DestinationPath '%ZIP_FILE%' -Force"
if errorlevel 1 (
    echo [ERROR] Zip basarisiz.
    pause & exit /b 1
)

REM --- 8) Release olustur / guncelle ---
echo.
echo [3/3] Release %VERSION% yukleniyor...
gh release view %VERSION% >nul 2>&1
if errorlevel 1 (
    gh release create %VERSION% "%ZIP_FILE%#%APP_NAME%-%VERSION%.zip" ^
        --title "%APP_NAME% %VERSION%" ^
        --notes "Otomatik surum %VERSION%"
) else (
    gh release upload %VERSION% "%ZIP_FILE%#%APP_NAME%-%VERSION%.zip" --clobber
)
if errorlevel 1 (
    echo [ERROR] Release yuklemesi basarisiz.
    pause & exit /b 1
)

echo.
echo [DONE] Release %VERSION% yayinlandi.
echo Indirme: gh release view %VERSION% --web
pause