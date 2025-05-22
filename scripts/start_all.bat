@echo off
echo.
echo ========================================
echo Starting Social Network...
echo ========================================

echo.
echo Starting Broker...
call scripts\start_broker.bat
timeout /t 2 /nobreak > nul

echo.
echo Starting Proxies...
call scripts\start_proxies.bat
timeout /t 2 /nobreak > nul

echo.
echo Starting 3 Servers...
call scripts\start_servers.bat
timeout /t 4 /nobreak > nul

echo.
echo Starting 5 Clients...
call scripts\start_clients.bat

echo.
echo ================================================================
echo ALL COMPONENTS STARTED!
echo ================================================================
echo.
echo - 1 Broker (port 5555/5556)
echo - 2 Proxies: Post Proxy (5557/5558) + Replication Proxy (6000/6001)
echo - 3 Servers
echo - 5 Clients
echo ================================================================
echo.
pause