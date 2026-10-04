package org.pandastic.relay.brain;

import android.content.Context;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.json.JSONObject;

/**
 * Qwen3.5-0.8B (llama.cpp) reads every SMS and chat question (contracts §5). Its reading is used only where
 * the keyword NLU found nothing (intent; a crop-consistent symptom); its output is forced into JSON by a GBNF grammar and then checked against a
 * fixed list here, and it never writes advice or prices. Any failure falls back to keywords.
 */
public final class LlmNlu implements Nlu, AutoCloseable {
    private static final String TAG = "PandasticLlm";
    /** The base model; FINE_TUNED (our LoRA, ml/modal_lora.py) is preferred when both are side-loaded. */
    public static final String MODEL_NAME = "Qwen3.5-0.8B-Q4_K_M.gguf";
    public static final String FINE_TUNED = "Qwen3.5-0.8B-pandastic-Q4_K_M.gguf";
    /** Prompt + answer; set explicitly so llama.cpp does not size the cache for the model's 262k context. */
    private static final int CONTEXT = 1536, MAX_TOKENS = 64;
    /** The SMS hub waits up to 60 s per question; the model gets a third of that, then keywords answer. */
    private static final int TIME_BUDGET_MS = 20_000;
    private static final Set<String> INTENTS = new HashSet<>(Arrays.asList("diagnose", "price", "planting", "help", "other"));

    private static final String DEFAULT_SYSTEM = "You read one short SMS from a smallholder farmer in Uganda. "
        + "It may be Swahili, English or Luganda, and may be misspelled. Reply with JSON only. "
        + "intent: diagnose (a problem with a crop), price (selling price or a buyer's offer), planting (when to plant), "
        + "help (how to use this service), or other. crop: coffee, maize, bean, or null. "
        + "symptom: the condition only if the words clearly name it, otherwise null. Never guess.";
    private static final String DEFAULT_GRAMMAR = String.join("\n",
        "root ::= \"{\" ws \"\\\"intent\\\":\" ws intent \",\" ws \"\\\"crop\\\":\" ws crop \",\" ws \"\\\"symptom\\\":\" ws symptom ws \"}\"",
        "intent ::= \"\\\"diagnose\\\"\" | \"\\\"price\\\"\" | \"\\\"planting\\\"\" | \"\\\"help\\\"\" | \"\\\"other\\\"\"",
        "crop ::= \"\\\"coffee\\\"\" | \"\\\"maize\\\"\" | \"\\\"bean\\\"\" | \"null\"",
        "symptom ::= \"\\\"rust\\\"\" | \"\\\"miner\\\"\" | \"\\\"cercospora\\\"\" | \"\\\"phoma\\\"\" | \"\\\"fall_armyworm\\\"\" | \"\\\"leaf_blight\\\"\" | \"\\\"streak_virus\\\"\" | \"\\\"lethal_necrosis\\\"\" | \"\\\"leaf_spot\\\"\" | \"\\\"angular_leaf_spot\\\"\" | \"null\"",
        "ws ::= [ ]?");

    private static boolean libraryLoaded;
    static {
        try { System.loadLibrary("pandastic_llm"); libraryLoaded = true; }
        catch (UnsatisfiedLinkError e) { Log.w(TAG, "LLM library not in this build"); }
    }

    private static native long nativeLoad(String path, int nCtx, int nThreads);
    private static native String nativeComplete(long handle, String prompt, String grammar, int maxTokens, int timeoutMs);
    private static native boolean nativeSetPrefix(long handle, String prefix);
    private static native String nativeWrite(long handle, String prompt, int maxTokens, int timeoutMs);
    private static native void nativeFree(long handle);

    private long handle;
    private boolean prefixCached;
    private final Nlu keywords;
    private final String system, grammar;
    public final String modelPath;

    /**
     * The model is side-loaded (never bundled): files/models/ or the app's external files/models/,
     * which `adb push` and USB can reach. Returns null when it is missing, so keywords are used alone.
     */
    public static LlmNlu open(Context context, Nlu keywords) {
        if (!libraryLoaded) return null;
        File model = find(context);
        if (model == null) { Log.i(TAG, "No " + FINE_TUNED + " or " + MODEL_NAME + " side-loaded; keyword NLU only"); return null; }
        int threads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() - 2));
        long handle = nativeLoad(model.getAbsolutePath(), CONTEXT, threads);
        if (handle == 0) return null;
        // Grammar and prompt come from ml/llm, copied into assets at build time.
        LlmNlu llm = new LlmNlu(handle, keywords, asset(context, "llm/system_prompt.txt", DEFAULT_SYSTEM),
            asset(context, "llm/slots.gbnf", DEFAULT_GRAMMAR), model.getAbsolutePath());
        long started = System.currentTimeMillis();
        boolean cached = llm.prefixCached = nativeSetPrefix(handle, llm.prefix());
        Log.i(TAG, "System prompt " + (cached ? "cached" : "NOT cached") + " in " + (System.currentTimeMillis() - started) + " ms");
        return llm;
    }

    private LlmNlu(long handle, Nlu keywords, String system, String grammar, String modelPath) {
        this.handle = handle;
        this.keywords = keywords;
        this.system = system.trim();
        this.grammar = stopAfterSymptom(grammar);
        this.modelPath = modelPath;
    }

    /** Where ml/llm/slots.gbnf's root rule leaves the symptom; the commodity and the offer follow. */
    private static final String AFTER_SYMPTOM = "symptom \",\\\"commodity\\\":\"";

    /**
     * Only intent, crop and symptom are read, and they come first in the JSON, so generation stops after the
     * symptom: about a third fewer tokens per message (ml/reports/llm_stop_after_symptom_lora.json). The grammar
     * ends at {@code ,"}, the token boundary the model writes there anyway, so every token before it is the one
     * the full grammar would give; {@link #closeJson} then ends the object. Other grammars are used unchanged.
     */
    static String stopAfterSymptom(String grammar) {
        int start = grammar.indexOf(AFTER_SYMPTOM);
        if (start < 0) return grammar;
        int lineEnd = grammar.indexOf('\n', start);
        return grammar.substring(0, start) + "symptom \",\\\"\"" + (lineEnd < 0 ? "" : grammar.substring(lineEnd));
    }

    /** Ends the object the cut grammar leaves open after the symptom (its trailing {@code ,"}); a whole object stays as it is. */
    static String closeJson(String output) {
        return output.endsWith(",\"") ? output.substring(0, output.length() - 2) + "}" : output;
    }

    /**
     * Hybrid policy measured in ml/reports/nlu_eval*.json (all slots right on held-out / fresh / fresh2 SMS):
     * keywords 0.72 / 0.88 / 0.83; fine-tuned model filling the intent and a crop-consistent symptom 0.94 /
     * 0.90 / 0.93. The base model invents symptoms (0.73 on fresh2), so it only names the intent. Taking the
     * model's crop always hurt: it says coffee or maize when the farmer wrote cassava, tomato or tea. So the
     * model never sets the crop, the offer, the language or the commodity.
     */
    @Override public Slots parse(String text, String lang) {
        Slots slots = keywords.parse(text, lang);
        boolean symptoms = fineTuned();
        lastRead = null;
        // The model reads every message (user decision, 07:05 UTC) so the farmer sees what the phone's AI
        // understood. Which reading wins is still the measured policy: merge() only fills what the keywords
        // missed, so the answers are the same as when the model ran only for those messages.
        JSONObject json = complete(text);
        if (json == null) { lastSource = "keywords_model_failed"; return slots; }
        String modelIntent = json.optString("intent", null);
        String modelCrop = json.isNull("crop") ? null : json.optString("crop", null);
        String intentBefore = slots.intent, symptomBefore = slots.symptom;
        merge(slots, modelIntent, symptoms ? json.optString("symptom", null) : null);
        boolean filled = !Objects.equals(intentBefore, slots.intent) || !Objects.equals(symptomBefore, slots.symptom);
        boolean sameIntent = Objects.equals(modelIntent, slots.intent)
            || (isGeneral(modelIntent) && isGeneral(slots.intent));
        boolean sameCrop = slots.crop == null || modelCrop == null || slots.crop.equals(modelCrop);
        lastSource = filled ? "model" : sameIntent ? "model_agreed" : "keywords_model_disagreed";
        Log.i(TAG, "LLM read intent=" + modelIntent + " crop=" + modelCrop + " symptom=" + json.optString("symptom", null)
            + " -> " + lastSource + " (final: " + slots.intent + ", " + slots.crop + ", " + slots.symptom + ")");
        if (filled || sameIntent) {
            lastRead = copy(slots);
            // Say only what the model read too: a crop or symptom it named differently came from the keywords
            // alone (probe 07:15: "worms inside" maize = fall armyworm by keywords, leaf blight by the model).
            if (!sameCrop) { lastRead.crop = null; lastRead.symptom = null; }
            if (lastRead.symptom != null && !lastRead.symptom.equals(json.optString("symptom", null))) lastRead.symptom = null;
        }
        return slots;
    }

    /**
     * How the last message was understood, for the UI, the hub log and the tests: "model" (its intent or
     * symptom was used), "model_agreed" (it read the same as the keywords), "keywords_model_disagreed" (the
     * keywords' reading was kept), "keywords_model_failed" (no answer within the time budget). Read on the
     * same thread as parse().
     */
    public volatile String lastSource = "keywords";
    /** The final reading of the last message if the model agreed with it or filled it; null otherwise. */
    private volatile Slots lastRead;

    /**
     * One fixed-template line for the reply: "AI ya simu imeelewa: bei, kahawa, 12,000." It names only slots
     * the model read too (null when it disagreed or failed), never advice.
     */
    public String understood(String lang) {
        Slots s = lastRead;
        if (s == null) return null;
        boolean en = "en".equals(lang);
        List<String> parts = new ArrayList<>();
        switch (s.intent == null ? "other" : s.intent) {
            case "price": parts.add(en ? "price" : "bei"); break;
            case "diagnose": parts.add(en ? "plant problem" : "tatizo la mmea"); break;
            case "planting": parts.add(en ? "planting" : "kupanda"); break;
            default: parts.add(en ? "help" : "msaada");
        }
        if (s.crop != null) parts.add(Templates.name(s.crop, lang));
        if (s.crop != null && s.symptom != null && "diagnose".equals(s.intent)) {
            String name = Templates.name(s.crop + "_" + s.symptom, lang);
            parts.add(Character.toLowerCase(name.charAt(0)) + name.substring(1));
        }
        if (s.offer != null && "price".equals(s.intent)) parts.add(Templates.money(s.offer));
        return (en ? "Phone AI understood: " : "AI ya simu imeelewa: ") + String.join(", ", parts) + ".";
    }

    private static boolean isGeneral(String intent) { return intent == null || "help".equals(intent) || "other".equals(intent); }

    private static Slots copy(Slots from) {
        Slots to = new Slots();
        to.lang = from.lang; to.intent = from.intent; to.crop = from.crop; to.symptom = from.symptom;
        to.commodity = from.commodity; to.offer = from.offer; to.intentProb = from.intentProb;
        return to;
    }

    boolean fineTuned() { return FINE_TUNED.equals(modelName()); }

    /** Where the model's reading can change the answer: no intent keyword, or (fine-tune only) a crop problem without a symptom. */
    static boolean needsModel(Slots keywordSlots, boolean symptoms) {
        return keywordSlots.intentProb == 0 || (symptoms && "diagnose".equals(keywordSlots.intent)
            && keywordSlots.symptom == null && keywordSlots.crop != null);
    }

    /**
     * The model's intent only if the keywords found none; its symptom only for a problem report without one,
     * and only if it exists for the crop the keywords found. Without a known crop the model's symptom can
     * belong to the wrong plant (SMS lab: Luganda "emmwanyi" = coffee got "maybe maize lethal necrosis"), so
     * it is dropped. Text symptoms are never CONFIDENT anyway: "may be X, don't spray yet, ask a person".
     */
    static Slots merge(Slots keywordSlots, String modelIntent, String modelSymptom) {
        if (keywordSlots.intentProb == 0 && INTENTS.contains(modelIntent)) {
            keywordSlots.intent = modelIntent;
            keywordSlots.intentProb = 0.8f;
        }
        if (modelSymptom != null && "diagnose".equals(keywordSlots.intent) && keywordSlots.symptom == null
            && symptomFits(keywordSlots.crop, modelSymptom)) keywordSlots.symptom = modelSymptom;
        return keywordSlots;
    }

    private static boolean symptomFits(String crop, String symptom) {
        return crop != null && !symptom.equals("healthy") && Resolver.LABELS.contains(crop + "_" + symptom);
    }

    /** Fixed part of the chat prompt; evaluated once and cached. */
    private String prefix() {
        return "<|im_start|>system\n" + system + "<|im_end|>\n<|im_start|>user\nSMS: ";
    }

    private JSONObject complete(String text) {
        // Qwen chat format with an empty thinking block: the grammar forces JSON from the first token.
        String prompt = (prefixCached ? "" : prefix())
            + text.replace("<|", "< |") + "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n";
        long started = System.currentTimeMillis();
        String output = nativeComplete(handle, prompt, grammar, MAX_TOKENS, TIME_BUDGET_MS);
        Log.i(TAG, "LLM slots in " + (System.currentTimeMillis() - started) + " ms");
        if (output == null) return null;
        try { return new JSONObject(closeJson(output)); }
        catch (Exception e) { return null; }
    }

    /**
     * Free text from the same model (no grammar), for the grounded reply writer: the caller gives the facts and
     * checks every word that comes back. Same thread as parse(). Null on timeout or failure.
     */
    public String write(String system, String user, int maxTokens, int timeoutMs) {
        String prompt = "<|im_start|>system\n" + system + "<|im_end|>\n<|im_start|>user\n" + user.replace("<|", "< |")
            + "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n";
        long started = System.currentTimeMillis();
        String output = nativeWrite(handle, prompt, maxTokens, timeoutMs);
        Log.i(TAG, "LLM wrote " + (output == null ? "nothing" : output.length() + " chars") + " in "
            + (System.currentTimeMillis() - started) + " ms");
        return output;
    }

    public static boolean runtimeAvailable() { return libraryLoaded; }

    public static boolean isModelName(String name) { return MODEL_NAME.equals(name) || FINE_TUNED.equals(name); }

    /** The fine-tuned file if present, else the base one; internal files first, then the USB-reachable folder. */
    public static File find(Context context) {
        return find(context, FINE_TUNED, MODEL_NAME);
    }

    /**
     * Optional larger chat writer (Qwen3.5-2B, 1.28 GB): it writes the helper's chat replies in English, where it is
     * much better than the 0.8B; reading SMS stays with the measured fine-tuned model.
     */
    public static final String WRITER = "Qwen3.5-2B-Q4_K_M.gguf";

    public static LlmNlu openWriter(Context context, Nlu keywords) {
        if (!libraryLoaded) return null;
        File model = find(context, WRITER);
        if (model == null) return null;
        int threads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() - 2));
        long handle = nativeLoad(model.getAbsolutePath(), 2048, threads);
        if (handle == 0) return null;
        Log.i(TAG, "Chat writer loaded: " + WRITER);
        return new LlmNlu(handle, keywords, DEFAULT_SYSTEM, DEFAULT_GRAMMAR, model.getAbsolutePath());
    }

    private static File find(Context context, String... names) {
        File external = context.getExternalFilesDir(null);
        for (String name : names) {
            File[] places = {new File(context.getFilesDir(), "models/" + name),
                external == null ? null : new File(external, "models/" + name)};
            for (File place : places) {
                if (place == null) continue;
                // The app creates the folders itself: a folder made by adb belongs to the shell user and is unreadable here.
                place.getParentFile().mkdirs();
                if (place.isFile() && place.length() > 100_000_000L) return place;
            }
        }
        return null;
    }

    /** File name of the loaded model (shown on the Models page). */
    public String modelName() { return new File(modelPath).getName(); }

    private static String asset(Context context, String path, String fallback) {
        try (InputStream in = context.getAssets().open(path); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int read; (read = in.read(buffer)) != -1; ) out.write(buffer, 0, read);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return fallback;
        }
    }

    @Override public synchronized void close() {
        if (handle != 0) { nativeFree(handle); handle = 0; }
    }
}
