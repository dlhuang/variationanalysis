#!/usr/bin/env bash

DISTRIBUTION=$1
shift
set -x

if [ -e "${DISTRIBUTION}/gatk" ]; then
    bin/parallel-gatk4-realign-filtered.sh ${DISTRIBUTION}/gatk "$@"
elif [ -e "${DISTRIBUTION}/GenomeAnalysisTK.jar" ]; then
    bin/parallel-gatk3-realign-filtered.sh ${DISTRIBUTION}/GenomeAnalysisTK.jar "$@"
else
    echo "Unable to execute GATK distribution at ${DISTRIBUTION}"
fi
