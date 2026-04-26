package vio.barie.core.dto.payment;

public class MegaPayResponse {
    private String resultCd;
    private String resultMsg;
    private String merId;
    private String trxId;
    private String merTrxId;
    private String invoiceNo;
    private String amount;
    private String currency;
    private String merchantToken;
    private String timeStamp;
    private String payType;
    private String bankId;
    private String issueBankName;
    private String cardNo;
    private String cardType;
    private String holderNm;
    private String payOption;
    private String userId;
    private String payToken;
    private String userFee;
    private String userFeeIs;
    private String termIs;
    private String firstAmount;
    private String nextAmount;
    private String status;
    private String cardEnroll3DS;
    private String icIssue;
    private String authorizations;
    private String transDt;
    private String transTm;

    /**
     * IPN / Inquiry dùng trxDt/trxTm.
     */
    private String trxDt;
    private String trxTm;
    private String vaNumber;
    private String vaName;
    private String startDt;
    private String endDt;

    public boolean isSuccess() {
        return "00_000".equals(resultCd);
    }
}
