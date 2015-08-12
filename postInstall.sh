#!/bin/sh

set -e

if [ -d  "/var/cache/PrintToBox" ] ; then
    if [ "`/usr/bin/stat -c '%a' /var/cache/PrintToBox`" != '2770' ] ; then
        /bin/chmod '2770' /var/cache/PrintToBox
    fi

    if [ "`/usr/bin/stat -c '%G' /var/cache/PrintToBox`" != 'printtobox' ] ; then
        /bin/chgrp printtobox /var/cache/PrintToBox
    fi
fi

