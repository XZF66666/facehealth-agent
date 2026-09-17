package com.example.rppg_mediapipe;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class DateUtils {
    private static final SimpleDateFormat DATE = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static final SimpleDateFormat DATE_TIME = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    public static String today() {
        return DATE.format(new Date());
    }

    public static String now() {
        return DATE_TIME.format(new Date());
    }
}