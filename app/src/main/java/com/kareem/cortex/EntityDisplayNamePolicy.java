package com.kareem.cortex;

import java.util.Locale;

/**
 * Presentation-safe cleanup for contact-backed person names.
 * Identity stays anchored to contact_id/phone; this only removes transport labels and numbers
 * that should never become part of a person's visible canonical name.
 */
public final class EntityDisplayNamePolicy {
    private EntityDisplayNamePolicy(){}

    public static String cleanContactName(String raw){
        String x=clean(raw);if(x.isEmpty())return "";

        // Common exports such as "M Zeen Phone: 012..." or "Osama ... Mobile +20...".
        x=x.replaceAll("(?i)\\s+(?:facebook\\s+|web\\s+)?(?:phone|mobile|telephone|tel)\\s*[:#-]?\\s*\\+?[0-9][0-9 ()+.-]{5,}$","");
        x=x.replaceAll("(?iu)\\s+(?:رقم|موبايل|تليفون|تلفون|هاتف)\\s*[:#-]?\\s*\\+?[0-9٠-٩][0-9٠-٩ ()+.-]{5,}$","");

        // Labels sometimes survive without a number in the title while the number is in raw_text.
        x=x.replaceAll("(?i)\\s+(?:facebook\\s+|web\\s+)?(?:phone|mobile|telephone|tel)\\s*$","");
        x=x.replaceAll("(?iu)\\s+(?:رقم|موبايل|تليفون|تلفون|هاتف)\\s*$","");

        // Contact-book annotations are useful metadata, not identity. Preserve the actual name.
        x=x.replaceAll("(?i)\\s+(?:new\\s+number|new\\s+no\\.?|new\\s+phone|new\\s+mobile)\\s*$","");
        x=x.replaceAll("(?iu)\\s+(?:رقم\\s+جديد|الرقم\\s+الجديد)\\s*$","");

        // Defensive cleanup for a bare trailing phone number after a separator.
        x=x.replaceAll("\\s*[:|·-]\\s*\\+?[0-9٠-٩][0-9٠-٩ ()+.-]{5,}$","");
        return clean(x);
    }

    static boolean looksLikeOnlyTransportLabel(String raw){
        String x=cleanContactName(raw).toLowerCase(Locale.ROOT);
        return x.isEmpty()||x.equals("phone")||x.equals("mobile")||x.equals("contact")||x.equals("number")||x.equals("رقم")||x.equals("موبايل");
    }

    private static String clean(String s){return s==null?"":s.replace('\n',' ').replace('\r',' ').replaceAll("\\s+"," ").trim();}
}