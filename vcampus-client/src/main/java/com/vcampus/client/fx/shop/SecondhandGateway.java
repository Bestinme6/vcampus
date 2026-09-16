package com.vcampus.client.fx.shop;

import com.vcampus.common.model.ShippingAddressChoice;
import java.io.IOException;

public interface SecondhandGateway {
    SecondhandData.ListingPage search(String keyword,int page)throws IOException;
    SecondhandData.Listing get(long itemId)throws IOException;
    ShopData.ImageChunk imageChunk(long imageId,ShopData.ImageVariant variant,int chunkIndex)throws IOException;
    SecondhandData.Receipt buy(long itemId,String operationId,ShippingAddressChoice address)throws IOException;
    SecondhandData.OrderPage orders(boolean selling,int page)throws IOException;
    SecondhandData.Order order(long orderId)throws IOException;
    SecondhandData.Receipt cancel(long orderId)throws IOException;
    SecondhandData.Receipt confirm(long orderId)throws IOException;
}
