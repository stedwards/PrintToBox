#!/bin/bash

groupadd -f printtobox

mkdir -m '2770' /var/cache/PrintToBox
chgrp printtobox /var/cache/PrintToBox

cp PrintToBox.conf /etc
chmod 0640 /etc/PrintToBox.conf

mkdir -m '0755' /usr/lib/PrintToBox

cp build/libs/PrintToBox-1.0.jar /usr/lib/PrintToBox/
chmod 0644 /usr/lib/PrintToBox/PrintToBox-1.0.jar

cp PrintToBox.sh /usr/lib/PrintToBox/
chmod 0755 /usr/lib/PrintToBox/PrintToBox.sh

ln -s /usr/lib/PrintToBox/PrintToBox.sh /usr/bin/PrintToBox
