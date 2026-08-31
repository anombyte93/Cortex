package com.kareem.cortex;

import java.util.Locale;

/** Shared local safety policy for secrets that should not enter model comparison telemetry. */
public final class SensitiveSignalPolicy {
    private SensitiveSignalPolicy(){}

    public static boolean containsSecret(String text){
        String x=text==null?"":text.toLowerCase(Locale.ROOT);
        return containsAny(x,
                "otp","one-time password","one time password","verification code","cvv","pin code",
                "رمز التحقق","كود التحقق","كلمة السر","كلمه السر");
    }

    private static boolean containsAny(String value,String... values){for(String v:values)if(value.contains(v))return true;return false;}
}
