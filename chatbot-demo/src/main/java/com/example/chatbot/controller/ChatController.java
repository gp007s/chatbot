package com.example.chatbot.controller;

import com.example.chatbot.config.VerificationProperties;
import com.example.chatbot.dto.*;
import com.example.chatbot.service.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final String DEFAULT_GREETING = "Hi! How can I help you today?";
    private static final String VERIFIED_GREETING =
            "Thanks, you're verified! I can help you check your application status, but I'll need your "
                    + "PCN (primary reference number) first - could you share it with me?";
    private static final String VERIFICATION_FAILED_MESSAGE =
            "We're sorry, we couldn't verify your identity with the information provided. "
                    + "This chat session has ended - please contact support directly for help.";
    private static final String VERIFICATION_UNAVAILABLE_MESSAGE =
            "We're unable to verify your identity right now. Please try again later or contact support directly.";
    private static final String VERIFICATION_TOO_LONG_MESSAGE =
            "We're having trouble verifying your identity through chat. "
                    + "This session has ended - please contact support directly for help.";
    private static final String SESSION_CLOSED_MESSAGE =
            "This chat session has ended. Please contact support directly for help.";

    private final OllamaChatService chatService;
    private final ConversationStore conversationStore;
    private final ChatRateLimiter rateLimiter;
    private final ApplicationUpdateApiClient applicationUpdateApiClient;
    private final ApplicationStatusApiClient applicationStatusApiClient;
    private final ApplicationStatusMessageMapper statusMessageMapper;
    private final SecurityCheckApiClient securityCheckApiClient;
    private final VerificationStore verificationStore;
    private final VerificationProperties verificationProperties;
    private final PcnExtractor pcnExtractor;

    public ChatController(OllamaChatService chatService,
                           ConversationStore conversationStore,
                           ChatRateLimiter rateLimiter,
                           ApplicationUpdateApiClient applicationUpdateApiClient,
                           ApplicationStatusApiClient applicationStatusApiClient,
                           ApplicationStatusMessageMapper statusMessageMapper,
                           SecurityCheckApiClient securityCheckApiClient,
                           VerificationStore verificationStore,
                           VerificationProperties verificationProperties,
                           PcnExtractor pcnExtractor) {
        this.chatService = chatService;
        this.conversationStore = conversationStore;
        this.rateLimiter = rateLimiter;
        this.applicationUpdateApiClient = applicationUpdateApiClient;
        this.applicationStatusApiClient = applicationStatusApiClient;
        this.statusMessageMapper = statusMessageMapper;
        this.securityCheckApiClient = securityCheckApiClient;
        this.verificationStore = verificationStore;
        this.verificationProperties = verificationProperties;
        this.pcnExtractor = pcnExtractor;
    }

    /** Called once when the widget panel opens: returns the greeting, or the fixed verification opener. */
    @PostMapping("/start")
    public ResponseEntity<ChatResponse> start(@Valid @RequestBody StartChatRequest request) {
        List<VerificationProperties.FieldConfig> fields = verificationProperties.getFields();

        if (!verificationProperties.isEnabled() || fields.isEmpty()) {
            verificationStore.stateFor(request.sessionId()).status = VerificationStore.Status.VERIFIED;
            return ResponseEntity.ok(ChatResponse.ok(DEFAULT_GREETING));
        }

        // Starts (or restarts) verification fresh for this session.
        conversationStore.clear(request.sessionId());
        VerificationStore.SessionVerification state = verificationStore.stateFor(request.sessionId());
        state.answers.clear();
        state.turnCount = 0;
        state.status = VerificationStore.Status.IN_PROGRESS;

        String opening = verificationProperties.getOpeningPrompt();
        conversationStore.append(request.sessionId(), new ChatTurn("assistant", opening));
        return ResponseEntity.ok(ChatResponse.ok(opening));
    }

    @PostMapping
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        if (!rateLimiter.allow(request.sessionId())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ChatResponse.ok("You're sending messages too quickly. Please wait a moment."));
        }

        VerificationStore.SessionVerification verification = verificationStore.stateFor(request.sessionId());

        if (verificationProperties.isEnabled() && verification.status == VerificationStore.Status.FAILED) {
            return ResponseEntity.ok(ChatResponse.ended(SESSION_CLOSED_MESSAGE));
        }

        if (verificationProperties.isEnabled() && verification.status == VerificationStore.Status.IN_PROGRESS) {
            return handleVerificationStep(request.sessionId(), request.message(), verification);
        }

        // Verified (or verification disabled) - normal chat flow.
        conversationStore.append(request.sessionId(), new ChatTurn("user", request.message()));

        // Deterministic path: as soon as a PCN shows up in the message, call
        // the real status API directly instead of waiting on the model to
        // decide to use the get_application_status tool.
        Optional<String> pcn = pcnExtractor.extract(request.message());
        if (pcn.isPresent()) {
            handlePcnLookup(request.sessionId(), pcn.get());
        }

        List<ChatTurn> history = conversationStore.historyFor(request.sessionId());

        try {
            String reply = chatService.ask(history);
            conversationStore.append(request.sessionId(), new ChatTurn("assistant", reply));
            return ResponseEntity.ok(ChatResponse.ok(reply));
        } catch (OllamaChatService.ChatProviderException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(ChatResponse.ok("Our chat assistant is unavailable right now. Please try again shortly."));
        }
    }

    /**
     * One turn of conversational identity verification. The model collects
     * fields naturally (see OllamaChatService.converseForVerification) -
     * but THIS method, not the model, decides when every field is filled
     * and makes the real SecurityCheckApi call. The pass/fail/unavailable
     * messages the customer sees are always these pre-written constants,
     * never model-authored text, so the model can't phrase a false
     * "you're verified." Fails CLOSED if SecurityCheckApi can't be
     * reached - flip that branch if your business wants fail-open instead.
     */
    private ResponseEntity<ChatResponse> handleVerificationStep(
            String sessionId, String message, VerificationStore.SessionVerification verification) {

        verification.turnCount++;
        if (verification.turnCount > verificationProperties.getMaxTurns()) {
            verification.status = VerificationStore.Status.FAILED;
            return ResponseEntity.ok(ChatResponse.ended(VERIFICATION_TOO_LONG_MESSAGE));
        }

        conversationStore.append(sessionId, new ChatTurn("user", message));
        List<ChatTurn> history = conversationStore.historyFor(sessionId);
        List<VerificationProperties.FieldConfig> fields = verificationProperties.getFields();

        OllamaChatService.VerificationTurnResult result;
        try {
            result = chatService.converseForVerification(
                    history, fields, verification.answers, verificationProperties.getSystemPrompt());
        } catch (OllamaChatService.ChatProviderException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(ChatResponse.ok("Sorry, I'm having trouble right now. Could you try again?"));
        }

        verification.answers.clear();
        verification.answers.putAll(result.updatedAnswers());

        boolean complete = fields.stream().allMatch(f -> {
            String v = verification.answers.get(f.getKey());
            return v != null && !v.isBlank();
        });

        if (!complete) {
            String reply = (result.reply() != null) ? result.reply() : "Could you also share the remaining details?";
            conversationStore.append(sessionId, new ChatTurn("assistant", reply));
            return ResponseEntity.ok(ChatResponse.ok(reply));
        }

        // Every field is filled - the CODE decides to call SecurityCheckApi
        // now. The model was never asked and never gets a vote.
        try {
            SecurityCheckApiClient.SecurityCheckResult secResult =
                    securityCheckApiClient.verify(sessionId, verification.answers);

            if (secResult.verified()) {
                verification.status = VerificationStore.Status.VERIFIED;
                // Drop the verification transcript (name/address/DOB) now that
                // it's served its purpose - it never gets summarized or sent
                // to the application-update API. The real chat starts clean.
                conversationStore.clear(sessionId);
                conversationStore.append(sessionId, new ChatTurn("assistant", VERIFIED_GREETING));
                return ResponseEntity.ok(ChatResponse.ok(VERIFIED_GREETING));
            } else {
                verification.status = VerificationStore.Status.FAILED;
                conversationStore.clear(sessionId);
                return ResponseEntity.ok(ChatResponse.ended(VERIFICATION_FAILED_MESSAGE));
            }
        } catch (RestClientException e) {
            verification.status = VerificationStore.Status.FAILED; // fail closed
            conversationStore.clear(sessionId);
            return ResponseEntity.ok(ChatResponse.ended(VERIFICATION_UNAVAILABLE_MESSAGE));
        }
    }

    /**
     * Calls the real status API for the given PCN and injects the result
     * (already mapped from code to customer-facing message, or a clean
     * failure note) into the conversation as a system turn, so the model
     * answers with facts instead of guessing.
     */
    private void handlePcnLookup(String sessionId, String pcn) {
        conversationStore.rememberPcn(sessionId, pcn);
        try {
            ApplicationStatusApiClient.ApplicationStatus status = applicationStatusApiClient.getStatus(pcn);
            String statusMessage = statusMessageMapper.messageFor(status.statusCode());
            conversationStore.append(sessionId, new ChatTurn("system",
                    "Status lookup result for PCN " + pcn + ": \"" + statusMessage + "\". "
                            + "Relay this message to the customer plainly. Never mention or invent a status code."));
        } catch (HttpClientErrorException.NotFound e) {
            // The status API explicitly said this PCN doesn't match anything on file -
            // different from the API being unreachable, so the customer gets a more
            // specific, more useful message.
            conversationStore.append(sessionId, new ChatTurn("system",
                    "No application was found with PCN " + pcn + ". Tell the customer this plainly, ask them "
                            + "to double-check the PCN, and offer to connect them with a human agent if it "
                            + "still doesn't match."));
        } catch (RestClientException e) {
            conversationStore.append(sessionId, new ChatTurn("system",
                    "The status service could not be reached while looking up PCN " + pcn + ". Tell the "
                            + "customer there's a temporary issue and ask them to try again shortly."));
        } catch (Exception e) {
            conversationStore.append(sessionId, new ChatTurn("system",
                    "An unexpected error occurred while looking up PCN " + pcn + ". "
                            + "Tell the customer to try again shortly."));
        }
    }

    /**
     * Called when the customer ends the chat. Summarizes the conversation
     * and posts it to the application-update backend API. Uses the PCN from
     * the request if given, otherwise falls back to whichever PCN was
     * detected earlier in this session.
     */
    @PostMapping("/end")
    public ResponseEntity<EndChatResponse> endChat(@Valid @RequestBody EndChatRequest request) {
        List<ChatTurn> history = conversationStore.historyFor(request.sessionId());
        if (history.isEmpty()) {
            return ResponseEntity.badRequest().body(new EndChatResponse("No conversation found for this session."));
        }

        String pcn = (request.pcn() != null && !request.pcn().isBlank())
                ? request.pcn()
                : conversationStore.pcnFor(request.sessionId());

        try {
            String summary = chatService.summarize(history);

            applicationUpdateApiClient.submit(new ApplicationUpdateApiClient.ApplicationUpdateRequest(
                    request.sessionId(), pcn, summary, request.resolved()));

            conversationStore.clear(request.sessionId());
            verificationStore.clear(request.sessionId());
            return ResponseEntity.ok(new EndChatResponse(summary));

        } catch (OllamaChatService.ChatProviderException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new EndChatResponse("Could not generate a summary right now. Please try again."));
        } catch (Exception e) {
            // Most likely the update API itself is unreachable or rejected the request.
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new EndChatResponse("Chat summary was generated but could not be saved. Please try again."));
        }
    }
}
