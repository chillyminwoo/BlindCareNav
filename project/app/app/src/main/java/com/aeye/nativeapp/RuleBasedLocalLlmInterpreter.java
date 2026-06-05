package com.aeye.nativeapp;

public class RuleBasedLocalLlmInterpreter implements LocalLlmInterpreter {
    private final VoiceCommandParser parser = new VoiceCommandParser();

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public VoiceCommand interpret(String utterance) {
        return parser.parse(utterance);
    }
}
