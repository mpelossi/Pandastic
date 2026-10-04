package org.pandastic.relay.brain;

import java.util.function.LongSupplier;

/**
 * The single entry point for answers (contracts §6). BrainHost calls it from one worker thread,
 * so it is not thread-safe. No model ever writes advice or price text: it all comes from
 * knowledge.sqlite and Templates, picked by the Resolver.
 */
public final class Brain {
    private final Nlu nlu;
    private final Resolver resolver;

    public Brain(Knowledge knowledge, Nlu nlu) {
        this(knowledge, nlu, System::currentTimeMillis);
    }

    /** For tests: a fixed clock makes price staleness reproducible. */
    public Brain(Knowledge knowledge, Nlu nlu, LongSupplier clock) {
        this.nlu = nlu != null ? nlu : new KeywordNlu(knowledge.lexicon());
        this.resolver = new Resolver(knowledge, clock);
    }

    /** Typed or SMS question. lang null = detect from the text (Swahili by default). */
    public Decision answerText(String text, String lang) {
        Slots slots = nlu.parse(text == null ? "" : text, lang);
        if (slots.lang == null) slots.lang = lang != null ? lang : "sw";
        switch (slots.intent == null ? "other" : slots.intent) {
            case "price": return resolver.price(slots);
            case "diagnose": return resolver.textOnly(slots);
            case "planting": return resolver.planting(slots);
            default: return resolver.help(slots);
        }
    }

    /**
     * Photo (+ optional question). qualityIssue is QualityGate.check's result (null = ok); result is null
     * when the classifier could not run.
     */
    public Decision answerPhoto(String qualityIssue, ClassifierResult result, String text, String lang) {
        // Photo + words: the words go through the same understanding as an SMS (keywords, then the language
        // model if one is loaded) and give the crop, the symptom the farmer describes and the reply language.
        String cropHint = null, symptomHint = null;
        if (text != null && !text.trim().isEmpty()) {
            Slots slots = nlu.parse(text, lang);
            cropHint = slots.crop;
            symptomHint = slots.symptom;
            if (lang == null) lang = slots.lang;
        }
        return resolver.photo(qualityIssue, result, cropHint, symptomHint, lang == null ? "sw" : lang);
    }
}
