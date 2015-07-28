#!/bin/bash

JAVA_HOME=/usr/lib/jvm/java-7-openjdk-amd64
PRINTTOBOX=/usr/lib/PrintToBox/PrintToBox-1.0.jar

$JAVA_HOME/bin/java -jar $PRINTTOBOX $@
