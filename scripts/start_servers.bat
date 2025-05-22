@echo off
echo Starting 3 Servers with Replication...
cd js-server

echo Starting Server 1...
start "Server 1" cmd /c "mode con: cols=70 lines=25 & color 0E & title Server 1 & node server.js 1"
timeout /t 3 /nobreak > nul

echo Starting Server 2...
start "Server 2" cmd /c "mode con: cols=70 lines=25 & color 0D & title Server 2 & node server.js 2"
timeout /t 3 /nobreak > nul

echo Starting Server 3...
start "Server 3" cmd /c "mode con: cols=70 lines=25 & color 0C & title Server 3 & node server.js 3"

cd ..
echo 3 servers started.