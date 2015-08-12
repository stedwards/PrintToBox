#!/bin/sh

set -e

# if $1 not empty and equal to 1 (RPM) or contains "upgrade" (DEB) then
# leave this alone!
[ ! -z ${1+x} ] && [ "$1" = "1" ] || test "${1#*upgrade}" != "$1" && exit 0

/bin/rm -f /usr/bin/PrintToBox

#Fedora forbids removing groups. Taking that advice.
