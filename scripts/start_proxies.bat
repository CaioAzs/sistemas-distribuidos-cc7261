@echo off
cd python-broker

echo Starting Post and Replication Proxies...
echo ===========================================

echo.
echo Starting Post Proxy (ports 5557/5558)...
start "Post Proxy" cmd /c "mode con: cols=80 lines=20 & title Post Proxy & python post_proxy.py"
timeout /t 1 /nobreak > nul

echo Starting Replication Proxy (ports 6000/6001)...
start "Replication Proxy" cmd /c "mode con: cols=80 lines=20 & title Replication Proxy & python replication_proxy.py"
timeout /t 1 /nobreak > nul

echo.
echo - Both proxies started!
echo.

cd ..