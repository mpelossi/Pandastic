package org.pandastic.relay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** The checks every model-written reply must pass before a farmer sees it (ReplyWriter.check). */
public class ReplyWriterTest {
    private static final String NOT_SURE_SW = "Sina uhakika kwa maneno pekee - usinyunyizie dawa bado. Inaweza kuwa Kutu ya majani "
        + "ya kahawa. Onyesha picha ya jani kwenye simu ya nyumbani, au uliza afisa ugani.";
    private static final String PRICE_EN = "Arabica parchment, farm-gate Aug 2026: UGX 15,500/kg (MAAIF/UCDA). Offer 12,000 is "
        + "23% below. Ask the cooperative before selling.";

    @Test public void aFaithfulRewritePasses() {
        String sw = ReplyWriter.check("Pole Mama. Sina uhakika, huenda ni kutu ya majani ya kahawa. Usinyunyizie dawa bado; "
            + "onyesha picha ya jani au uliza afisa ugani.", NOT_SURE_SW, "", "sw", "TEXT_ONLY");
        assertNotNull(ReplyWriter.reason, sw);
        String en = ReplyWriter.check("The buyer's 12,000 is 23% below the farm-gate price of UGX 15,500/kg (MAAIF/UCDA). "
            + "Ask the cooperative before you sell.", PRICE_EN, "", "en", "PRICE");
        assertNotNull(ReplyWriter.reason, en);
    }

    @Test public void inventionsAreRejected() {
        String[] bad = {
            "Sina uhakika. Hii ni ukungu wa mahindi. Usinyunyizie dawa bado, uliza afisa ugani.",            // other disease + crop
            "Sina uhakika, ni kutu. Usinyunyizie dawa bado; tumia copper 50 ml kwa lita, uliza afisa ugani.", // chemical + dose
            "Sina uhakika lakini kahawa yako ina afya. Usinyunyizie dawa bado, uliza afisa ugani.",          // healthy claim
            "Huenda ni kutu ya majani ya kahawa. Onyesha picha ya jani au uliza afisa ugani.",                // dropped warnings
            "Not sure from words, do not spray yet. It could be coffee leaf rust, ask the extension officer.", // wrong language
            "{\"intent\":\"diagnose\"}",                                                                      // JSON
            "Sina uhakika kwa maneno pekee - usinyunyizie dawa bado. Inaweza kuwa Kutu ya maj kahawa, uliza afisa ugani.", // non-word
        };
        for (String reply : bad) assertNull(reply, ReplyWriter.check(reply, NOT_SURE_SW, "", "sw", "TEXT_ONLY"));
        assertNull("new price", ReplyWriter.check("The buyer's 12,000 is low: the price is UGX 16,000/kg (MAAIF/UCDA). Ask the "
            + "cooperative.", PRICE_EN, "", "en", "PRICE"));
        String rust = "Coffee leaf rust. Copper oxychloride prevents rust; spray only if the extension officer agrees.";
        assertNull("flipped", ReplyWriter.check("Coffee leaf rust: copper oxychloride is not needed, so do not spray it unless the "
            + "extension officer agrees.", rust, "", "en", "CONFIDENT"));
        assertNull("judgment", ReplyWriter.check("Hii ni sawa: Kahawa Arabica (parchment), UGX 15,500/kg (MAAIF/UCDA). Uliza chama "
            + "kabla ya kuuza.", "Kahawa Arabica (parchment), bei ya shambani Ago 2026: UGX 15,500/kg (MAAIF/UCDA). Bei ya 12,000 "
            + "iko chini kwa 23%. Uliza chama kabla ya kuuza.", "", "sw", "PRICE"));
        assertNotNull("extra caution is not a flip", ReplyWriter.check("Your coffee has leaf rust. Copper oxychloride prevents "
            + "rust, but spray only if the extension officer agrees; otherwise, do not spray yet.", rust, "", "en", "CONFIDENT"));
        assertNull("empty politeness", ReplyWriter.check("Thank you for asking.", PRICE_EN, "", "en", "PRICE"));
        assertNull("copy", ReplyWriter.check(NOT_SURE_SW, NOT_SURE_SW, "", "sw", "TEXT_ONLY"));
        assertNull("no source", ReplyWriter.check("The buyer's 12,000 is 23% below UGX 15,500/kg. Ask the cooperative before "
            + "you sell.", PRICE_EN, "", "en", "PRICE"));
    }

    @Test public void aChemicalTheFarmerNamedIsStillNotRecommended() {
        assertNull(ReplyWriter.check("Sina uhakika, usinyunyizie dawa bado; mancozeb inaweza kusaidia, uliza afisa ugani.",
            NOT_SURE_SW, "nitumie mancozeb kwa kahawa?", "sw", "TEXT_ONLY"));
    }

    @Test public void quotesAndPrefixAreCleaned() {
        assertEquals("Sina uhakika, usinyunyizie dawa bado. Uliza afisa ugani kuhusu kutu ya majani ya kahawa.",
            ReplyWriter.check("\"Pandastic: Sina uhakika, usinyunyizie dawa bado. Uliza afisa ugani kuhusu kutu ya majani ya "
                + "kahawa.\"", NOT_SURE_SW, "", "sw", "TEXT_ONLY"));
    }
}
