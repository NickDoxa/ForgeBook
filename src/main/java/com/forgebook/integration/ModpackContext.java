package com.forgebook.integration;

/** Immutable modpack metadata cached at ServerStartedEvent. CF-01 / D-18. */
public record ModpackContext(String name, String summary) {}
