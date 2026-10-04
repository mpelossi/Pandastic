package org.pandastic.relay.brain;

/** Message text → slots. KeywordNlu (B) is always available; LlmNlu (A) implements the same interface. */
public interface Nlu {
    /** @param lang "sw", "en", or null to detect it from the text. Never returns null. */
    Slots parse(String text, String lang);
}
