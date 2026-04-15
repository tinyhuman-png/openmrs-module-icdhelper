<%
    ui.decorateWith("appui", "standardEmrPage")
%>

<style>
    .note-scroll-container { max-height: 150px; overflow-y: auto; border: 1px solid #eee; border-radius: 4px;
        margin-bottom: 15px; }
    .note-row { padding: 8px; border-bottom: 1px solid #f9f9f9; cursor: pointer; display: flex; justify-content:
        space-between; align-items: center; }
    .note-row:hover { background-color: #f0f7f6; }
    .note-row { position: relative; }
    .result-row:hover { background-color: #f0f7f6; }
    .result-row.selected-result { background-color: #e8f0fe !important; }
    .result-row input[type="checkbox"] { width: 16px; height: 16px; cursor: pointer; display: block; margin: auto; margin-top: 4px; }
    .selected-note { background-color: #e8f0fe !important; border-left: 4px solid #00463f; }
    .check-icon { display: none; color: #00463f; margin-left: 10px; }
    .selected-note .check-icon { display: inline; }
    .status-badge { font-size: 0.75em; padding: 4px 10px; border-radius: 12px; font-weight: bold; white-space: nowrap; }
    .match-badge { display: inline-block; font-size: 0.7em; font-weight: bold; padding: 2px 7px; border-radius: 10px;
        margin-left: 6px; vertical-align: middle; cursor: default; letter-spacing: 0.03em; }
    .match-badge.exact   { background: #d4edda; color: #155724; }
    .match-badge.narrower { background: #fff3cd; color: #856404; }
    .match-badge.broader  { background: #f8d7da; color: #721c24; }
    .match-badge.nomatch  { background: #e2e3e5; color: #383d41; }
    .match-badge:hover { opacity: 0.85; text-decoration: underline dotted; }
    .conf-high   { color: #155724; font-weight: bold; }
    .conf-medium { color: #856404; }
    .conf-low    { color: #721c24; }
</style>

<script type="text/javascript">
    var jq = jQuery;
    var breadcrumbs = [
        { icon: "icon-home", link: '/' + OPENMRS_CONTEXT_PATH + '/index.htm' },
        { label: "${ ui.encodeJavaScript(ui.format(patient)) }",
            link: '${ ui.pageLink("coreapps", "clinicianfacing/patient", [patientId: patient.uuid]) }'},
        { label: "ICD Helper" }
    ]

    function selectNote(element) {
        jq('.note-row').removeClass('selected-note'); // Deselect others
        jq(element).addClass('selected-note');        // Select current

        var text = jq(element).data('note-text');
        var uuid = jq(element).data('note-uuid');

        jq('#clinicalNoteDisplay').text(text);
        jq('#selectedNoteUuid').val(uuid);
    }

    jq(document).ready(function() {
        jq('.note-row').on('click', function() {
            selectNote(this);
        });
        jq('.match-badge').tooltip();
    });

    function submitAnalysis(type) {
        var textArea = document.getElementById("clinicalNoteDisplay");
        var hiddenInput = document.getElementById("textToAnalyze");

        var finalSelection = "";
        var fullText = "";

        var placeholder = jq(textArea).find('.placeholder-text');
        if (placeholder.length > 0) {
            finalSelection = "";
        } else {
            fullText = textArea.innerText.trim();
            if (type === 'selection') {
                var selection = window.getSelection();
                finalSelection = selection.toString().trim();

                if (finalSelection.trim().length < 3) {
                    alert("Please highlight at least a few characters to analyze.");
                    return;
                }
            } else {
                finalSelection = fullText;
            }
        }

        if (!finalSelection) {
            alert("The note is empty. Please select a note first.");
            return;
        }

        hiddenInput.value = finalSelection;

        jq('#lockedNoteUuid').val(jq('#selectedNoteUuid').val());
        jq('#action').val('predict');
        jq('#analysisMode').val(type);
        jq('#icdForm').submit();
    }

    jq('#clinicalNoteDisplay').on('mouseup keyup', function() {
        var textArea = document.getElementById("clinicalNoteDisplay");
        var start = textArea.selectionStart;
        var finish = textArea.selectionEnd;
        var selection = textArea.value.substring(start, finish);
        var feedbackDiv = jq('#selectionFeedback');

        if (selection.trim().length > 1000) {
            jq('#feedbackText').text("Selection is too long. Using the Full Note model for better context.");
            feedbackDiv.show();
        } else {
            feedbackDiv.hide();
        }
    });

    function toggleResultSelection(rowElement) {
        var row = jq(rowElement);
        var checkbox = row.find('input[name="selectedResults"]');

        // Toggle the selected class
        row.toggleClass('selected-result');

        // Sync the hidden checkbox state
        var isChecked = row.hasClass('selected-result');
        checkbox.prop('checked', isChecked);
    }
    function handleCheckboxClick(event, checkboxElement) {
    // Empêche le clic de se propager à la ligne (pour éviter un double toggle)
    event.stopPropagation();
    
    var checkbox = jq(checkboxElement);
    var row = checkbox.closest('.result-row');
    
    // On synchronise la classe de la ligne avec l'état de la checkbox
    if (checkbox.is(':checked')) {
        row.addClass('selected-result');
    } else {
        row.removeClass('selected-result');
    }
}

    function saveSelected() {
        var selectedValues = jq("input[name='selectedResults']:checked")

        if (selectedValues.length === 0) {
            alert("Please select at least one code to save.");
            return;
        }

        jq('#action').val('save');

        // Clear any previous hidden clones to prevent duplicates if the user clicks twice
        jq('#icdForm .temp-save-input').remove();

        selectedValues.each(function() {
            var val = jq(this).val();
            jq('#icdForm').append(
                jq('<input type="hidden" name="selectedResults" class="temp-save-input">').val(val)
            );
        });
        jq('#icdForm').submit();
    }
</script>

<form id="icdForm" method="post" action="icdhelper.page?patientId=${patient.uuid}&visitId=${visit.uuid}">
    <input type="hidden" id="textToAnalyze" name="clinicalNote" value="">
    <input type="hidden" id="selectedNoteUuid" name="selectedNoteUuid" value="${selectedNoteUuid ?: ''}">
    <input type="hidden" id="analysisMode" name="analysisMode" value="full">
    <input type="hidden" id="action" name="action" value="">
    <input type="hidden" id="lockedNoteUuid" name="lockedNoteUuid" value="${lockedNoteUuid ?: ''}">

    <h2>Intelligent ICD Coding Helper (ICD-10-CM)</h2>

    <div class="info-section">
        <div class="info-header" onclick="jq('#picker-body').slideToggle()" style="cursor: pointer;">
            <h3><i class="icon-list-ul"></i> Select a visit note</h3>
        </div>
        <div id="picker-body" class="info-body">
        <% if (binding.variables.containsKey('visitNotes') && visitNotes) { %>
            <div class="note-scroll-container">
                <% visitNotes.each { note -> %>
                    <div class="note-row ${selectedNoteUuid == note.uuid ? 'selected-note' : ''}"
                         data-note-text="${ ui.escapeHtml(note.valueText).replace('"', '&quot;').replace("'", "&#39;") }"
                         data-note-uuid="${ note.uuid }">
                        <span>${ ui.format(note.obsDatetime) } - ${ ui.escapeHtml(note.valueText.take(50)) }...</span>
                    </div>
                <% } %>
            </div>
        <% } else { %>
            <p>No previous notes found for this visit.</p>
        <% } %>
        </div>
    </div>

    <label><strong>Clinical note:</strong></label>
    <div id="clinicalNoteDisplay"
        style="min-height: 150px; height: 200px; overflow-y: auto; border: 1px solid #ddd;
            padding: 10px; background-color: #f9f9f9; white-space: pre-wrap; text-align: left;
            vertical-align: top; display: block; box-sizing: border-box;"
        ><%
        if (binding.variables.containsKey('fullNote') && fullNote) {
        %>${ ui.escapeHtml(fullNote) }<%
        } else {
        %><span class="placeholder-text" style="color: #999; font-style: italic;">
            Select a visit note from the list above to begin analysis...
        </span><% } %>
    </div>

    <div id="selectionFeedback" style="display: none; padding: 10px; margin-top: 5px;
         background-color: #fff3cd; border: 1px solid #ffeeba; color: #856404; border-radius: 4px;">
        <i class="icon-info-sign"></i><span id="feedbackText"></span>
    </div>
    <div style="margin-top: 15px;">
        <button type="button" class="confirm" onclick="submitAnalysis('full')">
            Analyze full note
        </button>

        <button type="button" class="button" onclick="submitAnalysis('selection')">
            Predict for selection
        </button>
    </div>
</form>

<% if (binding.variables.containsKey('successMessage') && successMessage) { %>
    <div id="success-alert" class="note-container" style="margin-top: 15px; background-color: #d4edda; color: #155724; padding: 10px; border: 1px solid #c3e6cb; border-radius: 4px; margin-bottom: 15px;">
        <i class="icon-ok"></i> ${ successMessage }
    </div>
<% } %>

<% if (binding.variables.containsKey('icdResults') && icdResults) { %>
    <div class="info-section" style="margin-top: 30px;">
        <div class="info-header">
            <h3>Suggested codes and concepts</h3>
        </div>

        <div class="info-body">
            <table id="results-table">
                <thead>
                    <tr>
                        <th style="width: 30px;"></th>
                        <th>ICD-10-CM Code</th>
                        <th>ICD-10-CM Description</th>
                        <th>Concept Name</th>
                        <th>Confidence</th>
                    </tr>
                </thead>
                <tbody>
                    <% icdResults.each { result -> %>
                        <tr class="result-row" onclick="toggleResultSelection(this)" style="cursor: pointer; border-bottom: 1px solid #eee;">
                            <td style="text-align: center; vertical-align: middle;">
                                <input type="checkbox" name="selectedResults"
                                value="${result.concept ? (result.concept.uuid + '|' + result.icdCode ) : ('RAW:' + result.icdCode + ': ' + result.description)}"
                                onclick="handleCheckboxClick(event, this)"> 
                            </td>
                            <td><strong>${ result.icdCode }</strong></td>
                            <td>${ result.description }</td>
                            <td>
                                <% if (result.mappingType == 'NO MAPPING') { %>
                                    <span style="color: #999; font-size: 0.9em;">Not found in dictionary</span>
                                <% } else { %>
                                    ${ ui.format(result.concept) }
                                <% } %>

                                <% if (result.mappingType == 'SAME-AS') { %>
                                        <span class="match-badge exact" title="Exact match: this concept maps directly to the ICD-10-CM code">
                                            =
                                        </span>
                                    <% } else if (result.mappingType == 'NARROWER-THAN') { %>
                                        <span class="match-badge narrower" title="Narrower: this concept is more specific than the ICD-10-CM code, there may be additional clinical detail">
                                            <
                                        </span>
                                    <% } else if (result.mappingType == 'BROADER-THAN') { %>
                                        <span class="match-badge broader" title="Broader: this concept is more general than the ICD-10-CM code, some clinical detail may be lost">
                                            >
                                        </span>
                                    <% } %>
                            </td>
                            <%
                                def confClass = "conf-low"
                                if (result.confidence != null) {
                                    if (result.confidence >= 0.75) confClass = "conf-high"
                                    else if (result.confidence >= 0.50) confClass = "conf-medium"
                                }
                            %>
                            <td class="${ confClass }">
                                <% if (result.confidence != null) { %>
                                    ${ String.format("%.2f", result.confidence) }
                                <% } else { %>
                                    <span style="color: #999;">N/A</span>
                                <% } %>
                            </td>
                        </tr>
                    <% } %>
                </tbody>
            </table>
        </div>
        <div style="margin-top: 20px; border-top: 1px solid #eee; padding-top: 15px;">
            <button type="button" class="confirm" onclick="saveSelected()">
                Save selected suggestions
            </button>
        </div>
    </div>
<% } %>

<% if (binding.variables.containsKey('error') && error) { %>
    <div id="error-alert" class="note-container" style="margin-top: 15px; background-color: #f8d7da; color: #721c24; padding: 10px; border: 1px solid #f5c6cb; border-radius: 4px; margin-bottom: 15px;">
        <i class="icon-remove-sign"></i> ${ error }
    </div>
<% } %>
