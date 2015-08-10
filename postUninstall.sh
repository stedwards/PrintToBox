#!/bin/bash

if [ -d  "/var/cache/PrintToBox" ] ; then
    /bin/rm -rf /var/cache/PrintToBox
fi

/bin/rm -f /usr/bin/PrintToBox

if [ -n "`/usr/bin/getent group printtobox`" ] ; then
    /usr/sbin/groupdel printtobox
fi
