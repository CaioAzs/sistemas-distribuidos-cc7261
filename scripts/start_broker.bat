@echo off
echo Starting Broker...
cd python-broker
start "Broker" cmd /c "mode con: cols=60 lines=20 & color 0A & title Broker & python broker.py"
cd ..