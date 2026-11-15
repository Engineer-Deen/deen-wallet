package com.glr.deenwallet.pawapay;

/**
 * CREATED    saved, nothing sent yet
 * COLLECTING deposit accepted: waiting for the sender to approve the prompt on their phone
 * COLLECTED  sender's money received: the transfer abroad is about to be sent
 * REMITTING  transfer abroad accepted: waiting for the final result
 * COMPLETED  recipient received the money (final)
 * REFUNDING  the transfer abroad did not work: giving the sender's money back
 * REFUNDED   sender got the money back (final)
 * FAILED     the sender was never charged (final)
 * REVIEW     needs a person: money was taken but neither delivered nor returned automatically (final until an admin acts)
 */
public enum CrossBorderStatus {
    CREATED, COLLECTING, COLLECTED, REMITTING, COMPLETED, REFUNDING, REFUNDED, FAILED, REVIEW;

    public boolean open() {
        return this == CREATED || this == COLLECTING || this == COLLECTED || this == REMITTING || this == REFUNDING;
    }
}
