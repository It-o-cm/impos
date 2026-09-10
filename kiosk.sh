#!/usr/bin/env bash
# Runs ON THE TILL, as root. Opens one Chrome window per screen and places
# each one where it belongs.
#
# Why it is not a single chrome command: this desktop's window manager
# ignores --window-position, and --kiosk goes fullscreen on whatever screen
# it lands on, so both windows pile up on the same one. The only thing that
# works is to open the window, then move it with wmctrl and only then ask
# for fullscreen, which applies to the screen the window is now on. Window
# ids change at every Chrome start, so each window is identified by what
# appeared between the two listings rather than by a fixed id or a title.

set -u

USER_NAME='utilis00'
XAUTH='/run/user/1000/gdm/Xauthority'
DISP=':0'
POS_URL='http://localhost:8080'
CUSTOMER_URL='http://localhost:8080/customer'
WIDTH=1024
HEIGHT=768
POS_AT='0,0'
CUSTOMER_AT='1024,0'

# Runs a command inside the desktop user's X session.
asuser() {
  su - "$USER_NAME" -c "XAUTHORITY=$XAUTH DISPLAY=$DISP $1"
}

# Lists the ids of every window currently managed.
window_ids() {
  asuser 'wmctrl -l' 2> /dev/null | awk '{print $1}'
}

# Opens one Chrome window on its own profile, detached from this shell.
launch() {
  setsid su - "$USER_NAME" -c "XAUTHORITY=$XAUTH DISPLAY=$DISP nohup /usr/bin/google-chrome --app=$1 --force-device-scale-factor=1 --window-size=$WIDTH,$HEIGHT --user-data-dir=/home/$USER_NAME/$2 > /tmp/$3 2>&1 &" < /dev/null > /dev/null 2>&1 &
}

# Moves every window of a list to a position, then makes it fullscreen there.
place() {
  local at="$1"
  shift
  local id
  for id in "$@"; do
    asuser "wmctrl -i -r $id -b remove,maximized_vert,maximized_horz"
    asuser "wmctrl -i -r $id -e 0,${at%,*},${at#*,},$WIDTH,$HEIGHT"
  done
  sleep 1
  for id in "$@"; do
    asuser "wmctrl -i -r $id -b add,fullscreen"
  done
}

# Opens one window and places it, returning once it is where it belongs.
open_on_screen() {
  local url="$1" profile="$2" logfile="$3" at="$4"
  local before after new
  before=$(window_ids)
  launch "$url" "$profile" "$logfile"
  sleep 6
  after=$(window_ids)
  new=$(comm -13 <(echo "$before" | sort) <(echo "$after" | sort))
  if [ -z "$new" ]; then
    echo "aucune fenetre apparue pour $url, voir /tmp/$logfile"
    return 1
  fi
  place "$at" $new
  echo "$url place en $at"
}

pkill -u "$USER_NAME" -f google-chrome
sleep 3
open_on_screen "$POS_URL" '.chrome-pos' 'chrome-pos.log' "$POS_AT"
open_on_screen "$CUSTOMER_URL" '.chrome-cust' 'chrome-cust.log' "$CUSTOMER_AT"
asuser 'wmctrl -l -G'
