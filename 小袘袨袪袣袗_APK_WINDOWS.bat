@echo off
setlocal
cd /d "%~dp0"

echo ========================================
echo MyFinance - сборка APK
echo ========================================
echo.
echo Этот файл запускается из папки проекта.
echo.

if not exist "%ANDROID_HOME%" if not exist "%LOCALAPPDATA%\Android\Sdk" (
  echo Не найден Android SDK.
  echo Установите Android Studio и Android SDK, затем запустите этот файл снова.
  pause
  exit /b 1
)

where gradle >nul 2>nul
if %errorlevel%==0 (
  echo Найден Gradle. Начинаю сборку...
  gradle assembleDebug
) else (
  echo Gradle не найден в PATH.
  echo Откройте проект в Android Studio и выберите Build -^> Build APK(s).
  echo.
  echo После сборки APK будет здесь:
  echo app\build\outputs\apk\debug\app-debug.apk
  pause
  exit /b 0
)

if exist "app\build\outputs\apk\debug\app-debug.apk" (
  echo.
  echo ГОТОВО!
  echo APK: %CD%\app\build\outputs\apk\debug\app-debug.apk
) else (
  echo Сборка завершилась без найденного APK. Проверьте сообщения выше.
)
pause
