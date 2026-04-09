# ICDHelper End-to-End (E2E) Test Suite

This directory contains the automated UI tests for the ICD Helper module, built with 
[Playwright](https://playwright.dev/) and TypeScript.

## Architecture & Test philosophy
To prevent test flakiness and database state collisions, this suite utilizes a highly isolated testing architecture:
* **Unique state per test:** The custom Playwright fixtures use the OpenMRS REST API to generate brand new, 
unique Patient, Visit, and Encounter for *every single test*, along with associated Observations.
* **Auto-teardown:** Once a test completes, the fixture automatically purges the generated patient data from 
the database, ensuring an unaltered environment for the next test.
* **Authentication bypass:** A dedicated global `setup.ts` script handles the OpenMRS login once, 
saving the session cookies to `storageState`. All subsequent tests reuse this state to skip the login screen and 
reduce test execution time.

## Prerequisites
* [Node.js](https://nodejs.org/) (v16+)
* Docker & Docker Compose
* The compiled `.omod` file and downloaded ML models (see step 1)

## Running the Tests Locally

1. **Build the module and download models**
Before starting the backend, ensure the module is compiled and the ONNX models are present, 
as Docker will mount them directly into the container. Run these from the root directory:
```bash
mvn clean install
./download_models.sh
````

2. **Start the OpenMRS backend**
Turn up the OpenMRS server and MySQL database using the provided Docker configuration.
```bash
docker-compose up -d
```

Wait for Tomcat to fully boot and the OpenMRS login screen to become available at `http://localhost:8080/openmrs/` 
before proceeding. (Note: this can take quite some time)

3. **Install dependencies**
```bash
npm ci
npx playwright install --with-deps
```

3. **Execute the Suite**
Run the tests in headless mode (default):
```bash
npx playwright test
```
Or run them with the Playwright UI for debugging:
```bash
npx playwright test --ui
```

### Continuous Integration 

The tests on Chromium are automatically executed on every push or pull request to the main branch via GitHub Actions. The CI pipeline builds 
the Java module, downloads the required ONNX models, spins up the Docker environment, and executes the tests.
The test reports are uploaded as artifacts, and stay available for 5 days.
