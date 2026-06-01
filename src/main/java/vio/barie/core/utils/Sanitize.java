package vio.barie.core.utils;

public class Sanitize {

    static public String clean(String input) {
        if (input == null) return "";
        return input.replaceAll("[!@#$%&*<=>?^'|\"\\r\\n]", "").trim();
    }
}
