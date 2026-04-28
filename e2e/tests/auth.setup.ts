import { test as setup, expect } from '@playwright/test';

const authFile = '.auth/user.json';

setup('authenticate and seed database', async ({ page, request }) => {
    console.log('Seeding required CIEL concepts...');
    const authHeader = 'Basic ' + Buffer.from('admin:Admin123').toString('base64');
    const requiredConcepts = [
        { uuid: '1284AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA', name: 'Diagnosis Coded' },
        { uuid: '161602AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA', name: 'Diagnosis Non-coded' },
        { uuid: '162169AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA', name: 'Visit Note' }
    ];

    for (const concept of requiredConcepts) {
        const checkResponse = await request.get(`ws/rest/v1/concept/${concept.uuid}`, {
            headers: { 'Authorization': authHeader }
        });

        if (!checkResponse.ok()) {
            console.log(`Injecting missing concept: ${concept.name}...`);
            const createResponse = await request.post('ws/rest/v1/concept', {
                headers: {
                    'Authorization': authHeader,
                    'Content-Type': 'application/json'
                },
                data: {
                    uuid: concept.uuid,
                    datatype: '8d4a4c94-c2cc-11de-8d13-0010c6dffd0f', // Generic "N/A" datatype
                    conceptClass: '8d4918b0-c2cc-11de-8d13-0010c6dffd0f', // Generic "Misc" class
                    names: [
                        {
                            name: concept.name,
                            locale: 'en',
                            localePreferred: true,
                            conceptNameType: 'FULLY_SPECIFIED'
                        }
                    ]
                }
            });
            expect(createResponse.ok()).toBeTruthy();
        } else {
            console.log(`Concept already exists: ${concept.name}`);
        }
    }
    console.log('Concepts seeded successfully!');

    await page.goto('login.htm');
    console.log('PLAYWRIGHT NAVIGATED TO:', page.url());
    await page.fill('#username', 'admin');
    await page.fill('#password', 'Admin123');
    await page.click("#Pharmacy")
    await page.click('#loginButton');

    // Wait for the final URL to ensure that the cookies are actually set.
    await page.waitForURL('**/home.page');
    await page.context().storageState({ path: authFile });
});