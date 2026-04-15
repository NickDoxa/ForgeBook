package com.forgebook.client;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Client-only initialization. Invoked ONLY via
 * DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> ClientSetup::init) from ForgeBookMod.
 *
 * This package (com.forgebook.client) is the ONLY package in the mod allowed to
 * import net.minecraft.client.* per D-10 / SCAF-02. CI enforces via grep lint.
 * Phase 4 expands this class with the in-inventory button injector + ChatScreen.
 */
public final class ClientSetup {
    private static final Logger LOG = LogManager.getLogger();

    private ClientSetup() {}

    public static void init() {
        LOG.info("ForgeBook client initialized (Phase 1 stub; Phase 4 adds UI).");
    }
}
