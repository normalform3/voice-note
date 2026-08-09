package com.voicenote.agent;

import java.util.List;

public record AgentSkill(String id, String version, String displayName, String description, List<String> routingExamples,
                         String instructions, List<String> allowedTools, boolean requireOverviewForMultipleDocuments) { }
