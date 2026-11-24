@echo off
setlocal
cd /d "%~dp0"
REM 交付包里启动器就在根目录；源码仓库里它在 scripts\ 下，构建产物在上一级。
if not exist "target\quarkus-app\quarkus-run.jar" if exist "..\target\quarkus-app\quarkus-run.jar" cd /d "%~dp0.."

REM Single-instance guard: if port 8081 is already listening, open the browser
REM and exit instead of starting a second instance on the same port.
netstat -ano | findstr /R /C:":8081 " | findstr /C:"LISTENING" >nul 2>&1
if not errorlevel 1 (
  echo 系统已在运行，直接打开浏览器：http://localhost:8081
  start "" http://localhost:8081
  timeout /t 3 >nul
  exit /b 0
)
title 审核员调配系统

if not exist ".env" (
  copy /Y ".env.example" ".env" >nul
  echo 已创建本机 AI 配置文件 .env。如需 DeepSeek，请在启动后填写 API Key 并重启。
)

REM 会话加密密钥：.env 中缺失或为空时，自动生成 48 位随机值（安全启动的硬性要求，无需手动填写）
findstr /R /C:"^AUTH_SESSION_KEY=." ".env" >nul 2>&1
if not errorlevel 1 goto sessionkeydone
for /f "delims=" %%K in ('powershell -NoProfile -Command "-join ((1..48) | ForEach-Object { Get-Random -InputObject ('ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'.ToCharArray()) })"') do set "SESSION_KEY=%%K"
>>".env" echo AUTH_SESSION_KEY=%SESSION_KEY%
echo 已自动生成随机会话密钥（48 位）并写入 .env，用于加密登录会话。
:sessionkeydone

set "JAVA_HOME=%CD%\.tools\jdk\jdk-21.0.12.1+1"
set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"

if not exist "%JAVA_EXE%" (
  echo [错误] 未找到内置 Java：%JAVA_EXE%
  echo 请确认压缩包已经完整解压。
  pause
  exit /b 1
)

if not exist "target\quarkus-app\quarkus-run.jar" (
  echo [错误] 未找到已构建程序 target\quarkus-app\quarkus-run.jar
  echo 请确认压缩包已经完整解压。
  pause
  exit /b 1
)

echo 系统地址：http://localhost:8081
echo 关闭此窗口或按 Ctrl+C 可停止系统。
start "" cmd /c "timeout /t 5 /nobreak >nul && start http://localhost:8081"

"%JAVA_EXE%" -Dquarkus.profile=sqlite -Dquarkus.http.port=8081 -jar "target\quarkus-app\quarkus-run.jar"

echo.
echo 系统已经停止。
pause
endlocal
