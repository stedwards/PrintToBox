#!/bin/bash

/usr/sbin/groupadd -f printtobox

if [ ! -d  "/var/cache/PrintToBox" ] ; then
    /bin/mkdir -m '2770' /var/cache/PrintToBox
    /bin/chgrp printtobox /var/cache/PrintToBox
fi

/bin/ln -sf /usr/lib/PrintToBox/bin/PrintToBox /usr/bin/PrintToBox
