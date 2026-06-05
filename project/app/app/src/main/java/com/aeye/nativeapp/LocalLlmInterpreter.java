package com.aeye.nativeapp;

public interface LocalLlmInterpreter {
    boolean isAvailable();

    VoiceCommand interpret(String utterance);
}
