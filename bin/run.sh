#!/bin/bash
# Runs Drake without Nailgun (needs an uberjar)
java -cp $(dirname $0)/../target/drake.jar drake.core "$@"
