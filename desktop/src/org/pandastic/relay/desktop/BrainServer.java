package org.pandastic.relay.desktop;

import android.content.Context;
import android.graphics.Bitmap;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import org.json.JSONArray;
import org.json.JSONObject;
import org.pandastic.relay.ReplyWriter;
import org.pandastic.relay.brain.Brain;
import org.pandastic.relay.brain.ClassifierResult;
import org.pandastic.relay.brain.Decision;
import org.pandastic.relay.brain.KeywordNlu;
import org.pandastic.relay.brain.Knowledge;
import org.pandastic.relay.brain.LeafClassifier;
import org.pandastic.relay.brain.LlmNlu;
import org.pandastic.relay.brain.QualityGate;
import org.pandastic.relay.brain.SmsFormatter;
import org.pandastic.relay.brain.Templates;
import org.pandastic.relay.hub.HubPolicy;

/**
 * The helper phone's brain over HTTP, for the Docker demo: the same Java classes as the APK (Brain, KeywordNlu,
 * LlmNlu with llama.cpp, ReplyWriter, HubPolicy, LeafClassifier, QualityGate), compiled against small Android
 * shims. sms/ask/photo follow BrainHost.smsReply/text/photo; keep them in step with that file.
 *
 *   POST /sms   {text}              → {reply, farming, decision}   automatic SMS: always the fixed answer
 *   POST /ask   {text, lang}        → decision                     helper chat: may add the checked ai_reply
 *   POST /photo {image, text, lang} → decision                     image = base64 JPEG (the WebView's 640 px copy)
 *   GET  /info                      → which models are loaded
 */
public final class BrainServer {
    private final Brain brain;
    private final KeywordNlu keywords;
    private final LlmNlu llm, writer;
    private final LeafClassifier classifier;

    private BrainServer(Context context) throws Exception {
        Knowledge knowledge = Knowledge.open(context);
        keywords = new KeywordNlu(knowledge.lexicon());
        llm = LlmNlu.open(context, keywords);
        writer = llm == null ? null : LlmNlu.openWriter(context, keywords);
        classifier = new LeafClassifier(context);
        brain = new Brain(knowledge, llm != null ? llm : keywords);
    }

    private synchronized JSONObject sms(String text) {
        // HubService: the reply follows the language of the SMS (lang null).
        Decision decision = brain.answerText(text, null);
        boolean farming = farming(text, decision.intent, null);
        String line = understood(farming, decision.lang);
        JSONObject json = new JSONObject(decision.toJson()).put("nlu", nluSource());
        if (line != null) json.put("understood", line);
        return new JSONObject().put("reply", SmsFormatter.withTail(SmsFormatter.format(decision), line))
            .put("farming", farming).put("decision", json);
    }

    private synchronized JSONObject ask(String text, String lang) {
        Decision decision = brain.answerText(text, lang);
        boolean farming = farming(text, decision.intent, lang);
        String line = understood(farming, decision.lang);
        JSONObject json = new JSONObject(decision.toJson()).put("nlu", nluSource());
        if (line != null) json.put("understood", line);
        String written;
        if (farming && "HELP".equals(decision.status))
            written = ReplyWriter.write(chatModel(), text, "en".equals(decision.lang) ? CAN_DO_EN : CAN_DO_SW, decision);
        else if (farming) written = ReplyWriter.write(chatModel(), text, SmsFormatter.facts(decision), decision);
        else {
            String help = Templates.help(decision.lang);
            json.put("status", "HELP").put("intent", "help").put("title", "Pandastic").put("message", help)
                .put("label", JSONObject.NULL).put("candidates", new JSONArray()).remove("understood");
            Decision menu = new Decision();
            menu.status = "HELP"; menu.lang = decision.lang; menu.message = help;
            written = ReplyWriter.write(chatModel(), text, "en".equals(decision.lang) ? CAN_DO_EN : CAN_DO_SW, menu);
        }
        if (written != null) json.put("ai_reply", written);
        return json;
    }

    private synchronized JSONObject photo(Bitmap bitmap, String text, String lang) throws Exception {
        String issue = QualityGate.check(bitmap);
        ClassifierResult result = issue == null ? classifier.classify(bitmap) : null;
        double plantShare = QualityGate.plantShare(bitmap);
        boolean notAPlant = result != null && plantShare < QualityGate.MIN_PLANT_SHARE;
        if (notAPlant) result = result.asOther();
        Decision answer = brain.answerPhoto(issue, result, text, lang);
        JSONObject decision = new JSONObject(answer.toJson());
        if (text != null && !text.trim().isEmpty()) {
            decision.put("nlu", nluSource());
            String line = understood(farming(text, decision.optString("intent", null), lang), decision.optString("lang", lang));
            if (line != null) decision.put("understood", line);
        }
        String written = issue == null && result != null ? ReplyWriter.write(chatModel(), text, SmsFormatter.facts(answer), answer) : null;
        if (written != null) decision.put("ai_reply", written);
        return decision.put("stub", classifier.stub)
            .put("plant_share", Math.round(plantShare * 100) / 100.0).put("not_a_plant", notAPlant);
    }

    private JSONObject info() {
        return new JSONObject().put("classifier", classifier.version).put("classifierStub", classifier.stub)
            .put("llm", llm == null ? JSONObject.NULL : llm.modelName())
            .put("chatModel", writer == null ? JSONObject.NULL : writer.modelName())
            .put("runtimeAvailable", LlmNlu.runtimeAvailable());
    }

    private static final String CAN_DO_EN = "Pandastic helps with coffee, maize and beans. Take a photo of a leaf "
        + "with the + button and it checks it for leaf diseases on this phone. Or ask about a buyer's price: send P, "
        + "the crop number (1 coffee, 2 maize, 3 beans) and the price, for example P 1 12000. It only helps with farming.";
    private static final String CAN_DO_SW = "Pandastic inasaidia kwa kahawa, mahindi na maharage. Piga picha ya jani "
        + "kwa kitufe cha + na itaangalia ugonjwa wa majani kwenye simu hii. Au uliza bei ya mnunuzi: tuma P, namba ya "
        + "zao (1 kahawa, 2 mahindi, 3 maharage) na bei, mfano P 1 12000. Inasaidia kwa kilimo tu.";

    private LlmNlu chatModel() { return writer != null ? writer : llm; }

    private String nluSource() { return llm != null ? llm.lastSource : "keywords_no_model"; }

    private boolean farming(String text, String intent, String lang) {
        return HubPolicy.isFarmingQuestion(text, keywords.parse(text, lang), intent);
    }

    private String understood(boolean farming, String lang) {
        return llm == null || !farming ? null : llm.understood(lang);
    }

    private static Bitmap decode(String base64) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
        if (image == null) throw new IOException("not an image");
        int width = image.getWidth(), height = image.getHeight();
        return Bitmap.of(width, height, image.getRGB(0, 0, width, height, null, 0, width));
    }

    private static String lang(JSONObject body) {
        String lang = body.optString("lang", "");
        return "sw".equals(lang) || "en".equals(lang) ? lang : null;
    }

    private void handle(HttpExchange exchange) throws IOException {
        int code = 200;
        JSONObject response;
        try {
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(exchange.getRequestMethod()) && "/info".equals(path)) response = info();
            else if (!"POST".equals(exchange.getRequestMethod())) { code = 404; response = new JSONObject().put("error", "not_found"); }
            else {
                byte[] raw = exchange.getRequestBody().readNBytes(8_000_001);
                if (raw.length > 8_000_000) throw new IllegalArgumentException("too_large");
                JSONObject body = new JSONObject(new String(raw, StandardCharsets.UTF_8));
                String text = body.optString("text", "");
                if (text.length() > 1000) throw new IllegalArgumentException("too_long");
                switch (path) {
                    case "/sms": response = sms(text); break;
                    case "/ask": response = ask(text, lang(body)); break;
                    case "/photo": response = photo(decode(body.getString("image")), text, lang(body)); break;
                    default: code = 404; response = new JSONObject().put("error", "not_found");
                }
            }
        } catch (Exception e) {
            code = e instanceof IllegalArgumentException || e instanceof IOException || e instanceof org.json.JSONException ? 400 : 500;
            response = new JSONObject().put("error", e.getClass().getSimpleName());
            System.err.println("E/PandasticBrainServer: " + e);
        }
        byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }

    public static void main(String[] args) throws Exception {
        File assets = new File(System.getenv().getOrDefault("PANDASTIC_ASSETS", "/opt/pandastic/assets"));
        File files = new File(System.getenv().getOrDefault("PANDASTIC_FILES", "/opt/pandastic/files"));
        int port = Integer.parseInt(System.getenv().getOrDefault("PANDASTIC_BRAIN_PORT", "8090"));
        long started = System.currentTimeMillis();
        BrainServer server = new BrainServer(new Context(assets, files));
        System.err.println("I/PandasticBrainServer: " + server.info() + " loaded in " + (System.currentTimeMillis() - started) + " ms");
        HttpServer http = HttpServer.create(new InetSocketAddress(port), 16);
        http.createContext("/", server::handle);
        http.setExecutor(Executors.newFixedThreadPool(4));
        http.start();
        System.err.println("I/PandasticBrainServer: listening on " + port);
    }
}
