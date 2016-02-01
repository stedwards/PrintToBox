#!/bin/sh

set -e

# if $1 not empty and equal to 2 (RPM) or contains "upgrade" (DEB) then
# leave the configuration alone!
[ ! -z ${1+x} ] && [ "$1" = "2" ] || test "${1#*upgrade}" != "$1" && exit 0

/usr/sbin/groupadd -r -f printtobox
