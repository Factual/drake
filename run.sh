#!/bin/bash
# Runs Drake without Nailgun (needs an uberjar)
java -cp $(dirname $0)/drake.jar drake.core "$@"
