#!/bin/bash
# 자식 프로세스가 LISTEN하고 부모는 대기하는 가짜 앱
python3 ../app-ok/server.py &
wait
