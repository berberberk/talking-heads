import { buildRepeatScenarioState, calculateTrainingStats, classifyReportState, evidenceTargetId, isUserMessage, transcriptMessageDomId } from "./report-utils.js";

const REPORT_POLL_INTERVAL_MS = 1_500;
const MAX_REPORT_POLL_ATTEMPTS = 20;
const id = new URLSearchParams(location.search).get("sessionId");
const byId = (value) => document.getElementById(value);
const title = byId("report-title");
const date = byId("report-date");
const loading = byId("report-loading");
const failure = byId("report-failure");
const content = byId("report-content");
const transcript = byId("transcript");
const transcriptDetails = byId("transcript-details");
const refreshButton = byId("report-refresh");
const retryButton = byId("retry-report");
let config;
let result;
let reportPollAttempts = 0;
let reportPollTimer = null;
let loadInFlight = false;

/** Безопасно создаёт текстовый DOM-элемент без интерполяции ответа модели. */
function textElement(tag, value, className = "") {
    const element = document.createElement(tag);
    element.textContent = value;
    if (className) element.className = className;
    return element;
}

/** Останавливает единственный отложенный опрос report state. */
function clearReportPoll() {
    if (reportPollTimer !== null) window.clearTimeout(reportPollTimer);
    reportPollTimer = null;
}

/** Показывает нейтральное ожидание, не подменяя его ошибкой evaluation. */
function showPending(titleText, message, canRefresh = false) {
    loading.hidden = false;
    failure.hidden = true;
    content.hidden = true;
    byId("report-loading-title").textContent = titleText;
    byId("report-loading-message").textContent = message;
    refreshButton.hidden = !canRefresh;
}

/** Показывает saved training с failed evaluation, не скрывая стенограмму. */
function showFailure(data) {
    clearReportPoll();
    loading.hidden = true;
    failure.hidden = false;
    content.hidden = true;
    retryButton.hidden = false;
    retryButton.disabled = false;
    byId("report-error").textContent = data.reportError || "Не удалось сформировать оценку. Попробуйте ещё раз.";
}

/** Отрисовывает стенограмму с раздельными id для USER и MODEL одной генерации. */
function showTranscript(messages) {
    transcript.replaceChildren(...messages.map((message) => {
        const user = isUserMessage(message);
        const row = document.createElement("article");
        row.className = `transcript__row transcript__row--${user ? "user" : "trainer"}`;
        row.id = transcriptMessageDomId(message);
        row.append(textElement("strong", user ? "Вы" : "AI-тренер"));
        row.append(textElement("p", message.text));
        if (message.interrupted) row.append(textElement("span", "Реплика прервана", "transcript__interrupted"));
        return row;
    }));
}

/** Открывает стенограмму и кратко подсвечивает конкретную реплику сотрудника. */
function revealEvidence(targetId) {
    const target = document.getElementById(targetId);
    if (!target) return;
    transcriptDetails.open = true;
    target.scrollIntoView({ behavior: "smooth", block: "center" });
    target.classList.add("is-highlighted");
    window.setTimeout(() => target.classList.remove("is-highlighted"), 2_000);
}

/** Создаёт ссылку на evidence только при настоящем numeric generation id. */
function evidenceLink(criterion) {
    const targetId = evidenceTargetId(criterion);
    if (!targetId) return null;
    const link = document.createElement("a");
    link.href = `#${targetId}`;
    link.textContent = "Показать ответ в стенограмме";
    link.addEventListener("click", (event) => {
        event.preventDefault();
        revealEvidence(targetId);
    });
    return link;
}

/** Показывает компактную статистику на основе реального wire contract ролей. */
function showStats(data) {
    const stats = calculateTrainingStats(data);
    const rows = [["Длительность", stats.duration], ["Реплик сотрудника", String(stats.userTurns)], ["Перебиваний", String(stats.interruptions)]];
    byId("training-stats").replaceChildren(...rows.flatMap(([label, value]) => [textElement("dt", label), textElement("dd", value)]));
}

/** Отрисовывает готовую валидированную оценку. */
function showReady(data) {
    clearReportPoll();
    const report = data.report;
    loading.hidden = true;
    failure.hidden = true;
    content.hidden = false;
    byId("overall-score").textContent = String(report.overallScore);
    byId("report-summary").textContent = report.summary;
    const criteria = byId("criteria-list");
    criteria.replaceChildren(...report.criteria.map((criterion) => {
        const card = document.createElement("article");
        card.className = "criterion-card";
        card.append(textElement("h3", `${criterion.name} — ${criterion.score}/5`), textElement("p", criterion.comment), textElement("blockquote", `«${criterion.evidence}»`));
        const link = evidenceLink(criterion);
        if (link) card.append(link);
        return card;
    }));
    byId("recommendations").replaceChildren(...report.recommendations.map((item) => textElement("li", item)));
    showStats(data);
}

/** Планирует один bounded poll; параллельных timers в report UI не бывает. */
function scheduleReportPoll() {
    if (reportPollAttempts >= MAX_REPORT_POLL_ATTEMPTS) {
        showPending("Отчёт ещё формируется", "Это занимает больше времени, чем обычно.", true);
        return;
    }
    reportPollAttempts += 1;
    clearReportPoll();
    reportPollTimer = window.setTimeout(() => void loadResult(), REPORT_POLL_INTERVAL_MS);
}

/** Применяет явное state machine result API к UI и polling. */
function renderResult(data) {
    const state = classifyReportState(data);
    if (state === "READY") return showReady(data);
    if (state === "FAILED") return showFailure(data);
    if (state === "ACTIVE") {
        clearReportPoll();
        retryButton.hidden = true;
        showPending("Тренировка ещё не завершена", "Вернитесь к тренировке и завершите её, чтобы получить оценку.");
        return;
    }
    if (state === "PENDING") {
        retryButton.hidden = true;
        showPending("✓ Тренировка завершена", "Формируем обратную связь по критериям сценария.");
        scheduleReportPoll();
        return;
    }
    clearReportPoll();
    retryButton.hidden = true;
    showPending("Не удалось определить состояние отчёта", "Проверьте статус ещё раз.", true);
}

/** Загружает safe result DTO и защищает страницу от дублирующихся fetch/poll loops. */
async function loadResult({ manual = false } = {}) {
    if (loadInFlight) return;
    if (manual) {
        clearReportPoll();
        reportPollAttempts = 0;
    }
    loadInFlight = true;
    try {
        const response = await fetch(`${config.chat_api_url}/api/sessions/${encodeURIComponent(id)}/result`);
        if (!response.ok) throw new Error(response.status === 404 ? "Тренировка не найдена" : "Не удалось обновить статус отчёта");
        result = await response.json();
        title.textContent = result.scenario?.title || "Свободная тренировка";
        date.textContent = result.finishedAt ? `Завершена: ${new Date(result.finishedAt).toLocaleString("ru-RU")}` : "Тренировка сохранена";
        showTranscript(result.messages || []);
        renderResult(result);
    } catch (error) {
        clearReportPoll();
        showPending("Не удалось обновить статус отчёта", error.message || "Проверьте соединение и попробуйте ещё раз.", true);
    } finally {
        loadInFlight = false;
    }
}

/** Запускает идемпотентный retry и всегда возвращает кнопку в корректное состояние. */
async function retryReport() {
    if (retryButton.disabled) return;
    retryButton.disabled = true;
    showPending("✓ Тренировка сохранена", "Повторно формируем обратную связь по критериям сценария.");
    try {
        const response = await fetch(`${config.chat_api_url}/api/sessions/${encodeURIComponent(id)}/report/retry`, { method: "POST" });
        if (!response.ok) throw new Error("Не удалось повторить формирование отчёта");
        reportPollAttempts = 0;
        await loadResult();
    } catch (error) {
        showFailure({ reportError: error.message || "Не удалось повторить формирование отчёта." });
    } finally {
        if (failure.hidden === false) retryButton.disabled = false;
    }
}

/** Повторяет только безопасно восстановимый preset либо начинает чистую free training. */
function repeatScenario() {
    const repeat = buildRepeatScenarioState(result);
    sessionStorage.removeItem("talking-heads.scenario-selection");
    sessionStorage.removeItem("talking-heads.scenario-display");
    if (!repeat.canRepeatDirectly) {
        location.assign("/");
        return;
    }
    if (repeat.selection) sessionStorage.setItem("talking-heads.scenario-selection", JSON.stringify(repeat.selection));
    if (repeat.display) sessionStorage.setItem("talking-heads.scenario-display", JSON.stringify(repeat.display));
    location.assign("/training.html");
}

retryButton.addEventListener("click", () => void retryReport());
refreshButton.addEventListener("click", () => void loadResult({ manual: true }));
byId("repeat-scenario").addEventListener("click", repeatScenario);
byId("save-report").addEventListener("click", () => window.print());
window.addEventListener("pagehide", clearReportPoll);

async function initialize() {
    try {
        if (!id) throw new Error("Не указан идентификатор тренировки");
        const configResponse = await fetch("/api/config");
        if (!configResponse.ok) throw new Error("Не удалось загрузить конфигурацию приложения");
        config = await configResponse.json();
        await loadResult();
    } catch (error) {
        showPending("Не удалось открыть результат", error.message || "Проверьте соединение и попробуйте ещё раз.", true);
        retryButton.hidden = true;
    }
}

void initialize();
