package vio.barie.core.utils;

import java.util.regex.Pattern;

public class PlateNumberUtils {

    private static final Pattern PLATE_PATTERN = Pattern.compile("^[0-9]{2}[A-Z][A-Z0-9]?[0-9]{5}$");

    static public String normalize(String plateNumber) {
        if (plateNumber == null) return null;
        return plateNumber.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();
    }

    static public boolean isValid(String normalizedPlateNumber) {
        if (normalizedPlateNumber == null) return false;
        return PLATE_PATTERN.matcher(normalizedPlateNumber).matches();
    }
}
