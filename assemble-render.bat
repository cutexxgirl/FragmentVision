@echo off
cd /d "%~dp0"
copy /b mods\render-parts\FragmentRender.zip.001+mods\render-parts\FragmentRender.zip.002+mods\render-parts\FragmentRender.zip.003 FragmentRender.zip
if errorlevel 1 exit /b 1
powershell -NoProfile -Command "Expand-Archive -LiteralPath FragmentRender.zip -DestinationPath mods -Force"
pause
