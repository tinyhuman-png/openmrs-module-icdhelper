#!/bin/bash
# Script to grab the ML models and needed data, leave one copy of the folder at root level for Docker/E2E testing,
# to use them in an OpenMRS instance, either keep them at root level and add your absolute path to here
# to the global property called 'icdhelper.modelDirectory',
# or copy them to default path of model directory: standard-openmrs-data-directory/icdhelper/models

# Exit immediately if a command exits with a non-zero status
set -e

echo "Downloading ONNX models and vocabularies..."
MODEL_URL=https://github.com/tinyhuman-png/openmrs-module-icdhelper/releases/...
curl -L -o models.tar.gz "$MODEL_URL" -> command when repo becomes public

echo "Extracting models..."
mkdir -p ./models
tar -xzf models.tar.gz -C ./models --strip-components=1

rm models.tar.gz
echo "Models successfully downloaded and extracted into ./models"
