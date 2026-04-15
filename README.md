[![Build Status](https://github.com/tinyhuman-png/openmrs-module-icdhelper/actions/workflows/ci_pipeline.yml/badge.svg)](https://github.com/tinyhuman-png/openmrs-module-icdhelper/actions)
# OpenMRS ICD-10-CM Coding Helper Module

This module provides OpenMRS with an offline coding assistant. It uses embedded Artificial Intelligence 
(SapBERT and a custom Hierarchical BERT) to analyze free-text clinical notes and suggest related ICD-10-CM codes, 
which are then mapped to OpenMRS Concepts and can be saved to the patient's medical record.
[Watch the demo on YouTube](https://www.youtube.com/watch?v=rWpzap2CXXQ)

[We would appreciate it if you could take a few minutes to answer our user experience survey](https://egouthiere.limesurvey.net/796197?lang=en&newtest=Y)

## Key Features
* **Offline AI inference:** The module uses ONNX Runtime to execute the machine learning models directly 
within the Java Virtual Machine, eliminating the need for external Python APIs or cloud services.
* **Dual analysis modes:**
    * *Selection mode:* Clinicians can highlight short excerpts. The module uses **SapBERT** to generate a 
    768-dimensional embedding and performs a rapid cosine similarity search against pre-computed vectors of 
    known ICD-10-CM codes. If the selection is too long (> 1000 characters), it falls back to full note mode.
    * *Full note mode:* Clinicians can analyze entire notes. The module uses a **custom Hierarchical BERT** model with 
    a 512-token sliding window (stride 256) to perform multi-label classification across all ICD-10-CM codes.
* **Integration in clinical workflow:** The tool is directly available in the Patient dashboard, under 
Current Visit Actions (provided they have an active visit).
* **Persistence of suggestions:** The suggested ICD-10-CM codes can be saved to the patient's record, 
as new Observations under the same Encounter as the Visit Note from which the clinical text comes from. 
Depending on whether a matching Concept was found for the selected ICD-10-CM code or not, it is saved as a 
coded value Concept under Concept 1284 ("Diagnosis, Coded"), or as free text under Concept 160221 ("Diagnosis, Non-coded")

## Architecture
This project is built using the standard OpenMRS module architecture (Spring MVC + Hibernate), and GSP for UI framework.
* `api/`: Contains the core services and custom ONNX predictors.
* `omod/`: Contains the UI controllers, GSP views, and the dashboard extension points.
* `e2e/`: Contains a containerized Playwright testing suite.

## Quick start (Docker)

The `e2e/` folder contains a Docker Compose setup that spins up a fully configured OpenMRS instance with the module
pre-deployed. This is the fastest way to evaluate the module without a manual OpenMRS installation.
Jump to [Installation and Setup](#installation-and-setup) for a detailed guide on a local OpenMRS instance.

### 1. Download the ML models
Because the ONNX models and pre-computed embeddings are large, they are not stored directly in this Git repository.
We provide you with a simple bash script `download_models.sh` to fetch all needed files. You can also download the
archive manually from the [GitHub release](https://github.com/tinyhuman-png/openmrs-module-icdhelper/releases/latest)
and extract it to the repository root.
In both cases, make sure the downloaded data lies in the `models` root folder.

### 2. Build the module
> **Note:** If you don't need to modify the source code, you can skip this step and download
> the pre-built `.omod` directly from the [latest GitHub Release](https://github.com/tinyhuman-png/openmrs-module-icdhelper/releases/latest).
```bash
mvn clean install
```

### 3. Start the server
```bash
cd e2e
docker compose up
```
Wait for OpenMRS to be ready (this can take 2–3 minutes). The instance is then accessible at 
`http://localhost:8080/openmrs`, you can log in with username:`admin` and password:`Admin123`.

### 4. Load the required concepts
The module depends on three Concepts from CIEL Concept Dictionary (`1284`, `160221`, and `162169`). 
You have two options:

* **Recommended:** Subscribe to the CIEL dictionary via the OpenConceptLab module in the OpenMRS admin panel. The
  dictionary itself can be found [here](https://app.openconceptlab.org/#/orgs/CIEL/sources/CIEL/), and you will find
  instructions on how to subscribe to the dictionary [here](https://www.youtube.com/watch?v=9vBH7YEeBWs). This option is more time-consuming, but the 
  database will contain more Concepts, so saved suggestions will more often be coded-value than free-text.
* **Quick alternative:** Run the Playwright setup suite, which seeds the minimum required concepts directly into
the database:
  ```bash
    cd e2e
    npx playwright test --project=setup
  ```
  Consult `e2e/README.md` for Playwright setup.

## Installation and Setup

### 1. Prerequisites
* Java 8+ and Maven
* OpenMRS Core 2.x (Tested against Reference Application 2.12.0, this module relies on coreapps, uiframework,
  openconceptlab and appui modules)
* OpenConceptLab subscription to CIEL Concept Dictionary (Concepts `1284`, `160221` and `162169` are required). See 
details at [Quick start point 3](#4-load-the-required-concepts).

### 2. Download the ML Models
Follow the steps from [Quick start point 1](#1-download-the-ml-models) to download all data related to the models.

Once it's done, copy the `models` folder in your OpenMRS server (keep one copy at the project root for the E2E tests). 
The default location in the server from which the module retrieves the models is 
`{OPENMRS_APP_DATA_DIR}/icdhelper/models/`, but it can be changed with `icdhelper.modelDirectory` 
global property (see step 4)

### 3. Build the Module
Because the embedded ML models and vocabularies require significant amount of memory, you must increase Maven's 
allocated RAM before building and running the module.

**Option A: Permanent setup (recommended)**
Configure your operating system to always allocate enough memory to Maven:

* **Linux / macOS (Zsh or Bash):**
Add the export to your shell profile so it loads automatically on startup:
```bash
# For macOS/Zsh users:
echo 'export MAVEN_OPTS="-Xmx3072m -Xms512m -XX:+UseG1GC"' >> ~/.zshrc
source ~/.zshrc

# For Linux/Bash users:
echo 'export MAVEN_OPTS="-Xmx3072m -Xms512m -XX:+UseG1GC"' >> ~/.bashrc
source ~/.bashrc
```

* **Windows:**
Open Command Prompt or PowerShell as Administrator and run the setx command to permanently save the variable to 
your Windows user profile:
```dos
setx MAVEN_OPTS "-Xmx3072m -Xms512m -XX:+UseG1GC"
```
(Note: You will need to close and reopen your terminal for this to take effect)

**Option B: Temporary setup**
If you only want to allocate the memory for your current session, run the following before building:

* **Linux/macOS:** `export MAVEN_OPTS="-Xmx3072m -Xms512m -XX:+UseG1GC"`
* **Windows (CMD):** `set MAVEN_OPTS="-Xmx3072m -Xms512m -XX:+UseG1GC"`
* **Windows (PowerShell):** `$env:MAVEN_OPTS="-Xmx3072m -Xms512m -XX:+UseG1GC"`

**Run the Build:**

Once the memory limits are set, compile the module:
> **Note:** If you don't need to modify the source code, you can skip this command and download
> the pre-built `.omod` directly from the [latest GitHub Release](https://github.com/tinyhuman-png/openmrs-module-icdhelper/releases/latest).
```bash
mvn clean install
```

### 4. Deploy
There are two ways to install the module, depending on your situation:

**Option A: Server is not yet running (file system)**
Copy the generated OMOD directly into the OpenMRS modules directory before starting the server:
```bash
cp omod/target/icdhelper-1.0.0-SNAPSHOT.omod your_sever_path/modules/
```
Then start OpenMRS. The module will be picked up automatically on startup.

**Option B: Server is already running (Admin UI)**
In the OpenMRS Administration panel (**System Administration** -> **Advanced Administration**), go to 
**Manage Modules** then **Add or Upgrade Module** and upload `omod/target/icdhelper-1.0.0-SNAPSHOT.omod`. 
OpenMRS will load the module without a full server restart.

**After the module is loaded (both options):**
By default, the module looks for models files at `{OPENMRS_APP_DATA_DIR}/icdhelper/models/`. If your models are elsewhere, 
go to **System Administration** then **Manage Global Properties** and set
`icdhelper.modelDirectory` to the correct absolute path. Then either restart OpenMRS or reload the module from 
Manage Modules for the change to take effect.

## Known limitations
* For now, the models are only able to understand English language. 
* As the custom Hierarchical BERT was trained on the MIMIC-IV dataset, it performs well given MIMIC-style 
discharge reports, but struggles more when the format is very different.
* The module match quality is directly dependent on the local Concept dictionary, and if CIEL is used, it often happens 
that some reference terms that could have been matched don't appear because they are retired.

You are always free to replace the models by your owns if they fit your usage better!
