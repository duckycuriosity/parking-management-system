package vio.barie.core.utils;

public class PlateNumberUtils {

    static public String normalize(String plateNumber) {
        if (plateNumber == null) return null;
        return plateNumber.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();
    }
}
