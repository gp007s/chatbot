(function () {
    // A random per-tab id. The backend uses it to keep conversation history
    // and verification progress (see ConversationStore.java,
    // VerificationStore.java). It resets if the page reloads - for a widget
    // that should remember across reloads, save this in sessionStorage.
    const sessionId = "sess-" + Math.random().toString(36).slice(2) + Date.now();
    let ended = false;
    let started = false;

    const panel = document.createElement("div");
    panel.id = "chatbot-panel";
    panel.innerHTML = `
        <div id="chatbot-header">
            <span>Chat with us</span>
            <button id="chatbot-close" aria-label="Close chat">&times;</button>
        </div>
        <div id="chatbot-messages"></div>
        <form id="chatbot-form">
            <input id="chatbot-input" type="text" placeholder="Type a message..." autocomplete="off" />
            <button id="chatbot-send" type="submit">Send</button>
        </form>
        <div id="chatbot-footer">
            <button id="chatbot-end" type="button">End chat</button>
        </div>
    `;
    document.body.appendChild(panel);

    const messagesEl = panel.querySelector("#chatbot-messages");
    const formEl = panel.querySelector("#chatbot-form");
    const inputEl = panel.querySelector("#chatbot-input");
    const sendBtn = panel.querySelector("#chatbot-send");
    const endBtn = panel.querySelector("#chatbot-end");

    function addMessage(text, role) {
        const div = document.createElement("div");
        div.className = "chatbot-msg " + role;
        div.textContent = text;
        messagesEl.appendChild(div);
        messagesEl.scrollTop = messagesEl.scrollHeight;
    }

    function lockChat() {
        ended = true;
        inputEl.disabled = true;
        sendBtn.disabled = true;
        endBtn.disabled = true;
    }

    async function openPanel() {
        panel.classList.add("open");
        inputEl.focus();
        if (started) return;
        started = true;

        // The backend decides what to say first: either a plain greeting,
        // or the first identity-verification question (see
        // ChatController#start). The widget doesn't hardcode this.
        try {
            const res = await fetch("/api/chat/start", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ sessionId })
            });
            const body = await res.json().catch(() => null);
            addMessage((body && body.reply) || "Hi! How can I help you today?", "assistant");
        } catch (err) {
            addMessage("Could not reach the chat service. Please try again shortly.", "assistant");
        }
    }

    // The link described in the task: clicking it opens the chatbot.
    const trigger = document.getElementById("chat-with-us");
    if (trigger) {
        trigger.addEventListener("click", function (e) {
            e.preventDefault();
            openPanel();
        });
    }

    panel.querySelector("#chatbot-close").addEventListener("click", function () {
        panel.classList.remove("open");
    });

    formEl.addEventListener("submit", async function (e) {
        e.preventDefault();
        if (ended) return;

        const message = inputEl.value.trim();
        if (!message) return;

        addMessage(message, "user");
        inputEl.value = "";
        inputEl.disabled = true;
        sendBtn.disabled = true;

        try {
            const res = await fetch("/api/chat", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ sessionId, message })
            });

            const body = await res.json().catch(() => null);
            addMessage((body && body.reply) || "Something went wrong. Please try again.", "assistant");

            // Set by the backend when identity verification fails, or the
            // session is otherwise closed - e.g. VerificationStore.Status.FAILED.
            if (body && body.sessionEnded) {
                lockChat();
                return; // skip the finally re-enable below
            }
        } catch (err) {
            addMessage("Could not reach the chat service. Check your connection and try again.", "assistant");
        } finally {
            if (!ended) {
                inputEl.disabled = false;
                sendBtn.disabled = false;
                inputEl.focus();
            }
        }
    });

    // Ends the chat: asks the backend to summarize the conversation and
    // submit it to the application-update API (see ChatController#endChat).
    endBtn.addEventListener("click", async function () {
        if (ended || messagesEl.children.length === 0) return;

        lockChat();
        addMessage("Wrapping up and saving a summary of this conversation...", "assistant");

        try {
            // pcn: null is fine - the backend falls back to whichever PCN it
            // already detected earlier in this session (see ChatController).
            const res = await fetch("/api/chat/end", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ sessionId, pcn: null, resolved: true })
            });
            const body = await res.json().catch(() => null);

            if (!res.ok) {
                addMessage((body && body.summary) || "Could not save a summary. Please try again.", "assistant");
                // Let the customer try again instead of getting stuck.
                ended = false;
                endBtn.disabled = false;
                inputEl.disabled = false;
                sendBtn.disabled = false;
            } else {
                addMessage("Thanks! This conversation has been recorded for our records.", "assistant");
            }
        } catch (err) {
            addMessage("Could not reach the chat service to save this conversation.", "assistant");
            ended = false;
            endBtn.disabled = false;
            inputEl.disabled = false;
            sendBtn.disabled = false;
        }
    });
})();
