package be.vlaanderen.omgeving.owlsda.agent;

/** Immutable transcript item for a session message exchange. */
public record SessionMessageLogEntry(
    String timestamp, String direction, String messageId, String content) {}
