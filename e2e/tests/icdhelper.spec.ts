import { test, expect } from '../fixtures';
import { ICDHelperPage } from '../pages/ICDHelperPage';

test.describe('ICDHelper module', () => {
    let icdPage: ICDHelperPage;

    test.beforeAll(async ({ request }) => {
        await request.post('/ws/rest/v1/systemsetting', {
            data: {
                property: 'icdhelper.modelPath',
                value: '/openmrs/icdhelper/models',
            },
            headers: {
                'Authorization': 'Basic ' + Buffer.from('admin:Admin123').toString('base64'),
                'Content-Type': 'application/json'
            },
        });
    });

    test.beforeEach(async ({ page }) => {
        icdPage = new ICDHelperPage(page);
    });

    test('should display empty state on GET', async ({ page, patient, visit }) => {
        await page.waitForTimeout(200);
        await icdPage.navigate(patient.uuid, visit.uuid);

        await expect(icdPage.resultTable).toHaveCount(0);
        await expect(icdPage.errorMessage).not.toBeVisible();
        await expect(icdPage.successMessage).not.toBeVisible();
    });

    test('full note predict and save workflow', async ({ page, request, patient, visit, visitNoteObs }) => {
        await icdPage.navigate(patient.uuid, visit.uuid);
        await icdPage.predictFull(visitNoteObs.uuid);

        // Structural assertions
        await expect(icdPage.resultRows).toHaveCount(20);
        const allCells = icdPage.resultRows.locator('td:not(:first-child)');
        const areAllCellsPopulated = await allCells.evaluateAll((cells) =>
            cells.every(cell => {
                const text = cell.textContent?.trim();
                return text !== undefined && text !== null && text.length > 0 && text !== 'null';
            })
        );
        expect(areAllCellsPopulated).toBeTruthy();

        // Select one SAME-AS and one NO MAPPING result
        await icdPage.selectResultByMappingType('=');
        await icdPage.selectResultByMappingType('none');
        await icdPage.save();

        await expect(icdPage.successMessage).toBeVisible();
        await expect(icdPage.successMessage).toHaveText("Diagnoses successfully saved to the patient record.")
        await expect(icdPage.errorMessage).not.toBeVisible();

        // Verify persistence via REST API
        const codedCount = await icdPage.getObsCountForPatient(
            request, patient.uuid,
            '1284AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'
        );
        const nonCodedCount = await icdPage.getObsCountForPatient(
            request, patient.uuid,
            '161602AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'
        );
        expect(codedCount).toBeGreaterThanOrEqual(1);
        expect(nonCodedCount).toBeGreaterThanOrEqual(1);
    });

    test('selection mode predict and save workflow', async ({ page, request, patient, visit, visitNoteObs }) => {
        await icdPage.navigate(patient.uuid, visit.uuid);
        await icdPage.predictSelection(visitNoteObs.uuid, 'acute symptoms');

        await expect(icdPage.resultRows.first()).toBeVisible();

        // Structural assertions
        await expect(icdPage.resultRows).toHaveCount(20);
        const allCells = icdPage.resultRows.locator('td:not(:first-child)');
        const areAllCellsPopulated = await allCells.evaluateAll((cells) =>
            cells.every(cell => {
                const text = cell.textContent?.trim();
                return text !== undefined && text !== null && text.length > 0 && text !== 'null';
            })
        );
        expect(areAllCellsPopulated).toBeTruthy();

        // Select one SAME-AS and one NO MAPPING result
        await icdPage.selectResultByMappingType('=');
        await icdPage.selectResultByMappingType('none');
        await icdPage.save();

        await expect(icdPage.successMessage).toBeVisible();
        await expect(icdPage.successMessage).toHaveText("Diagnoses successfully saved to the patient record.")
        await expect(icdPage.errorMessage).not.toBeVisible();

        // Verify persistence via REST API
        const codedCount = await icdPage.getObsCountForPatient(
            request, patient.uuid,
            '1284AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'
        );
        const nonCodedCount = await icdPage.getObsCountForPatient(
            request, patient.uuid,
            '161602AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'
        );
        expect(codedCount).toBeGreaterThanOrEqual(1);
        expect(nonCodedCount).toBeGreaterThanOrEqual(1);
    });

    test('saving is idempotent: no duplicate diagnoses on second save', async ({ page, request, patient, visit, visitNoteObs }) => {
        await icdPage.navigate(patient.uuid, visit.uuid);
        await icdPage.predictFull(visitNoteObs.uuid);
        await icdPage.selectResultByMappingType('=');
        await icdPage.save();

        const countAfterFirst = await icdPage.getObsCountForPatient(
            request, patient.uuid,
            '1284AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'
        );

        // Run the exact same flow again
        await icdPage.predictFull(visitNoteObs.uuid);
        await icdPage.selectResultByMappingType('=');
        await icdPage.save();

        const countAfterSecond = await icdPage.getObsCountForPatient(
            request, patient.uuid,
            '1284AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'
        );

        // Should not have created new obs
        expect(countAfterSecond).toBe(countAfterFirst);
    });

    test('note selection locks the displayed note after prediction', async ({ page, patient, visit, visitNoteObs }) => {
        await icdPage.navigate(patient.uuid, visit.uuid);
        await icdPage.selectNote(visitNoteObs.uuid);

        // Note should be displayed before prediction
        await expect(icdPage.clinicalNoteDisplay).toBeVisible();
        const noteTextBefore = await icdPage.clinicalNoteDisplay.textContent();

        await icdPage.predictFullButton.click();
        await icdPage.resultTable.waitFor({ timeout: 30_000 });

        // Note should remain displayed after prediction
        await expect(icdPage.clinicalNoteDisplay).toBeVisible();
        const noteTextAfter = await icdPage.clinicalNoteDisplay.textContent();
        expect(noteTextAfter?.trim()).toBe(noteTextBefore?.trim());
    });

    test('switching notes updates the displayed content', async ({ page, patient, visit, visitNoteObs, secondVisitNoteObs }) => {
        await icdPage.navigate(patient.uuid, visit.uuid);
        await icdPage.selectNote(visitNoteObs.uuid);
        const firstNoteText = await icdPage.clinicalNoteDisplay.textContent();

        await icdPage.selectNote(secondVisitNoteObs.uuid);
        const secondNoteText = await icdPage.clinicalNoteDisplay.textContent();

        expect(firstNoteText).not.toBe(secondNoteText);
    });

    test('should show error when predicting with no note selected', async ({ page, patient, visit }) => {
        await icdPage.navigate(patient.uuid, visit.uuid);

        page.once('dialog', async dialog => {
            expect(dialog.type()).toBe('alert');
            expect(dialog.message()).toBe('The note is empty. Please select a note first.');
            await dialog.dismiss();
        });

        await icdPage.predictFullButton.click();
        await expect(icdPage.resultTable).not.toBeVisible();
    });

    test('should show error when saving with nothing selected', async ({ page, patient, visit, visitNoteObs }) => {
        await icdPage.navigate(patient.uuid, visit.uuid);
        await icdPage.predictFull(visitNoteObs.uuid);

        page.once('dialog', async dialog => {
            expect(dialog.type()).toBe('alert');
            expect(dialog.message()).toBe('Please select at least one code to save.');
            await dialog.dismiss();
        });

        await icdPage.saveButton.click({ force: true });
        await expect(icdPage.successMessage).not.toBeVisible();
    });

    test('full note and selection mode produce different results for same note', async ({ page, patient, visit, visitNoteObs }) => {
        await icdPage.navigate(patient.uuid, visit.uuid);

        // Get full note results
        await icdPage.predictFull(visitNoteObs.uuid);
        const fullNoteCount = await icdPage.getResultCount();
        const fullNoteConcepts = await icdPage.getResultConcepts();

        // Get selection results on a substring
        await icdPage.predictSelection(visitNoteObs.uuid, 'acute symptoms');
        const selectionCount = await icdPage.getResultCount();
        const selectionConcepts = await icdPage.getResultConcepts();

        // Results should differ
        const areDifferent =
            JSON.stringify(fullNoteConcepts) !== JSON.stringify(selectionConcepts);
        expect(areDifferent).toBeTruthy();
    });
});