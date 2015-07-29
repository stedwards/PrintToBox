#!/bin/bash

/usr/sbin/groupadd -f printtobox

/bin/mkdir -m '2770' /var/cache/PrintToBox
/bin/chgrp printtobox /var/cache/PrintToBox

/bin/cp PrintToBox.conf /etc
/bin/chgrp printtobox /etc/PrintToBox.conf
/bin/chmod 0640 /etc/PrintToBox.conf

/bin/mkdir -m '0755' /usr/lib/PrintToBox

/bin/cp build/libs/PrintToBox-1.0.jar /usr/lib/PrintToBox/
/bin/chmod 0644 /usr/lib/PrintToBox/PrintToBox-1.0.jar

/bin/cp PrintToBox.sh /usr/lib/PrintToBox/
/bin/chmod 0755 /usr/lib/PrintToBox/PrintToBox.sh

/bin/ln -s /usr/lib/PrintToBox/PrintToBox.sh /usr/bin/PrintToBox
