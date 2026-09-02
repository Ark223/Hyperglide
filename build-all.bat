@echo off

echo Building 1.21.4...
call gradlew.bat clean build -Pmc=1.21.4 --no-daemon || exit /b 1

echo.
echo Building 1.21.11...
call gradlew.bat build -Pmc=1.21.11 --no-daemon || exit /b 1
