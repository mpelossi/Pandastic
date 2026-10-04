package org.pandastic.relay.brain;

/** What the NLU understood from one message (contracts §4, §6). Null fields = not found. */
public final class Slots {
    /** "sw" | "en". */
    public String lang;
    /** "diagnose" | "price" | "planting" | "help" | "other". */
    public String intent;
    /** "coffee" | "maize" | "bean". */
    public String crop;
    /** Condition suffix of a classifier label, e.g. "rust" (label = crop + "_" + symptom). */
    public String symptom;
    /** Price commodity code from knowledge.sqlite, e.g. "coffee_robusta_kiboko"; null = the crop's default. */
    public String commodity;
    /** Offered price per kg, or null. */
    public Double offer;
    /** 1.0 for keyword hits; a model's probability for LlmNlu. */
    public float intentProb;

    @Override public String toString() {
        return "Slots{lang=" + lang + ", intent=" + intent + ", crop=" + crop + ", symptom=" + symptom
            + ", commodity=" + commodity + ", offer=" + offer + ", intentProb=" + intentProb + "}";
    }
}
