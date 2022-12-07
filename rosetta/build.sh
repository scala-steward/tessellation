#!/bin/sh

IMAGENAME=rosetta-tester

docker build -t $IMAGENAME:$(date +%Y%m%d) .
docker build -t $IMAGENAME:latest .
