@echo off
REM ============================================================
REM Codelkup 后端启动脚本（Windows）
REM 用法：
REM   start-backend.bat          使用默认端口 8085
REM   start-backend.bat 9090     指定端口
REM ============================================================

setlocal

set PORT=%1
if "%PORT%"=="" set PORT=8085

set BACKEND_DIR=%~dp0
cd /d "%BACKEND_DIR%"

REM ⚠️ 必须剔除 SERVER__PORT 环境变量
REM    WorkBuddy 会注入 SERVER__PORT=65336，其优先级高于 application.yml，
REM    且 65336 已被 WorkBuddy 自身占用，不剔除必然启动失败。
set SERVER__PORT=

REM JDK 位置：优先用系统已配好的 JAVA_HOME（换机器就不用改这里）；
REM 没配才退回本机常见位置 —— 硬编码会让别人 clone 后直接启动失败。
if "%JAVA_HOME%"=="" set JAVA_HOME=D:\jdk\jdk-21

echo [Codelkup] 启动后端，端口 %PORT% ...
"%JAVA_HOME%\bin\java.exe" -jar target\codelkup-backend-0.0.1-SNAPSHOT.jar --server.port=%PORT%

endlocal
