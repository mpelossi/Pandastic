package org.pandastic.relay.hub;

import android.content.Context;
import java.util.concurrent.TimeUnit;
import org.pandastic.relay.BrainHost;

/** Turns one SMS question into one SMS reply. The hub calls it from a single thread. */
public interface Responder {
    Reply answer(String text, String lang) throws Exception;

    final class Reply {
        public final String sms;
        /** Decision JSON (contracts §2) for the local log and UI; may be null. */
        public final String decisionJson;
        /** False for a personal message ("how is school?"): the helper must not answer it (HubPolicy). */
        public final boolean farming;

        public Reply(String sms, String decisionJson, boolean farming) {
            this.sms = sms;
            this.decisionJson = decisionJson;
            this.farming = farming;
        }

        public Reply(String sms, String decisionJson) { this(sms, decisionJson, true); }
    }

    /** Answers through BrainHost, so SMS and in-app questions share one model thread. */
    static Responder create(Context context) {
        BrainHost host = BrainHost.get(context);
        return (text, lang) -> host.submit(() -> host.smsReply(text, lang)).get(60, TimeUnit.SECONDS);
    }

    /** Never guesses: tells the sender to ask a person. Used when no model or knowledge base is ready. */
    final class Fallback implements Responder {
        @Override public Reply answer(String text, String lang) {
            String sms = "en".equals(lang)
                ? "Pandastic: Not sure - ask a person (extension officer or cooperative). Do not spray yet."
                : "Pandastic: Sina uhakika - uliza mtu (afisa ugani au chama cha ushirika). Usinyunyizie dawa bado.";
            // No NLU ran, so only unmistakable farming words make this a question to answer.
            return new Reply(sms, "{\"status\":\"NO_DATA\",\"escalate\":true}", HubPolicy.looksLikeFarming(text));
        }
    }
}
