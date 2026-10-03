package dev.xsoz.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The client's logger, in a class with no game-client references so server-side code can use it. */
public final class XsozLog {
    public static final Logger LOG = LoggerFactory.getLogger("Xsoz");

    private XsozLog() { }
}
