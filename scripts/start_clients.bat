@echo off
echo Starting Multiple Clients...
cd client

rem
start "Client 1" cmd /c "mode con: cols=60 lines=20 & color 0C & title Client 1 & mvn exec:java -Dexec.mainClass=Client -Duser.id=1"
timeout /t 1 /nobreak > nul

start "Client 2" cmd /c "mode con: cols=60 lines=20 & color 0D & title Client 2 & mvn exec:java -Dexec.mainClass=Client -Duser.id=2"
timeout /t 1 /nobreak > nul

start "Client 3" cmd /c "mode con: cols=60 lines=20 & color 0F & title Client 3 & mvn exec:java -Dexec.mainClass=Client -Duser.id=3"
timeout /t 1 /nobreak > nul

start "Client 4" cmd /c "mode con: cols=60 lines=20 & color 0F & title Client 4 & mvn exec:java -Dexec.mainClass=Client -Duser.id=4"
timeout /t 1 /nobreak > nul

start "Client 5" cmd /c "mode con: cols=60 lines=20 & color 0F & title Client 5 & mvn exec:java -Dexec.mainClass=Client -Duser.id=5"

cd ..
echo All clients started!