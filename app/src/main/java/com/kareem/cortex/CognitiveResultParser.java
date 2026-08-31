package com.kareem.cortex;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CognitiveResultParser {

    private CognitiveResultParser() {}

    public static CognitiveResult parse(String raw) throws CognitiveContractException {
        try {
            String json = extractJson(raw);
            JSONObject root = new JSONObject(json);

            CognitiveDisposition disposition = parseDisposition(root.getString("disposition"));
            double confidence = root.getDouble("confidence");
            String reason = root.optString("reason", "");
            List<CognitiveItem> items = parseItems(root.optJSONArray("items"));

            return new CognitiveResult(disposition, confidence, reason, items);
        } catch (CognitiveContractException e) {
            throw e;
        } catch (Throwable t) {
            throw new CognitiveContractException("Invalid cognitive JSON", t);
        }
    }

    private static List<CognitiveItem> parseItems(JSONArray array) throws Exception {
        List<CognitiveItem> result = new ArrayList<>();
        if (array == null) return result;

        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            CognitiveKind kind = CognitiveKind.valueOf(
                    item.getString("kind").trim().toUpperCase(Locale.ROOT)
            );

            result.add(new CognitiveItem(
                    kind,
                    item.getString("summary"),
                    item.optInt("importance", 0),
                    item.optInt("urgency", 0),
                    nullableString(item, "person"),
                    parseDueAt(item.opt("due_at")),
                    item.optBoolean("requires_user_action", false),
                    item.optBoolean("requires_follow_up", false),
                    item.optBoolean("requires_content_extraction", false)
            ));
        }

        return result;
    }

    private static CognitiveDisposition parseDisposition(String value) throws CognitiveContractException {
        try {
            return CognitiveDisposition.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Throwable t) {
            throw new CognitiveContractException("Unknown disposition: " + value);
        }
    }

    private static String nullableString(JSONObject object, String key) {
        if (!object.has(key) || object.isNull(key)) return "";
        return object.optString(key, "");
    }

    private static Long parseDueAt(Object value) {
        if (value == null || value == JSONObject.NULL) return null;
        if (value instanceof Number) return ((Number) value).longValue();

        String text = String.valueOf(value).trim();
        if (text.isEmpty()) return null;

        try {
            return Long.parseLong(text);
        } catch (Throwable ignored) {}

        try {
            return Instant.parse(text).toEpochMilli();
        } catch (Throwable ignored) {}

        // Never guess an invalid model-generated date.
        return null;
    }

    private static String extractJson(String raw) throws CognitiveContractException {
        if (raw == null) throw new CognitiveContractException("Empty model response");

        String value = raw.trim();

        // Protect against an unexpected thinking block.
        int thinkEnd = value.lastIndexOf("</think>");
        if (thinkEnd >= 0) {
            value = value.substring(thinkEnd + "</think>".length()).trim();
        }

        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new CognitiveContractException("No JSON object in model response");
        }

        return value.substring(start, end + 1);
    }
}
