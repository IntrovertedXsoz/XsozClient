package dev.xsoz.client.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class UpdatesTest {
    private static JsonArray releases(String... tags) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < tags.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"tag_name\":\"").append(tags[i]).append("\",\"html_url\":\"https://example/").append(tags[i])
                    .append("\",\"assets\":[{\"name\":\"Xsoz-Client-Setup.exe\",\"browser_download_url\":\"x.exe\",\"size\":5},")
                    .append("{\"name\":\"xsozclient-").append(tags[i]).append(".jar\",\"browser_download_url\":\"https://example/")
                    .append(tags[i]).append(".jar\",\"size\":10}]}");
        }
        return JsonParser.parseString(sb.append(']').toString()).getAsJsonArray();
    }

    @Test
    void readsTagsAndModVersions() {
        assertEquals(new Version(0, 4, 0, Channel.STABLE, 0), Version.parse("v0.4.0"));
        assertEquals(new Version(0, 4, 0, Channel.DEV, 2), Version.parse("v0.4.0-dev.2"));
        assertEquals(new Version(0, 4, 0, Channel.EXPERIMENTAL, 1), Version.parse("0.4.0-exp.1+mc1.21.11"));
        assertNull(Version.parse("latest"));
        assertEquals("0.4.0 Dev 2", Version.parse("v0.4.0-dev.2").pretty());
    }

    @Test
    void ordersStableAfterItsTestBuilds() {
        assertTrue(Version.parse("0.4.0").newerThan(Version.parse("0.4.0-dev.9")));
        assertTrue(Version.parse("0.4.0-dev.1").newerThan(Version.parse("0.4.0-exp.5")));
        assertTrue(Version.parse("0.4.0-dev.2").newerThan(Version.parse("0.4.0-dev.1")));
        assertTrue(Version.parse("0.4.0-exp.1").newerThan(Version.parse("0.3.9")));
        assertFalse(Version.parse("0.3.0").newerThan(Version.parse("0.3.0")));
    }

    @Test
    void eachChannelOnlySeesItsReleases() {
        JsonArray all = releases("v0.3.0", "v0.4.0-dev.1", "v0.5.0-exp.1");
        Version cur = Version.parse("0.3.0");
        assertNull(Updates.pick(all, Channel.STABLE, cur), "stable players stay on stable");
        assertEquals("v0.4.0-dev.1", Updates.pick(all, Channel.DEV, cur).tag());
        assertEquals("v0.5.0-exp.1", Updates.pick(all, Channel.EXPERIMENTAL, cur).tag());
    }

    @Test
    void offersOnlyNewerAndPicksTheJar() {
        Updates.Release r = Updates.pick(releases("v0.2.0", "v0.3.1", "v0.3.0"), Channel.STABLE, Version.parse("0.3.0"));
        assertNotNull(r);
        assertEquals("v0.3.1", r.tag());
        assertTrue(r.jarUrl().endsWith(".jar"));
        assertFalse(r.switchBack());
        assertNull(Updates.pick(releases("v0.2.0", "v0.3.0"), Channel.STABLE, Version.parse("0.3.0")));
    }

    @Test
    void leavingExperimentalOffersTheWayBack() {
        Updates.Release r = Updates.pick(releases("v0.3.0", "v0.5.0-exp.1"), Channel.STABLE, Version.parse("0.5.0-exp.1"));
        assertNotNull(r);
        assertEquals("v0.3.0", r.tag());
        assertTrue(r.switchBack());
    }

    @Test
    void skipsReleasesWithoutTheModJar() {
        JsonArray a = JsonParser.parseString("[{\"tag_name\":\"v9.0.0\",\"assets\":[{\"name\":\"Setup.exe\",\"browser_download_url\":\"x\"}]}]").getAsJsonArray();
        assertNull(Updates.pick(a, Channel.STABLE, Version.parse("0.3.0")));
    }
}
