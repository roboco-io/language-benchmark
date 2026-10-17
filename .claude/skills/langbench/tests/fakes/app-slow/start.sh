#!/bin/bash
# LISTEN 전에 40초 대기하는 가짜 앱
sleep 40
exec python3 ../app-ok/server.py
