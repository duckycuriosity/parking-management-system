package vio.barie.core.service.filter;

public record QualityCheckResult(boolean accepted, String reason, double value) {
  public static QualityCheckResult accept() {
    return new QualityCheckResult(true, null, 0);
  }
  public static QualityCheckResult reject(String reason, double value) {
    return new QualityCheckResult(false, reason, value);
  }
}
