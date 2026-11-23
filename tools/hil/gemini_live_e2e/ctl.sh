#!/bin/bash
# usage: ctl.sh 'toolcall end_session' | 'toolcall phone_task open Spotify' | 'reply Bye' | status
printf '%s\n' "$*" | nc -q 2 127.0.0.1 18901
