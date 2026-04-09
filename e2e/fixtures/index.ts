import { test as base, request as apiRequest } from '@playwright/test';

interface CreatedPatient {
    uuid: string;
    display: string;
}

interface CreatedVisit {
    uuid: string;
    display: string;
}

interface CreatedEncounter {
    uuid: string;
}

type Fixtures = {
    patient: CreatedPatient;
    visit: CreatedVisit;
    visitNoteObs: {uuid:string};
    secondVisitNoteObs: {uuid:string},
};

const BASE_URL = process.env.E2E_BASE_URL ?? 'http://localhost:8080/openmrs';
const AUTH = 'Basic ' + Buffer.from('admin:Admin123').toString('base64');
const HEADERS = {
    'Authorization': AUTH,
    'Content-Type': 'application/json'
};

export const test = base.extend<Fixtures>({
    patient: async ({ request }, use) => {
        // Create patient via OpenMRS REST API
        const response = await request.post(`${BASE_URL}/ws/rest/v1/patient`, {
            headers: HEADERS,
            data: {
                person: {
                    names: [{ givenName: 'TestPatient', familyName: 'ICDHelper' }],
                    gender: 'M',
                    birthdate: '2000-01-01',
                    addresses: [{ preferred: true, country: 'Unknown' }],
                },
                identifiers: [{
                    identifier: generateValidOpenMrsId(),
                    identifierType: '05a29f94-c0ed-11e2-94be-8c13b969e334',
                    location: 'aff27d58-a15c-49a6-9beb-d30dcfc0c66e',   // Rely on the standard RefApp/CIEL dataset
                    preferred: true,
                }],
            },
        });
        if (!response.ok()) {
            const errorText = await response.text();
            throw new Error(`Failed to create patient! Status: ${response.status()}, Response: ${errorText}`);
        }

        const patient: CreatedPatient = await response.json();
        if (!patient || !patient.uuid) {
            throw new Error(`Patient created, but UUID is missing. Response: ${JSON.stringify(patient)}`);
        }

        await use(patient);

        // Teardown: void the patient after the test
        await request.delete(`${BASE_URL}/ws/rest/v1/patient/${patient.uuid}`, {
            headers: HEADERS,
        });
    },

    visit: async ({ request, patient }, use) => {
        const response = await request.post(`${BASE_URL}/ws/rest/v1/visit`, {
            headers: HEADERS,
            data: {
                patient: patient.uuid,
                visitType: '7b0f5697-27e3-40c4-8bae-f4049abfb4ed',  // Rely on the standard RefApp/CIEL dataset
                startDatetime: new Date().toISOString(),
                location: 'aff27d58-a15c-49a6-9beb-d30dcfc0c66e',   // Rely on the standard RefApp/CIEL dataset
            },
        });
        const visit: CreatedVisit = await response.json();

        await use(visit);

        await request.delete(`${BASE_URL}/ws/rest/v1/visit/${visit.uuid}`, {
            headers: HEADERS,
        });
    },

    visitNoteObs: async ({ request, patient, visit }, use) => {
        // First create an encounter of type Visit Note
        const encounterResponse = await request.post(`${BASE_URL}/ws/rest/v1/encounter`, {
            headers: HEADERS,
            data: {
                patient: patient.uuid,
                visit: visit.uuid,
                encounterType: 'e22e39fd-7db2-45e7-80f1-60fa0d5a4378',  // Rely on the standard RefApp/CIEL dataset
                encounterDatetime: new Date().toISOString(),
                location: 'aff27d58-a15c-49a6-9beb-d30dcfc0c66e',   // Rely on the standard RefApp/CIEL dataset
            },
        });
        const encounter: CreatedEncounter = await encounterResponse.json();

        // Then create the visit note obs linked to that encounter
        const obsRes = await request.post(`${BASE_URL}/ws/rest/v1/obs`, {
            headers: HEADERS,
            data: {
                person: patient.uuid,
                concept: '162169AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA',    // Rely on the standard RefApp/CIEL dataset
                obsDatetime: new Date().toISOString(),
                encounter: encounter.uuid,
                value: 'Patient presents with acute symptoms for E2E testing.',
            },
        });
        const obs = await obsRes.json();

        await use(obs);
        // obs is voided automatically when encounter/visit is deleted
    },

    secondVisitNoteObs: async ({ request, patient, visit }, use) => {
        // First create an encounter of type Visit Note
        const encounterResponse = await request.post(`${BASE_URL}/ws/rest/v1/encounter`, {
            headers: HEADERS,
            data: {
                patient: patient.uuid,
                visit: visit.uuid,
                encounterType: 'e22e39fd-7db2-45e7-80f1-60fa0d5a4378',  // Rely on the standard RefApp/CIEL dataset
                encounterDatetime: new Date().toISOString(),
                location: 'aff27d58-a15c-49a6-9beb-d30dcfc0c66e',   // Rely on the standard RefApp/CIEL dataset
            },
        });
        const encounter: CreatedEncounter = await encounterResponse.json();

        // Then create the visit note obs linked to that encounter
        const obsRes = await request.post(`${BASE_URL}/ws/rest/v1/obs`, {
            headers: HEADERS,
            data: {
                person: patient.uuid,
                concept: '162169AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA',    // Rely on the standard RefApp/CIEL dataset
                obsDatetime: new Date().toISOString(),
                encounter: encounter.uuid,
                value: 'Patient presents with major depressive episode.',
            },
        });
        const obs = await obsRes.json();

        await use(obs);
        // obs is voided automatically when encounter/visit is deleted
    },
});

function generateValidOpenMrsId(): string {
    const chars = "0123456789ACDEFGHJKLMNPRTUVWXY";

    // Create a random 5-character base string
    let baseString = "";
    for(let i = 0; i < 5; i++) {
        baseString += chars.charAt(Math.floor(Math.random() * chars.length));
    }

    // Calculate the Luhn Mod-30 check digit
    let sum = 0;
    let factor = 2;
    for (let i = baseString.length - 1; i >= 0; i--) {
        let val = chars.indexOf(baseString.charAt(i));
        let addend = factor * val;
        factor = (factor === 2) ? 1 : 2;
        addend = Math.floor(addend / 30) + (addend % 30);
        sum += addend;
    }

    let remainder = sum % 30;
    let checkDigitIndex = (30 - remainder) % 30;

    // Return the base string plus the calculated check digit
    return baseString + chars.charAt(checkDigitIndex);
}

export { expect } from '@playwright/test';