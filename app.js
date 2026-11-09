const form = document.querySelector("#contact-form");
const status = document.querySelector("#form-status");

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  status.textContent = "Sending your message…";
  form.querySelector("button[type=submit]").disabled = true;

  try {
    const response = await fetch(form.action, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8" },
      body: new URLSearchParams(new FormData(form)),
    });
    const result = await response.json();

    if (!response.ok) {
      throw new Error(result.message || "We couldn’t send your message. Please try again.");
    }

    form.reset();
    status.textContent = result.message;
  } catch (error) {
    status.textContent = error.message || "We couldn’t send your message. Please try again.";
  } finally {
    form.querySelector("button[type=submit]").disabled = false;
  }
});
