package com.vcampus.client.fx.shop;

import java.io.IOException;

/** Marks an I/O failure raised while exchanging a request with the shop server. */
public final class ShopTransportException extends IOException {
    public ShopTransportException(IOException cause) {
        super(cause.getMessage(), cause);
    }
}
