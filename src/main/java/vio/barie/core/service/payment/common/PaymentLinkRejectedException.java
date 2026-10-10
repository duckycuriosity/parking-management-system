package vio.barie.core.service.payment.common;

import vio.barie.core.config.exception.ServiceException;

/** Only a local precondition failure or an explicit gateway rejection, never a timeout. */
public class PaymentLinkRejectedException extends ServiceException {
    public PaymentLinkRejectedException(String message, int status) {
        super(message, status);
    }
}
