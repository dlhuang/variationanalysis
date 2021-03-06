#!/usr/bin/env bash

USAGE_STR=$(cat <<-END
    Usage: evaluate-genotypetensors-models.sh -m model-directory -c checkpoint-file -p model-prefix -t test-sbi -d dataset [-n]
    Monitor the progress of a model being trained by PyTorch
    -m should point to the directory of the trained PyTorch model being used with a config.properties within.
    -c should be a file with one checkpoint key per line
    -p should be the model prefix (usually best or latest)
    -t should be a path to the test SBI file, and -d should be the name of the dataset used for the sbi.
    -n disables writing progress out to a file
END
)

. `dirname "${BASH_SOURCE[0]}"`/common.sh

if [ $# -eq 0 ]; then
    echo "${USAGE_STR}"
    exit 0
fi

function assertEvaluateInstalled {
    evaluate-genotypes-vec.sh -h >/dev/null 2>&1 || { echo >&2 "This script requires evaluate-genotypes-vec.sh from Variation to be in your path. Aborting. Check to make sure it is in your path before running, then try again."; exit 1; }
}

assertEvaluateInstalled

function assertLogInstalled {
    log-evaluate.sh -h >/dev/null 2>&1 || { echo >&2 "This script requires log-evaluate.sh from GenotypeTensors to be in your path. Aborting. Check to make sure it is in your path before running, then try again."; exit 1; }
}

assertLogInstalled

MODEL_DIR=""
CHECKPOINT_FILE=""
MODEL_PREFIX=""
DATASET_SBI=""
DATASET_NAME=""
NONVERBOSE=0

while getopts ":hm:c:p:t:d:n" opt; do
    case "${opt}" in
        h)
            echo "${USAGE_STR}"
            exit 0
            ;;
        m)
            MODEL_DIR=$OPTARG
            ;;
        c)
            CHECKPOINT_FILE=$OPTARG
            ;;
        p)
            MODEL_PREFIX=$OPTARG
            ;;
        t)
            DATASET_SBI=$OPTARG
            ;;
        d)
            DATASET_NAME=$OPTARG
            ;;
        n)
            NONVERBOSE=1
            ;;
        \?)
            echo "Invalid option: -${OPTARG}" 1>&2
            exit 1;
            ;;
        :)
            echo "Invalid Option: -$OPTARG requires an argument" 1>&2
            exit 1
            ;;
    esac
done
shift $((OPTIND -1))

ERROR_STR=$(cat <<-END
   You must specify all options: -m, -c, -p, -t, and -d. If you are unsure about usage, run with no arguments or with -h.
END
)

if [ -z "${MODEL_DIR}" ] || [ -z "${CHECKPOINT_FILE}" ] || [ -z "${MODEL_PREFIX}" ] || [ -z "${DATASET_SBI}" ] || [ -z "${DATASET_NAME}" ]; then
    echo "${ERROR_STR}"
    exit 1
fi

LOG_OUTPUT="${CHECKPOINT_FILE%.*}_`basename ${DATASET_SBI} ".sbi"`_log.tsv"
LOG_PROGRESS_PATH="${CHECKPOINT_FILE%.*}_`basename ${DATASET_SBI} ".sbi"`_progress.log"
if [ ${NONVERBOSE} -ne 0 ]; then
    LOG_PROGRESS_PATH="/dev/null"
fi

while read checkpoint_key; do
    FULL_MODEL_PATH=$(python - <<EOF
import os
print(os.path.join("${MODEL_DIR}", "models", "pytorch_{}_{}.t7".format("${checkpoint_key}", "${MODEL_PREFIX}")))
EOF
)
    if [ ! -e "${FULL_MODEL_PATH}" ]; then
        echo "The model was not found: ${FULL_MODEL_PATH}"
        exit 1;
    fi
    RANDOM_OUTPUT_SUFFIX="${RANDOM}"
    echo "========= EVALUATING MODEL ${FULL_MODEL_PATH} ON DATASET ${DATASET_SBI} =========" >> ${LOG_PROGRESS_PATH}
    echo "Running evaluate on ${FULL_MODEL_PATH} with dataset ${DATASET_SBI}..."
    evaluate-genotypes-vec.sh -m "${MODEL_DIR}" -c "${checkpoint_key}" -p "${MODEL_PREFIX}" -t "${DATASET_SBI}" -d "${DATASET_NAME}" -r "${RANDOM_OUTPUT_SUFFIX}" >>"${LOG_PROGRESS_PATH}" 2>&1
    dieIfError "Unable to evaluate genotypes with the given parameters"
    log-evaluate.sh --dataset ${DATASET_NAME} --model-path "${MODEL_DIR}" --checkpoint-key "${checkpoint_key}" --model-label "${MODEL_PREFIX}" --vcf-path "output-${RANDOM_OUTPUT_SUFFIX}" --output-path "${LOG_OUTPUT}" >>"${LOG_PROGRESS_PATH}" 2>&1
    dieIfError "Unable to log results of evaluation with the given parameters"
done < "${CHECKPOINT_FILE}"

export COMBINED_PREDICT_STATISTICS="predict-statistics-`basename ${DATASET_SBI} ".sbi"`-`basename ${CHECKPOINT_FILE%.*}`-${MODEL_PREFIX}.tsv"
head -1 "predict-statistics-`basename ${DATASET_SBI} ".sbi"`-`head -1 ${CHECKPOINT_FILE}`-${MODEL_PREFIX}.tsv" > ${COMBINED_PREDICT_STATISTICS}
while read checkpoint_key; do
    sed 1d "predict-statistics-`basename ${DATASET_SBI} ".sbi"`-${checkpoint_key}-${MODEL_PREFIX}.tsv" >> ${COMBINED_PREDICT_STATISTICS}
    rm "predict-statistics-`basename ${DATASET_SBI} ".sbi"`-${checkpoint_key}-${MODEL_PREFIX}.tsv"
done < "${CHECKPOINT_FILE}"
