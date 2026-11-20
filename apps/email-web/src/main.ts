import "./style.css";

const confirmButton = document.querySelector<HTMLButtonElement>("#confirm");
const status = document.querySelector<HTMLParagraphElement>("#status");
const apiOrigin = __NEOCHAT_API_ORIGIN__;
const params = new URLSearchParams(window.location.search);
const token = params.get("t") ?? "";

if (params.has("t")) {
  window.history.replaceState(null, "", window.location.pathname);
}

function showStatus(message: string, kind: "info" | "error" | "success") {
  if (!status) return;
  status.textContent = message;
  status.dataset.kind = kind;
}

function failLink() {
  showStatus("Ссылка повреждена или истекла. Вернитесь в NeoChat и запросите новое письмо.", "error");
  if (confirmButton) confirmButton.hidden = true;
}

if (!confirmButton || !status) {
  // The static shell should always contain both elements.
} else if (!/^[A-Za-z0-9_-]{43}$/.test(token)) {
  failLink();
} else if (new URL(apiOrigin).hostname.endsWith(".example")) {
  showStatus("Страница ещё не подключена к серверу NeoChat.", "error");
  confirmButton.disabled = true;
}

confirmButton?.addEventListener("click", async () => {
  if (!token || confirmButton.disabled) return;
  confirmButton.disabled = true;
  confirmButton.textContent = "Подтверждаем…";
  showStatus("Проверяем ссылку…", "info");

  try {
    const response = await fetch(`${apiOrigin}/v1/accounts/email/confirm`, {
      method: "POST",
      mode: "cors",
      credentials: "omit",
      cache: "no-store",
      redirect: "error",
      referrerPolicy: "no-referrer",
      headers: { "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8" },
      body: new URLSearchParams({ t: token }).toString()
    });

    if (!response.ok) {
      failLink();
      return;
    }

    confirmButton.hidden = true;
    showStatus("Почта подтверждена. Можно вернуться в NeoChat.", "success");
  } catch {
    showStatus("Не удалось связаться с сервером. Проверьте подключение и попробуйте ещё раз.", "error");
    confirmButton.disabled = false;
    confirmButton.textContent = "Повторить подтверждение";
  }
});
