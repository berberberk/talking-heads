const SELECTION_KEY = "talking-heads.scenario-selection";
const DISPLAY_KEY = "talking-heads.scenario-display";
const MAX_SCENARIO_BYTES = 32 * 1024;
const preset = document.getElementById("scenario-preset");
const file = document.getElementById("scenario-file");
const fileName = document.getElementById("scenario-file-name");
const status = document.getElementById("scenario-status");
const preview = document.getElementById("scenario-preview");
const start = document.getElementById("start-training");
let chatApiUrl = null;
let selectedScenario = null;
let catalog = new Map();
let fileValidationPending = false;

sessionStorage.removeItem(SELECTION_KEY);
sessionStorage.removeItem(DISPLAY_KEY);
const setStatus = (message, state = "") => { status.textContent = message; status.dataset.state = state; };
const persistSelection = () => selectedScenario ? sessionStorage.setItem(SELECTION_KEY, JSON.stringify(selectedScenario)) : sessionStorage.removeItem(SELECTION_KEY);
async function loadConfig() { const response = await fetch("/api/config"); if (!response.ok) throw new Error("Не удалось получить конфигурацию приложения"); return response.json(); }
/** Показывает только title, число этапов и criteria — внутренние инструкции не попадают в DOM. */
function showPreview(data) {
    if (!data) { preview.hidden = true; preview.replaceChildren(); sessionStorage.removeItem(DISPLAY_KEY); return; }
    const title = document.createElement("strong"); title.textContent = data.title;
    const meta = document.createElement("p"); meta.textContent = `${data.stages.length} этапа(ов) • ${data.criteria.length} критерия оценки`;
    const chips = document.createElement("div"); chips.className = "scenario-chips";
    data.criteria.forEach((criterion) => { const chip = document.createElement("span"); chip.textContent = criterion; chips.append(chip); });
    preview.replaceChildren(title, meta, chips); preview.hidden = false;
    sessionStorage.setItem(DISPLAY_KEY, JSON.stringify({ title: data.title, criteria: data.criteria, stageCount: data.stages.length }));
}
async function loadPresets() {
    const response = await fetch(`${chatApiUrl}/api/scenarios`); if (!response.ok) throw new Error("Не удалось загрузить готовые сценарии");
    for (const scenario of await response.json()) { catalog.set(scenario.id, scenario); const option = document.createElement("option"); option.value = scenario.id; option.textContent = scenario.title; preset.append(option); }
    preset.disabled = false;
}
function selectPreset() {
    file.value = ""; fileName.textContent = "Markdown-файл до 32 КБ";
    selectedScenario = preset.value ? { presetId: preset.value } : null;
    persistSelection(); showPreview(preset.value ? catalog.get(preset.value) : null);
    setStatus(selectedScenario ? "Сценарий будет закреплён после первой реплики." : "Можно начать свободный диалог без сценария.", "success");
}
async function selectFile() {
    const selectedFile = file.files?.[0]; if (!selectedFile) return;
    if (!selectedFile.name.toLowerCase().endsWith(".md")) { file.value = ""; setStatus("Поддерживается только Markdown-файл с расширением .md.", "error"); return; }
    if (selectedFile.size > MAX_SCENARIO_BYTES) { file.value = ""; setStatus("Размер сценария не должен превышать 32 КБ.", "error"); return; }
    try {
        fileValidationPending = true; setStatus("Проверяем сценарий…"); const markdown = await selectedFile.text();
        const response = await fetch(`${chatApiUrl}/api/scenarios/validate`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ markdown }) });
        const payload = await response.json().catch(() => ({})); if (!response.ok) throw new Error(payload.error || "Сценарий не прошёл проверку");
        selectedScenario = { markdown }; preset.value = ""; fileName.textContent = `${selectedFile.name}: сценарий проверен`; persistSelection(); showPreview(payload);
        setStatus(`Готово: ${payload.stages?.length ?? 0} этапов и ${payload.criteria?.length ?? 0} критерия.`, "success");
    } catch (error) { selectedScenario = null; file.value = ""; persistSelection(); showPreview(null); setStatus(error.message, "error"); }
    finally { fileValidationPending = false; }
}
preset.addEventListener("change", selectPreset); file.addEventListener("change", selectFile);
start.addEventListener("click", (event) => { if (fileValidationPending) { event.preventDefault(); setStatus("Дождитесь проверки Markdown-файла.", "error"); } });
try { const config = await loadConfig(); chatApiUrl = config.chat_api_url; await loadPresets(); } catch (error) { file.disabled = true; setStatus(`${error.message}. Свободный диалог всё ещё доступен.`, "error"); }
