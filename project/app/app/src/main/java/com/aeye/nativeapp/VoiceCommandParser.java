package com.aeye.nativeapp;

import java.util.Locale;

public class VoiceCommandParser {
    public VoiceCommand parse(String utterance) {
        String raw = utterance == null ? "" : utterance.trim();
        String normalized = normalize(raw);

        if (normalized.isEmpty()) {
            return unknown(raw);
        }

        if (containsAny(normalized, "긴급", "보호자", "도와줘", "살려줘", "sos")) {
            return new VoiceCommand(VoiceCommand.IntentType.EMERGENCY, raw, "", "");
        }

        if (containsAny(normalized, "다시", "반복", "한번더", "또말", "재생")) {
            return new VoiceCommand(VoiceCommand.IntentType.REPEAT_GUIDANCE, raw, "", "");
        }

        if (containsAny(normalized, "다음", "미리", "다음안내", "다음길")) {
            return new VoiceCommand(VoiceCommand.IntentType.NEXT_GUIDANCE, raw, "", "");
        }

        if (containsAny(normalized, "안내종료", "길안내종료", "종료", "그만", "멈춰", "중지")) {
            return new VoiceCommand(VoiceCommand.IntentType.STOP_NAVIGATION, raw, "", "");
        }

        if (containsAny(normalized, "현재위치", "내위치", "여기어디", "어디야")) {
            return new VoiceCommand(VoiceCommand.IntentType.CURRENT_LOCATION, raw, "", "");
        }

        if (containsAny(normalized, "설정", "서버주소", "보호자번호", "tts")) {
            return new VoiceCommand(VoiceCommand.IntentType.OPEN_SETTINGS, raw, "", "");
        }

        if (containsAny(normalized, "즐겨찾기", "저장한곳", "저장장소")) {
            return new VoiceCommand(VoiceCommand.IntentType.FAVORITE, raw, "", "");
        }

        if (containsAny(normalized, "위험", "위험정보", "주의", "위험지점", "장애물정보")) {
            return new VoiceCommand(VoiceCommand.IntentType.RISK_INFO, raw, "", "");
        }

        if (containsAny(normalized, "주변", "근처", "가까운")) {
            return new VoiceCommand(VoiceCommand.IntentType.NEARBY, raw, "", parsePlaceType(normalized));
        }

        String destination = extractDestination(raw);
        String normalizedDestination = normalize(destination);

        if (destination.length() >= 2 && looksLikeDestination(normalized, normalizedDestination)) {
            return new VoiceCommand(VoiceCommand.IntentType.NAVIGATE, raw, destination, "");
        }

        return unknown(raw);
    }

    private static VoiceCommand unknown(String raw) {
        return new VoiceCommand(VoiceCommand.IntentType.UNKNOWN, raw, "", "");
    }

    private static String parsePlaceType(String normalized) {
        if (containsAny(normalized, "편의점", "마트", "가게", "매장")) {
            return "store";
        }

        if (containsAny(normalized, "화장실", "화장")) {
            return "toilet";
        }

        if (containsAny(normalized, "약국", "약")) {
            return "pharmacy";
        }

        if (containsAny(normalized, "병원", "의원", "의료")) {
            return "hospital";
        }

        if (containsAny(normalized, "지하철", "역", "출구")) {
            return "subway";
        }

        if (containsAny(normalized, "카페", "커피")) {
            return "cafe";
        }

        if (containsAny(normalized, "주민센터", "공공", "관공서")) {
            return "public_office";
        }

        return "";
    }

    private static String extractDestination(String raw) {
        String result = raw == null ? "" : raw.trim();
        String[] removablePhrases = {
                "안내해줘",
                "안내해 줘",
                "안내 해줘",
                "안내 해 줘",
                "안내해 주세요",
                "안내해",
                "길 안내해줘",
                "길 안내해 줘",
                "길 안내",
                "경로 안내",
                "경로 찾아줘",
                "경로 찾아 줘",
                "찾아줘",
                "찾아 줘",
                "가줘",
                "가 줘",
                "가자",
                "으로",
                "로"
        };

        for (String phrase : removablePhrases) {
            result = result.replace(phrase, " ");
        }

        result = normalizeSpokenNumbers(result);
        return result.replaceAll("\\s+", " ").trim();
    }

    private static String normalizeSpokenNumbers(String value) {
        String result = value == null ? "" : value;
        String[][] replacements = {
                {"열\\s*(번째|번)", "10번"},
                {"아홉\\s*(번째|번)", "9번"},
                {"구\\s*(번째|번)", "9번"},
                {"여덟\\s*(번째|번)", "8번"},
                {"팔\\s*(번째|번)", "8번"},
                {"일곱\\s*(번째|번)", "7번"},
                {"칠\\s*(번째|번)", "7번"},
                {"여섯\\s*(번째|번)", "6번"},
                {"육\\s*(번째|번)", "6번"},
                {"다섯\\s*(번째|번)", "5번"},
                {"오\\s*(번째|번)", "5번"},
                {"넷\\s*(번째|번)", "4번"},
                {"네\\s*(번째|번)", "4번"},
                {"사\\s*(번째|번)", "4번"},
                {"셋\\s*(번째|번)", "3번"},
                {"세\\s*(번째|번)", "3번"},
                {"삼\\s*(번째|번)", "3번"},
                {"둘\\s*(번째|번)", "2번"},
                {"두\\s*(번째|번)", "2번"},
                {"이\\s*(번째|번)", "2번"},
                {"하나\\s*(번째|번)", "1번"},
                {"한\\s*(번째|번)", "1번"},
                {"일\\s*(번째|번)", "1번"}
        };

        result = result.replaceAll("(\\d+)\\s*번", "$1번");

        for (String[] replacement : replacements) {
            result = result.replaceAll(replacement[0], replacement[1]);
        }

        return result;
    }

    private static boolean looksLikeDestination(String normalizedRaw, String normalizedDestination) {
        if (containsAny(normalizedRaw, "안내", "길", "경로", "찾아", "가줘", "가자")) {
            return true;
        }

        return containsAny(
                normalizedDestination,
                "역",
                "출구",
                "정문",
                "후문",
                "학교",
                "병원",
                "센터",
                "주민센터",
                "공원",
                "시장",
                "교차로",
                "사거리",
                "카페",
                "편의점",
                "마트",
                "약국",
                "구청",
                "동사무소"
        );
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.KOREA).replaceAll("\\s+", "");
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.KOREA).replaceAll("\\s+", ""))) {
                return true;
            }
        }

        return false;
    }
}
