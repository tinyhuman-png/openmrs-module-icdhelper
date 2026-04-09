import {Page, Locator, APIRequestContext} from '@playwright/test';

const BASE_URL = process.env.E2E_BASE_URL ?? 'http://localhost:8080/openmrs';
const AUTH = 'Basic ' + Buffer.from('admin:Admin123').toString('base64');
const HEADERS = {
    'Authorization': AUTH,
    'Content-Type': 'application/json'
};

export class ICDHelperPage {
    readonly page: Page;
    readonly clinicalNoteDisplay: Locator;
    readonly predictFullButton: Locator;
    readonly predictSelectionButton: Locator;
    readonly saveButton: Locator;
    readonly errorMessage: Locator;
    readonly successMessage: Locator;
    readonly resultTable: Locator;
    readonly resultRows: Locator;
    readonly visitNoteSelect: Locator;

    constructor(page: Page) {
        this.page = page;
        this.clinicalNoteDisplay = page.locator('#clinicalNoteDisplay');
        this.predictFullButton = page.getByRole('button', { name: 'Analyze full note' });
        this.predictSelectionButton = page.getByRole('button', { name: 'Predict for selection' });
        this.saveButton = page.getByRole('button', { name: 'Save selected suggestions' });
        this.errorMessage = page.locator('.error');
        this.successMessage = page.locator('#success-alert');
        this.resultTable = page.locator('#results-table');
        this.resultRows = this.resultTable.locator('tbody tr');
        this.visitNoteSelect = page.locator('[name="selectedNoteUuid"]');
    }

    async navigate(patientId: string, visitId: string) {
        await this.page.goto(
            `icdhelper/icdhelper.page?patientId=${patientId}&visitId=${visitId}`
        );
    }

    async selectNote(noteUuid: string) {
        await this.page.locator(`[data-note-uuid="${noteUuid}"]`).click();
    }

    async predictFull(noteUuid: string) {
        await this.selectNote(noteUuid);
        await this.predictFullButton.click();
        await this.resultTable.waitFor({ timeout: 30_000 });
    }

    async selectTextInNote(textToSelect: string) {
        await this.page.evaluate((text) => {
            const element = document.querySelector('#clinicalNoteDisplay');
            if (!element?.firstChild) return;

            const content = element.firstChild.textContent ?? '';
            const start = content.indexOf(text);
            if (start === -1) return;

            const range = document.createRange();
            range.setStart(element.firstChild, start);
            range.setEnd(element.firstChild, start + text.length);

            const sel = window.getSelection();
            sel?.removeAllRanges();
            sel?.addRange(range);
        }, textToSelect);
    }

    async predictSelection(noteUuid: string, textToSelect: string) {
        await this.selectNote(noteUuid);
        await this.selectTextInNote(textToSelect);
        await this.predictSelectionButton.click();
        await this.resultTable.waitFor({ timeout: 30_000 });
    }

    async selectResultByMappingType(mappingType: '<' | '>' | '=' | 'none') {
        let row;

        if (mappingType === 'none') {
            row = this.resultRows.filter({
                hasNot: this.page.locator('span.match-badge')
            }).first();
        } else {
            row = this.resultRows.filter({
                has: this.page.locator('span.match-badge', { hasText: mappingType })
            }).first();
        }

        await row.locator('td').first().click();
        return row;
    }

    async save() {
        await this.saveButton.click({ force: true });
        await this.successMessage.waitFor({ timeout: 10_000 });
    }

    async getResultCount(): Promise<number> {
        return await this.resultRows.count();
    }

    async getResultConcepts(): Promise<string[]> {
        return await this.resultRows.locator('td:nth-child(3)').allTextContents();
    }

    async getObsCountForPatient(request: APIRequestContext, patientUuid: string,
        conceptUuid: string): Promise<number> {
        const response = await request.get(
            `${BASE_URL}/ws/rest/v1/obs?patient=${patientUuid}&concept=${conceptUuid}&v=default`,
            { headers: HEADERS }
        );
        const data = await response.json();
        return data.results.length;
    }
}